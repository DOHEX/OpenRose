mod support;

use openrose_control::ControlErrorCode;
use openrose_control::snapshot::SettingStage;
use openrose_headset::{FieldReading, FieldValue, HeadsetCommand, HeadsetField};
use std::sync::Arc;
use std::time::Duration;
use support::{Harness, I5_INIT_WRITES, Options, elapse, settle};

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

fn last_two(harness: &Harness) -> Vec<String> {
    let writes = harness.headset.written_hex();
    writes[writes.len() - 2..].to_vec()
}

struct Vector {
    command: HeadsetCommand,
    write: &'static str,
    query: &'static str,
    reply: &'static str,
}

async fn confirm_scripted(harness: &Harness, field: HeadsetField, vector: Vector) {
    let before = harness.reading(field);
    let pending = spawn_set(harness, vector.command.clone());
    settle().await;
    assert_eq!(
        vec![vector.write.to_string(), vector.query.to_string()],
        last_two(harness),
        "{:?}",
        vector.command
    );
    assert!(!pending.is_finished(), "{:?}", vector.command);
    assert_eq!(before, harness.reading(field), "{:?}", vector.command);
    assert!(harness.inject_hex(vector.reply));
    settle().await;
    pending
        .await
        .unwrap()
        .unwrap_or_else(|e| panic!("{:?}: {e}", vector.command));
}

fn touch(side: &str, gesture: &str, action: &str) -> HeadsetCommand {
    HeadsetCommand::TouchBinding {
        side: side.into(),
        gesture: gesture.into(),
        action: action.into(),
    }
}

const UNKNOWN_TOUCH_REPLY: &str = "09ff000001040112000f0f0f0f0f0f0f0f98";
const KNOWN_TOUCH_REPLY: &str = "09ff00000104011200050f0f0f0f0f0f0f8e";

#[tokio::test(start_paused = true)]
async fn touch_confirmation_projects_only_the_requested_slot_and_allows_other_unknown_actions() {
    let harness = Harness::ready_i5().await;
    confirm_scripted(
        &harness,
        HeadsetField::TouchKeys,
        Vector {
            command: touch("left", "single", "play_pause"),
            write: "08ee00000004810d000000058d",
            query: "08ee00000004010a0005",
            reply: KNOWN_TOUCH_REPLY,
        },
    )
    .await;
    let Some(FieldValue::TouchKeys(bindings)) = harness.value(HeadsetField::TouchKeys) else {
        panic!("expected touch keys");
    };
    let target = bindings
        .iter()
        .find(|b| b.side == "left" && b.gesture == "single")
        .expect("target slot");
    assert_eq!(Some("play_pause".to_string()), target.action);
    assert!(
        bindings
            .iter()
            .filter(|b| !(b.side == "left" && b.gesture == "single"))
            .all(|b| b.action.is_none())
    );
}

#[tokio::test(start_paused = true)]
async fn i5_prompt_volume_supports_all_five_levels_without_an_off_level() {
    let harness = Harness::ready_i5().await;
    let levels = [
        (1, "08ee0000000e830b000092", "09ff0000010e030b000025"),
        (2, "08ee0000000e830b000193", "09ff0000010e030b000126"),
        (3, "08ee0000000e830b000294", "09ff0000010e030b000227"),
        (4, "08ee0000000e830b000395", "09ff0000010e030b000328"),
        (5, "08ee0000000e830b000496", "09ff0000010e030b000429"),
    ];
    for (level, write, reply) in levels {
        confirm_scripted(
            &harness,
            HeadsetField::PromptVolume,
            Vector {
                command: HeadsetCommand::PromptVolume(level),
                write,
                query: "08ee0000000e030a0011",
                reply,
            },
        )
        .await;
        assert_eq!(
            Some(FieldValue::Number(level)),
            harness.value(HeadsetField::PromptVolume)
        );
    }
}

