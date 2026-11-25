mod support;

use openrose_control::ControlErrorCode;
use openrose_control::selection::{
    TransportEndpoint, TransportSelection, TransportSelectionReason,
};
use openrose_control::settings::ControlSettings;
use openrose_control::snapshot::{ConnectionState, SettingStage};
use openrose_control::spp::{SppError, SppFailureKind};
use openrose_control::transport::{AudioProfileEvidence, TransportFacts};
use openrose_headset::{
    FieldReading, FieldValue, HeadsetCommand, HeadsetField, ModelIdentification, ModelProfile,
    NoiseMode, decode_hex,
};
use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;
use support::{Harness, Options, elapse, settle};

const CLASSIC: &str = "A1:B2:C3:D4:E5:F6";
const SPP_UUID: &str = "0cf12d31-fac3-4553-bd80-d6832e7b3931";

fn classic_audio() -> TransportFacts {
    TransportFacts {
        spp_available: true,
        audio_profile: AudioProfileEvidence::Classic,
    }
}

fn spp() -> TransportEndpoint {
    TransportEndpoint::SppRfcomm {
        address: CLASSIC.to_string(),
        service_uuid: SPP_UUID.to_string(),
    }
}

fn classic_i7() -> Harness {
    let harness = Harness::new(Options::i7());
    harness.headset.set_classic_address(Some(CLASSIC));
    harness.headset.set_connection_facts(classic_audio());
    harness
}

#[tokio::test(start_paused = true)]
async fn a_classic_i7_initializes_and_confirms_a_setting_over_its_spp_endpoint() {
    let harness = classic_i7();
    harness
        .headset
        .device()
        .set(HeadsetField::GameMode, FieldValue::Toggle(true))
        .expect("device game mode");
    harness.runtime.refresh_bonded_headsets().await;

    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");

    assert_eq!(vec![spp()], harness.headset.endpoints());
    assert_eq!(vec![CLASSIC.to_string()], harness.headset.facts_lookups());
    let snapshot = harness.snapshot();
    assert!(matches!(
        snapshot.connection,
        ConnectionState::Connected { .. }
    ));
    assert!(snapshot.ready_session().is_some());
    let headset = snapshot.headset.expect("headset state");
    assert_eq!(Some(CLASSIC.to_string()), headset.classic_address);
    assert_eq!(
        Some(TransportSelection {
            endpoint: spp(),
            reason: TransportSelectionReason::ClassicAudio,
        }),
        headset.transport_selection
    );
    assert_eq!(2, harness.headset.written_hex().len());
    assert_eq!(
        Some(FieldValue::Number(100)),
        harness.value(HeadsetField::LeftBattery)
    );
    assert_eq!(
        Some(FieldValue::Toggle(true)),
        harness.value(HeadsetField::GameMode)
    );

    harness.headset.mute(true);
    let runtime = Arc::clone(&harness.runtime);
    let pending =
        tokio::spawn(async move { runtime.set(HeadsetCommand::GameMode(false), None).await });
    settle().await;
    let writes = harness.headset.written_hex();
    assert_eq!(
        ["ff02020e0011aa", "ff0302fa0e0caa"],
        writes[writes.len() - 2..]
    );
    assert!(!pending.is_finished());

    let reply = decode_hex("dd00020e00edaa").expect("reply hex");
    assert!(harness.headset.inject(reply[..3].to_vec()));
    settle().await;
    assert!(!pending.is_finished());
    assert!(harness.headset.inject(reply[3..].to_vec()));
    pending
        .await
        .unwrap()
        .expect("the headset confirms the game mode over SPP");
    assert!(matches!(
        harness.reading(HeadsetField::GameMode),
        Some(FieldReading::Known {
            value: FieldValue::Toggle(false),
            ..
        })
    ));

    assert!(harness.runtime.disconnect().await);
    assert_eq!(None, harness.snapshot().headset);
}

#[tokio::test(start_paused = true)]
async fn a_setting_over_spp_is_applied_by_the_device_and_confirmed_by_its_readback() {
    let harness = classic_i7();
    harness.runtime.refresh_bonded_headsets().await;
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");
    assert_eq!(
        Some(&FieldValue::text("anc")),
        harness.headset.readings().get(&HeadsetField::NoiseMode)
    );

    harness
        .runtime
        .set(HeadsetCommand::NoiseMode(NoiseMode::Normal), None)
        .await
        .expect("the device confirms the noise mode");

    assert_eq!(vec![SettingStage::Confirmed], harness.stages());
    assert_eq!(
        Some(FieldValue::text("normal")),
        harness.value(HeadsetField::NoiseMode)
    );
    assert_eq!(
        Some(&FieldValue::text("normal")),
        harness.headset.readings().get(&HeadsetField::NoiseMode)
    );
    assert_eq!(vec![spp()], harness.headset.endpoints());
}

