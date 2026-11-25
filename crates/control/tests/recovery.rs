mod support;

use openrose_control::gatt::{GattError, GattEvent, GattFailureKind};
use openrose_control::simulator::{SimulatedHeadsetHandle, SimulatedTransport};
use openrose_control::snapshot::{ConnectionState, ControlSnapshot};
use openrose_control::transport::TransportRecoveryReason;
use openrose_control::{ControlErrorCode, ControlObserver, ControlRuntime, SessionTiming};
use openrose_headset::{FieldReading, HeadsetCommand, HeadsetField, NoiseMode};
use std::sync::Arc;
use std::time::Duration;
use support::faults::{FaultyTransport, LinkFaults};
use support::scripted::{A, add_i5, id, runtime_timed};
use support::{I5_INIT_WRITES, available, elapse, settle};

const BATTERY_QUERY: &str = "08ee00000001010a0002";
const NOISE_REPLY: &str = "09ff00000106020e000000010020";
const UNACCEPTED_BATTERY_REPLY: &str = "000000000001010000aabb00ff0000ff";

struct Rig {
    transport: Arc<FaultyTransport>,
    runtime: Arc<ControlRuntime>,
    headset: SimulatedHeadsetHandle,
}

fn timing(query_ms: u64) -> SessionTiming {
    SessionTiming {
        write_interval: Some(Duration::ZERO),
        query_timeout: Duration::from_millis(query_ms),
        write_timeout: Duration::from_millis(50),
        ..SessionTiming::default()
    }
}

fn rig(
    recovery: bool,
    query_ms: u64,
    plan: impl Fn(usize) -> LinkFaults + Send + Sync + 'static,
) -> Rig {
    let sim = Arc::new(SimulatedTransport::new());
    let headset = add_i5(&sim, A);
    let transport = FaultyTransport::new(Arc::clone(&sim), recovery, plan);
    let (runtime, _) = runtime_timed(transport.clone(), available(), timing(query_ms));
    Rig {
        transport,
        runtime,
        headset,
    }
}

fn first_link_only(faults: LinkFaults) -> impl Fn(usize) -> LinkFaults + Send + Sync + 'static {
    move |number| {
        if number == 1 {
            faults.clone()
        } else {
            LinkFaults::default()
        }
    }
}

fn gatt(kind: GattFailureKind, message: &str) -> GattError {
    GattError::new(kind, message)
}

fn recovery_reason(snapshot: &ControlSnapshot) -> Option<TransportRecoveryReason> {
    snapshot
        .connection_recovery
        .as_ref()
        .map(|recovery| recovery.reason)
}

fn writes(rig: &Rig, link: usize) -> Vec<String> {
    rig.transport.links()[link].writes()
}

fn error_of(result: Result<(), openrose_control::ControlError>) -> openrose_control::ControlError {
    result.expect_err("the connection should fail")
}

#[tokio::test(start_paused = true)]
async fn a_typed_subscription_failure_gets_one_fresh_link_and_reruns_initialization() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            fail_subscription: Some(gatt(GattFailureKind::Transient, "synthetic CCCD failure")),
            ..LinkFaults::default()
        }),
    );
    rig.runtime.connect(id(A)).await.expect("recovered");

    let links = rig.transport.links();
    assert_eq!(2, links.len());
    assert!(links[0].closed());
    assert!(rig.transport.previous_links_closed_at_every_open());
    assert!(writes(&rig, 0).is_empty());
    assert_eq!(I5_INIT_WRITES, writes(&rig, 1).len());
    let snapshot = rig.runtime.snapshot();
    assert_eq!(
        Some(TransportRecoveryReason::Subscription),
        recovery_reason(&snapshot)
    );
    assert!(matches!(
        snapshot.connection,
        ConnectionState::Connected { .. }
    ));

    links[0].deliver(openrose_headset::decode_hex("09ff00000101011100000164620000ffe2").unwrap());
    settle().await;
    assert_eq!(snapshot, rig.runtime.snapshot());
}

#[tokio::test(start_paused = true)]
async fn a_typed_link_failure_during_initialization_keeps_its_reason_for_recovery() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            event_on_first_write: Some(GattEvent::ServicesChanged),
            ..LinkFaults::default()
        }),
    );
    rig.runtime.connect(id(A)).await.expect("recovered");

    assert_eq!(2, rig.transport.links().len());
    assert!(rig.transport.links()[0].closed());
    assert_eq!(
        Some(TransportRecoveryReason::ServicesChanged),
        recovery_reason(&rig.runtime.snapshot())
    );
    assert_eq!(I5_INIT_WRITES, writes(&rig, 1).len());
}