#[tokio::test(start_paused = true)]
async fn i5_all_eight_touch_slots_and_actions_use_their_model_mapping() {
    let harness = Harness::ready_i5().await;
    let query = "08ee00000004010a0005";
    let vectors = [
        (
            "left",
            "single",
            "volume_up",
            "08ee00000004810d0000000088",
            "09ff00000104011200000f0f0f0f0f0f0f89",
        ),
        (
            "left",
            "double",
            "volume_down",
            "08ee00000004810d000001018a",
            "09ff000001040112000f010f0f0f0f0f0f8a",
        ),
        (
            "left",
            "triple",
            "previous_track",
            "08ee00000004810d000002028c",
            "09ff000001040112000f0f020f0f0f0f0f8b",
        ),
        (
            "left",
            "long",
            "next_track",
            "08ee00000004810d000003038e",
            "09ff000001040112000f0f0f030f0f0f0f8c",
        ),
        (
            "right",
            "single",
            "voice_assistant",
            "08ee00000004810d000100048d",
            "09ff000001040112000f0f0f0f040f0f0f8d",
        ),
        (
            "right",
            "double",
            "play_pause",
            "08ee00000004810d000101058f",
            "09ff000001040112000f0f0f0f0f050f0f8e",
        ),
        (
            "right",
            "triple",
            "none",
            "08ee00000004810d0001020691",
            "09ff000001040112000f0f0f0f0f0f060f8f",
        ),
        (
            "right",
            "long",
            "noise_cycle",
            "08ee00000004810d0001030793",
            "09ff000001040112000f0f0f0f0f0f0f0790",
        ),
    ];
    for (side, gesture, action, write, reply) in vectors {
        confirm_scripted(
            &harness,
            HeadsetField::TouchKeys,
            Vector {
                command: touch(side, gesture, action),
                write,
                query,
                reply,
            },
        )
        .await;
    }
}

#[tokio::test(start_paused = true)]
async fn unknown_target_touch_action_waits_for_its_known_readback() {
    let harness = Harness::ready_i5().await;
    let pending = spawn_set(&harness, touch("left", "single", "play_pause"));
    settle().await;
    harness.inject_hex(UNKNOWN_TOUCH_REPLY);
    settle().await;
    assert!(!pending.is_finished());
    assert_eq!(vec![SettingStage::Written], harness.stages());
    harness.inject_hex(KNOWN_TOUCH_REPLY);
    settle().await;
    pending.await.unwrap().expect("known readback confirms");
}

#[tokio::test(start_paused = true)]
async fn only_unknown_target_touch_action_times_out_instead_of_reporting_not_applied() {
    let harness = Harness::ready_i5().await;
    let pending = spawn_set(&harness, touch("left", "single", "play_pause"));
    settle().await;
    harness.inject_hex(UNKNOWN_TOUCH_REPLY);
    settle().await;
    elapse(51).await;
    assert_eq!(
        ControlErrorCode::Timeout,
        error_code(pending.await.unwrap())
    );
    assert_eq!(vec![SettingStage::TimedOut], harness.stages());
}

#[tokio::test(start_paused = true)]
async fn known_different_target_touch_action_reports_the_actual_mismatch() {
    let harness = Harness::ready_i5().await;
    let pending = spawn_set(&harness, touch("left", "single", "play_pause"));
    settle().await;
    harness.inject_hex("09ff00000104011200000f0f0f0f0f0f0f89");
    settle().await;
    assert_eq!(
        ControlErrorCode::NotApplied,
        error_code(pending.await.unwrap())
    );
    let Some(FieldValue::TouchKeys(bindings)) = harness.value(HeadsetField::TouchKeys) else {
        panic!("expected touch keys");
    };
    assert_eq!(Some("volume_up".to_string()), bindings[0].action);
}