fn ble_endpoint(harness: &Harness) -> TransportEndpoint {
    let gatt = harness.headset.profile().gatt();
    TransportEndpoint::BleGatt {
        service_uuid: gatt.service_uuid,
        write_uuid: gatt.write_uuid,
        notify_uuid: gatt.notify_uuid,
    }
}

struct Fallback {
    name: &'static str,
    options: Options,
    address: Option<&'static str>,
    facts: TransportFacts,
    force_ble: bool,
    reason: TransportSelectionReason,
    facts_lookups: usize,
}

fn facts(spp_available: bool, audio_profile: AudioProfileEvidence) -> TransportFacts {
    TransportFacts {
        spp_available,
        audio_profile,
    }
}

#[tokio::test(start_paused = true)]
async fn control_falls_back_to_ble_for_every_reason_it_cannot_prove_spp_usable() {
    let scenarios = [
        Fallback {
            name: "platform reports SPP unavailable",
            options: Options::i7(),
            address: Some(CLASSIC),
            facts: facts(false, AudioProfileEvidence::Classic),
            force_ble: false,
            reason: TransportSelectionReason::SppUnavailable,
            facts_lookups: 1,
        },
        Fallback {
            name: "no classic address",
            options: Options::i7(),
            address: None,
            facts: facts(true, AudioProfileEvidence::Classic),
            force_ble: false,
            reason: TransportSelectionReason::ClassicAddressUnavailable,
            facts_lookups: 0,
        },
        Fallback {
            name: "audio protocol unknown",
            options: Options::i7(),
            address: Some(CLASSIC),
            facts: facts(true, AudioProfileEvidence::Unknown),
            force_ble: false,
            reason: TransportSelectionReason::AudioProfileUnknown,
            facts_lookups: 1,
        },
        Fallback {
            name: "LE Audio",
            options: Options::i7(),
            address: Some(CLASSIC),
            facts: facts(true, AudioProfileEvidence::LeAudio),
            force_ble: false,
            reason: TransportSelectionReason::LeAudio,
            facts_lookups: 1,
        },
        Fallback {
            name: "forced BLE",
            options: Options::i7(),
            address: Some(CLASSIC),
            facts: facts(true, AudioProfileEvidence::Classic),
            force_ble: true,
            reason: TransportSelectionReason::ForcedBle,
            facts_lookups: 0,
        },
        Fallback {
            name: "model has no SPP identifier",
            options: Options::i5(),
            address: Some(CLASSIC),
            facts: facts(true, AudioProfileEvidence::Classic),
            force_ble: false,
            reason: TransportSelectionReason::ModelBleOnly,
            facts_lookups: 0,
        },
    ];
    for scenario in scenarios {
        let harness = Harness::new(scenario.options);
        harness.headset.set_classic_address(scenario.address);
        harness.headset.set_connection_facts(scenario.facts);
        harness.runtime.update_settings(ControlSettings {
            force_ble: scenario.force_ble,
        });
        harness.runtime.refresh_bonded_headsets().await;

        harness
            .runtime
            .connect(harness.id())
            .await
            .unwrap_or_else(|error| panic!("{}: {error}", scenario.name));

        let ble = ble_endpoint(&harness);
        assert_eq!(
            vec![ble.clone()],
            harness.headset.endpoints(),
            "{}",
            scenario.name
        );
        let snapshot = harness.snapshot();
        let headset = snapshot.headset.clone().expect("headset state");
        assert_eq!(
            Some(TransportSelection {
                endpoint: ble,
                reason: scenario.reason,
            }),
            headset.transport_selection,
            "{}",
            scenario.name
        );
        assert_eq!(
            scenario.facts_lookups,
            harness.headset.facts_lookups().len(),
            "{}",
            scenario.name
        );
        assert!(snapshot.ready_session().is_some(), "{}", scenario.name);
        assert_eq!(
            scenario.address.map(str::to_string),
            headset.classic_address,
            "{}",
            scenario.name
        );
        assert!(harness.runtime.disconnect().await, "{}", scenario.name);
    }
}

