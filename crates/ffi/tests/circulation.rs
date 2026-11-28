mod support;

use async_trait::async_trait;
use openrose_circulation::codec::{
    CIRCULATION_WIRE_FORMAT_VERSION, CirculationCommand, CirculationReply,
};
use openrose_circulation::node::{CirculationConfig, CirculationNode as CoreCirculationNode};
use openrose_circulation::{
    LocalAudio as CoreLocalAudio, NearbyPeer, NearbyPeerRecord, PeerDiscovery as CorePeerDiscovery,
    PeerId, PullOutcome, RemoteControlStatus, RemoteError, YieldReceipt, YieldRefusal,
};
use openrose_control::codec::CodecError;
use openrose_control::snapshot::ConnectionState;
use openrose_ffi::{
    CirculationFailure, CirculationNode, DriverError, LocalAudio, NearbySink, OwnerActive,
    PeerDiscovery, PeerRoute, PeerRouteDialer, YieldRefusalKind, circulation_wire_format_version,
    decode_circulation_command, decode_circulation_reply, encode_circulation_command,
    encode_circulation_reply,
};
use openrose_headset::{HeadsetCommand, HeadsetId, NoiseMode};
use std::net::IpAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};
use support::{I5_ADDRESS, World, eventually};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::sync::{Mutex as AsyncMutex, watch};

struct TempDir(PathBuf);

