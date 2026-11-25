use openrose_headset::simulator::SimulatedHeadset;
use openrose_headset::{FirmwareVersion, ModelProfile, decode_hex, encode_hex};

fn connect_time() -> openrose_headset::LocalDateTime {
    openrose_headset::LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time")
}


fn i5() -> SimulatedHeadset {
    SimulatedHeadset::new(
        ModelProfile::I5Zt,
        FirmwareVersion::new(vec![1, 2, 5], "125"),
    )
    .expect("i5 simulator")
}

fn exchange(headset: &mut SimulatedHeadset, request: &str) -> Vec<String> {
    headset
        .receive(&decode_hex(request).expect("request hex"))
        .iter()
        .map(|frame| encode_hex(frame))
        .collect()
}

#[test]
fn i5_answers_every_initial_query_with_the_actual_capture_bytes() {
    let mut headset = i5();
    let captured = [
        ("08ee00000001010a0002", "09ff00000101011100000164620000ffe2"),
        ("08ee00000006020a0008", "09ff00000106020e000000010020"),
        ("08ee00000002010a0003", "09ff00000102010b000118"),
        ("08ee00000006030a0009", "09ff00000106030b00011e"),
        ("08ee00000008010a0009", "09ff00000108010b00011e"),
        ("08ee0000000d010a000e", "09ff0000010d010e00010205002d"),
        (
            "08ee00000004010a0005",
            "09ff00000104011200060502040605030746",
        ),
        ("08ee0000000a010a000b", "09ff0000010a010b00001f"),
        ("08ee00000006050a000b", "09ff00000106050e000101010126"),
        ("08ee0000000c010a000d", "09ff0000010c010b000122"),
        ("08ee00000006070a000d", "09ff00000106070b000223"),
        ("08ee00000006040a000a", "09ff00000106040b00001e"),
        ("08ee0000000e030a0011", "09ff0000010e030b000126"),
    ];
    for (query, reply) in captured {
        assert_eq!(
            vec![reply.to_string()],
            exchange(&mut headset, query),
            "{query}"
        );
    }
}

#[test]
fn writes_change_device_state_that_only_the_readback_reveals() {
    let mut headset = i5();
    let cases = [
        (
            "08ee00000006820e00000100008d",
            "08ee00000006020a0008",
            "09ff00000106020e000001000020",
        ),
        (
            "08ee00000006870b00018f",
            "08ee00000006070a000d",
            "09ff00000106070b000122",
        ),
        (
            "08ee00000006840b00018c",
            "08ee00000006040a000a",
            "09ff00000106040b00011f",
        ),
        (
            "08ee00000006850e000100000191",
            "08ee00000006050a000b",
            "09ff00000106050e000100000124",
        ),
    ];
    for (write, readback, reply) in cases {
        assert_eq!(
            Vec::<String>::new(),
            exchange(&mut headset, write),
            "{write}"
        );
        assert_eq!(
            vec![reply.to_string()],
            exchange(&mut headset, readback),
            "{readback}"
        );
    }
}

#[test]
fn a_device_that_acknowledges_writes_echoes_the_setter_key_without_the_state_reply() {
    let mut headset = i5();
    headset.acknowledge_writes(true);
    assert_eq!(
        vec!["09ff00000106820e0000010000a0".to_string()],
        exchange(&mut headset, "08ee00000006820e00000100008d")
    );
    assert_eq!(
        vec!["09ff00000106020e000001000020".to_string()],
        exchange(&mut headset, "08ee00000006020a0008")
    );
}

#[test]
fn frames_with_a_bad_checksum_length_or_unknown_key_get_no_reply() {
    let mut headset = i5();
    assert!(exchange(&mut headset, "08ee00000006020a0009").is_empty());
    assert!(exchange(&mut headset, "08ee00000006020b0008").is_empty());
    assert!(exchange(&mut headset, "08ee00000063ff0a0063").is_empty());
    assert!(exchange(&mut headset, "08ee0000").is_empty());
}

#[test]
fn device_initiated_changes_emit_the_notification_of_the_changed_field() {
    use openrose_headset::{FieldValue, HeadsetField};
    let mut headset = i5();
    let frames = headset
        .set(HeadsetField::NoiseMode, FieldValue::text("anc"))
        .expect("anc is a ZT mode");
    assert_eq!(
        vec!["09ff00000106020e000100000020".to_string()],
        frames.iter().map(|f| encode_hex(f)).collect::<Vec<_>>()
    );
    assert_eq!(
        Some(&FieldValue::text("anc")),
        headset.readings().get(&HeadsetField::NoiseMode)
    );
    assert!(
        headset
            .set(HeadsetField::NoiseMode, FieldValue::text("dynamic"))
            .is_err()
    );
    assert_eq!(
        Some(&FieldValue::text("anc")),
        headset.readings().get(&HeadsetField::NoiseMode)
    );
    let battery = headset
        .set(HeadsetField::LeftBattery, FieldValue::Number(41))
        .expect("battery");
    assert_eq!(
        "09ff00000101011100000129620000ffa7",
        encode_hex(&battery[0])
    );
}

#[test]
fn firmware_must_be_three_hex_digits_and_families_without_a_device_side_are_reported() {
    use openrose_headset::simulator::SimulatorError;
    assert!(matches!(
        SimulatedHeadset::new(ModelProfile::I5Zt, FirmwareVersion::new(vec![1, 2], "12")),
        Err(SimulatorError::InvalidFirmware(_))
    ));
    assert!(matches!(
        SimulatedHeadset::new(
            ModelProfile::I5Zt,
            FirmwareVersion::new(vec![1, 16, 0], "1160")
        ),
        Err(SimulatorError::InvalidFirmware(_))
    ));
}

