mod support;

use openrose_control::ControlErrorCode;
use openrose_control::gatt::{GattError, GattFailureKind};
use openrose_control::snapshot::{
    ConnectionPhase, ConnectionState, PermissionState, PlatformState, TransportAvailability,
    TransportUnavailableCode,
};
use openrose_headset::{
    FieldReading, FieldValue, HeadsetCommand, HeadsetField, HeadsetId, NoiseMode,
};
use std::sync::Arc;
use std::time::Duration;
use support::{Harness, I5_INIT_WRITES, Options, settle};
use tokio::time::{Instant, advance};

const I5_INIT_QUERIES: [&str; 13] = [
    "08ee00000001010a0002",
    "08ee00000006020a0008",
    "08ee00000002010a0003",
    "08ee00000006030a0009",
    "08ee00000008010a0009",
    "08ee0000000d010a000e",
    "08ee00000004010a0005",
    "08ee0000000a010a000b",
    "08ee00000006050a000b",
    "08ee0000000c010a000d",
    "08ee00000006070a000d",
    "08ee00000006040a000a",
    "08ee0000000e030a0011",
];

fn error_code<T: std::fmt::Debug>(
    result: Result<T, openrose_control::ControlError>,
) -> ControlErrorCode {
    result.expect_err("expected a failure").code
}

