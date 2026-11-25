mod support;

use openrose_control::ControlErrorCode;
use openrose_control::gatt::{GattError, GattFailureKind};
use openrose_control::snapshot::{ConnectionState, SessionReference};
use openrose_headset::{FirmwareVersion, HeadsetCommand, HeadsetId, ModelProfile, NoiseMode};
use std::collections::BTreeSet;
use support::{Harness, I5_INIT_WRITES, Options, settle};

fn error_code<T: std::fmt::Debug>(
    result: Result<T, openrose_control::ControlError>,
) -> ControlErrorCode {
    result.expect_err("expected a failure").code
}

fn anc() -> HeadsetCommand {
    HeadsetCommand::NoiseMode(NoiseMode::Anc)
}

#[tokio::test(start_paused = true)]
async fn a_session_reference_names_the_headset_and_the_control_session() {
    let harness = Harness::ready(Options::i5()).await;
    let snapshot = harness.snapshot();
    assert_eq!(
        Some(SessionReference {
            headset_id: harness.id(),
            session_id: harness.session_id(),
        }),
        snapshot.ready_session()
    );
    harness.runtime.set(anc(), None).await.expect("set");
    assert_eq!(
        harness.session_id(),
        harness.snapshot().setting_operations[0].session_id
    );
}

#[tokio::test(start_paused = true)]
async fn requests_for_an_ended_or_foreign_session_are_invalid_and_never_reach_the_wire() {
    let harness = Harness::ready(Options::i5()).await;
    let first = harness.session_id();
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(harness.runtime.set(anc(), Some(first + 7)).await)
    );
    assert_eq!(I5_INIT_WRITES, harness.headset.written_hex().len());
    harness
        .runtime
        .set(anc(), Some(first))
        .await
        .expect("current session");

    assert!(harness.runtime.disconnect().await);
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("new session");
    let second = harness.session_id();
    assert!(second > first);
    let writes = harness.headset.written_hex().len();
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(harness.runtime.set(anc(), Some(first)).await)
    );
    assert_eq!(writes, harness.headset.written_hex().len());
    harness
        .runtime
        .set(anc(), Some(second))
        .await
        .expect("current session");
}

#[tokio::test(start_paused = true)]
async fn session_ids_from_different_owner_instances_never_collide() {
    let mut app = Options::i5();
    app.session_id_base = 1 << 32;
    let mut module = Options::i5();
    module.session_id_base = (1 << 62) | (1 << 32);
    let app = Harness::new(app);
    let module = Harness::new(module);
    let mut ids = BTreeSet::new();
    for _ in 0..2 {
        for harness in [&app, &module] {
            harness
                .runtime
                .connect(harness.id())
                .await
                .expect("connect");
            assert!(
                ids.insert(harness.session_id()),
                "session ids must be unique"
            );
            harness.runtime.disconnect().await;
        }
    }
    app.runtime.connect(app.id()).await.expect("connect");
    module.runtime.connect(module.id()).await.expect("connect");
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(app.runtime.set(anc(), Some(module.session_id())).await)
    );
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(module.runtime.set(anc(), Some(app.session_id())).await)
    );
    app.runtime
        .set(anc(), Some(app.session_id()))
        .await
        .expect("own session");
}

#[tokio::test(start_paused = true)]
async fn connecting_to_another_headset_replaces_the_only_control_session() {
    let harness = Harness::ready(Options::i5()).await;
    let first = harness.session_id();
    let second_headset = harness
        .transport
        .add_headset(
            HeadsetId::new("second"),
            ModelProfile::I5Zt,
            FirmwareVersion::new(vec![1, 2, 5], "125"),
        )
        .expect("second headset");
    harness
        .runtime
        .connect(second_headset.id())
        .await
        .expect("second headset");
    assert!(!harness.headset.link_open());
    assert!(second_headset.link_open());
    let ConnectionState::Connected {
        headset_id,
        session_id,
        ..
    } = harness.connection()
    else {
        panic!("expected a ready session");
    };
    assert_eq!(second_headset.id(), headset_id);
    assert!(session_id > first);
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(harness.runtime.set(anc(), Some(first)).await)
    );
}

#[tokio::test(start_paused = true)]
async fn a_link_that_ends_during_initialization_never_publishes_a_ready_session() {
    let harness = Harness::new(Options::i5());
    harness.headset.mute(true);
    let runtime = std::sync::Arc::clone(&harness.runtime);
    let id = harness.id();
    let connecting = tokio::spawn(async move { runtime.connect(id).await });
    settle().await;
    harness
        .headset
        .disconnect(GattError::new(GattFailureKind::Disconnected, "powered off"));
    settle().await;
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(connecting.await.unwrap())
    );
    assert_eq!(ConnectionState::Disconnected, harness.connection());
    assert!(
        harness
            .recorder
            .all()
            .iter()
            .all(|snapshot| !matches!(snapshot.connection, ConnectionState::Connected { .. }))
    );
}

#[tokio::test(start_paused = true)]
async fn ending_the_control_connection_leaves_the_system_audio_fact_alone() {
    let harness = Harness::ready(Options::i5()).await;
    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    assert_eq!(Some(harness.id()), harness.snapshot().audio_headset_id);
    assert!(harness.runtime.disconnect().await);
    assert_eq!(Some(harness.id()), harness.snapshot().audio_headset_id);
    harness.transport.set_audio_connected(None);
    settle().await;
    assert_eq!(None, harness.snapshot().audio_headset_id);
}

#[tokio::test(start_paused = true)]
async fn a_released_owner_never_opens_another_control_connection() {
    let harness = Harness::ready(Options::i5()).await;
    assert!(harness.runtime.release().await);
    assert!(!harness.headset.link_open());
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(harness.runtime.connect(harness.id()).await)
    );
    assert_eq!(1, harness.headset.connections_opened());
}