impl TempDir {
    fn new(prefix: &str) -> Self {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let dir = std::env::temp_dir().join(format!("openrose_ffi_{prefix}_{nanos}"));
        std::fs::create_dir_all(&dir).expect("create temp dir");
        Self(dir)
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

#[derive(Default)]
struct ForeignDiscovery {
    sink: Mutex<Option<Arc<NearbySink>>>,
    started_version: Mutex<Option<u32>>,
}

impl ForeignDiscovery {
    fn publish(&self, peers: Vec<NearbyPeerRecord>) {
        if let Some(sink) = self.sink.lock().expect("sink lock").as_ref() {
            sink.publish(peers);
        }
    }
}

#[async_trait]
impl PeerDiscovery for ForeignDiscovery {
    async fn start(
        &self,
        _peer_id: PeerId,
        _name: String,
        _port: u16,
        wire_version: u32,
        sink: Arc<NearbySink>,
    ) -> Result<(), DriverError> {
        *self.started_version.lock().expect("version lock") = Some(wire_version);
        *self.sink.lock().expect("sink lock") = Some(sink);
        Ok(())
    }

    async fn close(&self) {
        *self.sink.lock().expect("sink lock") = None;
    }
}

struct ForeignTcpRoute {
    address: String,
    port: u16,
    reader: AsyncMutex<Option<OwnedReadHalf>>,
    writer: AsyncMutex<Option<OwnedWriteHalf>>,
}

impl ForeignTcpRoute {
    fn new(address: String, port: u16) -> Self {
        Self {
            address,
            port,
            reader: AsyncMutex::new(None),
            writer: AsyncMutex::new(None),
        }
    }
}

#[async_trait]
impl PeerRoute for ForeignTcpRoute {
    async fn connect(&self) -> Result<(), DriverError> {
        let stream = TcpStream::connect(format!("{}:{}", self.address, self.port))
            .await
            .map_err(|e| DriverError::Terminal {
                detail: e.to_string(),
            })?;
        let (read_half, write_half) = stream.into_split();
        *self.reader.lock().await = Some(read_half);
        *self.writer.lock().await = Some(write_half);
        Ok(())
    }

    async fn read(&self) -> Result<Vec<u8>, DriverError> {
        let mut guard = self.reader.lock().await;
        let Some(reader) = guard.as_mut() else {
            return Err(DriverError::Terminal {
                detail: "route closed".to_string(),
            });
        };
        let mut buf = vec![0u8; 4096];
        let n = reader.read(&mut buf).await.map_err(|e| DriverError::Terminal {
            detail: e.to_string(),
        })?;
        buf.truncate(n);
        Ok(buf)
    }

    async fn write(&self, value: Vec<u8>) -> Result<(), DriverError> {
        let mut guard = self.writer.lock().await;
        let Some(writer) = guard.as_mut() else {
            return Err(DriverError::Terminal {
                detail: "route closed".to_string(),
            });
        };
        writer
            .write_all(&value)
            .await
            .map_err(|e| DriverError::Terminal {
                detail: e.to_string(),
            })
    }

    async fn close_link(&self) -> Result<(), DriverError> {
        *self.reader.lock().await = None;
        if let Some(mut writer) = self.writer.lock().await.take() {
            let _ = writer.shutdown().await;
        }
        Ok(())
    }
}

struct ForeignDialer {
    dial_count: Mutex<usize>,
}

impl ForeignDialer {
    fn new() -> Self {
        Self {
            dial_count: Mutex::new(0),
        }
    }

    fn dials(&self) -> usize {
        *self.dial_count.lock().expect("dial count lock")
    }
}

impl PeerRouteDialer for ForeignDialer {
    fn open_route(&self, address: String, port: u16) -> Result<Arc<dyn PeerRoute>, DriverError> {
        *self.dial_count.lock().expect("dial count lock") += 1;
        Ok(Arc::new(ForeignTcpRoute::new(address, port)))
    }
}

struct ForeignAudio {
    connected: AtomicBool,
    media_connected: AtomicBool,
    yield_calls: Mutex<Vec<String>>,
    acquire_calls: Mutex<Vec<String>>,
    refuse_yield: Mutex<Option<DriverError>>,
}

impl ForeignAudio {
    fn holding() -> Self {
        Self {
            connected: AtomicBool::new(true),
            media_connected: AtomicBool::new(true),
            yield_calls: Mutex::new(Vec::new()),
            acquire_calls: Mutex::new(Vec::new()),
            refuse_yield: Mutex::new(None),
        }
    }

    fn idle() -> Self {
        Self {
            connected: AtomicBool::new(false),
            media_connected: AtomicBool::new(false),
            yield_calls: Mutex::new(Vec::new()),
            acquire_calls: Mutex::new(Vec::new()),
            refuse_yield: Mutex::new(None),
        }
    }
}

#[async_trait]
impl LocalAudio for ForeignAudio {
    async fn is_connected(&self, _address: String) -> Result<bool, DriverError> {
        Ok(self.connected.load(Ordering::SeqCst))
    }

    async fn is_media_connected(&self, _address: String) -> Result<bool, DriverError> {
        Ok(self.media_connected.load(Ordering::SeqCst))
    }

    async fn yield_audio(&self, address: String) -> Result<(), DriverError> {
        if let Some(err) = self.refuse_yield.lock().expect("refuse lock").clone() {
            return Err(err);
        }
        self.yield_calls.lock().expect("yield calls").push(address);
        self.connected.store(false, Ordering::SeqCst);
        self.media_connected.store(false, Ordering::SeqCst);
        Ok(())
    }

    async fn acquire(&self, address: String) -> Result<(), DriverError> {
        self.acquire_calls.lock().expect("acquire calls").push(address);
        self.connected.store(true, Ordering::SeqCst);
        self.media_connected.store(true, Ordering::SeqCst);
        Ok(())
    }
}

struct FlagOwner(AtomicBool);

impl FlagOwner {
    fn new(active: bool) -> Self {
        Self(AtomicBool::new(active))
    }

    fn set(&self, active: bool) {
        self.0.store(active, Ordering::SeqCst);
    }
}

impl OwnerActive for FlagOwner {
    fn is_active(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

struct CoreDiscovery {
    tx: watch::Sender<Vec<NearbyPeer>>,
}

impl CoreDiscovery {
    fn new() -> Self {
        let (tx, _) = watch::channel(Vec::new());
        Self { tx }
    }

    fn publish(&self, peers: Vec<NearbyPeer>) {
        self.tx.send_replace(peers);
    }
}

#[async_trait]
impl CorePeerDiscovery for CoreDiscovery {
    async fn start(&self, _id: &PeerId, _name: &str, _port: u16) -> Result<(), RemoteError> {
        Ok(())
    }

    fn watch(&self) -> watch::Receiver<Vec<NearbyPeer>> {
        self.tx.subscribe()
    }

    async fn close(&self) {}
}

struct CoreAudio {
    media_connected: AtomicBool,
}

impl CoreAudio {
    fn new() -> Self {
        Self {
            media_connected: AtomicBool::new(false),
        }
    }
}

#[async_trait]
impl CoreLocalAudio for CoreAudio {
    async fn is_connected(&self, _address: &str) -> bool {
        self.media_connected.load(Ordering::SeqCst)
    }

    async fn is_media_connected(&self, _address: &str) -> bool {
        self.media_connected.load(Ordering::SeqCst)
    }

    async fn yield_audio(&self, address: &str) -> Result<YieldReceipt, YieldRefusal> {
        self.media_connected.store(false, Ordering::SeqCst);
        Ok(YieldReceipt {
            address: address.to_string(),
        })
    }

    async fn acquire(&self, _address: &str) -> Result<(), YieldRefusal> {
        self.media_connected.store(true, Ordering::SeqCst);
        Ok(())
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn phone_facade_pairs_serves_remote_read_set_gates_owner_and_yields_on_pull() {
    let world = World::i5();
    world.headset.set_audio_connected(true);
    world.runtime.connect(world.id()).await.expect("connect");
    eventually("phone control connected", || {
        matches!(world.snapshot().connection, ConnectionState::Connected { .. })
    })
    .await;

    let phone_dir = TempDir::new("phone");
    let phone_discovery = Arc::new(ForeignDiscovery::default());
    let phone_audio = Arc::new(ForeignAudio::holding());
    let phone_dialer = Arc::new(ForeignDialer::new());
    let phone_owner = Arc::new(FlagOwner::new(true));

    let phone = CirculationNode::new(
        phone_dir.0.to_string_lossy().to_string(),
        "Phone".to_string(),
        Some(0),
        Arc::clone(&world.runtime),
        phone_discovery.clone(),
        phone_audio.clone(),
        phone_dialer.clone(),
        phone_owner.clone(),
    )
    .expect("phone facade");

    let phone_port = phone.start().await.expect("start phone");
    assert_eq!(phone.bound_port(), Some(phone_port));
    assert_eq!(
        *phone_discovery.started_version.lock().expect("version"),
        Some(openrose_circulation::WIRE_VERSION)
    );

    let desktop_dir = TempDir::new("desktop");
    let desktop_discovery = Arc::new(CoreDiscovery::new());
    let desktop_audio = Arc::new(CoreAudio::new());
    let desktop = CoreCirculationNode::with_audio(
        CirculationConfig::loopback(&desktop_dir.0, "Desktop"),
        desktop_discovery.clone(),
        desktop_audio.clone(),
    )
    .expect("desktop node");
    let desktop_port = desktop.start().await.expect("start desktop");

    phone_discovery.publish(vec![NearbyPeerRecord {
        id: desktop.id().clone(),
        name: "Desktop".to_string(),
        addresses: vec!["127.0.0.1".to_string(), "not-an-ip".to_string()],
        port: desktop_port,
    }]);
    desktop_discovery.publish(vec![NearbyPeer {
        id: phone.id(),
        name: "Phone".to_string(),
        addresses: vec![IpAddr::from([127, 0, 0, 1])],
        port: phone_port,
    }]);

    eventually("phone sees nearby desktop", || phone.nearby().len() == 1).await;
    assert_eq!(phone.nearby()[0].addresses, vec!["127.0.0.1"]);

    let offer = phone.offer_pairing().expect("offer pairing");
    let current = phone.current_pairing_offer().expect("current pairing offer");
    assert_eq!(current.code, offer.code);
    assert_eq!(current.qr, offer.qr);
    let paired = desktop
        .pair(&phone.id(), &offer.code)
        .await
        .expect("desktop pairs with phone");
    assert_eq!(paired.id, phone.id());
    assert_eq!(phone.peers().len(), 1);

    let reading = desktop
        .remote_read(&phone.id())
        .await
        .expect("remote read");
    assert_eq!(reading.status, RemoteControlStatus::Ready);
    let session = reading.session.clone().expect("session");

    let updated = desktop
        .remote_set(
            &phone.id(),
            session,
            HeadsetCommand::NoiseMode(NoiseMode::Transparency),
        )
        .await
        .expect("remote set");
    assert_eq!(updated.noise_mode, Some(NoiseMode::Transparency));

    phone_owner.set(false);
    let inactive_err = desktop
        .remote_read(&phone.id())
        .await
        .expect_err("inactive owner rejects remote read");
    assert_eq!(inactive_err, RemoteError::OwnerInactive);
    phone_owner.set(true);

    let handoff = phone.handoff_status(Some(I5_ADDRESS.to_string())).await;
    assert!(handoff.available);
    assert!(handoff.media_ready);
    assert!(!handoff.offline);

    let outcome = desktop.pull(I5_ADDRESS).await.expect("desktop pulls");
    assert_eq!(outcome, PullOutcome::Acquired);
    assert_eq!(phone_audio.yield_calls.lock().expect("yield").as_slice(), &[I5_ADDRESS]);
    eventually("phone disconnected after yield", || {
        matches!(world.snapshot().connection, ConnectionState::Disconnected)
    })
    .await;

    let status_after = phone.handoff_status(Some(I5_ADDRESS.to_string())).await;
    assert!(!status_after.media_ready);

    phone_audio.refuse_yield.lock().expect("refuse").replace(DriverError::YieldRefused {
        kind: YieldRefusalKind::InCall,
        detail: "busy".to_string(),
    });
    assert!(matches!(
        phone_audio.yield_audio(I5_ADDRESS.to_string()).await,
        Err(DriverError::YieldRefused {
            kind: YieldRefusalKind::InCall,
            ..
        })
    ));

    let err = phone
        .pull("   ".to_string())
        .await
        .expect_err("blank address rejected");
    assert_eq!(err, CirculationFailure::Failed { error: RemoteError::InvalidArgument });

    let _ = phone_dialer.dials();
    phone.close().await;
    desktop.close().await;
}

#[tokio::test(flavor = "multi_thread")]
async fn phone_facade_uses_foreign_dialer_for_outbound_route_and_pull() {
    let world = World::i5();
    let phone_dir = TempDir::new("phone_dial");
    let phone_discovery = Arc::new(ForeignDiscovery::default());
    let phone_audio = Arc::new(ForeignAudio::idle());
    let phone_dialer = Arc::new(ForeignDialer::new());
    let phone_owner = Arc::new(FlagOwner::new(true));

    let phone = CirculationNode::new(
        phone_dir.0.to_string_lossy().to_string(),
        "Phone".to_string(),
        Some(0),
        Arc::clone(&world.runtime),
        phone_discovery.clone(),
        phone_audio.clone(),
        phone_dialer.clone(),
        phone_owner,
    )
    .expect("phone facade");
    let phone_port = phone.start().await.expect("start phone");

    let desktop_dir = TempDir::new("desktop_holder");
    let desktop_discovery = Arc::new(CoreDiscovery::new());
    let desktop_audio = Arc::new(CoreAudio::new());
    desktop_audio.media_connected.store(true, Ordering::SeqCst);
    let desktop = CoreCirculationNode::with_audio(
        CirculationConfig::loopback(&desktop_dir.0, "Desktop"),
        desktop_discovery.clone(),
        desktop_audio.clone(),
    )
    .expect("desktop node");
    let desktop_port = desktop.start().await.expect("start desktop");

    phone_discovery.publish(vec![NearbyPeerRecord {
        id: desktop.id().clone(),
        name: "Desktop".to_string(),
        addresses: vec!["127.0.0.1".to_string()],
        port: desktop_port,
    }]);
    desktop_discovery.publish(vec![NearbyPeer {
        id: phone.id(),
        name: "Phone".to_string(),
        addresses: vec![IpAddr::from([127, 0, 0, 1])],
        port: phone_port,
    }]);
    eventually("phone sees desktop", || phone.nearby().len() == 1).await;

    let offer = desktop.offer_pairing().expect("desktop offer");
    let paired = phone
        .pair_qr(offer.qr)
        .await
        .expect("phone pairs via qr");
    assert_eq!(paired.id, *desktop.id());
    assert!(phone_dialer.dials() >= 1);

    let outcome = phone
        .pull(I5_ADDRESS.to_string())
        .await
        .expect("phone pulls from desktop");
    assert_eq!(outcome, PullOutcome::Acquired);
    assert!(!desktop_audio.media_connected.load(Ordering::SeqCst));
    assert_eq!(
        phone_audio.acquire_calls.lock().expect("acquire").as_slice(),
        &[I5_ADDRESS]
    );

    phone.forget(desktop.id().clone()).await.expect("forget");
    assert!(phone.peers().is_empty());

    phone.close().await;
    desktop.close().await;
}

#[test]
fn circulation_codec_exports_round_trip_and_reject_bad_bytes() {
    assert_eq!(
        circulation_wire_format_version(),
        CIRCULATION_WIRE_FORMAT_VERSION
    );

    let command = CirculationCommand::Set {
        session: openrose_circulation::RemoteSessionReference {
            peer_id: PeerId::new("peer-1"),
            headset_id: HeadsetId::new(I5_ADDRESS),
            token: "tok-7".to_string(),
        },
        command: HeadsetCommand::NoiseMode(NoiseMode::Anc),
    };
    let encoded_cmd = encode_circulation_command(command.clone());
    assert_eq!(decode_circulation_command(encoded_cmd.clone()), Ok(command));

    let reply = CirculationReply::Pulled {
        outcome: PullOutcome::AcquiredWithPeersUnreachable,
    };
    let encoded_reply = encode_circulation_reply(reply.clone());
    assert_eq!(decode_circulation_reply(encoded_reply), Ok(reply));

    assert!(matches!(
        decode_circulation_command(b"bad".to_vec()),
        Err(CodecError::Malformed { .. })
    ));

    let mut wrong_version = encoded_cmd;
    wrong_version[3] = CIRCULATION_WIRE_FORMAT_VERSION + 1;
    assert_eq!(
        decode_circulation_command(wrong_version),
        Err(CodecError::UnsupportedVersion {
            expected: CIRCULATION_WIRE_FORMAT_VERSION,
            actual: CIRCULATION_WIRE_FORMAT_VERSION + 1,
        })
    );

}
