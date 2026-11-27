mod support;

use openrose_control::codec::{CodecError, WIRE_FORMAT_VERSION};
use openrose_control::settings::ControlSettings;
use openrose_control::snapshot::{
    ConnectionBlockReason, ControlSnapshot, HostPlatform, PermissionState, PlatformApplicability,
    PlatformState, TransportAvailability, TransportUnavailableReason,
};
use openrose_control::transport::{AudioProfileEvidence, TransportFacts};
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
async fn the_encoding_is_versioned_self_describing_and_rejects_malformed_or_wrong_version_bytes() {
    let harness = Harness::ready(Options::i5()).await;
    let encoded = harness.snapshot().encode();
    assert_eq!(b"ORC", &encoded[..3]);
    assert_eq!(WIRE_FORMAT_VERSION, encoded[3]);
    let value: serde_json::Value = serde_json::from_slice(&encoded[4..]).expect("json");
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
    assert!(matches!(
        ControlSnapshot::decode(b"ORC\x01{\"revision\":"),
        Err(CodecError::Malformed { .. })
    ));
    assert!(matches!(
        ControlSnapshot::decode(b"corrupt"),
        Err(CodecError::Malformed { .. })
    ));
    assert!(matches!(
        ControlSnapshot::decode(b""),
        Err(CodecError::Malformed { .. })
    ));
    let mut wrong_version = encoded;
    wrong_version[3] = WIRE_FORMAT_VERSION.wrapping_add(1);
    assert_eq!(
        Err(CodecError::UnsupportedVersion {
            expected: WIRE_FORMAT_VERSION,
            actual: WIRE_FORMAT_VERSION.wrapping_add(1),
        }),
        ControlSnapshot::decode(&wrong_version)
    );
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
                    reason: TransportUnavailableReason::BackendUnavailable {
                        detail: Some("org.bluez".to_string()),
                    },
                },
            ),
            Some(ConnectionBlockReason::TransportUnavailable {
                reason: TransportUnavailableReason::BackendUnavailable {
                    detail: Some("org.bluez".to_string()),
                },
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
async fn every_transport_unavailable_reason_survives_encoding() {
    let harness = Harness::new(Options::i5());
    for reason in [
        TransportUnavailableReason::UnsupportedPlatform {
            platform: Some("macOS".to_string()),
        },
        TransportUnavailableReason::BleUnsupported,
        TransportUnavailableReason::PermissionRequired,
        TransportUnavailableReason::NoAdapter,
        TransportUnavailableReason::AdapterPoweredOff,
        TransportUnavailableReason::AdapterStateUnknown,
        TransportUnavailableReason::AccessDenied,
        TransportUnavailableReason::ServiceUnavailable,
        TransportUnavailableReason::MissingDependency {
            detail: Some("libdbus".to_string()),
        },
        TransportUnavailableReason::Initializing,
        TransportUnavailableReason::Recovering,
        TransportUnavailableReason::ShuttingDown,
        TransportUnavailableReason::Closed,
        TransportUnavailableReason::BackendUnavailable {
            detail: Some("helper exited".to_string()),
        },
    ] {
        harness.runtime.update_platform_state(
            PlatformState::new(
                PermissionState::Granted,
                TransportAvailability::Unavailable {
                    reason: reason.clone(),
                },
            )
            .on(HostPlatform::MacOs),
        );
        let snapshot = harness.snapshot();
        assert_eq!(
            Some(ConnectionBlockReason::TransportUnavailable { reason }),
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
    options.id = "AA:BB:CC:00:00:F1";
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

#[tokio::test(start_paused = true)]
async fn control_settings_transport_selection_and_applicability_are_part_of_the_encoded_snapshot() {
    let mut options = Options::i7();
    options.platform =
        PlatformState::new(PermissionState::Granted, TransportAvailability::Available)
            .with_control_transport(PlatformApplicability::Applicable);
    let harness = Harness::new(options);
    harness
        .headset
        .set_classic_address(Some("A1:B2:C3:D4:E5:F6"));
    harness.headset.set_connection_facts(TransportFacts {
        spp_available: true,
        audio_profile: AudioProfileEvidence::Classic,
    });
    harness
        .runtime
        .update_settings(ControlSettings { force_ble: false });
    harness.runtime.refresh_bonded_headsets().await;
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");

    let snapshot = harness.snapshot();
    assert_eq!(
        PlatformApplicability::Applicable,
        snapshot.platform.control_transport_applicability
    );
    assert!(
        snapshot
            .headset
            .as_ref()
            .and_then(|headset| headset.transport_selection.as_ref())
            .is_some()
    );
    round_trip(&snapshot);
    let encoded = snapshot.encode();
    assert_eq!(WIRE_FORMAT_VERSION, encoded[3]);
    let value: serde_json::Value = serde_json::from_slice(&encoded[4..]).expect("json");
    assert_eq!(
        serde_json::json!("Applicable"),
        value["platform"]["control_transport_applicability"]
    );
    assert_eq!(serde_json::json!(false), value["settings"]["force_ble"]);
    assert_eq!(
        serde_json::json!("ClassicAudio"),
        value["headset"]["transport_selection"]["reason"]
    );
    assert_eq!(
        PlatformApplicability::Unknown,
        PlatformState::new(PermissionState::Granted, TransportAvailability::Available)
            .control_transport_applicability
    );
}
