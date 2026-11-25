mod support;

use async_trait::async_trait;
use openrose_control::gatt::{
    GattCharacteristic, GattConnection, GattDriver, GattError, GattEvent, GattFailureKind,
    GattService, GattSubscription,
};
use openrose_control::transport::{
    ControlConnection, OpenFailureKind, TransportError, TransportRecoveryReason,
};
use openrose_headset::GattProfile;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use support::settle;
use tokio::sync::{mpsc, watch};
use tokio::time::{Instant, advance};
use tokio_util::sync::CancellationToken;

#[derive(Clone, Debug)]
enum GateState {
    Closed,
    Open,
    Failed(GattError),
}

struct Gate(watch::Sender<GateState>);

impl Gate {
    fn closed() -> Arc<Self> {
        Arc::new(Self(watch::channel(GateState::Closed).0))
    }

    fn open(&self) {
        self.0.send_replace(GateState::Open);
    }

    fn fail(&self, error: GattError) {
        self.0.send_replace(GateState::Failed(error));
    }

    async fn wait(&self) -> Result<(), GattError> {
        let mut receiver = self.0.subscribe();
        let state = receiver
            .wait_for(|state| !matches!(state, GateState::Closed))
            .await
            .expect("gate sender alive")
            .clone();
        match state {
            GateState::Failed(error) => Err(error),
            _ => Ok(()),
        }
    }
}

#[derive(Default)]
struct Config {
    missing_discoveries: usize,
    negotiated_mtu: Option<u16>,
    discovered_services: Vec<GattService>,
    write_failure: Option<GattError>,
    discovery_gate: Option<Arc<Gate>>,
    subscription_gate: Option<Arc<Gate>>,
    write_gate: Option<Arc<Gate>>,
    mtu_gate: Option<Arc<Gate>>,
    close_gate: Option<Arc<Gate>>,
    refuse_close: bool,
}

struct FakeState {
    config: Mutex<Config>,
    discoveries: AtomicUsize,
    writes: Mutex<Vec<String>>,
    routing: AtomicBool,
    remote: Mutex<GattSubscription>,
    closed: AtomicBool,
    incoming: Mutex<Option<mpsc::UnboundedSender<GattEvent>>>,
}

impl FakeState {
    fn new() -> Arc<Self> {
        Arc::new(Self {
            config: Mutex::new(Config {
                negotiated_mtu: Some(185),
                discovered_services: default_services(),
                ..Config::default()
            }),
            discoveries: AtomicUsize::new(0),
            writes: Mutex::new(Vec::new()),
            routing: AtomicBool::new(false),
            remote: Mutex::new(GattSubscription::Disabled),
            closed: AtomicBool::new(false),
            incoming: Mutex::new(None),
        })
    }

    fn configure(&self, update: impl FnOnce(&mut Config)) {
        update(&mut self.config.lock().unwrap());
    }

    fn send(&self, event: GattEvent) {
        if let Some(sender) = self.incoming.lock().unwrap().as_ref() {
            let _ = sender.send(event);
        }
    }

    fn writes(&self) -> Vec<String> {
        self.writes.lock().unwrap().clone()
    }

    fn driver(self: &Arc<Self>) -> Box<dyn GattDriver> {
        let (sender, receiver) = mpsc::unbounded_channel();
        *self.incoming.lock().unwrap() = Some(sender);
        Box::new(FakeGattDriver {
            state: Arc::clone(self),
            events: tokio::sync::Mutex::new(receiver),
        })
    }
}

struct FakeGattDriver {
    state: Arc<FakeState>,
    events: tokio::sync::Mutex<mpsc::UnboundedReceiver<GattEvent>>,
}

fn gate_of(
    state: &FakeState,
    pick: impl FnOnce(&Config) -> Option<Arc<Gate>>,
) -> Option<Arc<Gate>> {
    pick(&state.config.lock().unwrap())
}

#[async_trait]
impl GattDriver for FakeGattDriver {
    async fn next_event(&self) -> Option<GattEvent> {
        self.events.lock().await.recv().await
    }

    async fn connect(&self) -> Result<(), GattError> {
        Ok(())
    }

    async fn discover_services(&self) -> Result<Vec<GattService>, GattError> {
        let attempt = self.state.discoveries.fetch_add(1, Ordering::SeqCst) + 1;
        if let Some(gate) = gate_of(&self.state, |c| c.discovery_gate.clone()) {
            gate.wait().await?;
        }
        let config = self.state.config.lock().unwrap();
        Ok(if attempt <= config.missing_discoveries {
            Vec::new()
        } else {
            config.discovered_services.clone()
        })
    }

