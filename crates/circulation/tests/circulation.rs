use async_trait::async_trait;
use openrose_circulation::{
    CirculationConfig, CirculationNode, HeadsetEndpoint, NearbyPeer, PeerDiscovery, PeerId,
    RemoteCommand, RemoteControlStatus, RemoteError, RemoteHandler,
};
use openrose_control::audio::RawAudioDevice;
use openrose_control::simulator::{SimulatedClock, SimulatedHeadsetHandle, SimulatedTransport};
use openrose_control::snapshot::{
    ControlSnapshot, PermissionState, PlatformState, TransportAvailability,
};
use openrose_control::{ControlConfig, ControlObserver, ControlRuntime, SessionTiming};
use openrose_headset::{
    FieldValue, FirmwareVersion, HeadsetCommand, HeadsetField, HeadsetId, LocalDateTime,
    ModelProfile, NoiseMode,
};
use std::collections::BTreeMap;
use std::fs;
use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::watch;

struct TempDir {
    path: PathBuf,
}

impl TempDir {
    fn new(label: &str) -> Self {
        static NEXT_ID: AtomicU64 = AtomicU64::new(1);
        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "openrose-circulation-{label}-{}-{id}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&path);
        fs::create_dir_all(&path).expect("create temp dir");
        Self { path }
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

struct LoopbackDiscoveryHub {
    state: Mutex<BTreeMap<PeerId, NearbyPeer>>,
    tx: watch::Sender<Vec<NearbyPeer>>,
    _rx: watch::Receiver<Vec<NearbyPeer>>,
}

impl LoopbackDiscoveryHub {
    fn new() -> Arc<Self> {
        let (tx, rx) = watch::channel(Vec::new());
        Arc::new(Self {
            state: Mutex::new(BTreeMap::new()),
            tx,
            _rx: rx,
        })
    }

    fn client(self: &Arc<Self>) -> Arc<LoopbackDiscovery> {
        Arc::new(LoopbackDiscovery {
            hub: Arc::clone(self),
            own_id: Mutex::new(None),
        })
    }

    fn publish(&self, peer: NearbyPeer) {
        let mut map = self.state.lock().expect("hub lock");
        map.insert(peer.id.clone(), peer);
        let list: Vec<NearbyPeer> = map.values().cloned().collect();
        self.tx.send_replace(list);
    }

    fn remove(&self, id: &PeerId) {
        let mut map = self.state.lock().expect("hub lock");
        if map.remove(id).is_some() {
            let list: Vec<NearbyPeer> = map.values().cloned().collect();
            self.tx.send_replace(list);
        }
    }
}

struct LoopbackDiscovery {
    hub: Arc<LoopbackDiscoveryHub>,
    own_id: Mutex<Option<PeerId>>,
}

#[async_trait]
impl PeerDiscovery for LoopbackDiscovery {
    async fn start(&self, id: &PeerId, name: &str, port: u16) -> Result<(), RemoteError> {
        *self.own_id.lock().expect("own_id lock") = Some(id.clone());
        self.hub.publish(NearbyPeer {
            id: id.clone(),
            name: name.to_string(),
            addresses: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            port,
        });
        Ok(())
    }

    fn watch(&self) -> watch::Receiver<Vec<NearbyPeer>> {
        self.hub.tx.subscribe()
    }

    async fn close(&self) {
        if let Some(id) = self.own_id.lock().expect("own_id lock").take() {
            self.hub.remove(&id);
        }
    }
}

struct NoopObserver;

impl ControlObserver for NoopObserver {
    fn state_changed(&self, _snapshot: &ControlSnapshot) {}
}

struct ControlRig {
    runtime: Arc<ControlRuntime>,
    transport: Arc<SimulatedTransport>,
    headset: SimulatedHeadsetHandle,
}

impl ControlRig {
    async fn ready_i5(id: &str) -> Self {
        let transport = Arc::new(SimulatedTransport::new());
        let headset = transport
            .add_headset(
                HeadsetId::new(id),
                ModelProfile::I5Zt,
                FirmwareVersion::new(vec![1, 2, 5], "125"),
            )
            .expect("add headset");
        transport
            .audio()
            .pair(RawAudioDevice::new(Some("EarFree i5"), id));
        transport.audio().set_audio_connected(Some(id));

        let clock = Arc::new(SimulatedClock::new(
            LocalDateTime::new(2026, 11, 28, 10, 0, 0).expect("valid time"),
        ));
        let mut config = ControlConfig::new(
            PlatformState::new(PermissionState::Granted, TransportAvailability::Available),
            clock,
        );
        config.timing = SessionTiming {
            write_interval: Some(Duration::ZERO),
            query_timeout: Duration::from_millis(200),
            write_timeout: Duration::from_millis(200),
            ..SessionTiming::default()
        };
        let runtime = Arc::new(ControlRuntime::new(
            transport.clone(),
            Arc::new(NoopObserver),
            config,
        ));
        runtime
            .connect(HeadsetId::new(id))
            .await
            .expect("connect control");

        Self {
            runtime,
            transport,
            headset,
        }
    }

    fn add_second_i5(&self, id: &str) -> SimulatedHeadsetHandle {
        let second = self
            .transport
            .add_headset(
                HeadsetId::new(id),
                ModelProfile::I5Zt,
                FirmwareVersion::new(vec![1, 2, 5], "125"),
            )
            .expect("add second headset");
        self.transport
            .audio()
            .pair(RawAudioDevice::new(Some("EarFree i5 Second"), id));
        second
    }
}

async fn wait_until(mut predicate: impl FnMut() -> bool) {
    for _ in 0..100 {
        if predicate() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
    panic!("condition not satisfied before timeout");
}

#[tokio::test]
async fn pairing_by_code_and_by_qr() {
    let hub = LoopbackDiscoveryHub::new();
    let dir_a = TempDir::new("pair-a");
    let dir_b = TempDir::new("pair-b");
    let dir_c = TempDir::new("pair-c");

    let node_a = CirculationNode::new(
        CirculationConfig::loopback(&dir_a.path, "Desktop A"),
        hub.client(),
    )
    .expect("node a");
    let node_b = CirculationNode::new(
        CirculationConfig::loopback(&dir_b.path, "Phone B"),
        hub.client(),
    )
    .expect("node b");
    let node_c = CirculationNode::new(
        CirculationConfig::loopback(&dir_c.path, "Tablet C"),
        hub.client(),
    )
    .expect("node c");

    node_a.start().await.expect("start a");
    node_b.start().await.expect("start b");
    node_c.start().await.expect("start c");

    wait_until(|| {
        node_b.nearby().iter().any(|p| &p.id == node_a.id())
            && node_c.nearby().iter().any(|p| &p.id == node_a.id())
    })
    .await;

    let offer_code = node_a.offer_pairing().expect("offer code");
    let paired_a = node_b
        .pair(node_a.id(), &offer_code.code)
        .await
        .expect("pair by code");
    assert_eq!(&paired_a.id, node_a.id());
    assert_eq!(paired_a.name, "Desktop A");
    assert!(node_a.current_pairing_offer().is_none());
    assert!(node_a.peers().iter().any(|p| &p.id == node_b.id()));
    assert!(node_b.peers().iter().any(|p| &p.id == node_a.id()));

    let offer_qr = node_a.offer_pairing().expect("offer qr");
    let paired_qr = node_c
        .pair_qr(&offer_qr.qr)
        .await
        .expect("pair by qr");
    assert_eq!(&paired_qr.id, node_a.id());
    assert!(node_a.peers().iter().any(|p| &p.id == node_c.id()));
    assert!(node_c.peers().iter().any(|p| &p.id == node_a.id()));

    node_a.close().await;
    node_b.close().await;
    node_c.close().await;
}

#[tokio::test]
async fn wrong_code_and_attempt_limit() {
    let hub = LoopbackDiscoveryHub::new();
    let dir_a = TempDir::new("wrong-a");
    let dir_b = TempDir::new("wrong-b");

    let node_a = CirculationNode::new(
        CirculationConfig::loopback(&dir_a.path, "Holder"),
        hub.client(),
    )
    .expect("node a");
    let node_b = CirculationNode::new(
        CirculationConfig::loopback(&dir_b.path, "Viewer"),
        hub.client(),
    )
    .expect("node b");

    node_a.start().await.expect("start a");
    node_b.start().await.expect("start b");
    wait_until(|| node_b.nearby().iter().any(|p| &p.id == node_a.id())).await;

    let offer = node_a.offer_pairing().expect("offer");
    let wrong_code = if offer.code == "000000" {
        "111111"
    } else {
        "000000"
    };

    for _ in 0..5 {
        let err = node_b
            .pair(node_a.id(), wrong_code)
            .await
            .expect_err("wrong code must fail");
        assert_eq!(err, RemoteError::PairingRejected);
    }

    let limit_err = node_b
        .pair(node_a.id(), &offer.code)
        .await
        .expect_err("6th attempt must fail");
    assert_eq!(limit_err, RemoteError::PairingAttemptsExceeded);
    assert!(node_a.peers().is_empty());
    assert!(node_b.peers().is_empty());

    node_a.close().await;
    node_b.close().await;
}

#[tokio::test]
async fn revocation_both_directions() {
    let hub = LoopbackDiscoveryHub::new();
    let dir_a = TempDir::new("revoke-a");
    let dir_b = TempDir::new("revoke-b");

    let rig = ControlRig::ready_i5("AA:BB:CC:00:00:05").await;
    let node_a = CirculationNode::with_control(
        CirculationConfig::loopback(&dir_a.path, "Holder"),
        hub.client(),
        Arc::clone(&rig.runtime),
    )
    .expect("node a");
    let node_b = CirculationNode::new(
        CirculationConfig::loopback(&dir_b.path, "Viewer"),
        hub.client(),
    )
    .expect("node b");

    node_a.start().await.expect("start a");
    node_b.start().await.expect("start b");
    wait_until(|| node_b.nearby().iter().any(|p| &p.id == node_a.id())).await;

    let offer = node_a.offer_pairing().expect("offer");
    node_b.pair(node_a.id(), &offer.code).await.expect("pair");
    assert_eq!(node_a.peers().len(), 1);
    assert_eq!(node_b.peers().len(), 1);

    node_b.forget(node_a.id()).await.expect("forget from b");
    wait_until(|| node_a.peers().is_empty()).await;
    assert!(node_b.peers().is_empty());
    assert_eq!(
        node_b.remote_read(node_a.id()).await.expect_err("unpaired"),
        RemoteError::NotPaired
    );

    let offer2 = node_a.offer_pairing().expect("offer 2");
    node_b.pair(node_a.id(), &offer2.code).await.expect("pair 2");
    assert_eq!(node_a.peers().len(), 1);
    assert_eq!(node_b.peers().len(), 1);

    node_a.forget(node_b.id()).await.expect("forget from a");
    wait_until(|| node_b.peers().is_empty()).await;
    assert!(node_a.peers().is_empty());

    node_a.close().await;
    node_b.close().await;
}

#[tokio::test]
async fn remote_read_battery_and_noise_mode_and_remote_set_confirmed() {
    let hub = LoopbackDiscoveryHub::new();
    let dir_a = TempDir::new("ctrl-a");
    let dir_b = TempDir::new("ctrl-b");

    let rig = ControlRig::ready_i5("AA:BB:CC:00:00:05").await;
    let node_a = CirculationNode::with_control(
        CirculationConfig::loopback(&dir_a.path, "Holder"),
        hub.client(),
        Arc::clone(&rig.runtime),
    )
    .expect("node a");
    let node_b = CirculationNode::new(
        CirculationConfig::loopback(&dir_b.path, "Viewer"),
        hub.client(),
    )
    .expect("node b");

    node_a.start().await.expect("start a");
    node_b.start().await.expect("start b");
    wait_until(|| node_b.nearby().iter().any(|p| &p.id == node_a.id())).await;

    let offer = node_a.offer_pairing().expect("offer");
    node_b.pair(node_a.id(), &offer.code).await.expect("pair");

    let reading = node_b
        .remote_read(node_a.id())
        .await
        .expect("remote read");
    assert_eq!(reading.status, RemoteControlStatus::Ready);
    assert_eq!(reading.profile, Some(ModelProfile::I5Zt));
    assert_eq!(reading.noise_mode, Some(NoiseMode::Wind));
    let battery = reading.battery.as_ref().expect("battery slots");
    assert!(battery.left.is_some());
    assert!(battery.right.is_some());
    let session = reading.session.clone().expect("session reference");

    let updated = node_b
        .remote_set(
            node_a.id(),
            session,
            HeadsetCommand::NoiseMode(NoiseMode::Anc),
        )
        .await
        .expect("remote set noise mode");
    assert_eq!(updated.noise_mode, Some(NoiseMode::Anc));
    assert_eq!(
        rig.runtime
            .snapshot()
            .headset
            .and_then(|h| h.readings.confirmed_noise_mode()),
        Some(NoiseMode::Anc)
    );
    assert_eq!(
        rig.headset.readings().get(&HeadsetField::NoiseMode),
        Some(&FieldValue::text("anc"))
    );

    node_a.close().await;
    node_b.close().await;
}

#[tokio::test]
async fn peer_offline_unavailable_then_back_rereads() {
    let hub = LoopbackDiscoveryHub::new();
    let dir_a = TempDir::new("avail-a");
    let dir_b = TempDir::new("avail-b");

    let rig = ControlRig::ready_i5("AA:BB:CC:00:00:05").await;
    let node_a = CirculationNode::with_control(
        CirculationConfig::loopback(&dir_a.path, "Holder"),
        hub.client(),
        Arc::clone(&rig.runtime),
    )
    .expect("node a");
    let node_b = CirculationNode::new(
        CirculationConfig::loopback(&dir_b.path, "Viewer"),
        hub.client(),
    )
    .expect("node b");

    node_a.start().await.expect("start a");
    node_b.start().await.expect("start b");
    wait_until(|| node_b.nearby().iter().any(|p| &p.id == node_a.id())).await;

    let offer = node_a.offer_pairing().expect("offer");
    node_b.pair(node_a.id(), &offer.code).await.expect("pair");
    let initial = node_b.remote_read(node_a.id()).await.expect("initial read");
    assert_eq!(initial.status, RemoteControlStatus::Ready);

    node_a.close().await;
    wait_until(|| node_b.peer_reading(node_a.id()).status == RemoteControlStatus::Unavailable)
        .await;

    rig.runtime
        .set(HeadsetCommand::NoiseMode(NoiseMode::Normal), None)
        .await
        .expect("local set while peer offline");

    node_a.start().await.expect("restart a");
    wait_until(|| {
        let reading = node_b.peer_reading(node_a.id());
        reading.status == RemoteControlStatus::Ready && reading.noise_mode == Some(NoiseMode::Normal)
    })
    .await;

    node_a.close().await;
    node_b.close().await;
}

#[tokio::test]
async fn late_confirmation_after_disconnect_or_replaced_session_refused() {
    let hub = LoopbackDiscoveryHub::new();
    let dir_a = TempDir::new("stale-a");
    let dir_b = TempDir::new("stale-b");

    let rig = ControlRig::ready_i5("AA:BB:CC:00:00:05").await;
    let node_a = CirculationNode::with_control(
        CirculationConfig::loopback(&dir_a.path, "Holder"),
        hub.client(),
        Arc::clone(&rig.runtime),
    )
    .expect("node a");
    let node_b = CirculationNode::new(
        CirculationConfig::loopback(&dir_b.path, "Viewer"),
        hub.client(),
    )
    .expect("node b");

    node_a.start().await.expect("start a");
    node_b.start().await.expect("start b");
    wait_until(|| node_b.nearby().iter().any(|p| &p.id == node_a.id())).await;

    let offer = node_a.offer_pairing().expect("offer");
    node_b.pair(node_a.id(), &offer.code).await.expect("pair");
    let initial = node_b.remote_read(node_a.id()).await.expect("initial read");
    let stale_session = initial.session.expect("initial session");

    rig.runtime.disconnect().await;
    let disconnect_err = node_b
        .remote_set(
            node_a.id(),
            stale_session.clone(),
            HeadsetCommand::NoiseMode(NoiseMode::Anc),
        )
        .await
        .expect_err("stale session after disconnect must fail");
    assert_eq!(disconnect_err, RemoteError::SessionExpired);

    rig.runtime
        .connect(HeadsetId::new("AA:BB:CC:00:00:05"))
        .await
        .expect("reconnect same headset");
    let replaced_err = node_b
        .remote_set(
            node_a.id(),
            stale_session.clone(),
            HeadsetCommand::NoiseMode(NoiseMode::Anc),
        )
        .await
        .expect_err("stale session after replaced session must fail");
    assert_eq!(replaced_err, RemoteError::SessionExpired);

    let second_id = "AA:BB:CC:00:00:06";
    let _second = rig.add_second_i5(second_id);
    rig.transport.audio().set_audio_connected(Some(second_id));
    rig.runtime
        .connect(HeadsetId::new(second_id))
        .await
        .expect("connect second headset");

    let fresh_second = node_b
        .remote_read(node_a.id())
        .await
        .expect("read second headset");
    assert_eq!(fresh_second.headset_id, Some(HeadsetId::new(second_id)));
    let swapped_err = node_b
        .remote_set(
            node_a.id(),
            stale_session,
            HeadsetCommand::NoiseMode(NoiseMode::Anc),
        )
        .await
        .expect_err("stale session on another headset must fail");
    assert_eq!(swapped_err, RemoteError::SessionExpired);

    node_a.close().await;
    node_b.close().await;
}

#[tokio::test]
async fn owner_inactive_refuses_remote_requests() {
    let rig = ControlRig::ready_i5("AA:BB:CC:00:00:05").await;
    let active = Arc::new(AtomicBool::new(false));
    let active_flag = Arc::clone(&active);
    let endpoint = HeadsetEndpoint::with_owner_active(
        PeerId::new("0".repeat(64)),
        Arc::clone(&rig.runtime),
        Arc::new(move || active_flag.load(Ordering::Relaxed)),
    );
    let err = endpoint
        .handle(RemoteCommand::Read)
        .await
        .expect_err("inactive owner must refuse");
    assert_eq!(err, RemoteError::OwnerInactive);

    active.store(true, Ordering::Relaxed);
    let ok = endpoint
        .handle(RemoteCommand::Read)
        .await
        .expect("active owner succeeds");
    assert_eq!(ok.status, RemoteControlStatus::Ready);
}