#[tokio::test(start_paused = true)]
async fn le_audio_keeps_ble_and_a_minimum_capacity_link_still_confirms_the_same_setting() {
    let harness = Harness::new(Options::i7());
    harness.headset.set_classic_address(Some(CLASSIC));
    harness
        .headset
        .set_connection_facts(facts(true, AudioProfileEvidence::LeAudio));
    harness.headset.negotiated_mtu(None);
    harness
        .headset
        .device()
        .set(HeadsetField::GameMode, FieldValue::Toggle(true))
        .expect("device game mode");
    harness.runtime.refresh_bonded_headsets().await;

    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");

    assert_eq!(
        Some(TransportSelectionReason::LeAudio),
        harness
            .snapshot()
            .headset
            .and_then(|headset| headset.transport_selection)
            .map(|selection| selection.reason)
    );
    assert_eq!(
        vec![
            "ff000ffa0d0c092c2d2a45460e322b01072fdaaa",
            "ff0104fa082e366aaa",
            "ff0202fa3e3baa",
        ],
        harness.headset.written_hex()
    );
    assert!(
        harness
            .headset
            .writes()
            .iter()
            .all(|write| write.bytes.len() <= 20)
    );
    assert_eq!(
        Some(FieldValue::Toggle(true)),
        harness.value(HeadsetField::GameMode)
    );

    harness
        .runtime
        .set(HeadsetCommand::GameMode(false), None)
        .await
        .expect("the device confirms over a 20-byte link");
    let writes = harness.headset.written_hex();
    assert_eq!(
        ["ff03020e0012aa", "ff0402fa0e0daa"],
        writes[writes.len() - 2..]
    );
    assert_eq!(
        Some(FieldValue::Toggle(false)),
        harness.value(HeadsetField::GameMode)
    );
}

#[tokio::test(start_paused = true)]
async fn force_ble_only_affects_the_next_connection() {
    let harness = classic_i7();
    harness.runtime.refresh_bonded_headsets().await;
    harness.headset.stall_facts();
    let runtime = Arc::clone(&harness.runtime);
    let id = harness.id();
    let pending = tokio::spawn(async move { runtime.connect(id).await });
    settle().await;
    assert_eq!(1, harness.headset.facts_lookups().len());
    assert!(harness.headset.endpoints().is_empty());

    harness
        .runtime
        .update_settings(ControlSettings { force_ble: true });
    harness.headset.resume_facts();
    pending
        .await
        .unwrap()
        .expect("the attempt keeps the preference it started with");
    assert_eq!(vec![spp()], harness.headset.endpoints());
    assert!(harness.snapshot().settings.force_ble);
    let selection = |harness: &Harness| {
        harness
            .snapshot()
            .headset
            .and_then(|headset| headset.transport_selection)
    };
    let connected = selection(&harness);
    assert_eq!(
        Some(TransportSelectionReason::ClassicAudio),
        connected.as_ref().map(|selection| selection.reason)
    );

    let writes = harness.headset.written_hex();
    harness
        .runtime
        .update_settings(ControlSettings { force_ble: false });
    settle().await;
    assert_eq!(vec![spp()], harness.headset.endpoints());
    assert_eq!(writes, harness.headset.written_hex());
    assert_eq!(connected, selection(&harness));

    harness
        .runtime
        .update_settings(ControlSettings { force_ble: true });
    assert!(harness.runtime.disconnect().await);
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("reconnect");
    assert_eq!(
        Some(TransportSelectionReason::ForcedBle),
        selection(&harness).map(|selection| selection.reason)
    );
    assert_eq!(2, harness.headset.endpoints().len());
}

fn selection_of(harness: &Harness) -> Option<TransportSelection> {
    harness
        .snapshot()
        .headset
        .and_then(|headset| headset.transport_selection)
}

fn code<T: std::fmt::Debug>(result: Result<T, openrose_control::ControlError>) -> ControlErrorCode {
    result.expect_err("expected a failure").code
}

#[tokio::test(start_paused = true)]
async fn a_failed_facts_lookup_cannot_prove_spp_and_connects_over_ble() {
    let harness = classic_i7();
    harness.headset.fail_facts(true);
    harness.runtime.refresh_bonded_headsets().await;

    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");

    assert_eq!(
        Some(TransportSelection {
            endpoint: ble_endpoint(&harness),
            reason: TransportSelectionReason::SppUnavailable,
        }),
        selection_of(&harness)
    );
    assert_eq!(vec![ble_endpoint(&harness)], harness.headset.endpoints());
}

#[tokio::test(start_paused = true)]
async fn dropping_one_connect_caller_does_not_cancel_the_session_owned_facts_lookup() {
    let harness = classic_i7();
    harness.runtime.refresh_bonded_headsets().await;
    harness.headset.stall_facts();
    let first = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;
    assert_eq!(1, harness.headset.facts_lookups().len());

    first.abort();
    settle().await;
    let second = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;
    assert_eq!(1, harness.headset.facts_lookups().len());
    assert!(!harness.headset.facts_cancelled());

    harness.headset.resume_facts();
    second.await.unwrap().expect("the shared attempt connects");
    assert_eq!(vec![spp()], harness.headset.endpoints());
    assert!(matches!(
        harness.connection(),
        ConnectionState::Connected { .. }
    ));
}