    async fn request_mtu(&self, _preferred: u16) -> Result<Option<u16>, GattError> {
        if let Some(gate) = gate_of(&self.state, |c| c.mtu_gate.clone()) {
            gate.wait().await?;
        }
        Ok(self.state.config.lock().unwrap().negotiated_mtu)
    }

    async fn set_notification_routing(
        &self,
        _characteristic: &GattCharacteristic,
        enabled: bool,
    ) -> Result<(), GattError> {
        self.state.routing.store(enabled, Ordering::SeqCst);
        Ok(())
    }

    async fn configure_subscription(
        &self,
        _characteristic: &GattCharacteristic,
        subscription: GattSubscription,
    ) -> Result<(), GattError> {
        assert!(
            self.state.routing.load(Ordering::SeqCst),
            "local routing must precede remote configuration"
        );
        *self.state.remote.lock().unwrap() = subscription;
        if subscription != GattSubscription::Disabled
            && let Some(gate) = gate_of(&self.state, |c| c.subscription_gate.clone())
        {
            gate.wait().await?;
        }
        Ok(())
    }

    async fn write(
        &self,
        _characteristic: &GattCharacteristic,
        value: &[u8],
        _with_response: bool,
    ) -> Result<(), GattError> {
        if let Some(error) = self.state.config.lock().unwrap().write_failure.clone() {
            return Err(error);
        }
        self.state
            .writes
            .lock()
            .unwrap()
            .push(openrose_headset::encode_hex(value));
        if let Some(gate) = gate_of(&self.state, |c| c.write_gate.clone()) {
            gate.wait().await?;
        }
        Ok(())
    }

    async fn close(&self) -> Result<(), GattError> {
        if let Some(gate) = gate_of(&self.state, |c| c.close_gate.clone()) {
            gate.wait().await?;
        }
        if self.state.config.lock().unwrap().refuse_close {
            return Err(GattError::new(GattFailureKind::Unknown, "refused"));
        }
        self.state.closed.store(true, Ordering::SeqCst);
        if let Some(gate) = gate_of(&self.state, |c| c.write_gate.clone()) {
            gate.fail(GattError::new(GattFailureKind::Disconnected, "closed"));
        }
        self.state.incoming.lock().unwrap().take();
        Ok(())
    }
}

fn characteristic(id: u64, uuid: &str, can_write: bool, can_notify: bool) -> GattCharacteristic {
    GattCharacteristic {
        instance_id: id,
        uuid: uuid.to_string(),
        can_write,
        can_write_without_response: false,
        can_notify,
        can_indicate: false,
    }
}

fn default_services() -> Vec<GattService> {
    vec![GattService {
        uuid: "service".to_string(),
        characteristics: vec![
            characteristic(1, "write", true, false),
            characteristic(2, "notify", false, true),
        ],
    }]
}

fn profile() -> GattProfile {
    GattProfile {
        service_uuid: "service".to_string(),
        write_uuid: "write".to_string(),
        notify_uuid: "notify".to_string(),
    }
}

async fn open(state: &Arc<FakeState>) -> Arc<GattConnection> {
    GattConnection::open(
        state.driver(),
        profile(),
        &|_| {},
        &CancellationToken::new(),
    )
    .await
    .expect("open")
}

fn recoverable(error: &TransportError) -> TransportRecoveryReason {
    match error {
        TransportError::Recoverable { reason, .. } => *reason,
        other => panic!("expected a recoverable failure, got {other:?}"),
    }
}

