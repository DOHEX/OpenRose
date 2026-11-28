mod support;

use openrose_headset::simulator::SimulatedHeadset;
use openrose_headset::{
    FieldReading, FieldValue, HeadsetCapabilities, HeadsetCommand, HeadsetField, HeadsetReadings,
    LocalDateTime, ModelProfile, NoiseMode, ProtocolCodec, confirmation_value,
    create_protocol_codec,
};
use std::collections::{BTreeMap, BTreeSet};

const MAX_WRITE_SIZE: usize = 512;

fn connect_time() -> LocalDateTime {
    LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time")
}

fn talk(
    headset: &mut SimulatedHeadset,
    codec: &mut dyn ProtocolCodec,
    frame: &[u8],
    readings: &mut BTreeMap<HeadsetField, FieldReading>,
) -> BTreeSet<String> {
    let mut answered = BTreeSet::new();
    for notification in headset.receive(frame) {
        for reply in codec.feed_notification(&notification) {
            answered.extend(reply.response_keys);
            readings.extend(reply.readings);
        }
    }
    answered
}

fn commands(capabilities: &HeadsetCapabilities) -> Vec<HeadsetCommand> {
    let mut out = Vec::new();
    if let Some(noise) = &capabilities.noise_control {
        out.extend(
            noise
                .modes
                .iter()
                .filter_map(|id| NoiseMode::from_id(id))
                .map(HeadsetCommand::NoiseMode),
        );
        if let Some(anc) = &noise.anc_level {
            out.extend(anc.levels.iter().map(|&l| HeadsetCommand::AncLevel(l)));
        }
        if let Some(transparency) = &noise.transparency_level {
            out.extend(
                transparency
                    .levels
                    .iter()
                    .map(|&l| HeadsetCommand::TransparencyLevel(l)),
            );
        }
        let all: BTreeMap<String, bool> = noise
            .cycle_modes
            .iter()
            .map(|mode| (mode.clone(), true))
            .collect();
        let mut partial = all.clone();
        if let Some(first) = noise.cycle_modes.iter().next() {
            partial.insert(first.clone(), false);
        }
        out.push(HeadsetCommand::NoiseCycle(partial));
        out.push(HeadsetCommand::NoiseCycle(all));
    }
    if let Some(equalizer) = &capabilities.equalizer {
        out.extend(
            equalizer
                .presets
                .iter()
                .map(|preset| HeadsetCommand::EqualizerPreset(preset.clone())),
        );
        if let Some(custom) = &equalizer.custom {
            out.push(HeadsetCommand::CustomEqualizer(
                (0..custom.bands_hz.len() as i32)
                    .map(|band| (band % 5) - 2)
                    .collect(),
            ));
        }
    }
    out.extend(
        capabilities
            .prompt_volume_levels
            .iter()
            .map(|&level| HeadsetCommand::PromptVolume(level)),
    );
    out.extend(
        capabilities
            .gain_levels
            .iter()
            .map(|&l| HeadsetCommand::Gain(l)),
    );
    if let Some(audio) = &capabilities.audio_control {
        out.extend(
            audio
                .modes
                .iter()
                .map(|mode| HeadsetCommand::AudioMode(mode.clone())),
        );
    }
    out.extend(
        capabilities
            .prompt_languages
            .iter()
            .map(|language| HeadsetCommand::PromptLanguage(language.clone())),
    );
    for on in [false, true] {
        out.extend([
            HeadsetCommand::GameMode(on),
            HeadsetCommand::Multipoint(on),
            HeadsetCommand::TouchEnabled(on),
            HeadsetCommand::EarDetection(on),
            HeadsetCommand::SpatialAudio(on),
        ]);
    }
    if let Some(touch) = &capabilities.touch_control {
        for slot in &touch.slots {
            for action in &touch.actions {
                out.push(HeadsetCommand::TouchBinding {
                    side: slot.side.clone(),
                    gesture: slot.gesture.clone(),
                    action: action.clone(),
                });
            }
        }
    }
    out
}

