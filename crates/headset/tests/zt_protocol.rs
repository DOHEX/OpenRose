use openrose_headset::{
    AudioControlCapabilities, FieldReading, FieldValue, FirmwareVersion, HeadsetCommand,
    HeadsetField, HeadsetReadings, HeadsetSetting, ModelProfile, NoiseLevelCapabilities,
    ProtocolError, TouchBinding, TouchSlot, ValidationErrorKind, confirmation_value,
    create_protocol_codec, decode_hex, encode_hex,
};
use std::collections::{BTreeMap, BTreeSet};

fn hex_bytes(hex: &str) -> Vec<u8> {
    decode_hex(hex).expect("valid test hex")
}

fn known_value(
    readings: &BTreeMap<HeadsetField, FieldReading>,
    field: HeadsetField,
) -> &FieldValue {
    match readings.get(&field) {
        Some(FieldReading::Known { value, .. }) => value,
        other => panic!("expected Known reading for {field:?}, got {other:?}"),
    }
}

fn single_reply(
    codec: &mut dyn openrose_headset::ProtocolCodec,
    hex: &str,
) -> openrose_headset::ProtocolReply {
    let mut replies = codec.feed_notification(&hex_bytes(hex));
    assert_eq!(1, replies.len());
    replies.pop().expect("single reply")
}

#[test]
fn i5_initial_read_matches_independent_sender_and_actual_battery_capture() {
    let mut codec = create_protocol_codec(ModelProfile::I5Zt);
    assert!(matches!(
        codec.init_queries(9),
        Err(ProtocolError::InvalidArgument(_))
    ));
    let queries = codec.init_queries(20).expect("init queries");
    let query_hexes: Vec<String> = queries.iter().map(|q| encode_hex(&q.bytes)).collect();
    assert_eq!(
        vec![
            "08ee00000001010a0002",
            "08ee00000006020a0008",
            "08ee00000002010a0003",
            "08ee00000006030a0009",
            "08ee00000008010a0009",
            "08ee0000000d010a000e",
            "08ee00000004010a0005",
            "08ee0000000a010a000b",
            "08ee00000006050a000b",
            "08ee0000000c010a000d",
            "08ee00000006070a000d",
            "08ee00000006040a000a",
            "08ee0000000e030a0011",
        ],
        query_hexes
    );

    let reply = single_reply(codec.as_mut(), "09ff00000101011100000164620000ffe2");
    assert_eq!(BTreeSet::from(["0101".to_string()]), reply.response_keys);
    assert_eq!(
        &FieldValue::Number(100),
        known_value(&reply.readings, HeadsetField::LeftBattery)
    );
    assert_eq!(
        &FieldValue::Number(98),
        known_value(&reply.readings, HeadsetField::RightBattery)
    );
    assert!(matches!(
        reply.readings.get(&HeadsetField::CaseBattery),
        Some(FieldReading::NotProvided { raw_hex }) if raw_hex == "ff"
    ));
}

#[test]
fn i5_actual_read_samples_keep_their_own_field_semantics() {
    let mut codec = create_protocol_codec(ModelProfile::I5Zt);
    let samples = [
        (
            "09ff00000106020e000000010020",
            HeadsetField::NoiseMode,
            FieldValue::text("wind"),
        ),
        (
            "09ff00000102010b000118",
            HeadsetField::Eq,
            FieldValue::text("hifi"),
        ),
        (
            "09ff00000106030b00011e",
            HeadsetField::GameMode,
            FieldValue::Toggle(false),
        ),
        (
            "09ff00000108010b00011e",
            HeadsetField::AudioMode,
            FieldValue::text("ldac"),
        ),
        (
            "09ff0000010d010e00010205002d",
            HeadsetField::Firmware,
            FieldValue::Firmware(FirmwareVersion::new(vec![1, 2, 5], "125")),
        ),
        (
            "09ff0000010a010b00001f",
            HeadsetField::TouchEnabled,
            FieldValue::Toggle(true),
        ),
        (
            "09ff0000010c010b000122",
            HeadsetField::EarDetection,
            FieldValue::Toggle(false),
        ),
        (
            "09ff00000106070b000223",
            HeadsetField::AncLevel,
            FieldValue::Number(5),
        ),
        (
            "09ff00000106040b00001e",
            HeadsetField::TransparencyLevel,
            FieldValue::Number(1),
        ),
        (
            "09ff0000010e030b000126",
            HeadsetField::PromptVolume,
            FieldValue::Number(2),
        ),
    ];
    for (raw, field, expected) in samples {
        let reply = single_reply(codec.as_mut(), raw);
        assert_eq!(&expected, known_value(&reply.readings, field));
    }

    let keys = single_reply(codec.as_mut(), "09ff00000104011200060502040605030746");
    let FieldValue::TouchKeys(bindings) = known_value(&keys.readings, HeadsetField::TouchKeys)
    else {
        panic!("expected TouchKeys");
    };
    assert_eq!(
        Some("none"),
        bindings.first().and_then(|b| b.action.as_deref())
    );
    assert_eq!(
        Some("noise_cycle"),
        bindings.last().and_then(|b| b.action.as_deref())
    );

    let cycle = single_reply(codec.as_mut(), "09ff00000106050e000101010126");
    assert_eq!(
        &FieldValue::NoiseCycle(BTreeMap::from([
            ("normal".to_string(), Some(true)),
            ("transparency".to_string(), Some(true)),
            ("wind".to_string(), Some(true)),
            ("anc".to_string(), Some(true)),
        ])),
        known_value(&cycle.readings, HeadsetField::NoiseCycle)
    );
}

