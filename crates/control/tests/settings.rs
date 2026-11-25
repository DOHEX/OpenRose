mod support;

use openrose_control::ControlErrorCode;
use openrose_control::snapshot::{ConnectionState, SettingStage};
use openrose_headset::{FieldReading, FieldValue, HeadsetCommand, HeadsetField, NoiseMode};
use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;
use support::{Harness, I5_INIT_WRITES, Options, elapse, settle};
use tokio::time::{Instant, advance};

fn error_code<T: std::fmt::Debug>(
    result: Result<T, openrose_control::ControlError>,
) -> ControlErrorCode {
    result.expect_err("expected a failure").code
}

fn spawn_set(
    harness: &Harness,
    command: HeadsetCommand,
) -> tokio::task::JoinHandle<Result<(), openrose_control::ControlError>> {
    let runtime = Arc::clone(&harness.runtime);
    tokio::spawn(async move { runtime.set(command, None).await })
}

fn hex(strings: &[&str]) -> Vec<String> {
    strings.iter().map(|s| s.to_string()).collect()
}

const NOISE_NORMAL_WRITE: &str = "08ee00000006820e00000100008d";
const NOISE_ANC_WRITE: &str = "08ee00000006820e00010000008d";
const NOISE_READBACK: &str = "08ee00000006020a0008";
const NOISE_REPLY_NORMAL: &str = "09ff00000106020e000001000020";
const NOISE_REPLY_ANC: &str = "09ff00000106020e000100000020";

#[tokio::test(start_paused = true)]
async fn i5_setting_writes_then_reads_before_confirming_actual_state() {
    let harness = Harness::ready_i5().await;
    let setting = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Normal));
    settle().await;

    assert_eq!(
        hex(&[NOISE_NORMAL_WRITE, NOISE_READBACK]),
        harness.writes_after_init()
    );
    assert!(!setting.is_finished());
    assert_eq!(
        Some(FieldValue::text("wind")),
        harness.value(HeadsetField::NoiseMode)
    );
    assert!(harness.inject_hex(NOISE_REPLY_NORMAL));
    settle().await;

    setting.await.unwrap().expect("confirmed");
    assert_eq!(
        Some(FieldValue::text("normal")),
        harness.value(HeadsetField::NoiseMode)
    );
    harness.runtime.disconnect().await;
}

#[tokio::test(start_paused = true)]
async fn the_simulated_device_confirms_the_same_setting_without_scripted_replies() {
    let harness = Harness::ready(Options::i5()).await;
    harness
        .runtime
        .set(HeadsetCommand::NoiseMode(NoiseMode::Transparency), None)
        .await
        .expect("device readback confirms");
    assert_eq!(
        Some(&FieldValue::text("transparency")),
        harness.headset.readings().get(&HeadsetField::NoiseMode)
    );
    assert_eq!(vec![SettingStage::Confirmed], harness.stages());
}

#[tokio::test(start_paused = true)]
async fn uncertain_transport_write_timeout_closes_the_link_and_never_starts_queued_settings() {
    let harness = Harness::ready_i5().await;
    harness.headset.stall_writes();
    let first = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Anc));
    settle().await;
    let second = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Normal));
    settle().await;
    advance(Duration::from_millis(51)).await;
    settle().await;

    assert_eq!(ControlErrorCode::Timeout, error_code(first.await.unwrap()));
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(second.await.unwrap())
    );
    assert_eq!(ConnectionState::Disconnected, harness.connection());
    assert_eq!(hex(&[NOISE_ANC_WRITE]), harness.writes_after_init());
    assert_eq!(
        vec![SettingStage::TimedOut, SettingStage::Disconnected],
        harness.stages()
    );
    harness.headset.resume_writes();
    advance(Duration::from_secs(1)).await;
    settle().await;
    assert_eq!(I5_INIT_WRITES + 1, harness.headset.written_hex().len());
}

#[tokio::test(start_paused = true)]
async fn submitted_and_written_are_observable_before_device_confirmation() {
    let harness = Harness::ready_i5().await;
    harness.headset.stall_writes();
    let pending = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Normal));
    settle().await;
    assert_eq!(vec![SettingStage::Submitted], harness.stages());
    assert_eq!(1, harness.snapshot().setting_operations[0].total_frames);
    assert!(!pending.is_finished());
    harness.headset.resume_writes();
    settle().await;
    assert_eq!(vec![SettingStage::Written], harness.stages());
    assert_eq!(1, harness.snapshot().setting_operations[0].written_frames);
    assert!(!pending.is_finished());
    harness.inject_hex(NOISE_REPLY_NORMAL);
    settle().await;
    pending.await.unwrap().expect("confirmed");
    assert_eq!(vec![SettingStage::Confirmed], harness.stages());
}

