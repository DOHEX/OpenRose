mod support;

use openrose_control::ControlErrorCode;
use openrose_control::snapshot::{
    ConnectionState, PermissionState, PlatformState, TransportAvailability,
};
use openrose_control::transport::ControlTransport;
use openrose_headset::HeadsetId;
use std::sync::Arc;
use std::time::Duration;
use support::scripted::{A, B, GatedAudio, ScriptedTransport, add_i5, id, runtime_over, simulated};
use support::{available, elapse, settle};

fn error_code<T: std::fmt::Debug>(
    result: Result<T, openrose_control::ControlError>,
) -> ControlErrorCode {
    result.expect_err("expected a failure").code
}

fn connected(runtime: &openrose_control::ControlRuntime) -> Option<HeadsetId> {
    match runtime.snapshot().connection {
        ConnectionState::Connected { headset_id, .. } => Some(headset_id),
        _ => None,
    }
}

fn target(runtime: &openrose_control::ControlRuntime) -> Option<HeadsetId> {
    runtime.snapshot().connection_target.map(|target| target.id)
}

#[tokio::test(start_paused = true)]
async fn a_targeted_connect_sets_the_target_before_it_waits_and_establishes_the_session() {
    let (transport, runtime, _) = simulated(available());
    let headset = add_i5(&transport, A);
    settle().await;

    let mut connecting = Box::pin(runtime.connect_pinned(id(A)));
    let _ = tokio::time::timeout(Duration::ZERO, &mut connecting).await;
    assert_eq!(Some(id(A)), target(&runtime));

    connecting.await.expect("connected");
    assert_eq!(Some(id(A)), connected(&runtime));
    assert_eq!(
        Some(id(A)),
        runtime.snapshot().headset.map(|h| h.headset_id)
    );
    assert_eq!(1, headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn a_failed_target_keeps_its_identity_and_stops_audio_from_connecting_another_headset() {
    let denied = PlatformState::new(PermissionState::Denied, TransportAvailability::Available);
    let (transport, runtime, _) = simulated(denied);
    add_i5(&transport, A);
    settle().await;
    assert_eq!(
        ControlErrorCode::PermissionRequired,
        error_code(runtime.connect_pinned(id(A)).await)
    );
    assert_eq!(Some(id(A)), target(&runtime));
    assert_eq!(
        Some(ControlErrorCode::PermissionRequired),
        runtime.snapshot().last_error.map(|error| error.code)
    );

    let (transport, runtime, _) = simulated(available());
    add_i5(&transport, A);
    let other = add_i5(&transport, B);
    settle().await;
    let unknown = id("unknown-rose-model");
    assert_eq!(
        ControlErrorCode::Unsupported,
        error_code(runtime.connect_pinned(unknown.clone()).await)
    );
    let snapshot = runtime.snapshot();
    let kept = snapshot.connection_target.expect("the failed target stays");
    assert_eq!(unknown, kept.id);
    assert_eq!(None, kept.profile);
    assert_eq!(
        Some(ControlErrorCode::Unsupported),
        snapshot.last_error.map(|error| error.code)
    );
    assert_eq!(ConnectionState::Disconnected, snapshot.connection);

    transport.set_audio_connected(Some(id(B)));
    settle().await;
    elapse(100).await;
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(Some(unknown), target(&runtime));
    assert_eq!(0, other.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn a_connect_timeout_keeps_the_target_and_stops_audio_from_connecting_another_headset() {
    let (transport, runtime, _) = simulated(available());
    let silent = add_i5(&transport, A);
    let other = add_i5(&transport, B);
    silent.mute(true);
    settle().await;

    assert_eq!(
        ControlErrorCode::Timeout,
        error_code(runtime.connect_pinned(id(A)).await)
    );
    let snapshot = runtime.snapshot();
    assert_eq!(ConnectionState::Disconnected, snapshot.connection);
    assert_eq!(Some(id(A)), target(&runtime));
    assert_eq!(
        Some(ControlErrorCode::Timeout),
        snapshot.last_error.map(|error| error.code)
    );
    assert_eq!(1, silent.connections_opened());

    transport.set_audio_connected(Some(id(B)));
    settle().await;
    elapse(100).await;
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(Some(id(A)), target(&runtime));
    assert_eq!(0, other.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn repeated_connects_to_the_same_target_reuse_the_ready_or_inflight_attempt() {
    let (transport, runtime, _) = simulated(available());
    let headset = add_i5(&transport, A);
    headset.open_delay(Duration::from_millis(100));
    settle().await;

    let first = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect_pinned(id(A)).await }
    });
    settle().await;
    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connecting { .. }
    ));
    assert_eq!(1, headset.connections_opened());
    let second = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect_pinned(id(A)).await }
    });
    settle().await;
    assert_eq!(1, headset.connections_opened());

    elapse(300).await;
    first.await.unwrap().expect("first caller");
    second.await.unwrap().expect("second caller");
    assert_eq!(Some(id(A)), connected(&runtime));
    runtime.connect_pinned(id(A)).await.expect("third caller");
    assert_eq!(1, headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn a_transition_to_another_target_ends_the_previous_session_and_connects_the_new_headset() {
    let (transport, runtime, _) = simulated(available());
    let first = add_i5(&transport, A);
    let second = add_i5(&transport, B);
    settle().await;

    runtime.connect_pinned(id(A)).await.expect("first");
    assert_eq!(Some(id(A)), connected(&runtime));
    assert!(first.link_open());

    runtime.connect_pinned(id(B)).await.expect("second");
    assert!(!first.link_open());
    assert!(second.link_open());
    assert_eq!(Some(id(B)), connected(&runtime));
    assert_eq!(Some(id(B)), target(&runtime));
}

#[tokio::test(start_paused = true)]
async fn an_explicit_target_wins_over_a_suspended_audio_lookup() {
    let sim = Arc::new(openrose_control::simulator::SimulatedTransport::new());
    let first = add_i5(&sim, A);
    let second = add_i5(&sim, B);
    let gated = GatedAudio::over(sim.audio_facts());
    let transport = ScriptedTransport::new(Arc::clone(&sim), gated.clone());
    let (runtime, _) = runtime_over(transport, available());
    settle().await;

    let gate = gated.hold_next_bonded_lookup();
    sim.set_audio_connected(Some(id(B)));
    settle().await;

    runtime
        .connect_pinned(id(A))
        .await
        .expect("explicit target");
    assert_eq!(Some(id(A)), connected(&runtime));
    gate.send(true).unwrap();
    settle().await;
    elapse(100).await;

    assert_eq!(Some(id(A)), connected(&runtime));
    assert_eq!(Some(id(A)), target(&runtime));
    assert_eq!(1, first.connections_opened());
    assert_eq!(0, second.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn a_second_selection_ends_the_first_attempt_that_is_still_opening() {
    let (transport, runtime, _) = simulated(available());
    let slow = add_i5(&transport, A);
    add_i5(&transport, B);
    slow.open_delay(Duration::from_millis(1_000));
    settle().await;

    let first = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect_pinned(id(A)).await }
    });
    settle().await;
    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connecting { ref headset_id, .. } if *headset_id == id(A)
    ));

    runtime.connect_pinned(id(B)).await.expect("second");
    assert_eq!(Some(id(B)), connected(&runtime));

    elapse(2_000).await;
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(first.await.unwrap())
    );
    assert_eq!(Some(id(B)), connected(&runtime));
    assert_eq!(Some(id(B)), target(&runtime));
    assert!(!slow.link_open());
}

