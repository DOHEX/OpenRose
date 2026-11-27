mod support;

use openrose_control::ControlErrorCode;
use openrose_control::snapshot::{
    ConnectionState, HostPlatform, PermissionState, PlatformState, TransportAvailability,
    TransportUnavailableReason,
};
use support::scripted::{A, add_i5, id, simulated};
use support::{available, elapse, settle};

fn android(unlocked: bool) -> PlatformState {
    PlatformState::new(PermissionState::Granted, TransportAvailability::Available)
        .on(HostPlatform::Android)
        .with_user_unlocked(unlocked)
}

#[tokio::test(start_paused = true)]
async fn nothing_connects_before_the_first_unlock_even_when_the_headset_already_has_audio() {
    let (transport, runtime, _) = simulated(android(false));
    let headset = add_i5(&transport, A);
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(100).await;

    assert_eq!(Some(id(A)), runtime.snapshot().audio_headset_id);
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(0, headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn the_first_unlock_connects_an_audio_connection_that_predates_it() {
    let (transport, runtime, _) = simulated(android(false));
    let headset = add_i5(&transport, A);
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    assert_eq!(0, headset.connections_opened());

    runtime.update_platform_state(android(true));
    settle().await;
    elapse(100).await;

    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connected { .. }
    ));
    assert_eq!(1, headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn an_audio_connection_that_ended_before_unlock_does_not_connect_at_unlock() {
    let (transport, runtime, _) = simulated(android(false));
    let headset = add_i5(&transport, A);
    transport.set_audio_connected(Some(id(A)));
    transport.set_audio_connected(None);
    settle().await;

    runtime.update_platform_state(android(true));
    settle().await;
    elapse(100).await;

    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(0, headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn an_audio_connection_after_unlock_connects_and_repeated_events_keep_one_connection() {
    let (transport, runtime, _) = simulated(android(false));
    let headset = add_i5(&transport, A);
    runtime.update_platform_state(android(true));
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(100).await;
    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connected { .. }
    ));

    for _ in 0..3 {
        runtime.update_platform_state(android(true));
        transport.set_audio_connected(Some(id(A)));
        settle().await;
        elapse(100).await;
    }

    assert_eq!(1, headset.connections_opened());
    assert!(headset.link_open());
}

#[tokio::test(start_paused = true)]
async fn a_manual_disconnect_is_not_undone_by_later_platform_refreshes() {
    let (transport, runtime, _) = simulated(android(false));
    let headset = add_i5(&transport, A);
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    runtime.update_platform_state(android(true));
    settle().await;
    elapse(100).await;
    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connected { .. }
    ));

    runtime.disconnect().await;
    for _ in 0..3 {
        runtime.update_platform_state(android(true));
    }
    settle().await;
    elapse(100).await;

    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(1, headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn an_audio_connection_found_while_the_transport_was_unavailable_connects_when_it_becomes_available()
 {
    let unavailable = PlatformState::new(
        PermissionState::Granted,
        TransportAvailability::Unavailable {
            reason: TransportUnavailableReason::Initializing,
        },
    );
    let (transport, runtime, _) = simulated(unavailable);
    let headset = add_i5(&transport, A);
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(100).await;
    assert_eq!(0, headset.connections_opened());
    assert_eq!(
        Some(ControlErrorCode::TransportUnavailable),
        runtime.snapshot().last_error.map(|error| error.code)
    );

    runtime.update_platform_state(available());
    settle().await;
    elapse(100).await;
    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connected { .. }
    ));
    assert_eq!(1, headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn granting_the_permission_reads_an_already_connected_audio_without_a_new_event() {
    let denied = PlatformState::new(
        PermissionState::Required,
        TransportAvailability::Unavailable {
            reason: TransportUnavailableReason::PermissionRequired,
        },
    );
    let (transport, runtime, _) = simulated(denied);
    let headset = add_i5(&transport, A);
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    assert_eq!(ConnectionState::Disconnected, runtime.snapshot().connection);
    assert_eq!(None, runtime.snapshot().audio_headset_id);

    runtime.update_platform_state(available());
    settle().await;
    elapse(100).await;
    let ConnectionState::Connected { headset_id, .. } = runtime.snapshot().connection else {
        panic!("the existing audio connection should connect once permitted");
    };
    assert_eq!(id(A), headset_id);

    assert!(runtime.release().await);
    assert_eq!(
        ControlErrorCode::NotReady,
        runtime
            .connect(id(A))
            .await
            .expect_err("retired owner")
            .code
    );
    assert_eq!(1, headset.connections_opened());
}