#[tokio::test(start_paused = true)]
async fn cached_matching_value_cannot_confirm_and_unknown_fresh_value_times_out() {
    let harness = Harness::ready_i5().await;
    let pending = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Wind));
    settle().await;
    assert!(!pending.is_finished());
    harness.inject_hex("09ff00000106020e000000000000");
    settle().await;
    assert!(!pending.is_finished());
    assert!(matches!(
        harness.reading(HeadsetField::NoiseMode),
        Some(FieldReading::Unknown { .. })
    ));
    advance(Duration::from_millis(51)).await;
    settle().await;
    assert_eq!(
        ControlErrorCode::Timeout,
        error_code(pending.await.unwrap())
    );
    assert_eq!(vec![SettingStage::TimedOut], harness.stages());
    assert_eq!(ConnectionState::Disconnected, harness.connection());
}

#[tokio::test(start_paused = true)]
async fn different_fresh_device_state_is_not_applied_and_remains_authoritative() {
    let harness = Harness::ready_i5().await;
    let pending = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Anc));
    settle().await;
    harness.inject_hex(NOISE_REPLY_NORMAL);
    settle().await;
    assert_eq!(
        ControlErrorCode::NotApplied,
        error_code(pending.await.unwrap())
    );
    assert_eq!(vec![SettingStage::NotApplied], harness.stages());
    assert_eq!(
        Some(FieldValue::text("normal")),
        harness.value(HeadsetField::NoiseMode)
    );
    assert!(matches!(
        harness.connection(),
        ConnectionState::Connected { .. }
    ));
}

#[tokio::test(start_paused = true)]
async fn invalid_values_and_unsupported_model_modes_never_produce_wire_traffic() {
    let harness = Harness::ready_i5().await;
    for command in [
        HeadsetCommand::AncLevel(2),
        HeadsetCommand::TransparencyLevel(6),
        HeadsetCommand::NoiseCycle(BTreeMap::from([("anc".to_string(), true)])),
    ] {
        assert_eq!(
            ControlErrorCode::InvalidArgument,
            error_code(harness.runtime.set(command, None).await)
        );
    }
    assert_eq!(
        ControlErrorCode::Unsupported,
        error_code(
            harness
                .runtime
                .set(HeadsetCommand::NoiseMode(NoiseMode::Auto), None)
                .await
        )
    );
    assert_eq!(I5_INIT_WRITES, harness.headset.written_hex().len());
    assert!(harness.snapshot().setting_operations.is_empty());
    assert_eq!(
        ControlErrorCode::Unsupported,
        harness
            .snapshot()
            .last_error
            .expect("rejection is observable")
            .code
    );
}

#[tokio::test(start_paused = true)]
async fn i5_noise_level_writes_remain_independent_of_current_mode_and_other_confirmed_level() {
    let harness = Harness::ready_i5().await;
    assert_eq!(
        Some(FieldValue::text("wind")),
        harness.value(HeadsetField::NoiseMode)
    );

    let anc = spawn_set(&harness, HeadsetCommand::AncLevel(3));
    settle().await;
    assert_eq!(
        hex(&["08ee00000006870b00018f", "08ee00000006070a000d"]),
        harness.writes_after_init()
    );
    harness.inject_hex("09ff00000106070b000122");
    settle().await;
    anc.await.unwrap().expect("anc level");
    assert_eq!(
        Some(FieldValue::text("wind")),
        harness.value(HeadsetField::NoiseMode)
    );
    assert_eq!(
        Some(FieldValue::Number(3)),
        harness.value(HeadsetField::AncLevel)
    );
    assert_eq!(
        Some(FieldValue::Number(1)),
        harness.value(HeadsetField::TransparencyLevel)
    );

    let transparency = spawn_set(&harness, HeadsetCommand::TransparencyLevel(3));
    settle().await;
    assert_eq!(
        hex(&[
            "08ee00000006870b00018f",
            "08ee00000006070a000d",
            "08ee00000006840b00018c",
            "08ee00000006040a000a",
        ]),
        harness.writes_after_init()
    );
    harness.inject_hex("09ff00000106040b00011f");
    settle().await;
    transparency.await.unwrap().expect("transparency level");
    assert_eq!(
        Some(FieldValue::text("wind")),
        harness.value(HeadsetField::NoiseMode)
    );
    assert_eq!(
        Some(FieldValue::Number(3)),
        harness.value(HeadsetField::AncLevel)
    );
    assert_eq!(
        Some(FieldValue::Number(3)),
        harness.value(HeadsetField::TransparencyLevel)
    );
}