#[tokio::test(start_paused = true)]
async fn without_recovery_support_a_silent_first_query_is_final_but_typed_failures_still_retry() {
    let silent = rig(
        false,
        10,
        first_link_only(LinkFaults {
            silent: true,
            ..LinkFaults::default()
        }),
    );
    let error = error_of(silent.runtime.connect(id(A)).await);
    assert_eq!(ControlErrorCode::Timeout, error.code);
    assert_eq!(1, silent.transport.links().len());
    assert_eq!(None, recovery_reason(&silent.runtime.snapshot()));

    let typed = rig(
        false,
        10,
        first_link_only(LinkFaults {
            fail_subscription: Some(gatt(GattFailureKind::Transient, "synthetic CCCD failure")),
            ..LinkFaults::default()
        }),
    );
    typed
        .runtime
        .connect(id(A))
        .await
        .expect("typed failures retry");
    assert_eq!(2, typed.transport.links().len());
}

#[tokio::test(start_paused = true)]
async fn a_first_read_without_a_meaningful_response_uses_its_single_retry_with_a_fresh_codec() {
    let rig = rig(
        true,
        10,
        first_link_only(LinkFaults {
            silent: true,
            ..LinkFaults::default()
        }),
    );
    rig.runtime.connect(id(A)).await.expect("recovered");

    assert_eq!(2, rig.transport.links().len());
    assert_eq!(1, writes(&rig, 0).len());
    assert_eq!(I5_INIT_WRITES, writes(&rig, 1).len());
    assert!(rig.transport.previous_links_closed_at_every_open());
    assert_eq!(BATTERY_QUERY, writes(&rig, 1)[0]);
    let snapshot = rig.runtime.snapshot();
    assert_eq!(
        Some(TransportRecoveryReason::NoInitialResponse),
        recovery_reason(&snapshot)
    );
    let ConnectionState::Connected { session_id, .. } = snapshot.connection else {
        panic!("expected a ready session");
    };
    assert_eq!(
        Some(session_id),
        snapshot
            .connection_recovery
            .map(|recovery| recovery.session_id)
    );
}

#[tokio::test(start_paused = true)]
async fn a_matching_but_unaccepted_first_reply_waits_to_the_deadline_then_uses_the_recovery_budget()
{
    let rig = rig(
        true,
        10,
        first_link_only(LinkFaults {
            first_reply: Some(openrose_headset::decode_hex(UNACCEPTED_BATTERY_REPLY).unwrap()),
            ..LinkFaults::default()
        }),
    );
    let connecting = tokio::spawn({
        let runtime = Arc::clone(&rig.runtime);
        async move { runtime.connect(id(A)).await }
    });
    settle().await;

    assert_eq!(1, writes(&rig, 0).len());
    let headset = rig.runtime.snapshot().headset.expect("loading headset");
    assert!(matches!(
        headset.readings.fields.get(&HeadsetField::LeftBattery),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(matches!(
        headset.readings.fields.get(&HeadsetField::RightBattery),
        Some(FieldReading::NotProvided { .. })
    ));
    assert!(!connecting.is_finished());

    elapse(10).await;
    settle().await;
    connecting.await.unwrap().expect("recovered");
    assert_eq!(2, rig.transport.links().len());
    assert_eq!(1, writes(&rig, 0).len());
    assert_eq!(I5_INIT_WRITES, writes(&rig, 1).len());
    assert_eq!(
        Some(TransportRecoveryReason::NoInitialResponse),
        recovery_reason(&rig.runtime.snapshot())
    );
}

#[tokio::test(start_paused = true)]
async fn a_later_meaningful_state_before_the_first_query_deadline_keeps_the_same_link() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            first_reply: Some(openrose_headset::decode_hex(UNACCEPTED_BATTERY_REPLY).unwrap()),
            ..LinkFaults::default()
        }),
    );
    let connecting = tokio::spawn({
        let runtime = Arc::clone(&rig.runtime);
        async move { runtime.connect(id(A)).await }
    });
    settle().await;
    assert_eq!(1, writes(&rig, 0).len());
    assert!(!connecting.is_finished());

    let link = rig.transport.links()[0].clone();
    link.answer_again();
    link.deliver(openrose_headset::decode_hex(NOISE_REPLY).unwrap());
    settle().await;

    connecting.await.unwrap().expect("same link");
    assert_eq!(1, rig.transport.links().len());
    assert_eq!(I5_INIT_WRITES, link.writes().len());
    assert_eq!(None, recovery_reason(&rig.runtime.snapshot()));
}

