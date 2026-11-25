mod support;

use openrose_control::snapshot::{
    ConnectionBlockReason, ControlSnapshot, HostPlatform, PermissionState, PlatformState,
    TransportAvailability, TransportUnavailableCode,
};
use openrose_headset::{HeadsetCommand, ModelProfile, NoiseMode, ReconnectGuidance};
use support::{Harness, Options, settle};

fn round_trip(snapshot: &ControlSnapshot) {
    let decoded = ControlSnapshot::decode(&snapshot.encode()).expect("decodes");
    assert_eq!(snapshot, &decoded);
}

#[tokio::test(start_paused = true)]
async fn snapshots_survive_the_serde_round_trip_in_every_stage_of_a_session() {
    let harness = Harness::new(Options::i5());
    round_trip(&harness.snapshot());
    harness.runtime.refresh_bonded_headsets().await;
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
    let _ = harness
        .runtime
        .set(HeadsetCommand::AncLevel(2), None)
        .await
        .expect_err("rejected");
    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    harness.headset.mute(true);
    let runtime = std::sync::Arc::clone(&harness.runtime);
    let pending = tokio::spawn(async move {
        runtime
            .set(HeadsetCommand::NoiseMode(NoiseMode::Normal), None)
            .await
    });
    settle().await;
    let snapshot = harness.snapshot();
    assert!(snapshot.headset.is_some());
    assert!(!snapshot.setting_operations.is_empty());
    assert!(snapshot.last_error.is_some() || snapshot.setting_operations.len() > 1);
    assert_eq!(Some(harness.id()), snapshot.audio_headset_id);
    round_trip(&snapshot);
    for observed in harness.recorder.all() {
        round_trip(&observed);
    }
    harness.runtime.disconnect().await;
    let _ = pending.await;
    round_trip(&harness.snapshot());
}

#[tokio::test(start_paused = true)]
async fn the_encoding_is_self_describing_and_garbage_is_rejected() {
    let harness = Harness::ready(Options::i5()).await;
    let value: serde_json::Value =
        serde_json::from_slice(&harness.snapshot().encode()).expect("json");
    for key in [
        "revision",
        "platform",
        "connection",
        "bonded_headsets",
        "headset",
        "last_error",
        "setting_operations",
        "audio_headset_id",
        "connection_block_reason",
        "reconnect_guidance",
    ] {
        assert!(value.get(key).is_some(), "missing {key}");
    }
    assert!(ControlSnapshot::decode(b"{\"revision\":").is_err());
    assert!(ControlSnapshot::decode(b"").is_err());
}

#[tokio::test(start_paused = true)]
async fn the_snapshot_names_why_a_connection_is_blocked_with_the_priority_of_the_core_rules() {
    let harness = Harness::new(Options::i5());
    assert_eq!(None, harness.snapshot().connection_block_reason);

    let cases = [
        (
            PlatformState::new(PermissionState::Denied, TransportAvailability::Disabled),
            Some(ConnectionBlockReason::BluetoothPermissionRequired),
        ),
        (
            PlatformState::new(PermissionState::Required, TransportAvailability::Available),
            Some(ConnectionBlockReason::BluetoothPermissionRequired),
        ),
        (
            PlatformState::new(PermissionState::Granted, TransportAvailability::Disabled),
            Some(ConnectionBlockReason::BluetoothDisabled),
        ),
        (
            PlatformState::new(
                PermissionState::NotRequired,
                TransportAvailability::Unavailable {
                    code: TransportUnavailableCode::AccessDenied,
                    detail: Some("org.bluez".to_string()),
                },
            ),
            Some(ConnectionBlockReason::TransportUnavailable {
                code: TransportUnavailableCode::AccessDenied,
                detail: Some("org.bluez".to_string()),
            }),
        ),
        (
            PlatformState::new(PermissionState::Granted, TransportAvailability::Available),
            None,
        ),
    ];
    for (platform, expected) in cases {
        harness.runtime.update_platform_state(platform);
        let snapshot = harness.snapshot();
        assert_eq!(expected, snapshot.connection_block_reason);
        round_trip(&snapshot);
    }
    settle().await;
    assert!(
        harness
            .recorder
            .all()
            .iter()
            .any(|s| s.connection_block_reason == Some(ConnectionBlockReason::BluetoothDisabled)),
        "the observer is told about every change of the reason"
    );
}

#[tokio::test(start_paused = true)]
async fn every_transport_unavailable_code_survives_encoding() {
    let harness = Harness::new(Options::i5());
    for code in [
        TransportUnavailableCode::UnsupportedPlatform,
        TransportUnavailableCode::BleUnsupported,
        TransportUnavailableCode::PermissionRequired,
        TransportUnavailableCode::NoAdapter,
        TransportUnavailableCode::AdapterPoweredOff,
        TransportUnavailableCode::AdapterStateUnknown,
        TransportUnavailableCode::AccessDenied,
        TransportUnavailableCode::ServiceUnavailable,
        TransportUnavailableCode::MissingDependency,
        TransportUnavailableCode::Initializing,
        TransportUnavailableCode::Recovering,
        TransportUnavailableCode::ShuttingDown,
        TransportUnavailableCode::Closed,
        TransportUnavailableCode::BackendUnavailable,
    ] {
        harness.runtime.update_platform_state(
            PlatformState::new(
                PermissionState::Granted,
                TransportAvailability::Unavailable { code, detail: None },
            )
            .on(HostPlatform::MacOs),
        );
        let snapshot = harness.snapshot();
        assert_eq!(
            Some(ConnectionBlockReason::TransportUnavailable { code, detail: None }),
            snapshot.connection_block_reason
        );
        round_trip(&snapshot);
    }
}

#[tokio::test(start_paused = true)]
async fn the_snapshot_gives_the_reconnect_guidance_of_the_headset_model() {
    let harness = Harness::new(Options::i5());
    assert_eq!(None, harness.snapshot().reconnect_guidance);
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");
    assert_eq!(
        Some(ReconnectGuidance::OpenCaseAutoConnect),
        harness.snapshot().reconnect_guidance
    );

    let mut options = Options::i5();
    options.profile = ModelProfile::HeadFeelZt;
    options.id = "headfeel";
    let single_unit = Harness::new(options);
    single_unit
        .runtime
        .connect(single_unit.id())
        .await
        .expect("connect");
    assert_eq!(
        Some(ReconnectGuidance::WaitSystemBluetooth),
        single_unit.snapshot().reconnect_guidance
    );
}
