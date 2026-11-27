mod support;

use async_trait::async_trait;
use openrose_control::ControlErrorCode;
use openrose_control::audio::{AudioEvents, AudioFactsDriver, RawAudioDevice, RawAudioSnapshot};
use openrose_control::gatt::GattDriver;
use openrose_control::scan::{Advertisement, ScanDriver};
use openrose_control::simulator::SimulatedTransport;
use openrose_control::snapshot::{ConnectionPhase, ConnectionState, ScanState};
use openrose_control::transport::{ControlTransport, TransportError};
use openrose_headset::HeadsetId;
use std::collections::BTreeMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use support::scripted::{A, ScriptedTransport, add_i5, id, runtime_over, simulated};
use support::{Harness, Options, available, elapse, settle};
use tokio::sync::watch;

fn is_connected(snapshot: &openrose_control::snapshot::ControlSnapshot) -> bool {
    matches!(snapshot.connection, ConnectionState::Connected { .. })
}

#[tokio::test(start_paused = true)]
async fn the_add_flow_reports_success_only_for_the_session_that_is_still_current() {
    let harness = Harness::i5();
    harness
        .runtime
        .connect_for_add_headset(harness.id())
        .await
        .expect("added");
    let snapshot = harness.snapshot();
    assert!(is_connected(&snapshot));
    assert_eq!(Some(harness.id()), snapshot.connection_target.map(|t| t.id));
}

#[tokio::test(start_paused = true)]
async fn cancelling_the_add_flow_closes_its_unfinished_link_and_stops_initialization() {
    let harness = Harness::new(Options::i5().paced());
    harness.headset.mute(true);
    let adding = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect_for_add_headset(id).await }
    });
    settle().await;
    elapse(200).await;
    let ConnectionState::Connecting { phase, .. } = harness.snapshot().connection else {
        panic!("the add flow should still be initializing");
    };
    assert_eq!(ConnectionPhase::Initializing, phase);
    let writes_before_cancel = harness.headset.written_hex();

    harness.runtime.cancel_add_headset().await;
    assert_eq!(
        ControlErrorCode::Disconnected,
        adding.await.unwrap().expect_err("cancelled").code
    );
    tokio::time::advance(Duration::from_secs(20)).await;
    settle().await;

    let snapshot = harness.snapshot();
    assert_eq!(ConnectionState::Disconnected, snapshot.connection);
    assert!(snapshot.headset.is_none());
    assert_eq!(None, snapshot.last_error);
    assert!(!harness.headset.link_open());
    assert_eq!(writes_before_cancel, harness.headset.written_hex());
}

#[tokio::test(start_paused = true)]
async fn cancelling_the_add_flow_does_not_cancel_a_connection_another_entry_started() {
    let (transport, runtime, _) = simulated(available());
    let headset = add_i5(&transport, A);
    headset.open_delay(Duration::from_secs(1));
    settle().await;

    let original = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect(id(A)).await }
    });
    settle().await;
    let adding = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect_for_add_headset(id(A)).await }
    });
    settle().await;

    runtime.cancel_add_headset().await;
    elapse(1_500).await;

    original
        .await
        .unwrap()
        .expect("the original caller keeps its connection");
    adding
        .await
        .unwrap()
        .expect("the add flow shares the same session");
    assert!(is_connected(&runtime.snapshot()));
    assert!(headset.link_open());
}

#[tokio::test(start_paused = true)]
async fn a_failed_old_add_flow_never_unbinds_the_newer_audio_session_of_the_same_headset() {
    let (transport, runtime, _) = simulated(available());
    let headset = add_i5(&transport, A);
    headset.open_delay(Duration::from_millis(500));
    settle().await;

    let adding = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect_for_add_headset(id(A)).await }
    });
    settle().await;
    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connecting { .. }
    ));

    runtime.disconnect().await;
    headset.open_delay(Duration::ZERO);
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(1_000).await;
    assert_eq!(
        ControlErrorCode::Disconnected,
        adding.await.unwrap().expect_err("replaced add flow").code
    );
    let replacement = runtime.snapshot();
    assert!(is_connected(&replacement));

    transport.set_audio_connected(None);
    settle().await;
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert!(!headset.link_open());
}

struct GateBondedAudio {
    inner: Arc<dyn AudioFactsDriver>,
    open: watch::Receiver<bool>,
}

#[async_trait]
impl AudioFactsDriver for GateBondedAudio {
    async fn subscribe(&self) -> Box<dyn AudioEvents> {
        self.inner.subscribe().await
    }

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError> {
        let _ = self.open.clone().wait_for(|open| *open).await;
        self.inner.bonded_devices().await
    }

    async fn snapshot(&self, target_addresses: &[String]) -> RawAudioSnapshot {
        self.inner.snapshot(target_addresses).await
    }
}