fn exchange_init(
    profile: ModelProfile,
    headset: &mut SimulatedHeadset,
    codec: &mut dyn ProtocolCodec,
) -> BTreeMap<HeadsetField, FieldReading> {
    let mut readings = BTreeMap::new();
    let mut queries = codec
        .init_queries(MAX_WRITE_SIZE, connect_time())
        .unwrap_or_else(|error| panic!("{profile:?}: {error}"));
    let mut firmware_queries_added = false;
    let mut index = 0;
    while index < queries.len() {
        let query = queries[index].clone();
        let answered = talk(headset, codec, &query.bytes, &mut readings);
        assert!(
            query.response_keys.is_subset(&answered),
            "{profile:?} {}: expected {:?}, answered {answered:?}",
            query.key,
            query.response_keys
        );
        let readable = profile.capabilities(None, None).readable_fields;
        for field in &query.expected_fields {
            if *field == HeadsetField::HideSearch || !readable.contains(field) {
                continue;
            }
            let reading = readings.get(field);
            let answered = match reading {
                Some(FieldReading::Known { .. }) => true,
                Some(FieldReading::NotProvided { .. }) => matches!(
                    field,
                    HeadsetField::LeftBattery
                        | HeadsetField::RightBattery
                        | HeadsetField::CaseBattery
                ),
                Some(FieldReading::Unknown { .. }) => matches!(
                    field,
                    HeadsetField::CaseBattery
                        | HeadsetField::LeftInCase
                        | HeadsetField::RightInCase
                        | HeadsetField::CaseFlag
                ),
                _ => false,
            };
            assert!(answered, "{profile:?} {} {field:?}: {reading:?}", query.key);
        }
        index += 1;
        if index == queries.len() && !firmware_queries_added {
            firmware_queries_added = true;
            let firmware = match readings.get(&HeadsetField::Firmware) {
                Some(FieldReading::Known {
                    value: FieldValue::Firmware(version),
                    ..
                }) => Some(version.clone()),
                _ => None,
            };
            queries.extend(codec.firmware_init_queries(firmware.as_ref()));
        }
    }
    readings
}

#[test]
fn every_model_answers_the_initial_queries_its_own_codec_sends() {
    for profile in ModelProfile::ALL.iter().copied() {
        for firmware in support::firmwares(profile) {
            let mut headset = SimulatedHeadset::new(profile, firmware.clone())
                .unwrap_or_else(|error| panic!("{profile:?}: {error}"));
            let mut codec = create_protocol_codec(profile);
            exchange_init(profile, &mut headset, codec.as_mut());
        }
    }
}

#[test]
fn every_documented_command_is_confirmed_by_the_device_readback_for_every_model() {
    let mut exercised = 0;
    for profile in ModelProfile::ALL.iter().copied() {
        for firmware in support::firmwares(profile) {
            let mut headset = SimulatedHeadset::new(profile, firmware.clone())
                .unwrap_or_else(|error| panic!("{profile:?}: {error}"));
            let mut codec = create_protocol_codec(profile);
            let mut fields = exchange_init(profile, &mut headset, codec.as_mut());
            let state = HeadsetReadings::from_fields(profile, fields.clone());
            let confirmed_firmware = state.confirmed_firmware().cloned();
            for command in commands(&state.capabilities) {
                if state.validate(&command).is_err() {
                    continue;
                }
                let prepared = codec
                    .prepare_setting(
                        command.setting(),
                        &command.value(),
                        confirmed_firmware.as_ref(),
                        &fields,
                    )
                    .unwrap_or_else(|e| panic!("{profile:?} {command:?}: {e}"));
                let mut fresh = BTreeMap::new();
                for frame in &prepared.write_frames {
                    talk(&mut headset, codec.as_mut(), frame, &mut fresh);
                }
                for query in prepared.readbacks() {
                    talk(&mut headset, codec.as_mut(), &query.bytes, &mut fresh);
                }
                for (field, expected) in &prepared.expected_values {
                    assert_eq!(
                        Some(expected.clone()),
                        confirmation_value(fresh.get(field), expected),
                        "{profile:?} {} {command:?} {field:?}",
                        firmware.display_text
                    );
                }
                fields.extend(fresh);
                exercised += 1;
            }
        }
    }
    assert!(exercised > 1000, "only {exercised} commands exercised");
}
