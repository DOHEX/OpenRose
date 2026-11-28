mod support;

use openrose_control::audio::RawAudioDevice;
use openrose_control::snapshot::{
    ConnectionPhase, ConnectionState, ControlSnapshot, HeadsetCandidate,
};
use openrose_headset::{FirmwareVersion, HeadsetId, ModelProfile, ReconnectGuidance};
use support::scripted::{A, B, add_i5, id, simulated};
use support::{available, settle};

const HEADFEEL: &str = "AA:BB:CC:00:00:F1";

fn current(snapshot: &ControlSnapshot) -> Option<HeadsetId> {
    snapshot.current_headset().map(|headset| headset.id)
}

async fn two_headsets() -> (
    std::sync::Arc<openrose_control::simulator::SimulatedTransport>,
    std::sync::Arc<openrose_control::ControlRuntime>,
) {
    let (transport, runtime, _) = simulated(available());
    add_i5(&transport, A);
    add_i5(&transport, B);
    settle().await;
    (transport, runtime)
}

#[tokio::test(start_paused = true)]
async fn without_a_target_or_a_session_the_first_paired_headset_is_current() {
    let (_, runtime) = two_headsets().await;
    assert_eq!(Some(id(A)), current(&runtime.snapshot()));
}

#[tokio::test(start_paused = true)]
async fn selecting_a_paired_headset_switches_the_current_headset_with_one_command() {
    let (_, runtime) = two_headsets().await;
    runtime.select_current_headset(id(B));
    assert_eq!(Some(id(B)), current(&runtime.snapshot()));
    assert_eq!(Some(id(B)), runtime.snapshot().selected_headset_id);
}

#[tokio::test(start_paused = true)]
async fn selecting_a_headset_that_is_not_paired_clears_the_selection() {
    let (_, runtime) = two_headsets().await;
    runtime.select_current_headset(id(B));
    runtime.select_current_headset(id("AA:BB:CC:00:00:99"));
    let snapshot = runtime.snapshot();
    assert_eq!(None, snapshot.selected_headset_id);
    assert_eq!(Some(id(A)), current(&snapshot));
}

#[tokio::test(start_paused = true)]
async fn the_connection_target_wins_over_the_selection() {
    let (_, runtime) = two_headsets().await;
    runtime.select_current_headset(id(B));
    runtime.connect(id(A)).await.expect("connect");
    assert_eq!(Some(id(A)), current(&runtime.snapshot()));
    runtime.disconnect().await;
    assert_eq!(Some(id(A)), current(&runtime.snapshot()));
}

#[tokio::test(start_paused = true)]
async fn the_headset_of_an_active_session_is_looked_up_without_substituting_another() {
    let (_, runtime) = two_headsets().await;
    runtime.connect(id(B)).await.expect("connect");
    let ready = runtime.snapshot();

    let mut without_target = ready.clone();
    without_target.connection_target = None;
    assert_eq!(Some(id(B)), current(&without_target));

    let mut connecting = without_target.clone();
    connecting.connection = ConnectionState::Connecting {
        headset_id: id(B),
        session_id: 9,
        phase: ConnectionPhase::Discovering,
    };
    assert_eq!(Some(id(B)), current(&connecting));

    let mut foreign = without_target.clone();
    foreign.connection = ConnectionState::Connecting {
        headset_id: id("scan-only"),
        session_id: 9,
        phase: ConnectionPhase::Connecting,
    };
    assert_eq!(None, current(&foreign));
    foreign.candidates.push(HeadsetCandidate::supported(
        id("scan-only"),
        ModelProfile::I5Zt,
    ));
    assert_eq!(Some(id("scan-only")), current(&foreign));
}

#[tokio::test(start_paused = true)]
async fn a_changed_paired_list_invalidates_the_selection_but_other_paired_devices_do_not() {
    let (transport, runtime) = two_headsets().await;
    runtime.select_current_headset(id(B));

    transport
        .audio()
        .pair(RawAudioDevice::new(Some("Car Kit"), "11:22:33:44:55:66"));
    settle().await;
    assert_eq!(Some(id(B)), runtime.snapshot().selected_headset_id);

    transport
        .add_headset(
            id(HEADFEEL),
            ModelProfile::HeadFeelZt,
            FirmwareVersion::new(vec![1, 2, 5], "125"),
        )
        .unwrap();
    settle().await;
    let snapshot = runtime.snapshot();
    assert_eq!(None, snapshot.selected_headset_id);
    assert_eq!(Some(id(A)), current(&snapshot));
}

#[tokio::test(start_paused = true)]
async fn the_reconnect_guidance_follows_the_current_headset() {
    let (transport, runtime, _) = simulated(available());
    add_i5(&transport, A);
    transport
        .add_headset(
            id(HEADFEEL),
            ModelProfile::HeadFeelZt,
            FirmwareVersion::new(vec![1, 2, 5], "125"),
        )
        .unwrap();
    settle().await;
    assert_eq!(
        Some(ReconnectGuidance::OpenCaseAutoConnect),
        runtime.snapshot().reconnect_guidance
    );
    runtime.select_current_headset(id(HEADFEEL));
    assert_eq!(
        Some(ReconnectGuidance::WaitSystemBluetooth),
        runtime.snapshot().reconnect_guidance
    );
}