#[tokio::test(start_paused = true)]
async fn simultaneous_settings_stay_fifo_even_when_one_caller_cancels() {
    let harness = Harness::ready_i5().await;
    let first = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Anc));
    settle().await;
    let second = spawn_set(&harness, HeadsetCommand::AncLevel(5));
    settle().await;
    assert_eq!(
        vec![SettingStage::Written, SettingStage::Submitted],
        harness.stages()
    );
    assert_eq!(I5_INIT_WRITES + 2, harness.headset.written_hex().len());
    first.abort();
    settle().await;
    assert_eq!(I5_INIT_WRITES + 2, harness.headset.written_hex().len());
    harness.inject_hex(NOISE_REPLY_ANC);
    settle().await;
    assert_eq!(
        hex(&[
            NOISE_ANC_WRITE,
            NOISE_READBACK,
            "08ee00000006870b000290",
            "08ee00000006070a000d",
        ]),
        harness.writes_after_init()
    );
    assert!(!second.is_finished());
    harness.inject_hex("09ff00000106070b000223");
    settle().await;
    second.await.unwrap().expect("second setting");
    assert_eq!(
        vec![SettingStage::Confirmed, SettingStage::Confirmed],
        harness.stages()
    );
}

#[tokio::test(start_paused = true)]
async fn disconnect_ends_both_active_and_queued_settings_and_reconnect_never_replays() {
    let harness = Harness::ready_i5().await;
    let first = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Anc));
    settle().await;
    let second = spawn_set(&harness, HeadsetCommand::TransparencyLevel(3));
    settle().await;
    harness.runtime.disconnect().await;
    settle().await;
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(first.await.unwrap())
    );
    assert_eq!(
        ControlErrorCode::Disconnected,
        error_code(second.await.unwrap())
    );
    assert_eq!(
        vec![SettingStage::Disconnected, SettingStage::Disconnected],
        harness.stages()
    );
    advance(Duration::from_secs(1)).await;
    settle().await;
    assert_eq!(I5_INIT_WRITES + 2, harness.headset.written_hex().len());
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(
            harness
                .runtime
                .set(HeadsetCommand::NoiseMode(NoiseMode::Normal), None)
                .await
        )
    );
}

#[tokio::test(start_paused = true)]
async fn all_setting_and_readback_writes_keep_the_initialization_pacing() {
    let mut options = Options::i5();
    options.timing.write_interval = Some(Duration::from_millis(100));
    let harness = Harness::ready(options).await;
    harness.headset.mute(true);
    let before = Instant::now();
    let pending = spawn_set(&harness, HeadsetCommand::TransparencyLevel(3));
    settle().await;
    elapse(201).await;
    settle().await;
    assert_eq!(
        hex(&["08ee00000006840b00018c", "08ee00000006040a000a"]),
        harness.writes_after_init()
    );
    assert_eq!(vec![SettingStage::Written], harness.stages());
    let writes = harness.headset.writes();
    assert!(
        writes
            .windows(2)
            .all(|pair| pair[1].at - pair[0].at >= Duration::from_millis(100))
    );
    assert!(writes[I5_INIT_WRITES].at - before >= Duration::from_millis(100));
    harness.inject_hex("09ff00000106040b00011f");
    settle().await;
    pending.await.unwrap().expect("confirmed");
}

#[tokio::test(start_paused = true)]
async fn a_submitted_cycle_keeps_the_values_the_caller_gave() {
    let harness = Harness::ready_i5().await;
    let first = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Anc));
    settle().await;
    let mut flags = BTreeMap::from([
        ("normal".to_string(), true),
        ("transparency".to_string(), false),
        ("wind".to_string(), false),
        ("anc".to_string(), true),
    ]);
    let second = spawn_set(&harness, HeadsetCommand::NoiseCycle(flags.clone()));
    settle().await;
    flags.insert("anc".to_string(), false);
    harness.inject_hex(NOISE_REPLY_ANC);
    settle().await;
    first.await.unwrap().expect("first");
    assert_eq!(
        hex(&["08ee00000006850e000100000191", "08ee00000006050a000b"]),
        harness.writes_after_init().split_off(2)
    );
    let FieldValue::NoiseCycle(requested) =
        &harness.snapshot().setting_operations[1].requested_value
    else {
        panic!("expected a cycle");
    };
    assert_eq!(Some(&Some(true)), requested.get("anc"));
    harness.inject_hex("09ff00000106050e000100000124");
    settle().await;
    second.await.unwrap().expect("second");
}