#[tokio::test(start_paused = true)]
async fn cancelling_the_add_flow_during_candidate_lookup_prevents_any_link_from_opening() {
    let sim = Arc::new(SimulatedTransport::new());
    let (gate, open) = watch::channel(true);
    let audio = Arc::new(GateBondedAudio {
        inner: sim.audio_facts(),
        open,
    });
    let transport = ScriptedTransport::new(Arc::clone(&sim), audio);
    let (runtime, _) = runtime_over(transport, available());
    settle().await;

    gate.send_replace(false);
    let headset = add_i5(&sim, A);
    let adding = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect_for_add_headset(id(A)).await }
    });
    settle().await;
    assert!(!adding.is_finished());
    assert_eq!(0, headset.connections_opened());

    runtime.cancel_add_headset().await;
    settle().await;
    assert!(
        adding.is_finished(),
        "cancel_add_headset must stop an add flow waiting in candidate lookup"
    );
    assert_eq!(
        ControlErrorCode::Disconnected,
        adding
            .await
            .unwrap()
            .expect_err("cancelled during lookup")
            .code
    );

    gate.send_replace(true);
    settle().await;
    elapse(1_000).await;

    assert_eq!(0, headset.connections_opened());
    assert!(!headset.link_open());
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(None, runtime.snapshot().last_error);

    runtime
        .connect_for_add_headset(id(A))
        .await
        .expect("a later add flow can still connect");
    assert!(is_connected(&runtime.snapshot()));
}

struct GatedStopScan {
    inner: Box<dyn ScanDriver>,
    stop_started: Arc<AtomicBool>,
    stop_gate: watch::Receiver<bool>,
}

#[async_trait]
impl ScanDriver for GatedStopScan {
    async fn next_advertisement(&self) -> Result<Option<Advertisement>, TransportError> {
        self.inner.next_advertisement().await
    }

    async fn stop(&self) {
        self.stop_started.store(true, Ordering::SeqCst);
        let _ = self.stop_gate.clone().wait_for(|open| *open).await;
        self.inner.stop().await;
    }
}

struct GatedStopScanTransport {
    sim: Arc<SimulatedTransport>,
    stop_started: Arc<AtomicBool>,
    stop_gate: watch::Receiver<bool>,
}

#[async_trait]
impl ControlTransport for GatedStopScanTransport {
    async fn open_gatt(&self, headset: &HeadsetId) -> Result<Box<dyn GattDriver>, TransportError> {
        self.sim.open_gatt(headset).await
    }

    async fn start_scan(&self) -> Result<Box<dyn ScanDriver>, TransportError> {
        let inner = self.sim.start_scan().await?;
        Ok(Box::new(GatedStopScan {
            inner,
            stop_started: Arc::clone(&self.stop_started),
            stop_gate: self.stop_gate.clone(),
        }))
    }

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver> {
        self.sim.audio_facts()
    }
}

#[tokio::test(start_paused = true)]
async fn cancelling_the_add_flow_during_scan_stop_prevents_any_link_from_opening() {
    let sim = Arc::new(SimulatedTransport::new());
    let headset = add_i5(&sim, A);
    sim.audio().unpair(A);
    let stop_started = Arc::new(AtomicBool::new(false));
    let (release_stop, stop_gate) = watch::channel(false);
    let transport = Arc::new(GatedStopScanTransport {
        sim: Arc::clone(&sim),
        stop_started: Arc::clone(&stop_started),
        stop_gate,
    });
    let (runtime, _) = runtime_over(transport, available());
    settle().await;

    runtime.start_scan().await.expect("scan started");
    sim.scan().advertise(Advertisement {
        manufacturer_data: BTreeMap::from([(0x5352, vec![0x00, 0x01, 0x00, 0x01])]),
        ..Advertisement::new(id(A))
    });
    settle().await;

    let adding = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect_for_add_headset(id(A)).await }
    });
    settle().await;
    assert!(stop_started.load(Ordering::SeqCst));
    assert!(!adding.is_finished());
    assert_eq!(0, headset.connections_opened());

    runtime.cancel_add_headset().await;
    settle().await;
    assert!(
        adding.is_finished(),
        "cancel_add_headset must stop an add flow waiting in scan stop"
    );
    assert_eq!(
        ControlErrorCode::Disconnected,
        adding
            .await
            .unwrap()
            .expect_err("cancelled during scan stop")
            .code
    );

    let _ = release_stop.send(true);
    settle().await;
    elapse(1_000).await;

    assert_eq!(0, headset.connections_opened());
    assert!(!headset.link_open());
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(ScanState::Idle, runtime.snapshot().scan);
    assert_eq!(None, runtime.snapshot().last_error);
}