#[tokio::test(start_paused = true)]
async fn disconnect_cancels_a_late_facts_result_before_any_transport_connection() {
    let harness = classic_i7();
    harness.runtime.refresh_bonded_headsets().await;
    harness.headset.stall_facts();
    let pending = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;

    assert!(harness.runtime.disconnect().await);
    settle().await;
    harness.headset.resume_facts();

    assert_eq!(ControlErrorCode::Disconnected, code(pending.await.unwrap()));
    assert!(harness.headset.facts_cancelled());
    assert!(harness.headset.endpoints().is_empty());
    assert_eq!(None, selection_of(&harness));
    assert_eq!(ConnectionState::Disconnected, harness.connection());
}

#[tokio::test(start_paused = true)]
async fn the_connect_deadline_ends_the_attempt_during_the_facts_lookup_without_starting_either_link()
 {
    let mut options = Options::i7();
    options.timing.connect_timeout = Duration::from_millis(50);
    let harness = Harness::new(options);
    harness.headset.set_classic_address(Some(CLASSIC));
    harness.headset.set_connection_facts(classic_audio());
    harness.runtime.refresh_bonded_headsets().await;
    harness.headset.stall_facts();
    let pending = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;

    elapse(51).await;

    assert_eq!(ControlErrorCode::Timeout, code(pending.await.unwrap()));
    assert!(harness.headset.facts_cancelled());
    assert!(harness.headset.endpoints().is_empty());
    assert_eq!(None, selection_of(&harness));
    assert_eq!(ConnectionState::Disconnected, harness.connection());
}

#[tokio::test(start_paused = true)]
async fn a_link_without_any_meaningful_response_clears_the_published_selection() {
    let harness = Harness::new(Options::i7());
    harness.headset.set_classic_address(Some(CLASSIC));
    harness.headset.mute(true);
    harness.headset.open_delay(Duration::from_secs(1));
    harness.runtime.refresh_bonded_headsets().await;
    let pending = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let id = harness.id();
        async move { runtime.connect(id).await }
    });
    settle().await;
    assert_eq!(
        Some(TransportSelection {
            endpoint: ble_endpoint(&harness),
            reason: TransportSelectionReason::SppUnavailable,
        }),
        selection_of(&harness)
    );

    assert_eq!(ControlErrorCode::Timeout, code(pending.await.unwrap()));
    assert_eq!(None, selection_of(&harness));
    assert_eq!(ConnectionState::Disconnected, harness.connection());
}

#[tokio::test(start_paused = true)]
async fn a_failed_spp_attempt_never_falls_back_to_a_second_ble_connection() {
    let cases = [
        (
            SppFailureKind::Permission,
            ControlErrorCode::PermissionRequired,
        ),
        (SppFailureKind::Unsupported, ControlErrorCode::Unsupported),
        (SppFailureKind::Disconnected, ControlErrorCode::Disconnected),
        (SppFailureKind::Unknown, ControlErrorCode::Disconnected),
    ];
    for (kind, expected) in cases {
        let harness = classic_i7();
        harness
            .headset
            .fail_spp_connect(Some(SppError::new(kind, "Synthetic SPP failure")));
        harness.runtime.refresh_bonded_headsets().await;

        assert_eq!(
            expected,
            code(harness.runtime.connect(harness.id()).await),
            "{kind:?}"
        );

        assert_eq!(vec![spp()], harness.headset.endpoints(), "{kind:?}");
        assert_eq!(1, harness.headset.connections_opened(), "{kind:?}");
        assert!(!harness.headset.link_open(), "{kind:?}");
        assert_eq!(None, selection_of(&harness), "{kind:?}");
        assert_eq!(
            ConnectionState::Disconnected,
            harness.connection(),
            "{kind:?}"
        );
    }
}

#[tokio::test(start_paused = true)]
async fn the_platform_refusing_the_spp_socket_ends_the_attempt_without_a_ble_retry() {
    let harness = classic_i7();
    harness.runtime.refresh_bonded_headsets().await;
    harness.headset.fail_spp_writes(Some(SppError::new(
        SppFailureKind::Unknown,
        "Synthetic SPP write failure",
    )));

    assert_eq!(
        ControlErrorCode::Disconnected,
        code(harness.runtime.connect(harness.id()).await)
    );

    assert_eq!(vec![spp()], harness.headset.endpoints());
    assert_eq!(None, selection_of(&harness));
}