#[tokio::test(start_paused = true)]
async fn a_first_query_write_deadline_uses_the_one_recovery_budget() {
    let rig = rig(
        true,
        10,
        first_link_only(LinkFaults {
            hang_write_at: Some(0),
            ..LinkFaults::default()
        }),
    );
    rig.runtime.connect(id(A)).await.expect("recovered");

    assert_eq!(2, rig.transport.links().len());
    assert!(rig.transport.links()[0].closed());
    assert_eq!(1, writes(&rig, 0).len());
    assert_eq!(I5_INIT_WRITES, writes(&rig, 1).len());
    assert_eq!(
        Some(TransportRecoveryReason::WriteFailed),
        recovery_reason(&rig.runtime.snapshot())
    );
}

#[tokio::test(start_paused = true)]
async fn an_explicitly_transient_first_write_failure_uses_the_one_recovery_budget() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            fail_write_at: Some((
                0,
                gatt(GattFailureKind::Transient, "transient gatt write error"),
            )),
            ..LinkFaults::default()
        }),
    );
    rig.runtime.connect(id(A)).await.expect("recovered");

    assert_eq!(2, rig.transport.links().len());
    assert!(rig.transport.links()[0].closed());
    assert_eq!(1, writes(&rig, 0).len());
    assert_eq!(I5_INIT_WRITES, writes(&rig, 1).len());
    assert_eq!(
        Some(TransportRecoveryReason::WriteFailed),
        recovery_reason(&rig.runtime.snapshot())
    );
}

#[tokio::test(start_paused = true)]
async fn an_unclassified_first_write_failure_is_final_and_spends_no_recovery() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            fail_write_at: Some((
                0,
                gatt(GattFailureKind::Unknown, "unclassified write error"),
            )),
            ..LinkFaults::default()
        }),
    );
    let error = error_of(rig.runtime.connect(id(A)).await);
    assert_eq!(ControlErrorCode::Disconnected, error.code);
    assert_eq!(1, rig.transport.links().len());
    assert_eq!(None, recovery_reason(&rig.runtime.snapshot()));
}

#[tokio::test(start_paused = true)]
async fn a_later_initialization_write_failure_does_not_use_the_first_query_budget() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            fail_write_at: Some((1, gatt(GattFailureKind::Transient, "later write error"))),
            ..LinkFaults::default()
        }),
    );
    let error = error_of(rig.runtime.connect(id(A)).await);
    assert_eq!(ControlErrorCode::Disconnected, error.code);
    assert_eq!(1, rig.transport.links().len());
    assert_eq!(2, writes(&rig, 0).len());
    assert_eq!(None, recovery_reason(&rig.runtime.snapshot()));
}

#[tokio::test(start_paused = true)]
async fn a_permission_failure_on_the_first_query_does_not_spend_the_recovery_budget() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            fail_write_at: Some((0, gatt(GattFailureKind::Permission, "permission revoked"))),
            ..LinkFaults::default()
        }),
    );
    let error = error_of(rig.runtime.connect(id(A)).await);
    assert_eq!(ControlErrorCode::PermissionRequired, error.code);
    assert_eq!(1, rig.transport.links().len());
    assert_eq!(None, recovery_reason(&rig.runtime.snapshot()));
}

#[tokio::test(start_paused = true)]
async fn a_terminal_failure_on_the_first_query_keeps_its_message_and_is_not_retried() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            fail_write_at: Some((
                0,
                gatt(GattFailureKind::Disconnected, "native write is terminal"),
            )),
            ..LinkFaults::default()
        }),
    );
    let error = error_of(rig.runtime.connect(id(A)).await);
    assert_eq!(ControlErrorCode::Disconnected, error.code);
    assert_eq!("native write is terminal", error.message);
    assert_eq!(1, rig.transport.links().len());
    assert_eq!(None, recovery_reason(&rig.runtime.snapshot()));
}

#[tokio::test(start_paused = true)]
async fn no_initial_response_consumes_at_most_one_recovery_attempt() {
    let rig = rig(true, 10, |_| LinkFaults {
        silent: true,
        ..LinkFaults::default()
    });
    let error = error_of(rig.runtime.connect(id(A)).await);
    assert_eq!(ControlErrorCode::Timeout, error.code);
    assert_eq!(2, rig.transport.links().len());
    assert!(
        rig.transport
            .links()
            .iter()
            .all(|link| link.writes().len() == 1)
    );
    assert_eq!(
        Some(TransportRecoveryReason::NoInitialResponse),
        recovery_reason(&rig.runtime.snapshot())
    );
}