mod codec_agreement {
    use super::connect_time;
    use openrose_headset::simulator::SimulatedHeadset;
    use openrose_headset::{
        FieldReading, FirmwareVersion, HeadsetCapabilities, HeadsetCommand, HeadsetField,
        HeadsetReadings, ModelProfile, NoiseMode, ProtocolCodec, ProtocolFamily,
        confirmation_value, create_protocol_codec,
    };
    use std::collections::BTreeMap;

    fn zt_profiles() -> impl Iterator<Item = ModelProfile> {
        ModelProfile::ALL
            .iter()
            .copied()
            .filter(|profile| profile.family() == ProtocolFamily::Zt)
    }

    fn talk(
        headset: &mut SimulatedHeadset,
        codec: &mut dyn ProtocolCodec,
        frame: &[u8],
        readings: &mut BTreeMap<HeadsetField, FieldReading>,
    ) -> usize {
        let mut count = 0;
        for notification in headset.receive(frame) {
            for reply in codec.feed_notification(&notification) {
                count += 1;
                readings.extend(reply.readings);
            }
        }
        count
    }

    fn commands(caps: &HeadsetCapabilities) -> Vec<HeadsetCommand> {
        let mut out = Vec::new();
        if let Some(noise) = &caps.noise_control {
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
                .map(|m| (m.clone(), true))
                .collect();
            let mut partial = all.clone();
            if let Some(first) = noise.cycle_modes.iter().next() {
                partial.insert(first.clone(), false);
            }
            out.push(HeadsetCommand::NoiseCycle(partial));
            out.push(HeadsetCommand::NoiseCycle(all));
        }
        if let Some(eq) = &caps.equalizer {
            out.extend(
                eq.presets
                    .iter()
                    .map(|p| HeadsetCommand::EqualizerPreset(p.clone())),
            );
            if let Some(custom) = &eq.custom {
                out.push(HeadsetCommand::CustomEqualizer(
                    (0..custom.bands_hz.len() as i32)
                        .map(|band| (band % 5) - 2)
                        .collect(),
                ));
            }
        }
        out.extend(
            caps.prompt_volume_levels
                .iter()
                .map(|&l| HeadsetCommand::PromptVolume(l)),
        );
        out.extend(caps.gain_levels.iter().map(|&l| HeadsetCommand::Gain(l)));
        if let Some(audio) = &caps.audio_control {
            out.extend(
                audio
                    .modes
                    .iter()
                    .map(|m| HeadsetCommand::AudioMode(m.clone())),
            );
        }
        for on in [false, true] {
            out.extend([
                HeadsetCommand::GameMode(on),
                HeadsetCommand::Multipoint(on),
                HeadsetCommand::TouchEnabled(on),
                HeadsetCommand::EarDetection(on),
            ]);
        }
        if let Some(touch) = &caps.touch_control {
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

    #[test]
    fn every_zt_model_answers_its_initial_queries_in_a_way_the_real_codec_reads() {
        for profile in zt_profiles() {
            let mut headset =
                SimulatedHeadset::new(profile, FirmwareVersion::new(vec![1, 3, 0], "130"))
                    .expect("simulator");
            let mut codec = create_protocol_codec(profile);
            let mut readings = BTreeMap::new();
            for query in codec.init_queries(20, connect_time()).expect("queries") {
                let replies = talk(&mut headset, codec.as_mut(), &query.bytes, &mut readings);
                if !query.response_keys.is_empty() {
                    assert_eq!(1, replies, "{profile:?} {}", query.key);
                }
                for field in &query.expected_fields {
                    if *field == HeadsetField::HideSearch {
                        continue;
                    }
                    let answered = match readings.get(field) {
                        Some(FieldReading::Known { .. }) => true,
                        Some(FieldReading::NotProvided { .. }) => matches!(
                            field,
                            HeadsetField::LeftBattery
                                | HeadsetField::RightBattery
                                | HeadsetField::CaseBattery
                        ),
                        _ => false,
                    };
                    assert!(
                        answered,
                        "{profile:?} {} {field:?}: {:?}",
                        query.key,
                        readings.get(field)
                    );
                }
            }
        }
    }

    #[test]
    fn every_documented_command_is_confirmed_by_the_device_readback_for_every_zt_model() {
        let mut exercised = 0;
        for profile in zt_profiles() {
            let mut headset =
                SimulatedHeadset::new(profile, FirmwareVersion::new(vec![1, 3, 0], "130"))
                    .expect("simulator");
            let mut codec = create_protocol_codec(profile);
            let mut fields = BTreeMap::new();
            for query in codec.init_queries(20, connect_time()).expect("queries") {
                talk(&mut headset, codec.as_mut(), &query.bytes, &mut fields);
            }
            let state = HeadsetReadings::from_fields(profile, fields.clone());
            let firmware = state.confirmed_firmware().cloned();
            for command in commands(&state.capabilities) {
                if state.validate(&command).is_err() {
                    continue;
                }
                let prepared = codec
                    .prepare_setting(
                        command.setting(),
                        &command.value(),
                        firmware.as_ref(),
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
                        "{profile:?} {command:?} {field:?}"
                    );
                }
                fields.extend(fresh);
                exercised += 1;
            }
        }
        assert!(exercised > 80, "only {exercised} commands exercised");
    }
}
