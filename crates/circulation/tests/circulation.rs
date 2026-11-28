use async_trait::async_trait;
use openrose_circulation::{
    CirculationConfig, CirculationNode, HeadsetEndpoint, LocalAudio, NearbyPeer, PeerDiscovery,
    PeerId, PullOutcome, RemoteCommand, RemoteControlStatus, RemoteError, RemoteHandler,
    YieldReceipt, YieldRefusal,
};
use openrose_control::audio::RawAudioDevice;
use openrose_control::simulator::{SimulatedClock, SimulatedHeadsetHandle, SimulatedTransport};
use openrose_control::snapshot::{
    ConnectionState, ControlSnapshot, PermissionState, PlatformState, TransportAvailability,
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
        Self::i5(id, true).await
    }

    async fn i5(id: &str, connected: bool) -> Self {
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
        if connected {
            transport.audio().set_audio_connected(Some(id));
        }

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
        if connected {
            runtime
                .connect(HeadsetId::new(id))
                .await
                .expect("connect control");
        }

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

struct ScriptedAudioState {
    headset: String,
    connected: bool,
    media_connected: bool,
    yield_refusal: Option<YieldRefusal>,
    acquire_refusal: Option<YieldRefusal>,
    yield_delay: Duration,
    acquire_delay: Duration,
}

struct ScriptedAudio {
    label: String,
    transport: Arc<SimulatedTransport>,
    events: Arc<Mutex<Vec<String>>>,
    state: Mutex<ScriptedAudioState>,
    yield_gate: tokio::sync::Notify,
}

impl ScriptedAudio {
    fn new(
        label: &str,
        headset: &str,
        connected: bool,
        transport: Arc<SimulatedTransport>,
        events: Arc<Mutex<Vec<String>>>,
    ) -> Arc<Self> {
        Arc::new(Self {
            label: label.to_string(),
            transport,
            events,
            state: Mutex::new(ScriptedAudioState {
                headset: headset.to_string(),
                connected,
                media_connected: connected,
                yield_refusal: None,
                acquire_refusal: None,
                yield_delay: Duration::ZERO,
                acquire_delay: Duration::ZERO,
            }),
            yield_gate: tokio::sync::Notify::new(),
        })
    }

    fn set_connected_only(&self, connected: bool) {
        let mut state = self.state.lock().expect("audio state lock");
        state.connected = connected;
        state.media_connected = false;
    }

    fn set_yield_refusal(&self, refusal: Option<YieldRefusal>) {
        self.state.lock().expect("audio state lock").yield_refusal = refusal;
    }

    fn set_acquire_refusal(&self, refusal: Option<YieldRefusal>) {
        self.state.lock().expect("audio state lock").acquire_refusal = refusal;
    }

    fn set_yield_delay(&self, delay: Duration) {
        self.state.lock().expect("audio state lock").yield_delay = delay;
    }

    fn is_holding(&self) -> bool {
        let state = self.state.lock().expect("audio state lock");
        state.connected && state.media_connected
    }
}

#[async_trait]
impl LocalAudio for ScriptedAudio {
    async fn is_connected(&self, address: &str) -> bool {
        let state = self.state.lock().expect("audio state lock");
        state.connected && state.headset.eq_ignore_ascii_case(address)
    }

    async fn is_media_connected(&self, address: &str) -> bool {
        let state = self.state.lock().expect("audio state lock");
        state.media_connected && state.headset.eq_ignore_ascii_case(address)
    }

    async fn yield_audio(&self, address: &str) -> Result<YieldReceipt, YieldRefusal> {
        self.yield_gate.notify_waiters();
        let (delay, refusal) = {
            let state = self.state.lock().expect("audio state lock");
            (state.yield_delay, state.yield_refusal.clone())
        };
        if !delay.is_zero() {
            tokio::time::sleep(delay).await;
        }
        if let Some(refusal) = refusal {
            return Err(refusal);
        }
        {
            let mut state = self.state.lock().expect("audio state lock");
            state.connected = false;
            state.media_connected = false;
        }
        self.transport.audio().set_audio_connected(None);
        self.events
            .lock()
            .expect("events lock")
            .push(format!("{} yield", self.label));
        Ok(YieldReceipt {
            address: address.to_string(),
        })
    }

    async fn acquire(&self, address: &str) -> Result<(), YieldRefusal> {
        let (delay, refusal) = {
            let state = self.state.lock().expect("audio state lock");
            (state.acquire_delay, state.acquire_refusal.clone())
        };
        if !delay.is_zero() {
            tokio::time::sleep(delay).await;
        }
        if let Some(refusal) = refusal {
            return Err(refusal);
        }
        {
            let mut state = self.state.lock().expect("audio state lock");
            state.connected = true;
            state.media_connected = true;
        }
        self.transport
            .audio()
            .set_audio_connected(Some(address));
        self.events
            .lock()
            .expect("events lock")
            .push(format!("{} acquire", self.label));
        Ok(())
    }
}

struct PullPair {
    _dir_desktop: TempDir,
    _dir_phone: TempDir,
    desktop_rig: ControlRig,
    phone_rig: ControlRig,
    desktop_audio: Arc<ScriptedAudio>,
    phone_audio: Arc<ScriptedAudio>,
    desktop: CirculationNode,
    phone: CirculationNode,
    events: Arc<Mutex<Vec<String>>>,
}

impl PullPair {
    async fn paired(
        headset: &str,
        desktop_holds: bool,
        phone_holds: bool,
        desktop_timeout: Option<Duration>,
    ) -> Self {
        let hub = LoopbackDiscoveryHub::new();
        let dir_desktop = TempDir::new("pull-desktop");
        let dir_phone = TempDir::new("pull-phone");
        let events = Arc::new(Mutex::new(Vec::new()));

        let desktop_rig = ControlRig::i5(headset, desktop_holds).await;
        let phone_rig = ControlRig::i5(headset, phone_holds).await;
        let desktop_audio = ScriptedAudio::new(
            "Desktop",
            headset,
            desktop_holds,
            Arc::clone(&desktop_rig.transport),
            Arc::clone(&events),
        );
        let phone_audio = ScriptedAudio::new(
            "Phone",
            headset,
            phone_holds,
            Arc::clone(&phone_rig.transport),
            Arc::clone(&events),
        );

        let mut desktop_cfg = CirculationConfig::loopback(&dir_desktop.path, "Desktop");
        if let Some(timeout) = desktop_timeout {
            desktop_cfg = desktop_cfg.with_response_timeout(timeout);
        }
        let desktop = CirculationNode::with_control_and_audio(
            desktop_cfg,
            hub.client(),
            Arc::clone(&desktop_rig.runtime),
            desktop_audio.clone(),
        )
        .expect("desktop node");
        let phone = CirculationNode::with_control_and_audio(
            CirculationConfig::loopback(&dir_phone.path, "Phone"),
            hub.client(),
            Arc::clone(&phone_rig.runtime),
            phone_audio.clone(),
        )
        .expect("phone node");

        desktop.start().await.expect("start desktop");
        phone.start().await.expect("start phone");
        wait_until(|| phone.nearby().iter().any(|p| &p.id == desktop.id())).await;

        let offer = desktop.offer_pairing().expect("offer");
        phone.pair_qr(&offer.qr).await.expect("pair");
        wait_until(|| desktop.nearby().iter().any(|p| &p.id == phone.id())).await;

        Self {
            _dir_desktop: dir_desktop,
            _dir_phone: dir_phone,
            desktop_rig,
            phone_rig,
            desktop_audio,
            phone_audio,
            desktop,
            phone,
            events,
        }
    }

    async fn close(self) {
        self.desktop.close().await;
        self.phone.close().await;
    }
}

#[tokio::test]
async fn pull_receipt_then_acquire_moves_audio_and_control() {
    let headset = "AA:BB:CC:00:00:05";
    let pair = PullPair::paired(headset, false, true, None).await;

    pair.desktop_audio.set_connected_only(true);
    let outcome = pair.desktop.pull(headset).await.expect("pull should succeed");
    assert_eq!(outcome, PullOutcome::Acquired);
    assert_eq!(
        *pair.events.lock().expect("events lock"),
        vec!["Phone yield".to_string(), "Desktop acquire".to_string()]
    );
    assert!(!pair.phone_audio.is_holding());
    assert!(pair.desktop_audio.is_holding());

    wait_until(|| {
        matches!(
            pair.phone_rig.runtime.snapshot().connection,
            ConnectionState::Disconnected
        ) && pair.desktop_rig.runtime.snapshot().ready_session().is_some()
    })
    .await;

    assert_eq!(
        pair.desktop.pull(headset).await.expect("already connected"),
        PullOutcome::AlreadyConnected
    );

    let back = pair
        .phone
        .pull(headset)
        .await
        .expect("pull back to phone");
    assert_eq!(back, PullOutcome::Acquired);
    wait_until(|| {
        matches!(
            pair.desktop_rig.runtime.snapshot().connection,
            ConnectionState::Disconnected
        ) && pair.phone_rig.runtime.snapshot().ready_session().is_some()
    })
    .await;

    pair.close().await;
}

#[tokio::test]
async fn pull_refused_in_call_and_mic_in_use_keep_holder_connected() {
    let headset = "AA:BB:CC:00:00:06";
    let pair = PullPair::paired(headset, false, true, None).await;

    pair.phone_audio
        .set_yield_refusal(Some(YieldRefusal::InCall));
    assert_eq!(
        pair.desktop.pull(headset).await,
        Err(RemoteError::YieldRefused(YieldRefusal::InCall))
    );
    assert!(pair.phone_audio.is_holding());
    assert!(!pair.desktop_audio.is_holding());
    assert!(pair.phone_rig.runtime.snapshot().ready_session().is_some());
    assert!(pair.desktop_rig.runtime.snapshot().ready_session().is_none());
    assert!(pair.events.lock().expect("events lock").is_empty());

    pair.phone_audio
        .set_yield_refusal(Some(YieldRefusal::MicInUse));
    assert_eq!(
        pair.desktop.pull(headset).await,
        Err(RemoteError::YieldRefused(YieldRefusal::MicInUse))
    );
    assert!(pair.phone_audio.is_holding());
    assert!(!pair.desktop_audio.is_holding());
    assert!(pair.phone_rig.runtime.snapshot().ready_session().is_some());
    assert!(pair.desktop_rig.runtime.snapshot().ready_session().is_none());
    assert!(pair.events.lock().expect("events lock").is_empty());

    pair.close().await;
}

#[tokio::test]
async fn pull_when_nobody_holds_acquires_directly() {
    let headset = "AA:BB:CC:00:00:07";
    let pair = PullPair::paired(headset, false, false, None).await;

    assert_eq!(
        pair.desktop.pull(headset).await,
        Ok(PullOutcome::Acquired)
    );
    assert_eq!(
        *pair.events.lock().expect("events lock"),
        vec!["Desktop acquire".to_string()]
    );
    assert!(pair.desktop_audio.is_holding());
    assert!(!pair.phone_audio.is_holding());
    wait_until(|| pair.desktop_rig.runtime.snapshot().ready_session().is_some()).await;

    pair.close().await;
}

#[tokio::test]
async fn pull_when_nobody_reachable_still_attempts_acquire() {
    let headset = "AA:BB:CC:00:00:08";
    let pair = PullPair::paired(headset, false, false, None).await;

    pair.phone.close().await;
    wait_until(|| pair.desktop.nearby().is_empty()).await;

    assert_eq!(
        pair.desktop.pull(headset).await,
        Ok(PullOutcome::AcquiredWithPeersUnreachable)
    );
    assert_eq!(
        *pair.events.lock().expect("events lock"),
        vec!["Desktop acquire".to_string()]
    );
    assert!(pair.desktop_audio.is_holding());
    wait_until(|| pair.desktop_rig.runtime.snapshot().ready_session().is_some()).await;

    pair.desktop.close().await;
}

#[tokio::test]
async fn pull_acquire_failure_returns_error_and_never_switches_back() {
    let headset = "AA:BB:CC:00:00:09";
    let pair = PullPair::paired(headset, false, true, None).await;
    let refusal = YieldRefusal::Unavailable("a2dp_connect_failed".to_string());
    pair.desktop_audio
        .set_acquire_refusal(Some(refusal.clone()));

    assert_eq!(
        pair.desktop.pull(headset).await,
        Err(RemoteError::AcquireFailed(refusal))
    );
    assert_eq!(
        *pair.events.lock().expect("events lock"),
        vec!["Phone yield".to_string()]
    );
    assert!(!pair.phone_audio.is_holding());
    assert!(!pair.desktop_audio.is_holding());
    wait_until(|| {
        matches!(
            pair.phone_rig.runtime.snapshot().connection,
            ConnectionState::Disconnected
        )
    })
    .await;
    assert!(pair.desktop_rig.runtime.snapshot().ready_session().is_none());

    pair.close().await;
}

#[tokio::test]
async fn pull_timeout_returns_error_without_acquiring() {
    let headset = "AA:BB:CC:00:00:0A";
    let pair = PullPair::paired(headset, false, true, Some(Duration::from_millis(120))).await;
    pair.phone_audio
        .set_yield_delay(Duration::from_millis(350));

    assert_eq!(pair.desktop.pull(headset).await, Err(RemoteError::Timeout));
    assert!(!pair.desktop_audio.is_holding());
    assert!(pair.desktop_rig.runtime.snapshot().ready_session().is_none());

    pair.close().await;
}

#[tokio::test]
async fn concurrent_pull_is_refused() {
    let headset = "AA:BB:CC:00:00:0B";
    let pair = PullPair::paired(headset, false, true, None).await;
    pair.phone_audio
        .set_yield_delay(Duration::from_millis(120));

    let first_notified = pair.phone_audio.yield_gate.notified();
    let desktop_first = pair.desktop.clone();
    let first = tokio::spawn(async move { desktop_first.pull(headset).await });
    first_notified.await;

    assert_eq!(
        pair.desktop.pull(headset).await,
        Err(RemoteError::PullInProgress)
    );
    assert_eq!(
        first.await.expect("join first pull"),
        Ok(PullOutcome::Acquired)
    );
    assert!(pair.desktop_audio.is_holding());
    assert!(!pair.phone_audio.is_holding());

    pair.close().await;
}