#[tokio::test(start_paused = true)]
async fn a_ble_readback_timeout_ends_only_that_session_and_clears_its_selection() {
    let harness = Harness::new(Options::i7());
    harness.headset.set_classic_address(Some(CLASSIC));
    harness
        .headset
        .set_connection_facts(facts(true, AudioProfileEvidence::LeAudio));
    harness.runtime.refresh_bonded_headsets().await;
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");
    harness.headset.mute(true);

    let result = harness
        .runtime
        .set(HeadsetCommand::GameMode(false), None)
        .await;
    let writes_after_query = harness.headset.written_hex().len();

    assert_eq!(ControlErrorCode::Timeout, code(result));
    assert_eq!(vec![SettingStage::TimedOut], harness.stages());
    assert_eq!(ConnectionState::Disconnected, harness.connection());
    assert_eq!(None, selection_of(&harness));
    elapse(200).await;
    assert_eq!(writes_after_query, harness.headset.written_hex().len());
    assert_eq!(1, harness.headset.endpoints().len());
}

#[tokio::test(start_paused = true)]
async fn an_spp_remote_disconnect_ends_the_session_like_any_other_link_failure() {
    let harness = classic_i7();
    harness.runtime.refresh_bonded_headsets().await;
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");
    harness.headset.mute(true);
    let pending = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        async move { runtime.set(HeadsetCommand::GameMode(false), None).await }
    });
    settle().await;
    assert!(!pending.is_finished());

    harness.headset.disconnect_spp(SppError::new(
        SppFailureKind::Disconnected,
        "Synthetic SPP stream ended",
    ));

    assert_eq!(ControlErrorCode::Disconnected, code(pending.await.unwrap()));
    assert_eq!(vec![SettingStage::Disconnected], harness.stages());
    assert_eq!(ConnectionState::Disconnected, harness.connection());
    assert_eq!(None, selection_of(&harness));
    assert!(!harness.headset.link_open());
    assert_eq!(1, harness.headset.endpoints().len());
}

#[tokio::test(start_paused = true)]
async fn disconnecting_closes_the_spp_socket_and_a_refused_close_is_reported() {
    let harness = classic_i7();
    harness.runtime.refresh_bonded_headsets().await;
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("connect");
    assert!(harness.headset.link_open());

    harness.headset.refuse_close(true);
    assert!(!harness.runtime.disconnect().await);
    assert!(harness.headset.link_open());
    harness.headset.refuse_close(false);
    assert!(harness.runtime.disconnect().await);
    assert!(!harness.headset.link_open());
}

#[tokio::test(start_paused = true)]
async fn expanded_v2_models_use_the_address_their_earphones_advertise_for_spp() {
    let records = [
        (
            ModelProfile::BudsFeelMk2RsV2,
            0x150b,
            "0a0b00000101000000a1b2c3d4e5f6",
        ),
        (
            ModelProfile::OpenFeelMk2RsV2,
            0x0e0b,
            "b65700000101000000a1b2c3d4e5f6",
        ),
        (
            ModelProfile::Swift2RsV2,
            0x9959,
            "064c00000301000000a1b2c3d4e5f6",
        ),
        (
            ModelProfile::JasmineRsV2,
            0x0a0b,
            "0a1100000101000000a1b2c3d4e5f6",
        ),
    ];
    for (expected, company, payload) in records {
        let advertisement = BTreeMap::from([(company, decode_hex(payload).expect("payload hex"))]);
        let profile = ModelIdentification::identify(&advertisement).expect("identified model");
        assert_eq!(expected, profile);
        let address = ModelIdentification::classic_address(
            &ModelIdentification::normalized_hex(&advertisement),
            Some(profile),
        );
        assert_eq!(Some(CLASSIC.to_string()), address, "{profile:?}");

        let harness = Harness::new(Options {
            profile,
            id: "v2",
            ..Options::i5()
        });
        harness.headset.set_classic_address(address.as_deref());
        harness.headset.set_connection_facts(classic_audio());
        harness.runtime.refresh_bonded_headsets().await;
        harness
            .runtime
            .connect(harness.id())
            .await
            .unwrap_or_else(|error| panic!("{profile:?}: {error}"));

        assert_eq!(Some(SPP_UUID), profile.spp_control_uuid());
        assert_eq!(vec![spp()], harness.headset.endpoints(), "{profile:?}");
        assert_eq!(
            Some(profile),
            harness.snapshot().headset.map(|headset| headset.profile())
        );
        assert!(harness.runtime.disconnect().await);
    }
}