#[tokio::test(start_paused = true)]
async fn i5_audio_and_multipoint_selections_confirm_the_coupling_in_both_directions() {
    let harness = Harness::ready_i5().await;
    let query = "08ee00000008010a0009";
    let vectors = [
        (
            HeadsetCommand::AudioMode("aac_sbc".into()),
            "08ee00000008810b00008a",
            "aac_sbc",
            true,
            "09ff00000108010b00001d",
        ),
        (
            HeadsetCommand::Multipoint(false),
            "08ee00000008810b00018b",
            "ldac",
            false,
            "09ff00000108010b00011e",
        ),
        (
            HeadsetCommand::AudioMode("ldac".into()),
            "08ee00000008810b00018b",
            "ldac",
            false,
            "09ff00000108010b00011e",
        ),
        (
            HeadsetCommand::Multipoint(true),
            "08ee00000008810b00008a",
            "aac_sbc",
            true,
            "09ff00000108010b00001d",
        ),
    ];
    for (command, write, mode, multipoint, reply) in vectors {
        let before_audio = harness.reading(HeadsetField::AudioMode);
        let before_multipoint = harness.reading(HeadsetField::Multipoint);
        let pending = spawn_set(&harness, command);
        settle().await;
        assert_eq!(
            vec![write.to_string(), query.to_string()],
            last_two(&harness)
        );
        assert!(!pending.is_finished());
        assert_eq!(before_audio, harness.reading(HeadsetField::AudioMode));
        assert_eq!(before_multipoint, harness.reading(HeadsetField::Multipoint));
        harness.inject_hex(reply);
        settle().await;
        pending.await.unwrap().expect("coupled confirmation");
        assert_eq!(
            Some(FieldValue::text(mode)),
            harness.value(HeadsetField::AudioMode)
        );
        assert_eq!(
            Some(FieldValue::Toggle(multipoint)),
            harness.value(HeadsetField::Multipoint)
        );
    }
}

#[tokio::test(start_paused = true)]
async fn touch_and_detection_switches_use_inverse_polarity_without_changing_other_facts() {
    let harness = Harness::ready_i5().await;
    let vectors = [
        (
            HeadsetCommand::TouchEnabled(false),
            HeadsetField::TouchEnabled,
            false,
            "08ee0000000a810b00018d",
            "08ee0000000a010a000b",
            "09ff0000010a010b000120",
        ),
        (
            HeadsetCommand::TouchEnabled(true),
            HeadsetField::TouchEnabled,
            true,
            "08ee0000000a810b00008c",
            "08ee0000000a010a000b",
            "09ff0000010a010b00001f",
        ),
        (
            HeadsetCommand::EarDetection(true),
            HeadsetField::EarDetection,
            true,
            "08ee0000000c810b00008e",
            "08ee0000000c010a000d",
            "09ff0000010c010b000021",
        ),
        (
            HeadsetCommand::EarDetection(false),
            HeadsetField::EarDetection,
            false,
            "08ee0000000c810b00018f",
            "08ee0000000c010a000d",
            "09ff0000010c010b000122",
        ),
    ];
    for (command, field, enabled, write, query, reply) in vectors {
        let before = harness.snapshot().headset.expect("headset").readings.fields;
        confirm_scripted(
            &harness,
            field,
            Vector {
                command,
                write,
                query,
                reply,
            },
        )
        .await;
        assert_eq!(Some(FieldValue::Toggle(enabled)), harness.value(field));
        let mut after = harness.snapshot().headset.expect("headset").readings.fields;
        let mut expected = before;
        after.remove(&field);
        expected.remove(&field);
        assert_eq!(expected, after, "{field:?}");
    }
}

#[tokio::test(start_paused = true)]
async fn unsupported_options_invalid_touch_slots_and_volume_boundaries_never_reach_the_wire() {
    let harness = Harness::ready_i5().await;
    for command in [
        touch("middle", "single", "none"),
        touch("left", "quadruple", "none"),
    ] {
        assert_eq!(
            ControlErrorCode::InvalidArgument,
            error_code(harness.runtime.set(command, None).await)
        );
    }
    for command in [
        touch("left", "single", "find"),
        touch("left", "single", "game_mode"),
        HeadsetCommand::AudioMode("spatial".into()),
        HeadsetCommand::AudioMode("lhdc".into()),
    ] {
        assert_eq!(
            ControlErrorCode::Unsupported,
            error_code(harness.runtime.set(command, None).await)
        );
    }
    for level in [0, 6] {
        assert_eq!(
            ControlErrorCode::InvalidArgument,
            error_code(
                harness
                    .runtime
                    .set(HeadsetCommand::PromptVolume(level), None)
                    .await
            )
        );
    }
    assert_eq!(I5_INIT_WRITES, harness.headset.written_hex().len());
    assert!(harness.snapshot().setting_operations.is_empty());
    harness.runtime.disconnect().await;
    assert_eq!(
        ControlErrorCode::NotReady,
        error_code(
            harness
                .runtime
                .set(HeadsetCommand::TouchEnabled(true), None)
                .await
        )
    );
}