#[test]
fn i5_unknown_and_truncated_notifications_cannot_invent_state_or_rx_checksum() {
    let mut codec = create_protocol_codec(ModelProfile::I5Zt);
    assert!(!single_reply(codec.as_mut(), "09ff0000010101").accepted);

    let unknown = single_reply(codec.as_mut(), "000000000006030000a2");
    assert!(matches!(
        unknown.readings.get(&HeadsetField::GameMode),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(!unknown.accepted);

    let recognized = single_reply(codec.as_mut(), "000000000006030000a0");
    assert_eq!(
        &FieldValue::Toggle(true),
        known_value(&recognized.readings, HeadsetField::GameMode)
    );

    assert!(!single_reply(codec.as_mut(), "0000000000ffff").accepted);
    assert!(!single_reply(codec.as_mut(), "00000000000605000002020202").accepted);
    assert!(!single_reply(codec.as_mut(), "000000000004010000ffffffffffffffff").accepted);
}

#[test]
fn supplied_i5_battery_bytes_keep_unavailable_case_and_zero_distinct_from_ff() {
    let mut i5 = create_protocol_codec(ModelProfile::I5Zt);
    let no_charging = single_reply(i5.as_mut(), "000000000001010000aabb6432000050");
    assert!(matches!(
        no_charging.readings.get(&HeadsetField::CaseBattery),
        Some(FieldReading::Unknown { raw_hex }) if raw_hex == "50"
    ));

    let zero = single_reply(i5.as_mut(), "000000000001010000aabb00320000ff");
    assert!(matches!(
        zero.readings.get(&HeadsetField::LeftBattery),
        Some(FieldReading::Unknown { raw_hex }) if raw_hex == "00"
    ));
    assert!(matches!(
        zero.readings.get(&HeadsetField::CaseBattery),
        Some(FieldReading::NotProvided { .. })
    ));
}

#[test]
fn zt_initialization_uses_each_models_documented_order() {
    let i3 = create_protocol_codec(ModelProfile::I3Zt)
        .init_queries(20)
        .expect("i3 init");
    assert_eq!(
        vec![
            "08ee00000001010a0002",
            "08ee00000006020a0008",
            "08ee00000002010a0003",
            "08ee00000006030a0009",
            "08ee00000008010a0009",
            "08ee00000004010a0005",
            "08ee0000000a010a000b",
            "08ee0000000b010a000c",
        ],
        i3.iter().map(|q| encode_hex(&q.bytes)).collect::<Vec<_>>()
    );

    let head = create_protocol_codec(ModelProfile::HeadFeelZt)
        .init_queries(20)
        .expect("headfeel init");
    assert_eq!(
        vec![
            "0101", "0602", "0201", "0202", "0203", "0603", "0801", "0d01", "0401", "0a01", "0103",
            "0801", "0802", "0803", "0604", "0e03", "0605",
        ],
        head.iter().map(|q| q.key.as_str()).collect::<Vec<_>>()
    );
    assert_eq!("08ee00000001030a0004", encode_hex(&head[10].bytes));
    assert_eq!(
        BTreeSet::from([HeadsetField::AudioMode]),
        head[6].expected_fields
    );
    assert!(head[0].expected_fields.is_empty());
    assert!(head[0].response_keys.is_empty());
    assert_eq!(
        13,
        create_protocol_codec(ModelProfile::I5Zt)
            .init_queries(20)
            .expect("i5 init")
            .len()
    );
}

#[test]
fn i3_noise_and_headfeel_independent_controls_use_model_specific_wire_values() {
    let empty = BTreeMap::new();
    let mut i3 = create_protocol_codec(ModelProfile::I3Zt);
    let noise = i3
        .prepare_setting(
            HeadsetSetting::NoiseMode,
            &FieldValue::text("anc"),
            None,
            &empty,
        )
        .expect("i3 anc");
    assert_eq!(
        "08ee00000006820d000100008c",
        encode_hex(&noise.write_frames[0])
    );

    let mut head = create_protocol_codec(ModelProfile::HeadFeelZt);
    let multi = head
        .prepare_setting(
            HeadsetSetting::Multipoint,
            &FieldValue::Toggle(true),
            None,
            &empty,
        )
        .expect("headfeel multipoint");
    assert_eq!("08ee00000008820b00018c", encode_hex(&multi.write_frames[0]));
    assert_eq!("08ee00000008020a000a", encode_hex(&multi.readback.bytes));
    assert_eq!(
        BTreeMap::from([(HeadsetField::Multipoint, FieldValue::Toggle(true))]),
        multi.expected_values
    );

    let prompt = head
        .prepare_setting(
            HeadsetSetting::PromptVolume,
            &FieldValue::Number(3),
            None,
            &empty,
        )
        .expect("headfeel prompt");
    assert_eq!(
        "08ee0000000e030b000315",
        encode_hex(&prompt.write_frames[0])
    );

    let anc_level = head
        .prepare_setting(
            HeadsetSetting::AncLevel,
            &FieldValue::Number(1),
            None,
            &empty,
        )
        .expect("headfeel anc level");
    assert_eq!(
        "08ee00000006870b00018f",
        encode_hex(&anc_level.write_frames[0])
    );

    let firmware = FirmwareVersion::new(vec![1, 3, 0], "130");
    let gain = head
        .prepare_setting(
            HeadsetSetting::Gain,
            &FieldValue::Number(1),
            Some(&firmware),
            &empty,
        )
        .expect("headfeel gain");
    assert_eq!("08ee00000002830b000288", encode_hex(&gain.write_frames[0]));
}

#[test]
fn headfeel_synthetic_replies_keep_single_battery_and_independent_multipoint() {
    let mut codec = create_protocol_codec(ModelProfile::HeadFeelZt);
    let battery = single_reply(codec.as_mut(), "09ff00000101030a0050");
    assert_eq!(
        &FieldValue::Number(80),
        known_value(&battery.readings, HeadsetField::CaseBattery)
    );
    assert!(!battery.readings.contains_key(&HeadsetField::LeftBattery));

    let audio = single_reply(codec.as_mut(), "09ff00000108010a0003");
    assert_eq!(
        &FieldValue::text("ldac_lhdc"),
        known_value(&audio.readings, HeadsetField::AudioMode)
    );
    assert!(!audio.readings.contains_key(&HeadsetField::Multipoint));

    let multi = single_reply(codec.as_mut(), "09ff00000108020a0001");
    assert_eq!(
        &FieldValue::Toggle(true),
        known_value(&multi.readings, HeadsetField::Multipoint)
    );

    let prompt = single_reply(codec.as_mut(), "09ff0000010e030a0003");
    assert_eq!(
        &FieldValue::Number(3),
        known_value(&prompt.readings, HeadsetField::PromptVolume)
    );

    let eq = single_reply(codec.as_mut(), "09ff0000010202130000810200000000000000");
    assert_eq!(
        &FieldValue::IntegerList(vec![0, -1, 2, 0, 0, 0, 0, 0, 0, 0]),
        known_value(&eq.readings, HeadsetField::CustomEq)
    );
}

#[test]
fn headfeel_custom_eq_selects_curve_slot_then_writes_ten_signed_bytes() {
    let empty = BTreeMap::new();
    let mut codec = create_protocol_codec(ModelProfile::HeadFeelZt);
    let firmware = FirmwareVersion::new(vec![1, 3, 0], "130");
    let curve = vec![3, 0, -1, 2, -6, 6, 0, -2, 4, -3];
    let setting = codec
        .prepare_setting(
            HeadsetSetting::CustomEq,
            &FieldValue::IntegerList(curve.clone()),
            Some(&firmware),
            &empty,
        )
        .expect("custom eq");

    assert_eq!(
        vec![
            "08ee00000002810b000387",
            "08ee0000000282140003008102860600820483a9",
        ],
        setting
            .write_frames
            .iter()
            .map(|f| encode_hex(f))
            .collect::<Vec<_>>()
    );
    assert_eq!(20, setting.write_frames.last().expect("frame").len());
    assert_eq!("0201", setting.readback.key);
    assert_eq!(
        vec!["0202"],
        setting
            .additional_readbacks
            .iter()
            .map(|q| q.key.as_str())
            .collect::<Vec<_>>()
    );
    assert_eq!(
        vec![
            BTreeSet::from(["0201".to_string()]),
            BTreeSet::from(["0202".to_string()]),
        ],
        setting
            .readbacks()
            .into_iter()
            .map(|q| q.response_keys.clone())
            .collect::<Vec<_>>()
    );
    assert_eq!(
        vec![
            BTreeSet::from([HeadsetField::Eq]),
            BTreeSet::from([HeadsetField::CustomEq]),
        ],
        setting
            .readbacks()
            .into_iter()
            .map(|q| q.expected_fields.clone())
            .collect::<Vec<_>>()
    );
    assert_eq!(
        BTreeMap::from([
            (HeadsetField::Eq, FieldValue::text("custom")),
            (
                HeadsetField::CustomEq,
                FieldValue::IntegerList(curve.clone())
            ),
        ]),
        setting.expected_values
    );

    let init = codec.init_queries(20).expect("init");
    let custom_eq_query = init.iter().find(|q| q.key == "0202").expect("0202 query");
    assert_eq!("08ee00000002020a0004", encode_hex(&custom_eq_query.bytes));
    assert_eq!(
        BTreeSet::from([HeadsetField::CustomEq]),
        custom_eq_query.expected_fields
    );

    let reply = single_reply(codec.as_mut(), "09ff0000010202130003008102860600820483");
    assert_eq!(
        &FieldValue::IntegerList(curve),
        known_value(&reply.readings, HeadsetField::CustomEq)
    );
}

#[test]
fn headfeel_custom_eq_rejects_bad_curves_before_framing() {
    let empty = BTreeMap::new();
    let mut codec = create_protocol_codec(ModelProfile::HeadFeelZt);
    let firmware = FirmwareVersion::new(vec![1, 3, 0], "130");
    let mut high = vec![0; 10];
    high[4] = 7;
    let mut low = vec![0; 10];
    low[4] = -7;

    for bad in [
        FieldValue::IntegerList(vec![]),
        FieldValue::IntegerList(vec![0; 9]),
        FieldValue::IntegerList(vec![0; 11]),
        FieldValue::IntegerList(high),
        FieldValue::IntegerList(low),
        FieldValue::text("custom"),
    ] {
        assert!(
            matches!(
                codec.prepare_setting(HeadsetSetting::CustomEq, &bad, Some(&firmware), &empty),
                Err(ProtocolError::InvalidArgument(_))
            ),
            "{bad:?}"
        );
    }

    let flat = FieldValue::IntegerList(vec![0; 10]);
    let fw129 = FirmwareVersion::new(vec![1, 2, 9], "129");
    assert!(matches!(
        codec.prepare_setting(HeadsetSetting::CustomEq, &flat, Some(&fw129), &empty),
        Err(ProtocolError::Unsupported(_))
    ));
    assert!(matches!(
        codec.prepare_setting(HeadsetSetting::CustomEq, &flat, None, &empty),
        Err(ProtocolError::Unsupported(_))
    ));
}

#[test]
fn i3_hidden_search_reply_remains_unknown_and_headfeel_firmware_gates_custom_audio() {
    let empty = BTreeMap::new();
    let mut i3 = create_protocol_codec(ModelProfile::I3Zt);
    let result = single_reply(i3.as_mut(), "09ff0000010b010a0001");
    assert!(matches!(
        result.readings.get(&HeadsetField::HideSearch),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(!result.accepted);

    let profile = ModelProfile::HeadFeelZt;
    let fw129 = FirmwareVersion::new(vec![1, 2, 9], "129");
    let fw130 = FirmwareVersion::new(vec![1, 3, 0], "130");
    let fw131 = FirmwareVersion::new(vec![1, 3, 1], "131");

    assert!(
        !profile
            .capabilities(None, None)
            .writable_settings
            .contains(&HeadsetSetting::Gain)
    );
    assert!(
        !profile
            .capabilities(Some(&fw129), None)
            .writable_settings
            .contains(&HeadsetSetting::Gain)
    );
    assert!(
        profile
            .capabilities(Some(&fw130), None)
            .writable_settings
            .contains(&HeadsetSetting::Gain)
    );
    assert!(
        !profile
            .capabilities(None, None)
            .writable_settings
            .contains(&HeadsetSetting::CustomEq)
    );
    assert!(
        !profile
            .capabilities(Some(&fw129), None)
            .writable_settings
            .contains(&HeadsetSetting::CustomEq)
    );
    assert!(
        profile
            .capabilities(Some(&fw130), None)
            .writable_settings
            .contains(&HeadsetSetting::CustomEq)
    );
    assert!(
        profile
            .capabilities(Some(&fw131), None)
            .writable_settings
            .contains(&HeadsetSetting::CustomEq)
    );

    assert!(
        profile
            .capabilities(Some(&fw129), None)
            .equalizer
            .expect("eq")
            .custom
            .is_none()
    );
    let custom = profile
        .capabilities(Some(&fw130), None)
        .equalizer
        .expect("eq")
        .custom
        .expect("custom eq");
    assert_eq!(
        vec![20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000],
        custom.bands_hz
    );
    assert_eq!(-6, custom.min_gain);
    assert_eq!(6, custom.max_gain);
    assert!(
        profile
            .capabilities(None, None)
            .readable_fields
            .contains(&HeadsetField::CustomEq)
    );
    assert!(
        !ModelProfile::I5Zt
            .capabilities(None, None)
            .readable_fields
            .contains(&HeadsetField::CustomEq)
    );

    let touch = profile
        .capabilities(None, None)
        .touch_control
        .expect("headfeel touch");
    assert_eq!(
        Some("电源键"),
        touch
            .slot_labels
            .get(&TouchSlot::new("left", "single"))
            .map(String::as_str)
    );
    assert_eq!(
        Some("ANC键"),
        touch
            .slot_labels
            .get(&TouchSlot::new("right", "double"))
            .map(String::as_str)
    );

    let mut codec = create_protocol_codec(profile);
    assert!(matches!(
        codec.prepare_setting(
            HeadsetSetting::Eq,
            &FieldValue::text("custom"),
            Some(&fw129),
            &empty
        ),
        Err(ProtocolError::Unsupported(_))
    ));
    let custom_select = codec
        .prepare_setting(
            HeadsetSetting::Eq,
            &FieldValue::text("custom"),
            Some(&fw130),
            &empty,
        )
        .expect("custom eq preset");
    assert_eq!(
        "08ee00000002810b000387",
        encode_hex(&custom_select.write_frames[0])
    );
}

#[test]
fn i5_noise_modes_levels_cycle_and_echo_rejection_match_independent_literals() {
    let empty = BTreeMap::new();
    let mut i5 = create_protocol_codec(ModelProfile::I5Zt);
    let anc = i5
        .prepare_setting(
            HeadsetSetting::NoiseMode,
            &FieldValue::text("anc"),
            None,
            &empty,
        )
        .expect("i5 anc");
    assert_eq!(
        "08ee00000006820e00010000008d",
        encode_hex(&anc.write_frames[0])
    );
    assert_eq!("08ee00000006020a0008", encode_hex(&anc.readback.bytes));
    assert_eq!(
        BTreeSet::from(["0602".to_string()]),
        anc.readback.response_keys
    );
    assert_eq!(
        BTreeMap::from([(HeadsetField::NoiseMode, FieldValue::text("anc"))]),
        anc.expected_values
    );

    for (mode, literal) in [
        ("normal", "08ee00000006820e00000100008d"),
        ("transparency", "08ee00000006820e00000000018d"),
        ("wind", "08ee00000006820e00000001008d"),
    ] {
        let setting = i5
            .prepare_setting(
                HeadsetSetting::NoiseMode,
                &FieldValue::text(mode),
                None,
                &empty,
            )
            .expect("i5 mode");
        assert_eq!(literal, encode_hex(&setting.write_frames[0]), "{mode}");
    }

    for &profile in ModelProfile::ALL {
        let caps = profile.capabilities(None, None);
        if !caps.writable_settings.contains(&HeadsetSetting::AncLevel) {
            assert!(
                caps.noise_control
                    .as_ref()
                    .and_then(|n| n.anc_level.as_ref())
                    .is_none()
            );
            continue;
        }
        let noise = caps.noise_control.as_ref().expect("noise control");
        let anc_cap = noise
            .level_capabilities(HeadsetSetting::AncLevel)
            .expect("anc level");
        let trans_cap = noise
            .level_capabilities(HeadsetSetting::TransparencyLevel)
            .expect("transparency level");
        assert_eq!(BTreeSet::from([1, 3, 5]), anc_cap.levels);
        assert_eq!(BTreeSet::from(["anc".to_string()]), anc_cap.visible_modes);
        let expected_transparency_levels = if matches!(
            profile,
            ModelProfile::CeramicsURsV2 | ModelProfile::CeramicsUHsc2710RsV2
        ) {
            BTreeSet::from([1, 5])
        } else {
            BTreeSet::from([1, 3, 5])
        };
        assert_eq!(expected_transparency_levels, trans_cap.levels);
        assert_eq!(
            BTreeSet::from(["transparency".to_string()]),
            trans_cap.visible_modes
        );
        assert!(anc_cap.is_visible_in(Some("anc")));
        assert!(!anc_cap.is_visible_in(Some("normal")));
        assert!(!anc_cap.is_visible_in(Some("wind")));
        assert!(!trans_cap.is_visible_in(Some("wind")));
        assert!(!trans_cap.is_visible_in(Some("anc")));
        assert!(!anc_cap.is_visible_in(None));
        assert!(!trans_cap.is_visible_in(Some("unrecognized")));
    }

    let empty_level = NoiseLevelCapabilities {
        levels: BTreeSet::new(),
        visible_modes: BTreeSet::from(["anc".to_string()]),
    };
    assert!(!empty_level.is_visible_in(Some("anc")));

    for (kind, field, literals) in [
        (
            HeadsetSetting::AncLevel,
            HeadsetField::AncLevel,
            [
                "08ee00000006870b00008e",
                "08ee00000006870b00018f",
                "08ee00000006870b000290",
            ],
        ),
        (
            HeadsetSetting::TransparencyLevel,
            HeadsetField::TransparencyLevel,
            [
                "08ee00000006840b00008b",
                "08ee00000006840b00018c",
                "08ee00000006840b00028d",
            ],
        ),
    ] {
        for (idx, level) in [1, 3, 5].into_iter().enumerate() {
            let setting = i5
                .prepare_setting(kind, &FieldValue::Number(level), None, &empty)
                .expect("level");
            assert_eq!(literals[idx], encode_hex(&setting.write_frames[0]));
            assert_eq!(BTreeSet::from([field]), setting.readback.expected_fields);
            assert_eq!(
                BTreeMap::from([(field, FieldValue::Number(level))]),
                setting.expected_values
            );
        }
    }

    let cycle_input = FieldValue::NoiseCycle(BTreeMap::from([
        ("normal".to_string(), Some(true)),
        ("transparency".to_string(), Some(false)),
        ("wind".to_string(), Some(false)),
        ("anc".to_string(), Some(true)),
    ]));
    let cycle = i5
        .prepare_setting(HeadsetSetting::NoiseCycle, &cycle_input, None, &empty)
        .expect("cycle");
    assert_eq!(
        "08ee00000006850e000100000191",
        encode_hex(&cycle.write_frames[0])
    );
    assert_eq!("08ee00000006050a000b", encode_hex(&cycle.readback.bytes));

    for &profile in ModelProfile::ALL {
        let caps = profile.capabilities(None, None);
        if !caps.writable_settings.contains(&HeadsetSetting::NoiseCycle) {
            continue;
        }
        let noise = caps.noise_control.expect("noise");
        assert_eq!(0, noise.minimum_cycle_selections);
        let none = FieldValue::NoiseCycle(
            noise
                .cycle_modes
                .iter()
                .map(|m| (m.clone(), Some(false)))
                .collect(),
        );
        let prepared = create_protocol_codec(profile)
            .prepare_setting(HeadsetSetting::NoiseCycle, &none, None, &empty)
            .expect("empty cycle");
        assert_eq!(
            Some(&none),
            prepared.expected_values.get(&HeadsetField::NoiseCycle)
        );
    }

    for (kind, value) in [
        (HeadsetSetting::NoiseMode, FieldValue::Toggle(true)),
        (HeadsetSetting::AncLevel, FieldValue::Number(2)),
        (HeadsetSetting::TransparencyLevel, FieldValue::Number(0)),
        (HeadsetSetting::AncLevel, FieldValue::text("3")),
        (
            HeadsetSetting::NoiseCycle,
            FieldValue::NoiseCycle(BTreeMap::from([("anc".to_string(), Some(true))])),
        ),
        (
            HeadsetSetting::NoiseCycle,
            FieldValue::NoiseCycle(BTreeMap::from([
                ("anc".to_string(), None),
                ("normal".to_string(), Some(false)),
                ("wind".to_string(), Some(false)),
                ("transparency".to_string(), Some(false)),
            ])),
        ),
        (
            HeadsetSetting::NoiseCycle,
            FieldValue::NoiseCycle(BTreeMap::from([
                ("anc".to_string(), Some(true)),
                ("normal".to_string(), Some(false)),
                ("wind".to_string(), Some(false)),
                ("transparency".to_string(), Some(false)),
                ("auto".to_string(), Some(true)),
            ])),
        ),
    ] {
        assert!(
            matches!(
                i5.prepare_setting(kind, &value, None, &empty),
                Err(ProtocolError::InvalidArgument(_))
            ),
            "{kind:?} {value:?}"
        );
    }

    for unsupported_mode in ["deep", "auto", "dynamic"] {
        assert!(matches!(
            i5.prepare_setting(
                HeadsetSetting::NoiseMode,
                &FieldValue::text(unsupported_mode),
                None,
                &empty
            ),
            Err(ProtocolError::Unsupported(_))
        ));
    }

    let echo = single_reply(i5.as_mut(), "0000000000068200000100000020");
    assert!(!echo.accepted);
    assert!(!echo.readings.contains_key(&HeadsetField::NoiseMode));
    let state = single_reply(i5.as_mut(), "0000000000060200000000010020");
    assert_eq!(BTreeSet::from(["0602".to_string()]), state.response_keys);
    assert_eq!(
        &FieldValue::text("wind"),
        known_value(&state.readings, HeadsetField::NoiseMode)
    );
}

#[test]
fn i5_equalizer_presets_game_mode_and_readbacks_match_independent_literals() {
    let empty = BTreeMap::new();
    let mut i5 = create_protocol_codec(ModelProfile::I5Zt);
    for (preset, tx) in [
        ("pop", "08ee00000002810b000084"),
        ("hifi", "08ee00000002810b000185"),
        ("rock", "08ee00000002810b000286"),
        ("light", "08ee00000002810b000387"),
    ] {
        let setting = i5
            .prepare_setting(HeadsetSetting::Eq, &FieldValue::text(preset), None, &empty)
            .expect("preset");
        assert_eq!(tx, encode_hex(&setting.write_frames[0]));
        assert_eq!("08ee00000002010a0003", encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeSet::from(["0201".to_string()]),
            setting.readback.response_keys
        );
        assert_eq!(
            BTreeMap::from([(HeadsetField::Eq, FieldValue::text(preset))]),
            setting.expected_values
        );
    }

    for (enabled, tx) in [
        (true, "08ee00000006830b00008a"),
        (false, "08ee00000006830b00018b"),
    ] {
        let setting = i5
            .prepare_setting(
                HeadsetSetting::GameMode,
                &FieldValue::Toggle(enabled),
                None,
                &empty,
            )
            .expect("game mode");
        assert_eq!(tx, encode_hex(&setting.write_frames[0]));
        assert_eq!("08ee00000006030a0009", encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeSet::from(["0603".to_string()]),
            setting.readback.response_keys
        );
        assert_eq!(
            BTreeMap::from([(HeadsetField::GameMode, FieldValue::Toggle(enabled))]),
            setting.expected_values
        );
    }

    for (kind, value) in [
        (HeadsetSetting::Eq, FieldValue::Number(0)),
        (HeadsetSetting::Eq, FieldValue::IntegerList(vec![0; 10])),
        (HeadsetSetting::GameMode, FieldValue::Number(1)),
        (HeadsetSetting::GameMode, FieldValue::text("true")),
    ] {
        assert!(matches!(
            i5.prepare_setting(kind, &value, None, &empty),
            Err(ProtocolError::InvalidArgument(_))
        ));
    }

    for preset in ["custom", "custom_slot", "game1", "bass", "POP", "eap"] {
        assert!(matches!(
            i5.prepare_setting(HeadsetSetting::Eq, &FieldValue::text(preset), None, &empty),
            Err(ProtocolError::Unsupported(_))
        ));
    }
    assert!(matches!(
        i5.prepare_setting(
            HeadsetSetting::Eq,
            &FieldValue::text("eap"),
            Some(&FirmwareVersion::new(vec![1, 5, 2], "152")),
            &empty
        ),
        Err(ProtocolError::Unsupported(_))
    ));

    for (rx, preset) in [
        ("000000000002010000a0", "pop"),
        ("000000000002010000a1", "hifi"),
        ("000000000002010000a2", "rock"),
        ("000000000002010000a3", "light"),
    ] {
        let reply = single_reply(i5.as_mut(), rx);
        assert_eq!(BTreeSet::from(["0201".to_string()]), reply.response_keys);
        assert_eq!(
            &FieldValue::text(preset),
            known_value(&reply.readings, HeadsetField::Eq)
        );
    }

    for (rx, enabled) in [
        ("000000000006030000a0", true),
        ("000000000006030000a1", false),
    ] {
        let reply = single_reply(i5.as_mut(), rx);
        assert_eq!(
            &FieldValue::Toggle(enabled),
            known_value(&reply.readings, HeadsetField::GameMode)
        );
    }
    assert!(matches!(
        single_reply(i5.as_mut(), "000000000006030000a2")
            .readings
            .get(&HeadsetField::GameMode),
        Some(FieldReading::Unknown { .. })
    ));

    for echo in ["08ee00000002810b000084", "08ee00000006830b00008a"] {
        let reply = single_reply(i5.as_mut(), echo);
        assert!(!reply.accepted);
        assert!(!reply.readings.contains_key(&HeadsetField::Eq));
        assert!(!reply.readings.contains_key(&HeadsetField::GameMode));
    }
}

#[test]
fn i5_device_controls_audio_multipoint_touch_and_prompt_match_independent_literals() {
    let empty = BTreeMap::new();
    let i5_caps = ModelProfile::I5Zt.capabilities(None, None);
    let i5_touch = i5_caps.touch_control.expect("i5 touch");
    assert_eq!(8, i5_touch.slots.len());
    assert_eq!(
        BTreeSet::from([
            "volume_up".to_string(),
            "volume_down".to_string(),
            "previous_track".to_string(),
            "next_track".to_string(),
            "voice_assistant".to_string(),
            "play_pause".to_string(),
            "none".to_string(),
            "noise_cycle".to_string(),
        ]),
        i5_touch.actions
    );
    assert_eq!(
        Some(AudioControlCapabilities {
            modes: BTreeSet::from(["aac_sbc".to_string(), "ldac".to_string()]),
            multipoint_coupled: true,
        }),
        i5_caps.audio_control
    );
    assert_eq!(
        (1..=5).collect::<BTreeSet<_>>(),
        i5_caps.prompt_volume_levels
    );

    let mut i5 = create_protocol_codec(ModelProfile::I5Zt);
    for (kind, value, multipoint) in [
        (HeadsetSetting::AudioMode, FieldValue::text("aac_sbc"), true),
        (HeadsetSetting::AudioMode, FieldValue::text("ldac"), false),
        (HeadsetSetting::Multipoint, FieldValue::Toggle(true), true),
        (HeadsetSetting::Multipoint, FieldValue::Toggle(false), false),
    ] {
        let setting = i5
            .prepare_setting(kind, &value, None, &empty)
            .expect("audio/multipoint");
        assert_eq!(
            if multipoint {
                "08ee00000008810b00008a"
            } else {
                "08ee00000008810b00018b"
            },
            encode_hex(&setting.write_frames[0])
        );
        assert_eq!("08ee00000008010a0009", encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeSet::from(["0801".to_string()]),
            setting.readback.response_keys
        );
        assert_eq!(
            BTreeSet::from([HeadsetField::AudioMode, HeadsetField::Multipoint]),
            setting.readback.expected_fields
        );
        assert_eq!(
            BTreeMap::from([
                (
                    HeadsetField::AudioMode,
                    FieldValue::text(if multipoint { "aac_sbc" } else { "ldac" })
                ),
                (HeadsetField::Multipoint, FieldValue::Toggle(multipoint)),
            ]),
            setting.expected_values
        );
    }

    for (kind, field, enabled, tx, rx) in [
        (
            HeadsetSetting::TouchEnabled,
            HeadsetField::TouchEnabled,
            true,
            "08ee0000000a810b00008c",
            "08ee0000000a010a000b",
        ),
        (
            HeadsetSetting::TouchEnabled,
            HeadsetField::TouchEnabled,
            false,
            "08ee0000000a810b00018d",
            "08ee0000000a010a000b",
        ),
        (
            HeadsetSetting::EarDetection,
            HeadsetField::EarDetection,
            true,
            "08ee0000000c810b00008e",
            "08ee0000000c010a000d",
        ),
        (
            HeadsetSetting::EarDetection,
            HeadsetField::EarDetection,
            false,
            "08ee0000000c810b00018f",
            "08ee0000000c010a000d",
        ),
    ] {
        let setting = i5
            .prepare_setting(kind, &FieldValue::Toggle(enabled), None, &empty)
            .expect("toggle");
        assert_eq!(tx, encode_hex(&setting.write_frames[0]));
        assert_eq!(rx, encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeMap::from([(field, FieldValue::Toggle(enabled))]),
            setting.expected_values
        );
    }

    let request = FieldValue::touch_assignment("left", "double", "play_pause");
    let binding = i5
        .prepare_setting(HeadsetSetting::TouchBinding, &request, None, &empty)
        .expect("touch binding");
    assert_eq!(
        "08ee00000004810d000001058e",
        encode_hex(&binding.write_frames[0])
    );
    assert_eq!("08ee00000004010a0005", encode_hex(&binding.readback.bytes));
    assert_eq!(
        BTreeSet::from(["0401".to_string()]),
        binding.readback.response_keys
    );
    assert_eq!(
        BTreeMap::from([(HeadsetField::TouchKeys, request)]),
        binding.expected_values
    );

    let slot_vectors = [
        ("left", "single", "08ee00000004810d000000058d"),
        ("left", "double", "08ee00000004810d000001058e"),
        ("left", "triple", "08ee00000004810d000002058f"),
        ("left", "long", "08ee00000004810d0000030590"),
        ("right", "single", "08ee00000004810d000100058e"),
        ("right", "double", "08ee00000004810d000101058f"),
        ("right", "triple", "08ee00000004810d0001020590"),
        ("right", "long", "08ee00000004810d0001030591"),
    ];
    for (side, gesture, tx) in slot_vectors {
        let req = FieldValue::touch_assignment(side, gesture, "play_pause");
        let setting = i5
            .prepare_setting(HeadsetSetting::TouchBinding, &req, None, &empty)
            .expect("slot");
        assert_eq!(tx, encode_hex(&setting.write_frames[0]));
    }

    for (action, tx) in [
        ("volume_up", "08ee00000004810d0000000088"),
        ("volume_down", "08ee00000004810d0000000189"),
        ("previous_track", "08ee00000004810d000000028a"),
        ("next_track", "08ee00000004810d000000038b"),
        ("voice_assistant", "08ee00000004810d000000048c"),
        ("play_pause", "08ee00000004810d000000058d"),
        ("none", "08ee00000004810d000000068e"),
        ("noise_cycle", "08ee00000004810d000000078f"),
    ] {
        let req = FieldValue::touch_assignment("left", "single", action);
        let setting = i5
            .prepare_setting(HeadsetSetting::TouchBinding, &req, None, &empty)
            .expect("action");
        assert_eq!(tx, encode_hex(&setting.write_frames[0]));
    }

    for (level, tx) in [
        (1, "08ee0000000e830b000092"),
        (2, "08ee0000000e830b000193"),
        (3, "08ee0000000e830b000294"),
        (4, "08ee0000000e830b000395"),
        (5, "08ee0000000e830b000496"),
    ] {
        let setting = i5
            .prepare_setting(
                HeadsetSetting::PromptVolume,
                &FieldValue::Number(level),
                None,
                &empty,
            )
            .expect("prompt");
        assert_eq!(tx, encode_hex(&setting.write_frames[0]));
        assert_eq!("08ee0000000e030a0011", encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeSet::from(["0e03".to_string()]),
            setting.readback.response_keys
        );
        assert_eq!(
            BTreeMap::from([(HeadsetField::PromptVolume, FieldValue::Number(level))]),
            setting.expected_values
        );
    }

    for (kind, value) in [
        (HeadsetSetting::AudioMode, FieldValue::Number(0)),
        (HeadsetSetting::Multipoint, FieldValue::text("true")),
        (HeadsetSetting::TouchEnabled, FieldValue::Number(0)),
        (HeadsetSetting::EarDetection, FieldValue::text("on")),
        (HeadsetSetting::TouchBinding, FieldValue::TouchKeys(vec![])),
        (
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("both", "single", "none"),
        ),
        (
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("left", "hold", "none"),
        ),
        (HeadsetSetting::PromptVolume, FieldValue::Number(0)),
        (HeadsetSetting::PromptVolume, FieldValue::Number(6)),
        (HeadsetSetting::PromptVolume, FieldValue::Number(-1)),
        (HeadsetSetting::PromptVolume, FieldValue::Toggle(false)),
    ] {
        assert!(
            matches!(
                i5.prepare_setting(kind, &value, None, &empty),
                Err(ProtocolError::InvalidArgument(_))
            ),
            "{kind:?} {value:?}"
        );
    }

    for (kind, value) in [
        (HeadsetSetting::AudioMode, FieldValue::text("opus")),
        (HeadsetSetting::AudioMode, FieldValue::text("lhdc")),
        (HeadsetSetting::AudioMode, FieldValue::text("lc3")),
        (
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("left", "single", "head_tracking"),
        ),
        (
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("right", "long", "game_mode"),
        ),
    ] {
        assert!(
            matches!(
                i5.prepare_setting(kind, &value, None, &empty),
                Err(ProtocolError::Unsupported(_))
            ),
            "{kind:?} {value:?}"
        );
    }

    let touch_reply = single_reply(i5.as_mut(), "0000000000040100000f050f0f0f0f0f0f");
    let FieldValue::TouchKeys(touch_bindings) =
        known_value(&touch_reply.readings, HeadsetField::TouchKeys)
    else {
        panic!("expected TouchKeys");
    };
    assert_eq!(
        Some("play_pause"),
        touch_bindings
            .iter()
            .find(|b| b.side == "left" && b.gesture == "double")
            .and_then(|b| b.action.as_deref())
    );
    assert_eq!(
        7,
        touch_bindings.iter().filter(|b| b.action.is_none()).count()
    );
    assert!(!single_reply(i5.as_mut(), "0000000000040100000f050f0f0f0f0f").accepted);

    for (rx, field, expected) in [
        (
            "00000000000a010000a0",
            HeadsetField::TouchEnabled,
            FieldValue::Toggle(true),
        ),
        (
            "00000000000c010000a1",
            HeadsetField::EarDetection,
            FieldValue::Toggle(false),
        ),
        (
            "00000000000e030000a0",
            HeadsetField::PromptVolume,
            FieldValue::Number(1),
        ),
        (
            "00000000000e030000a4",
            HeadsetField::PromptVolume,
            FieldValue::Number(5),
        ),
    ] {
        let reply = single_reply(i5.as_mut(), rx);
        assert_eq!(&expected, known_value(&reply.readings, field));
    }

    for (rx, mode, multipoint) in [
        ("000000000008010000a0", "aac_sbc", true),
        ("000000000008010000a1", "ldac", false),
    ] {
        let reply = single_reply(i5.as_mut(), rx);
        assert_eq!(
            &FieldValue::text(mode),
            known_value(&reply.readings, HeadsetField::AudioMode)
        );
        assert_eq!(
            &FieldValue::Toggle(multipoint),
            known_value(&reply.readings, HeadsetField::Multipoint)
        );
    }

    let unknown_audio = single_reply(i5.as_mut(), "000000000008010000a2");
    assert!(matches!(
        unknown_audio.readings.get(&HeadsetField::AudioMode),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(matches!(
        unknown_audio.readings.get(&HeadsetField::Multipoint),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(matches!(
        single_reply(i5.as_mut(), "00000000000e030000a5")
            .readings
            .get(&HeadsetField::PromptVolume),
        Some(FieldReading::Unknown { .. })
    ));
}

#[test]
fn readback_confirmation_and_readings_update_preserve_adr_0033_evidence() {
    let mut i5 = create_protocol_codec(ModelProfile::I5Zt);
    let reply = single_reply(i5.as_mut(), "09ff00000104011200060502040605030746");
    let expected = FieldValue::touch_assignment("left", "double", "play_pause");
    assert_eq!(
        Some(expected.clone()),
        confirmation_value(reply.readings.get(&HeadsetField::TouchKeys), &expected)
    );

    let partial_cycle = FieldReading::known(
        FieldValue::NoiseCycle(BTreeMap::from([
            ("normal".to_string(), Some(true)),
            ("transparency".to_string(), None),
            ("wind".to_string(), Some(false)),
            ("anc".to_string(), Some(true)),
        ])),
        "01020001",
    );
    assert_eq!(
        None,
        confirmation_value(
            Some(&partial_cycle),
            &FieldValue::NoiseCycle(BTreeMap::from([
                ("normal".to_string(), Some(true)),
                ("transparency".to_string(), Some(false)),
                ("wind".to_string(), Some(false)),
                ("anc".to_string(), Some(true)),
            ]))
        )
    );

    let state = HeadsetReadings::new(ModelProfile::I5Zt).with_readings(&reply.readings);
    let simulated = state.simulate_readback(&BTreeMap::from([(
        HeadsetField::TouchKeys,
        FieldValue::touch_assignment("left", "double", "next_track"),
    )]));
    let FieldValue::TouchKeys(updated_bindings) = known_value(&simulated, HeadsetField::TouchKeys)
    else {
        panic!("expected TouchKeys");
    };
    let left_double = updated_bindings
        .iter()
        .find(|b: &&TouchBinding| b.side == "left" && b.gesture == "double")
        .expect("left/double");
    assert_eq!(Some("next_track"), left_double.action.as_deref());
    assert_eq!(5, left_double.raw_action);

    let mut head = HeadsetReadings::new(ModelProfile::HeadFeelZt);
    assert_eq!(
        Err(ValidationErrorKind::Unsupported),
        head.validate(&HeadsetCommand::Gain(1)).map_err(|e| e.kind)
    );
    head = head.with_readings(&BTreeMap::from([(
        HeadsetField::Firmware,
        FieldReading::known(
            FieldValue::Firmware(FirmwareVersion::new(vec![1, 3, 0], "130")),
            "010300",
        ),
    )]));
    assert_eq!(Ok(()), head.validate(&HeadsetCommand::Gain(1)));

    let encoded = serde_json::to_string(&head).expect("serialize");
    let decoded: HeadsetReadings = serde_json::from_str(&encoded).expect("deserialize");
    assert_eq!(head, decoded);
}