#[tokio::test(start_paused = true)]
async fn missing_services_are_retried_twice_before_the_connection_can_write() {
    let state = FakeState::new();
    state.configure(|c| c.missing_discoveries = 2);
    let connection = open(&state).await;
    connection.subscribe().await.expect("subscribe");
    connection.write(&[0x12, 0x34]).await.expect("write");
    assert_eq!(vec!["1234".to_string()], state.writes());
    assert_eq!(3, state.discoveries.load(Ordering::SeqCst));
    connection.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn negotiated_mtu_bounds_complete_packets_and_falls_back_when_unavailable() {
    for (mtu, capacity) in [
        (None, 20),
        (Some(22), 20),
        (Some(23), 20),
        (Some(185), 182),
        (Some(517), 512),
    ] {
        let state = FakeState::new();
        state.configure(|c| c.negotiated_mtu = mtu);
        let connection = open(&state).await;
        assert_eq!(capacity, connection.max_write_size(), "{mtu:?}");
        connection.write(&vec![1; capacity]).await.expect("write");
        connection.close().await.expect("close");
    }
}

#[tokio::test(start_paused = true)]
async fn subscription_deadline_rolls_back_remote_and_local_delivery_before_failing() {
    let state = FakeState::new();
    state.configure(|c| c.subscription_gate = Some(Gate::closed()));
    let connection = open(&state).await;
    let subscribing = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.subscribe().await }
    });
    settle().await;
    assert!(state.routing.load(Ordering::SeqCst));
    advance(Duration::from_millis(4_999)).await;
    settle().await;
    assert!(!subscribing.is_finished());
    advance(Duration::from_millis(1)).await;
    settle().await;
    assert!(
        subscribing.is_finished(),
        "subscription must fail at 5 seconds"
    );
    let failure = subscribing.await.unwrap().expect_err("deadline");
    assert_eq!(TransportRecoveryReason::Subscription, recoverable(&failure));
    assert!(!state.routing.load(Ordering::SeqCst));
    assert_eq!(GattSubscription::Disabled, *state.remote.lock().unwrap());
    connection.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn write_failures_only_recover_for_explicitly_transient_native_errors() {
    for kind in [
        GattFailureKind::Transient,
        GattFailureKind::Permission,
        GattFailureKind::Unsupported,
        GattFailureKind::InvalidValue,
        GattFailureKind::Disconnected,
        GattFailureKind::Unknown,
    ] {
        let state = FakeState::new();
        state.configure(|c| c.write_failure = Some(GattError::new(kind, "native failure")));
        let connection = open(&state).await;
        let failure = connection.write(&[1]).await.expect_err("write failure");
        match (kind, &failure) {
            (GattFailureKind::Transient, TransportError::Recoverable { reason, .. }) => {
                assert_eq!(TransportRecoveryReason::WriteFailed, *reason)
            }
            (GattFailureKind::Permission, TransportError::PermissionDenied(_))
            | (GattFailureKind::Unsupported, TransportError::Unsupported(_))
            | (GattFailureKind::InvalidValue, TransportError::InvalidArgument(_))
            | (
                GattFailureKind::Disconnected | GattFailureKind::Unknown,
                TransportError::Terminal(_),
            ) => {}
            other => panic!("unexpected classification {other:?}"),
        }
        connection.close().await.expect("close");
    }
}

#[tokio::test(start_paused = true)]
async fn services_changed_during_discovery_discard_the_snapshot_and_later_invalidate_the_link() {
    let state = FakeState::new();
    let gate = Gate::closed();
    state.configure(|c| c.discovery_gate = Some(gate.clone()));
    let opening = tokio::spawn({
        let driver = state.driver();
        async move { GattConnection::open(driver, profile(), &|_| {}, &CancellationToken::new()).await }
    });
    settle().await;
    state.send(GattEvent::ServicesChanged);
    settle().await;
    gate.open();
    let connection = opening.await.unwrap().expect("open");
    assert_eq!(2, state.discoveries.load(Ordering::SeqCst));

    let receiving = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.next_notification().await }
    });
    state.send(GattEvent::ServicesChanged);
    settle().await;
    assert!(
        receiving.is_finished(),
        "a changed service database must end the old connection"
    );
    let ended = receiving.await.unwrap().expect_err("ended");
    assert_eq!(
        TransportRecoveryReason::ServicesChanged,
        recoverable(&ended)
    );
    let failure = connection
        .write(&[1])
        .await
        .expect_err("write after change");
    assert_eq!(
        TransportRecoveryReason::ServicesChanged,
        recoverable(&failure)
    );
    assert!(state.writes().is_empty());
    connection.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn concurrent_commands_wait_for_native_completion_in_submission_order() {
    let state = FakeState::new();
    let gate = Gate::closed();
    state.configure(|c| c.write_gate = Some(gate.clone()));
    let connection = open(&state).await;
    let first = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.write(&[1]).await }
    });
    settle().await;
    let second = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.write(&[2]).await }
    });
    settle().await;
    assert_eq!(vec!["01".to_string()], state.writes());
    assert!(!second.is_finished());
    gate.open();
    first.await.unwrap().expect("first");
    second.await.unwrap().expect("second");
    assert_eq!(vec!["01".to_string(), "02".to_string()], state.writes());
    connection.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn exhausted_discovery_closes_the_driver_and_preserves_the_recovery_reason() {
    let state = FakeState::new();
    state.configure(|c| c.missing_discoveries = 10);
    let failure = GattConnection::open(
        state.driver(),
        profile(),
        &|_| {},
        &CancellationToken::new(),
    )
    .await
    .expect_err("no services");
    let OpenFailureKind::Transport(error) = failure.kind else {
        panic!("expected a transport failure");
    };
    assert_eq!(
        TransportRecoveryReason::ServicesUnavailable,
        recoverable(&error)
    );
    assert!(failure.unreleased.is_none());
    assert_eq!(3, state.discoveries.load(Ordering::SeqCst));
    assert!(state.closed.load(Ordering::SeqCst));
}