#[tokio::test(start_paused = true)]
async fn a_typed_failure_after_ready_ends_the_session_and_never_replays_an_in_flight_setting() {
    let rig = rig(true, 30, |_| LinkFaults::default());
    rig.runtime.connect(id(A)).await.expect("ready");
    rig.headset.mute(true);

    let setting = tokio::spawn({
        let runtime = Arc::clone(&rig.runtime);
        async move {
            runtime
                .set(HeadsetCommand::NoiseMode(NoiseMode::Normal), None)
                .await
        }
    });
    settle().await;
    assert!(writes(&rig, 0).len() > I5_INIT_WRITES);
    rig.transport.links()[0].raise(GattEvent::ServicesChanged);
    settle().await;

    assert_eq!(
        ControlErrorCode::Disconnected,
        setting.await.unwrap().expect_err("never replayed").code
    );
    assert_eq!(1, rig.transport.links().len());
    assert_eq!(
        ConnectionState::Disconnected,
        rig.runtime.snapshot().connection
    );

    rig.headset.mute(false);
    rig.runtime.connect(id(A)).await.expect("reconnected");
    assert_eq!(2, rig.transport.links().len());
    assert_eq!(I5_INIT_WRITES, writes(&rig, 1).len());
    assert!(writes(&rig, 1).iter().all(|write| !write.contains("0682")));
    assert_eq!(None, recovery_reason(&rig.runtime.snapshot()));
}

struct EndsTheLinkWhenReady {
    transport: Arc<FaultyTransport>,
}

impl ControlObserver for EndsTheLinkWhenReady {
    fn state_changed(&self, snapshot: &ControlSnapshot) {
        if matches!(snapshot.connection, ConnectionState::Connected { .. }) {
            self.transport.links()[0].raise(GattEvent::ServicesChanged);
        }
    }
}

#[tokio::test(start_paused = true)]
async fn a_state_observer_that_ends_the_link_right_after_ready_cannot_leave_a_dead_connected_state()
{
    let sim = Arc::new(SimulatedTransport::new());
    add_i5(&sim, A);
    let transport = FaultyTransport::new(Arc::clone(&sim), true, |_| LinkFaults::default());
    let clock = Arc::new(openrose_control::simulator::SimulatedClock::new(
        support::connect_time(),
    ));
    let mut config = openrose_control::ControlConfig::new(available(), clock);
    config.timing = timing(30);
    let runtime = Arc::new(ControlRuntime::new(
        transport.clone(),
        Arc::new(EndsTheLinkWhenReady {
            transport: transport.clone(),
        }),
        config,
    ));

    let _ = runtime.connect(id(A)).await;
    settle().await;

    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(1, transport.links().len());
    assert_eq!(None, recovery_reason(&runtime.snapshot()));
}

#[tokio::test(start_paused = true)]
async fn a_disconnect_during_recovery_cancels_the_reconnect_until_the_user_connects_again() {
    let rig = rig(
        true,
        30,
        first_link_only(LinkFaults {
            fail_subscription: Some(gatt(GattFailureKind::Transient, "synthetic CCCD failure")),
            ..LinkFaults::default()
        }),
    );
    let gate = rig.transport.hold_open(2);
    let connecting = tokio::spawn({
        let runtime = Arc::clone(&rig.runtime);
        async move { runtime.connect(id(A)).await }
    });
    settle().await;

    assert_eq!(2, rig.transport.opens());
    assert_eq!(
        Some(TransportRecoveryReason::Subscription),
        recovery_reason(&rig.runtime.snapshot())
    );
    assert_eq!(2, rig.transport.links().len());
    rig.runtime.disconnect().await;
    gate.send_replace(true);
    settle().await;

    assert_eq!(
        ControlErrorCode::Disconnected,
        connecting.await.unwrap().expect_err("cancelled").code
    );
    assert_eq!(2, rig.transport.opens());
    assert!(rig.transport.links().iter().all(|link| link.closed()));
    assert_eq!(
        ConnectionState::Disconnected,
        rig.runtime.snapshot().connection
    );
    assert_eq!(None, recovery_reason(&rig.runtime.snapshot()));

    rig.runtime
        .connect(id(A))
        .await
        .expect("the user connects again");
    assert_eq!(3, rig.transport.opens());
}
