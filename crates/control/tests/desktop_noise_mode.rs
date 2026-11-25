mod support;

use openrose_control::ControlErrorCode;
use openrose_control::snapshot::{
    ConnectionPhase, ConnectionState, HostPlatform, PermissionState, PlatformState,
    TransportAvailability,
};
use openrose_headset::{FieldValue, HeadsetCommand, HeadsetField, ModelProfile, NoiseMode};
use std::sync::Arc;
use support::{Harness, Options, elapse, settle};

fn error_code<T: std::fmt::Debug>(
    result: Result<T, openrose_control::ControlError>,
) -> ControlErrorCode {
    result.expect_err("expected a failure").code
}

fn normal() -> HeadsetCommand {
    HeadsetCommand::NoiseMode(NoiseMode::Normal)
}

fn connect_in_background(
    harness: &Harness,
) -> tokio::task::JoinHandle<Result<(), openrose_control::ControlError>> {
    let runtime = Arc::clone(&harness.runtime);
    let id = harness.id();
    tokio::spawn(async move { runtime.connect(id).await })
}

async fn initializing_with_system_audio(
    options: Options,
    stalled_key: &str,
) -> (
    Harness,
    tokio::task::JoinHandle<Result<(), openrose_control::ControlError>>,
) {
    let harness = Harness::new(options);
    harness.headset.ignore_key(stalled_key);
    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    let connecting = connect_in_background(&harness);
    settle().await;
    let ConnectionState::Connecting { phase, .. } = harness.connection() else {
        panic!("the control session should still be initializing");
    };
    assert_eq!(ConnectionPhase::Initializing, phase);
    (harness, connecting)
}

#[tokio::test(start_paused = true)]
async fn desktop_i5_admits_a_noise_mode_change_during_initialization_and_runs_it_in_the_next_write_slot()
 {
    let (harness, connecting) =
        initializing_with_system_audio(Options::i5().desktop(), "0605").await;
    let snapshot = harness.snapshot();
    assert!(snapshot.ready_session().is_none());
    assert!(snapshot.noise_mode_session().is_some());
    assert!(
        harness
            .runtime
            .set(HeadsetCommand::GameMode(true), None)
            .await
            .is_err()
    );

    let pending = {
        let runtime = Arc::clone(&harness.runtime);
        tokio::spawn(async move { runtime.set(normal(), None).await })
    };
    settle().await;
    elapse(60).await;
    pending
        .await
        .unwrap()
        .expect("the headset confirms the early change");
    connecting.await.unwrap().expect("initialization completes");

    let writes = harness.headset.written_hex();
    assert_eq!("08ee00000006050a000b", writes[8]);
    assert_eq!("08ee00000006820e00000100008d", writes[9]);
    assert_eq!("08ee00000006020a0008", writes[10]);
    assert_eq!("08ee0000000c010a000d", writes[11]);
    assert_eq!(
        Some(FieldValue::text("normal")),
        harness.value(HeadsetField::NoiseMode)
    );
}

#[tokio::test(start_paused = true)]
async fn the_early_admission_belongs_to_desktop_i5_with_system_audio_and_a_confirmed_mode_only() {
    let (android, connecting) = initializing_with_system_audio(Options::i5(), "0605").await;
    android.runtime.update_platform_state(
        PlatformState::new(PermissionState::Granted, TransportAvailability::Available)
            .on(HostPlatform::Android),
    );
    assert!(android.snapshot().noise_mode_session().is_none());
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(android.runtime.set(normal(), None).await)
    );
    elapse(60).await;
    connecting.await.unwrap().expect("connects");

    let harness = Harness::new(Options::i5().desktop());
    harness.headset.ignore_key("0605");
    let connecting = connect_in_background(&harness);
    settle().await;
    assert!(
        harness.snapshot().noise_mode_session().is_none(),
        "no system audio"
    );
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(harness.runtime.set(normal(), None).await)
    );
    elapse(60).await;
    connecting.await.unwrap().expect("connects");

    let (unconfirmed, connecting) =
        initializing_with_system_audio(Options::i5().desktop(), "0602").await;
    assert!(
        unconfirmed.snapshot().noise_mode_session().is_none(),
        "mode not read yet"
    );
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(unconfirmed.runtime.set(normal(), None).await)
    );
    elapse(60).await;
    connecting.await.unwrap().expect("connects");

    let mut options = Options::i5().desktop();
    options.profile = ModelProfile::I3Zt;
    options.id = "i3";
    let (other_model, connecting) = initializing_with_system_audio(options, "0a01").await;
    assert!(
        other_model.snapshot().noise_mode_session().is_none(),
        "only the i5 is admitted early"
    );
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(other_model.runtime.set(normal(), None).await)
    );
    elapse(60).await;
    connecting.await.unwrap().expect("connects");
}

#[tokio::test(start_paused = true)]
async fn once_ready_every_platform_uses_the_ready_session_for_the_noise_mode() {
    let harness = Harness::ready(Options::i5()).await;
    let snapshot = harness.snapshot();
    assert_eq!(snapshot.ready_session(), snapshot.noise_mode_session());
    harness
        .runtime
        .set(normal(), None)
        .await
        .expect("confirmed");
}