#[tokio::test(start_paused = true)]
async fn i5_game_on_and_off_use_the_opposite_wire_polarity_and_wait_for_readback() {
    let harness = Harness::ready_i5().await;
    for (enabled, write, reply) in [
        (true, "08ee00000006830b00008a", "09ff00000106030b00001d"),
        (false, "08ee00000006830b00018b", "09ff00000106030b00011e"),
    ] {
        confirm_scripted(
            &harness,
            HeadsetField::GameMode,
            Vector {
                command: HeadsetCommand::GameMode(enabled),
                write,
                query: "08ee00000006030a0009",
                reply,
            },
        )
        .await;
        assert_eq!(
            Some(FieldValue::Toggle(enabled)),
            harness.value(HeadsetField::GameMode)
        );
    }
}

#[tokio::test(start_paused = true)]
async fn all_four_i5_presets_match_independent_commands_and_device_readbacks() {
    let harness = Harness::ready_i5().await;
    for (preset, write, reply) in [
        ("pop", "08ee00000002810b000084", "09ff00000102010b000017"),
        ("hifi", "08ee00000002810b000185", "09ff00000102010b000118"),
        ("rock", "08ee00000002810b000286", "09ff00000102010b000219"),
        ("light", "08ee00000002810b000387", "09ff00000102010b00031a"),
    ] {
        confirm_scripted(
            &harness,
            HeadsetField::Eq,
            Vector {
                command: HeadsetCommand::EqualizerPreset(preset.into()),
                write,
                query: "08ee00000002010a0003",
                reply,
            },
        )
        .await;
        assert_eq!(
            Some(FieldValue::text(preset)),
            harness.value(HeadsetField::Eq),
            "{preset}"
        );
    }
}

#[tokio::test(start_paused = true)]
async fn unsupported_presets_produce_no_wire_traffic() {
    let harness = Harness::ready_i5().await;
    for preset in ["custom", "custom_slot", "game1", "missing", "eap"] {
        assert_eq!(
            ControlErrorCode::Unsupported,
            error_code(
                harness
                    .runtime
                    .set(HeadsetCommand::EqualizerPreset(preset.into()), None)
                    .await
            ),
            "{preset}"
        );
    }
    assert_eq!(I5_INIT_WRITES, harness.headset.written_hex().len());
    assert!(harness.snapshot().setting_operations.is_empty());
}

#[tokio::test(start_paused = true)]
async fn every_i5_setting_is_confirmed_by_the_device_itself_not_by_a_script() {
    let harness = Harness::ready(Options::i5()).await;
    let commands = [
        (
            HeadsetCommand::EqualizerPreset("rock".into()),
            HeadsetField::Eq,
            FieldValue::text("rock"),
        ),
        (
            HeadsetCommand::GameMode(true),
            HeadsetField::GameMode,
            FieldValue::Toggle(true),
        ),
        (
            HeadsetCommand::PromptVolume(5),
            HeadsetField::PromptVolume,
            FieldValue::Number(5),
        ),
        (
            HeadsetCommand::AudioMode("aac_sbc".into()),
            HeadsetField::AudioMode,
            FieldValue::text("aac_sbc"),
        ),
        (
            HeadsetCommand::TouchEnabled(false),
            HeadsetField::TouchEnabled,
            FieldValue::Toggle(false),
        ),
        (
            HeadsetCommand::EarDetection(true),
            HeadsetField::EarDetection,
            FieldValue::Toggle(true),
        ),
        (
            HeadsetCommand::AncLevel(1),
            HeadsetField::AncLevel,
            FieldValue::Number(1),
        ),
    ];
    for (command, field, expected) in commands {
        harness
            .runtime
            .set(command.clone(), None)
            .await
            .expect("confirmed");
        assert_eq!(Some(expected.clone()), harness.value(field), "{command:?}");
        assert_eq!(
            Some(&expected),
            harness.headset.readings().get(&field),
            "{command:?}"
        );
    }
    assert!(matches!(
        harness.reading(HeadsetField::Multipoint),
        Some(FieldReading::Known {
            value: FieldValue::Toggle(true),
            ..
        })
    ));
    assert!(
        harness
            .stages()
            .iter()
            .all(|stage| *stage == SettingStage::Confirmed)
    );
    elapse(Duration::from_secs(1).as_millis() as u64 / 100).await;
}