#[tokio::test(start_paused = true)]
async fn an_explicit_disconnect_prevents_the_opening_attempt_from_resuming() {
    let (transport, runtime, _) = simulated(available());
    let slow = add_i5(&transport, A);
    slow.open_delay(Duration::from_millis(1_000));
    settle().await;

    let attempt = tokio::spawn({
        let runtime = Arc::clone(&runtime);
        async move { runtime.connect_pinned(id(A)).await }
    });
    settle().await;
    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connecting { .. }
    ));

    assert!(runtime.disconnect().await);
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    elapse(2_000).await;

    assert!(attempt.await.unwrap().is_err());
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(Some(id(A)), target(&runtime));
    assert!(!slow.link_open());
}

#[tokio::test(start_paused = true)]
async fn a_manual_connect_replaces_the_pinned_target_and_a_manual_retry_retains_it() {
    let (transport, runtime, _) = simulated(available());
    let first = add_i5(&transport, A);
    add_i5(&transport, B);
    settle().await;

    runtime.connect_pinned(id(A)).await.expect("pinned");
    assert_eq!(Some(id(A)), target(&runtime));
    runtime.connect(id(B)).await.expect("manual");
    assert_eq!(Some(id(B)), target(&runtime));
    assert_eq!(Some(id(B)), connected(&runtime));

    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(100).await;
    assert_eq!(Some(id(B)), connected(&runtime));

    runtime.disconnect().await;
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(Some(id(B)), target(&runtime));

    transport.set_audio_connected(None);
    settle().await;
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(100).await;
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(1, first.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn an_inherited_audio_session_ends_with_its_audio_under_a_pin_and_never_falls_back() {
    let (transport, runtime, _) = simulated(available());
    let first = add_i5(&transport, A);
    let second = add_i5(&transport, B);
    settle().await;

    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(100).await;
    assert_eq!(Some(id(A)), connected(&runtime));

    runtime.connect_pinned(id(A)).await.expect("pinned");
    assert_eq!(1, first.connections_opened());
    assert_eq!(Some(id(A)), target(&runtime));

    transport.set_audio_connected(None);
    settle().await;
    elapse(100).await;
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(Some(id(A)), target(&runtime));

    transport.set_audio_connected(Some(id(B)));
    settle().await;
    elapse(100).await;
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(Some(id(A)), target(&runtime));
    assert_eq!(1, first.connections_opened());
    assert_eq!(0, second.connections_opened());
}
