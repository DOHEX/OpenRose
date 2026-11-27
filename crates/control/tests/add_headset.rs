mod support;

use openrose_control::ControlErrorCode;
use openrose_control::snapshot::{ConnectionPhase, ConnectionState};
use std::sync::Arc;
use std::time::Duration;
use support::scripted::{A, add_i5, id, simulated};
use support::{Harness, Options, available, elapse, settle};

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