#[tokio::test(start_paused = true)]
async fn permission_is_required_before_connecting() {
    let mut options = Options::i5();
    options.platform =
        PlatformState::new(PermissionState::Denied, TransportAvailability::Available);
    let harness = Harness::new(options);
    assert_eq!(
        ControlErrorCode::PermissionRequired,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    assert_eq!(ConnectionState::Disconnected, harness.connection());
    assert_eq!(0, harness.headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn an_unavailable_platform_transport_blocks_connecting_with_its_reason() {
    let mut options = Options::i5();
    options.platform = PlatformState::new(
        PermissionState::Granted,
        TransportAvailability::Unavailable {
            code: TransportUnavailableCode::AdapterPoweredOff,
            detail: Some("hci0".to_string()),
        },
    );
    let harness = Harness::new(options);
    assert_eq!(
        ControlErrorCode::TransportUnavailable,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    assert_eq!(0, harness.headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn a_headset_without_model_evidence_never_receives_a_connection() {
    let harness = Harness::i5();
    assert_eq!(
        ControlErrorCode::Unsupported,
        error_code(harness.runtime.connect(HeadsetId::new("unknown")).await)
    );
    assert_eq!(0, harness.headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn i5_connects_after_subscription_and_exposes_confirmed_values_without_inventing_case_battery()
 {
    let harness = Harness::new(Options::i5().paced());
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");

    assert!(matches!(
        harness.connection(),
        ConnectionState::Connected { .. }
    ));
    assert_eq!(
        Some(FieldValue::Number(100)),
        harness.value(HeadsetField::LeftBattery)
    );
    assert!(matches!(
        harness.reading(HeadsetField::CaseBattery),
        Some(FieldReading::NotProvided { raw_hex }) if raw_hex == "ff"
    ));
    let Some(FieldValue::Firmware(firmware)) = harness.value(HeadsetField::Firmware) else {
        panic!("firmware should be confirmed");
    };
    assert_eq!("125", firmware.display_text);
    assert_eq!(I5_INIT_QUERIES.to_vec(), harness.headset.written_hex());
    let snapshot = harness.snapshot();
    let headset = snapshot.headset.expect("headset");
    assert!(
        headset
            .readings
            .fields
            .values()
            .all(|reading| *reading != FieldReading::Loading)
    );
    let times: Vec<Instant> = harness.headset.writes().iter().map(|w| w.at).collect();
    assert!(
        times
            .windows(2)
            .all(|pair| pair[1] - pair[0] >= Duration::from_millis(100))
    );
    assert_eq!(
        ControlErrorCode::Unsupported,
        error_code(
            harness
                .runtime
                .set(HeadsetCommand::EqualizerPreset("eap".into()), None)
                .await
        )
    );
    assert!(harness.runtime.disconnect().await);
    assert!(!harness.headset.link_open());
    assert_eq!(ConnectionState::Disconnected, harness.connection());
}

#[tokio::test(start_paused = true)]
async fn partial_read_timeout_keeps_confirmed_fields_and_reports_the_unavailable_field() {
    let harness = Harness::new(Options::i5());
    harness.headset.ignore_key("0d01");
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");

    assert!(matches!(
        harness.connection(),
        ConnectionState::Connected { .. }
    ));
    assert_eq!(
        Some(FieldValue::Number(100)),
        harness.value(HeadsetField::LeftBattery)
    );
    assert_eq!(
        Some(FieldReading::Loading),
        harness.reading(HeadsetField::Firmware)
    );
    let snapshot = harness.snapshot();
    let diagnostics = snapshot.headset.expect("headset").diagnostics;
    assert_eq!(
        ControlErrorCode::Timeout,
        diagnostics.field_errors[&HeadsetField::Firmware].code
    );
    assert_eq!(
        ControlErrorCode::Timeout,
        snapshot.last_error.expect("last error").code
    );
}

#[tokio::test(start_paused = true)]
async fn remote_disconnect_allows_a_fresh_connection_and_rejects_old_callbacks() {
    let harness = Harness::ready(Options::i5()).await;
    let first_session = harness.session_id();
    let first_link = harness.headset.connections_opened();
    harness.headset.disconnect(GattError::new(
        GattFailureKind::Disconnected,
        "Device powered off",
    ));
    settle().await;
    let snapshot = harness.snapshot();
    assert_eq!(ConnectionState::Disconnected, snapshot.connection);
    assert!(snapshot.headset.is_none());
    assert_eq!(
        ControlErrorCode::Disconnected,
        snapshot.last_error.expect("error").code
    );

    harness.headset.ignore_key("0d01");
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("reconnect");
    assert!(harness.session_id() > first_session);
    assert_eq!(
        Some(FieldReading::Loading),
        harness.reading(HeadsetField::Firmware)
    );
    let before = harness.snapshot();
    let late = openrose_headset::decode_hex("09ff0000010d010e00010205002d").unwrap();
    assert!(
        !harness.headset.inject_late(first_link, late),
        "a closed link must not accept late traffic"
    );
    settle().await;
    assert_eq!(before, harness.snapshot());
    assert_eq!(2, harness.headset.connections_opened());
    assert!(harness.headset.link_open());
    assert_eq!(2 * I5_INIT_WRITES, harness.headset.written_hex().len());
}

#[tokio::test(start_paused = true)]
async fn concurrent_entrypoints_share_one_connection_and_unknown_notifications_remain_observable() {
    let harness = Harness::new(Options::i5());
    let (first, second) = tokio::join!(
        harness.runtime.connect(harness.id()),
        harness.runtime.connect(harness.id())
    );
    first.expect("first entrypoint");
    second.expect("second entrypoint");
    assert_eq!(I5_INIT_WRITES, harness.headset.written_hex().len());
    assert_eq!(1, harness.headset.connections_opened());

    assert!(harness.inject_hex("0000000000ffff"));
    assert!(harness.inject_hex("000000000006030000a2"));
    settle().await;
    let headset = harness.snapshot().headset.expect("headset");
    assert_eq!(
        Some(&FieldReading::Unknown {
            raw_hex: "a2".into()
        }),
        headset.readings.fields.get(&HeadsetField::GameMode)
    );
    assert_eq!(
        vec![
            "0000000000ffff".to_string(),
            "000000000006030000a2".to_string()
        ],
        headset.diagnostics.unknown_notifications
    );
    assert!(
        headset
            .diagnostics
            .raw_notifications
            .contains(&"0000000000ffff".to_string())
    );
}

#[tokio::test(start_paused = true)]
async fn disconnect_cancels_pending_reads_and_never_replays_them_after_the_deadline() {
    let harness = Harness::new(Options::i5());
    harness.headset.mute(true);
    let pending = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;
    let ConnectionState::Connecting { phase, .. } = harness.connection() else {
        panic!("expected an initializing session");
    };
    assert_eq!(ConnectionPhase::Initializing, phase);

    assert!(harness.runtime.disconnect().await);
    settle().await;
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(pending.await.unwrap())
    );
    advance(Duration::from_secs(60)).await;
    settle().await;
    assert!(!harness.headset.link_open());
    assert_eq!(1, harness.headset.written_hex().len());
    assert_eq!(ConnectionState::Disconnected, harness.connection());
    assert!(harness.snapshot().headset.is_none());
}

#[tokio::test(start_paused = true)]
async fn an_unresponsive_device_times_out_without_claiming_protocol_readiness() {
    let harness = Harness::new(Options::i5());
    harness.headset.mute(true);
    assert_eq!(
        ControlErrorCode::Timeout,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    settle().await;
    let snapshot = harness.snapshot();
    assert_eq!(
        ControlErrorCode::Timeout,
        snapshot.last_error.expect("error").code
    );
    assert_eq!(ConnectionState::Disconnected, snapshot.connection);
    assert!(!harness.headset.link_open());
}

#[tokio::test(start_paused = true)]
async fn dropping_one_entrypoint_does_not_cancel_the_shared_connection() {
    let harness = Harness::new(Options::i5());
    harness.headset.reply_latency(Duration::from_millis(5));
    let first = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;
    let second = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;
    first.abort();
    second
        .await
        .unwrap()
        .expect("the shared connection survives");
    assert!(matches!(
        harness.connection(),
        ConnectionState::Connected { .. }
    ));
    assert_eq!(I5_INIT_WRITES, harness.headset.written_hex().len());
    assert!(harness.runtime.disconnect().await);
}

#[tokio::test(start_paused = true)]
async fn an_opened_link_is_closed_when_connection_acquisition_loses_to_its_deadline() {
    let mut options = Options::i5();
    options.timing.connect_timeout = Duration::from_millis(10);
    let harness = Harness::new(options);
    harness.headset.open_delay(Duration::from_millis(20));
    assert_eq!(
        ControlErrorCode::Timeout,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    assert_eq!(ConnectionState::Disconnected, harness.connection());
    assert!(!harness.headset.link_open());
}

#[tokio::test(start_paused = true)]
async fn a_link_that_refuses_to_close_blocks_new_connections_until_release_is_confirmed() {
    let harness = Harness::ready(Options::i5()).await;
    harness.headset.refuse_close(true);
    assert!(!harness.runtime.disconnect().await);
    assert_eq!(ConnectionState::Disconnected, harness.connection());
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    assert_eq!(1, harness.headset.connections_opened());
    harness.headset.refuse_close(false);
    assert!(harness.runtime.release().await);
    assert!(!harness.headset.link_open());
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    assert_eq!(1, harness.headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn release_during_setup_waits_for_the_driver_close_and_retires_the_owner() {
    let harness = Harness::new(Options::i5());
    harness.headset.open_delay(Duration::from_secs(3_600));
    harness.headset.refuse_close(true);
    let connecting = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;
    let retiring = harness.runtime.release();
    assert!(!retiring.await);
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(connecting.await.unwrap())
    );
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    assert_eq!(1, harness.headset.connections_opened());
    harness.headset.refuse_close(false);
    assert!(harness.runtime.release().await);
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    assert_eq!(1, harness.headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn the_observer_receives_every_revision_in_order_starting_with_the_initial_state() {
    let harness = Harness::i5();
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");
    harness
        .runtime
        .set(HeadsetCommand::NoiseMode(NoiseMode::Anc), None)
        .await
        .expect("set");
    settle().await;
    let revisions: Vec<u64> = harness.recorder.all().iter().map(|s| s.revision).collect();
    assert_eq!(0, revisions[0]);
    assert!(revisions.windows(2).all(|pair| pair[1] == pair[0] + 1));
    assert_eq!(
        harness.snapshot().revision,
        *revisions.last().expect("revisions")
    );
    assert_eq!(
        ConnectionState::Disconnected,
        harness.recorder.all()[0].connection
    );
}
