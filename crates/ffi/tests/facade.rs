mod support;

use openrose_control::gatt::GattFailureKind;
use openrose_control::snapshot::{
    ConnectionState, PermissionState, PlatformState, TransportAvailability,
};
use openrose_control::{ControlError, ControlErrorCode};
use openrose_ffi::{
    ControlFailure, DriverError, SimulatedHeadset, current_headset, default_session_timing,
    model_display_name, model_gatt_profile, ready_session, session_for,
};
use openrose_headset::{
    FieldValue, FirmwareVersion, HeadsetCommand, HeadsetField, HeadsetSetting, ModelProfile,
    NoiseMode, decode_hex,
};
use std::sync::Arc;
use support::{NOISE_READBACK_QUERY, NOISE_REPLY_ANC, World, eventually};

fn error_of<T: std::fmt::Debug>(result: Result<T, ControlFailure>) -> ControlError {
    match result.expect_err("expected a failure") {
        ControlFailure::Failed { error } => error,
    }
}

fn code_of<T: std::fmt::Debug>(result: Result<T, ControlFailure>) -> ControlErrorCode {
    error_of(result).code
}

#[tokio::test(flavor = "multi_thread")]
async fn a_setting_is_confirmed_only_by_the_headsets_readback() {
    let world = World::i5();
    world.runtime.connect(world.id()).await.expect("ready");
    assert!(matches!(
        world.snapshot().connection,
        ConnectionState::Connected { .. }
    ));
    world.headset.mute(true);
    let writes_before = world.headset.written().len();
    let session = world.session();

    let runtime = Arc::clone(&world.runtime);
    let setting = tokio::spawn(async move {
        runtime
            .set(HeadsetCommand::NoiseMode(NoiseMode::Anc), Some(session))
            .await
    });
    eventually("the readback query", || {
        world.headset.written()[writes_before..]
            .iter()
            .any(|frame| frame == NOISE_READBACK_QUERY)
    })
    .await;

    assert!(!setting.is_finished());
    assert_eq!(
        Some(FieldValue::text("wind")),
        world.value(HeadsetField::NoiseMode)
    );
    world.headset.inject(NOISE_REPLY_ANC);
    setting.await.unwrap().expect("confirmed by the headset");
    assert_eq!(
        Some(FieldValue::text("anc")),
        world.value(HeadsetField::NoiseMode)
    );
    assert!(world.runtime.disconnect().await);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_request_for_another_session_is_rejected_without_touching_the_headset() {
    let world = World::i5();
    world.runtime.connect(world.id()).await.expect("ready");
    let writes_before = world.headset.written().len();

    let result = world
        .runtime
        .set(
            HeadsetCommand::NoiseMode(NoiseMode::Anc),
            Some(world.session() + 1),
        )
        .await;

    assert_eq!(ControlErrorCode::Disconnected, code_of(result));
    assert_eq!(writes_before, world.headset.written().len());
    assert!(world.runtime.disconnect().await);
}

#[tokio::test(flavor = "multi_thread")]
async fn disconnecting_completes_a_pending_setting_instead_of_leaving_it_hanging() {
    let world = World::i5();
    world.runtime.connect(world.id()).await.expect("ready");
    world.headset.mute(true);
    let writes_before = world.headset.written().len();
    let runtime = Arc::clone(&world.runtime);
    let setting = tokio::spawn(async move {
        runtime
            .set(HeadsetCommand::NoiseMode(NoiseMode::Anc), None)
            .await
    });
    eventually("the write", || {
        world.headset.written()[writes_before..]
            .iter()
            .any(|frame| frame == NOISE_READBACK_QUERY)
    })
    .await;
    assert!(world.runtime.disconnect().await);

    assert_eq!(
        ControlErrorCode::Disconnected,
        code_of(setting.await.unwrap())
    );
}

#[tokio::test(flavor = "multi_thread")]
async fn a_platform_failure_reaches_the_caller_in_the_core_error_taxonomy() {
    let world = World::i5();
    world.headset.fail_connect_with(Some(DriverError::Gatt {
        kind: GattFailureKind::Permission,
        detail: "BLUETOOTH_CONNECT was revoked".to_string(),
    }));

    let result = world.runtime.connect(world.id()).await;

    assert_eq!(ControlErrorCode::PermissionRequired, code_of(result));
    assert!(matches!(
        world.snapshot().connection,
        ConnectionState::Disconnected
    ));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_driver_that_cannot_open_the_link_reports_why_in_the_core_error_taxonomy() {
    let cases = [
        (
            DriverError::PermissionDenied {
                detail: "BLUETOOTH_CONNECT was revoked".to_string(),
            },
            "BLUETOOTH_CONNECT was revoked",
            ControlErrorCode::PermissionRequired,
        ),
        (
            DriverError::Unsupported {
                detail: "no LE radio".to_string(),
            },
            "no LE radio",
            ControlErrorCode::Unsupported,
        ),
        (
            DriverError::InvalidArgument {
                detail: "malformed address".to_string(),
            },
            "malformed address",
            ControlErrorCode::Unsupported,
        ),
        (
            DriverError::Terminal {
                detail: "adapter reset".to_string(),
            },
            "adapter reset",
            ControlErrorCode::Disconnected,
        ),
    ];

    for (failure, detail, code) in cases {
        let world = World::i5();
        world.headset.fail_open_with(Some(failure));

        let error = error_of(world.runtime.connect(world.id()).await);

        assert_eq!(code, error.code, "{detail}");
        assert_eq!(detail, error.message);
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn a_terminal_failure_during_a_gatt_call_ends_the_connection_with_the_drivers_detail() {
    let world = World::i5();
    world.headset.fail_connect_with(Some(DriverError::Terminal {
        detail: "the callback crashed".to_string(),
    }));

    let error = error_of(world.runtime.connect(world.id()).await);

    assert_eq!(ControlErrorCode::Disconnected, error.code);
    assert_eq!("the callback crashed", error.message);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_panicking_driver_fails_that_call_and_the_runtime_keeps_serving() {
    let world = World::i5();
    world.headset.panic_on_open(true);
    let runtime = Arc::clone(&world.runtime);
    let id = world.id();

    let crashed = tokio::spawn(async move { runtime.connect(id).await }).await;

    assert!(crashed.expect_err("the panic surfaces").is_panic());
    assert!(world.runtime.disconnect().await);
    world.headset.panic_on_open(false);
    world.runtime.connect(world.id()).await.expect("recovers");
    assert!(matches!(
        world.snapshot().connection,
        ConnectionState::Connected { .. }
    ));
    assert!(world.runtime.disconnect().await);
}

#[tokio::test(flavor = "multi_thread")]
async fn the_observer_receives_every_revision_in_order() {
    let world = World::i5();
    world.runtime.connect(world.id()).await.expect("ready");
    let latest = world.snapshot().revision;
    eventually("the last revision", || {
        world
            .recorder
            .all()
            .last()
            .is_some_and(|snapshot| snapshot.revision == latest)
    })
    .await;

    let revisions: Vec<u64> = world
        .recorder
        .all()
        .iter()
        .map(|snapshot| snapshot.revision)
        .collect();

    assert_eq!(Some(&0), revisions.first());
    assert!(revisions.windows(2).all(|pair| pair[1] == pair[0] + 1));
    assert!(world.runtime.disconnect().await);
}

#[test]
fn constructing_and_updating_platform_state_from_a_plain_thread_uses_the_shared_runtime() {
    let handle = std::thread::spawn(|| {
        let world = World::i5();
        world.runtime.update_platform_state(PlatformState::new(
            PermissionState::Granted,
            TransportAvailability::Disabled,
        ));
        assert_eq!(
            TransportAvailability::Disabled,
            world.snapshot().platform.transport
        );
    });
    handle.join().expect("plain thread finished cleanly");
}

#[tokio::test(flavor = "multi_thread")]
async fn snapshot_helpers_and_simulated_headset_facade_round_trip() {
    let world = World::i5();
    world.runtime.connect(world.id()).await.expect("ready");
    let snapshot = world.snapshot();

    let current = current_headset(snapshot.clone()).expect("current headset");
    assert_eq!(world.id(), current.id);
    let ready = ready_session(snapshot.clone()).expect("ready session");
    assert_eq!(
        Some(ready.clone()),
        session_for(snapshot, HeadsetSetting::NoiseMode)
    );
    assert_eq!("EarFree i5", model_display_name(ModelProfile::I5Zt));
    assert!(
        !model_gatt_profile(ModelProfile::I5Zt)
            .service_uuid
            .is_empty()
    );
    assert!(default_session_timing().connect_timeout.as_millis() > 0);

    let simulated = SimulatedHeadset::new(
        ModelProfile::I5Zt,
        FirmwareVersion::new(vec![1, 2, 5], "125"),
    )
    .expect("i5 simulator");
    let replies = simulated.receive(decode_hex(NOISE_READBACK_QUERY).expect("query hex"));
    assert!(!replies.is_empty());
    assert_eq!(
        Some(&FieldValue::text("wind")),
        simulated.readings().get(&HeadsetField::NoiseMode)
    );
    assert!(world.runtime.disconnect().await);
}

#[tokio::test(flavor = "multi_thread")]
async fn cancel_add_headset_ends_an_in_flight_add_attempt() {
    let world = World::i5();
    world.headset.mute(true);
    let runtime = Arc::clone(&world.runtime);
    let id = world.id();
    let add = tokio::spawn(async move { runtime.connect_for_add_headset(id).await });
    eventually("connecting state", || {
        matches!(
            world.snapshot().connection,
            ConnectionState::Connecting { .. }
        )
    })
    .await;

    world.runtime.cancel_add_headset().await;
    assert_eq!(ControlErrorCode::Disconnected, code_of(add.await.unwrap()));
    assert!(matches!(
        world.snapshot().connection,
        ConnectionState::Disconnected
    ));
}