#[tokio::test(start_paused = true)]
async fn a_driver_that_refuses_to_close_is_handed_back_as_unreleased_and_closes_on_retry() {
    let state = FakeState::new();
    state.configure(|c| {
        c.missing_discoveries = 10;
        c.refuse_close = true;
    });
    let failure = GattConnection::open(
        state.driver(),
        profile(),
        &|_| {},
        &CancellationToken::new(),
    )
    .await
    .expect_err("no services");
    let unreleased = failure.unreleased.expect("close was refused");
    assert!(!state.closed.load(Ordering::SeqCst));
    state.configure(|c| c.refuse_close = false);
    unreleased.close().await.expect("retry");
    assert!(state.closed.load(Ordering::SeqCst));
}

#[tokio::test(start_paused = true)]
async fn cancelling_an_open_in_flight_releases_the_half_open_driver() {
    let state = FakeState::new();
    state.configure(|c| c.discovery_gate = Some(Gate::closed()));
    let cancel = CancellationToken::new();
    let opening = tokio::spawn({
        let driver = state.driver();
        let cancel = cancel.clone();
        async move { GattConnection::open(driver, profile(), &|_| {}, &cancel).await }
    });
    settle().await;
    cancel.cancel();
    let failure = opening.await.unwrap().expect_err("cancelled");
    assert!(matches!(failure.kind, OpenFailureKind::Cancelled));
    assert!(state.closed.load(Ordering::SeqCst));
}

#[tokio::test(start_paused = true)]
async fn mtu_timeout_falls_back_without_blocking_subscription_or_small_packets() {
    let state = FakeState::new();
    state.configure(|c| c.mtu_gate = Some(Gate::closed()));
    let started = Instant::now();
    let connection = open(&state).await;
    assert_eq!(Duration::from_millis(1_500), started.elapsed());
    assert_eq!(20, connection.max_write_size());
    connection.subscribe().await.expect("subscribe");
    connection.write(&[1; 20]).await.expect("write");
    assert!(state.routing.load(Ordering::SeqCst));
    assert_eq!(GattSubscription::Notify, *state.remote.lock().unwrap());
    connection.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn service_change_during_a_pending_write_preserves_the_shared_failure() {
    let state = FakeState::new();
    state.configure(|c| c.write_gate = Some(Gate::closed()));
    let connection = open(&state).await;
    let writing = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.write(&[1]).await }
    });
    settle().await;
    state.send(GattEvent::ServicesChanged);
    settle().await;
    let failure = writing.await.unwrap().expect_err("pending write");
    assert_eq!(
        TransportRecoveryReason::ServicesChanged,
        recoverable(&failure)
    );
    connection.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn notifications_retain_characteristic_identity_when_services_reuse_a_uuid() {
    let state = FakeState::new();
    state.configure(|c| {
        c.discovered_services = vec![
            GattService {
                uuid: "service".to_string(),
                characteristics: vec![
                    characteristic(1, "write", true, false),
                    characteristic(2, "notify", false, true),
                ],
            },
            GattService {
                uuid: "another-service".to_string(),
                characteristics: vec![characteristic(3, "notify", false, true)],
            },
        ];
    });
    let connection = open(&state).await;
    connection.subscribe().await.expect("subscribe");
    let next = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.next_notification().await }
    });
    state.send(GattEvent::Notification {
        instance_id: 3,
        value: vec![0x55],
    });
    state.send(GattEvent::Notification {
        instance_id: 2,
        value: vec![0x12],
    });
    assert_eq!(vec![0x12], next.await.unwrap().expect("notification"));
    connection.close().await.expect("close");
}