#[tokio::test(start_paused = true)]
async fn timeout_and_late_old_reply_cannot_change_a_reconnected_session() {
    let harness = Harness::ready_i5().await;
    let pending = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Anc));
    settle().await;
    advance(Duration::from_millis(51)).await;
    settle().await;
    assert_eq!(
        ControlErrorCode::Timeout,
        error_code(pending.await.unwrap())
    );
    let old_link = harness.headset.connections_opened();

    harness.headset.mute(false);
    harness
        .runtime
        .connect(harness.id())
        .await
        .expect("fresh session");
    harness.headset.mute(true);
    let before = harness.snapshot();
    let late = openrose_headset::decode_hex(NOISE_REPLY_ANC).unwrap();
    assert!(!harness.headset.inject_late(old_link, late));
    settle().await;
    assert_eq!(before, harness.snapshot());
    assert_eq!(
        I5_INIT_WRITES,
        harness.headset.written_hex().len() - I5_INIT_WRITES - 2
    );
    harness.runtime.disconnect().await;
}

#[tokio::test(start_paused = true)]
async fn setter_echo_cannot_replace_the_mandatory_fresh_readback() {
    let harness = Harness::ready_i5().await;
    let pending = spawn_set(&harness, HeadsetCommand::NoiseMode(NoiseMode::Normal));
    settle().await;
    harness.inject_hex("09ff00000106820e000001000020");
    settle().await;
    assert_eq!(
        hex(&[NOISE_NORMAL_WRITE, NOISE_READBACK]),
        harness.writes_after_init()
    );
    assert_eq!(vec![SettingStage::Written], harness.stages());
    assert_eq!(
        Some(FieldValue::text("wind")),
        harness.value(HeadsetField::NoiseMode)
    );
    assert!(!pending.is_finished());
    harness.inject_hex(NOISE_REPLY_NORMAL);
    settle().await;
    pending.await.unwrap().expect("confirmed by the readback");
}

#[tokio::test(start_paused = true)]
async fn a_device_that_echoes_every_write_is_still_confirmed_only_by_its_readback() {
    let harness = Harness::ready(Options::i5()).await;
    harness.headset.device().acknowledge_writes(true);
    harness
        .runtime
        .set(HeadsetCommand::NoiseMode(NoiseMode::Normal), None)
        .await
        .expect("confirmed");
    assert_eq!(vec![SettingStage::Confirmed], harness.stages());
    assert_eq!(
        Some(FieldValue::text("normal")),
        harness.value(HeadsetField::NoiseMode)
    );
}

#[tokio::test(start_paused = true)]
async fn the_public_queue_has_a_bound_and_disconnect_completes_every_accepted_caller() {
    let harness = Harness::ready_i5().await;
    let mut pending = Vec::new();
    for _ in 0..33 {
        pending.push(spawn_set(
            &harness,
            HeadsetCommand::NoiseMode(NoiseMode::Anc),
        ));
        settle().await;
    }
    let last = pending.pop().expect("33 callers");
    assert_eq!(ControlErrorCode::NotReady, error_code(last.await.unwrap()));
    assert_eq!(32, harness.snapshot().setting_operations.len());
    assert_eq!(I5_INIT_WRITES + 2, harness.headset.written_hex().len());
    harness.runtime.disconnect().await;
    settle().await;
    for accepted in pending {
        assert_eq!(
            ControlErrorCode::Disconnected,
            error_code(accepted.await.unwrap())
        );
    }
    assert!(
        harness
            .stages()
            .iter()
            .all(|stage| *stage == SettingStage::Disconnected)
    );
    advance(Duration::from_secs(1)).await;
    settle().await;
    assert_eq!(I5_INIT_WRITES + 2, harness.headset.written_hex().len());
}

#[tokio::test(start_paused = true)]
async fn i5_partially_unknown_cycle_readback_waits_for_complete_device_state() {
    let harness = Harness::ready_i5().await;
    let requested = BTreeMap::from([
        ("normal".to_string(), true),
        ("transparency".to_string(), false),
        ("wind".to_string(), false),
        ("anc".to_string(), true),
    ]);
    let pending = spawn_set(&harness, HeadsetCommand::NoiseCycle(requested.clone()));
    settle().await;
    harness.inject_hex("09ff00000106050e0001ff000123");
    settle().await;
    let Some(FieldValue::NoiseCycle(actual)) = harness.value(HeadsetField::NoiseCycle) else {
        panic!("expected a cycle reading");
    };
    assert_eq!(None, actual["transparency"]);
    assert!(!pending.is_finished());
    assert_eq!(vec![SettingStage::Written], harness.stages());

    harness.inject_hex("09ff00000106050e000100000124");
    settle().await;
    pending.await.unwrap().expect("complete state confirms");
    assert_eq!(vec![SettingStage::Confirmed], harness.stages());
    assert_eq!(
        Some(FieldValue::NoiseCycle(
            requested
                .iter()
                .map(|(k, v)| (k.clone(), Some(*v)))
                .collect()
        )),
        harness.value(HeadsetField::NoiseCycle)
    );
}
