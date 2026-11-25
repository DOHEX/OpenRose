use openrose_headset::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting, ModelProfile,
    ProtocolCodec, ProtocolError, ProtocolReply, ProtocolSetting, TouchSlot, create_protocol_codec,
    decode_hex, encode_hex,
};
use std::collections::{BTreeMap, BTreeSet};

fn connect_time() -> openrose_headset::LocalDateTime {
    openrose_headset::LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time")
}


const RS_V2_MODELS: [ModelProfile; 32] = [
    ModelProfile::I7RsV2,
    ModelProfile::BudsFeelMk2RsV2,
    ModelProfile::OpenFeelMk2RsV2,
    ModelProfile::Swift2RsV2,
    ModelProfile::JasmineRsV2,
    ModelProfile::CeramicsMk2RsV2,
    ModelProfile::CeramicsMk28912fRsV2,
    ModelProfile::CeramicsMk25736RsV2,
    ModelProfile::CeramicsURsV2,
    ModelProfile::CeramicsUHsc2710RsV2,
    ModelProfile::SpeakFreeRsV2,
    ModelProfile::MagicianRsV2,
    ModelProfile::FurinaRsV2,
    ModelProfile::ZirconRsV2,
    ModelProfile::HyacinthRsV2,
    ModelProfile::AstroXRsV2,
    ModelProfile::OpalRsV2,
    ModelProfile::MirrorRsV2,
    ModelProfile::Ky01RsV2,
    ModelProfile::CoralRsV2,
    ModelProfile::PatamonRsV2,
    ModelProfile::TailmonRsV2,
    ModelProfile::GabumonRsV2,
    ModelProfile::AgumonRsV2,
    ModelProfile::SpeakFeel2RsV2,
    ModelProfile::OpenFeelLRsV2,
    ModelProfile::FirstAngelRsV2,
    ModelProfile::BudsFeelAeNeoRsV2,
    ModelProfile::EarFeelIeNeoRsV2,
    ModelProfile::EarFeelIe2RsV2,
    ModelProfile::SportFeel8912fRsV2,
    ModelProfile::MokaRsV2,
];

fn hex_bytes(hex: &str) -> Vec<u8> {
    decode_hex(hex).expect("valid test hex")
}

fn fw(parts: &[i32]) -> FirmwareVersion {
    FirmwareVersion::from_parts(parts.to_vec())
}

fn codec(profile: ModelProfile) -> Box<dyn ProtocolCodec> {
    create_protocol_codec(profile)
}

fn prepare(
    codec: &mut dyn ProtocolCodec,
    setting: HeadsetSetting,
    value: &FieldValue,
) -> Result<ProtocolSetting, ProtocolError> {
    codec.prepare_setting(setting, value, None, &BTreeMap::new())
}

fn prepare_with(
    codec: &mut dyn ProtocolCodec,
    setting: HeadsetSetting,
    value: &FieldValue,
    firmware: &FirmwareVersion,
) -> Result<ProtocolSetting, ProtocolError> {
    codec.prepare_setting(setting, value, Some(firmware), &BTreeMap::new())
}

fn is_invalid<T>(result: &Result<T, ProtocolError>) -> bool {
    matches!(result, Err(ProtocolError::InvalidArgument(_)))
}

fn is_unsupported<T>(result: &Result<T, ProtocolError>) -> bool {
    matches!(result, Err(ProtocolError::Unsupported(_)))
}

fn write_hex(setting: &ProtocolSetting) -> String {
    assert_eq!(1, setting.write_frames.len());
    encode_hex(&setting.write_frames[0])
}

fn synthetic(type_id: u8, value: &str) -> Vec<u8> {
    let value = hex_bytes(value);
    let mut frame = vec![0xdd, 0, (value.len() + 1) as u8, type_id];
    frame.extend(value);
    let sum = frame.iter().fold(0u8, |sum, &byte| sum.wrapping_add(byte));
    frame.push(sum);
    frame.push(0xaa);
    frame
}

fn single_reply(codec: &mut dyn ProtocolCodec, bytes: &[u8]) -> ProtocolReply {
    let mut replies = codec.feed_notification(bytes);
    assert_eq!(1, replies.len(), "{}", encode_hex(bytes));
    replies.pop().expect("single reply")
}

fn reply_hex(codec: &mut dyn ProtocolCodec, hex: &str) -> ProtocolReply {
    single_reply(codec, &hex_bytes(hex))
}

fn known(reply: &ProtocolReply, field: HeadsetField) -> &FieldValue {
    match reply.readings.get(&field) {
        Some(FieldReading::Known { value, .. }) => value,
        other => panic!("expected Known reading for {field:?}, got {other:?}"),
    }
}

fn keys(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|item| (*item).to_string()).collect()
}

fn fields(items: &[HeadsetField]) -> BTreeSet<HeadsetField> {
    items.iter().copied().collect()
}

#[test]
fn fragmented_unknown_payload_cannot_become_an_inner_known_frame() {
    let frame = hex_bytes("dd000899dd00020901e9aafaaa");
    let whole = codec(ModelProfile::I7RsV2).feed_notification(&frame);
    let mut split = codec(ModelProfile::I7RsV2);
    let first = split.feed_notification(&hex_bytes("dd000899dd00020901e9aa"));
    let rest = split.feed_notification(&hex_bytes("faaa"));

    assert!(first.is_empty());
    let joined: Vec<ProtocolReply> = first.into_iter().chain(rest).collect();
    assert_eq!(whole, joined);
    assert!(joined.iter().all(|reply| !reply.accepted));
    assert!(
        joined
            .iter()
            .all(|reply| !reply.readings.contains_key(&HeadsetField::NoiseMode))
    );
    for cut in 1..frame.len() {
        let mut codec = codec(ModelProfile::I7RsV2);
        let mut replies = codec.feed_notification(&frame[..cut]);
        replies.extend(codec.feed_notification(&frame[cut..]));
        assert_eq!(whole, replies, "cut={cut}");
    }
}

#[test]
fn i7_initial_query_uses_independent_literal_and_complete_small_mtu_queries() {
    let mut codec = codec(ModelProfile::I7RsV2);
    let initial = codec.init_queries(512, connect_time()).expect("init queries");
    assert_eq!(
        vec![
            "ff0012fa0d0c092c2d2a45460e322b01072f082e3649aa",
            "ff0102fa3e3aaa",
        ],
        initial
            .iter()
            .map(|query| encode_hex(&query.bytes))
            .collect::<Vec<_>>()
    );
    codec.reset();
    let small = codec.init_queries(20, connect_time()).expect("small init queries");
    assert_eq!(
        vec![
            "ff000ffa0d0c092c2d2a45460e322b01072fdaaa",
            "ff0104fa082e366aaa",
            "ff0202fa3e3baa",
        ],
        small
            .iter()
            .map(|query| encode_hex(&query.bytes))
            .collect::<Vec<_>>()
    );
    assert_eq!(keys(&["08", "2e", "36"]), small[1].response_keys);
    assert!(small.iter().all(|query| query.bytes.len() <= 20));
    assert!(is_invalid(&codec.init_queries(6, connect_time())));
}

#[test]
fn magician_retains_the_legacy_fixed_info_query() {
    let mut codec = codec(ModelProfile::MagicianRsV2);
    let initial = codec.init_queries(512, connect_time()).expect("init queries");
    assert_eq!(
        "ff001efa010708090c0d0e122a2b2c2d2e2f313233363738393a3b3c3d3f454649e9aa",
        encode_hex(&initial[0].bytes)
    );
    codec.reset();
    let small = codec.init_queries(20, connect_time()).expect("small init queries");
    let mic = small
        .iter()
        .find(|query| query.key == "fa49")
        .expect("mic query");
    assert!(mic.response_keys.is_empty());
}

#[test]
fn i7_synthetic_stream_keeps_delimiters_and_accepts_only_documented_checksum_families() {
    let mut codec = codec(ModelProfile::I7RsV2);
    let sample = hex_bytes("dd000599aadd010003aa");
    assert!(codec.feed_notification(&sample[0..5]).is_empty());
    assert!(codec.feed_notification(&sample[5..8]).is_empty());
    let unknown = single_reply(codec.as_mut(), &sample[8..]);
    assert_eq!(encode_hex(&sample), unknown.raw_hex);
    assert!(!unknown.accepted);

    let batch = codec.feed_notification(&hex_bytes("dd00020901020e01faaadd00020901020e011daa"));
    assert_eq!(2, batch.len());
    for reply in &batch {
        assert_eq!(keys(&["09", "0e"]), reply.response_keys);
        assert_eq!(
            &FieldValue::text("anc"),
            known(reply, HeadsetField::NoiseMode)
        );
        assert_eq!(
            &FieldValue::Toggle(true),
            known(reply, HeadsetField::GameMode)
        );
    }
    codec.reset();
    assert!(
        codec
            .feed_notification(&hex_bytes("dd00020901020e0100aa"))
            .iter()
            .all(|reply| !reply.accepted)
    );
    codec.reset();
    assert!(
        codec
            .feed_notification(&hex_bytes("ff00020901e9aa"))
            .iter()
            .all(|reply| !reply.accepted)
    );
}

#[test]
fn i7_synthetic_readings_preserve_battery_flags_polarity_and_protocol_specific_values() {
    let mut codec = codec(ModelProfile::I7RsV2);
    let mut read =
        |type_id: u8, value: &str| single_reply(codec.as_mut(), &synthetic(type_id, value));

    let battery = read(0x0c, "e43250");
    assert_eq!(
        &FieldValue::Number(100),
        known(&battery, HeadsetField::LeftBattery)
    );
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&battery, HeadsetField::LeftInCase)
    );
    assert_eq!(
        &FieldValue::Number(80),
        known(&battery, HeadsetField::CaseBattery)
    );
    let out_of_case = read(0x0c, "ff3250");
    assert!(matches!(
        out_of_case.readings.get(&HeadsetField::CaseBattery),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(matches!(
        out_of_case.readings.get(&HeadsetField::LeftInCase),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(!read(0x0c, "ffffff").accepted);
    assert_eq!(
        &FieldValue::Firmware(fw(&[1, 5, 2])),
        known(&read(0x0d, "010502"), HeadsetField::Firmware)
    );

    let samples = [
        (
            0x07,
            "00",
            HeadsetField::TouchEnabled,
            FieldValue::Toggle(true),
        ),
        (
            0x08,
            "01",
            HeadsetField::EarDetection,
            FieldValue::Toggle(false),
        ),
        (0x0e, "01", HeadsetField::GameMode, FieldValue::Toggle(true)),
        (
            0x32,
            "01",
            HeadsetField::Multipoint,
            FieldValue::Toggle(true),
        ),
        (0x2a, "00", HeadsetField::Eq, FieldValue::text("hifi")),
        (0x2a, "0000", HeadsetField::Eq, FieldValue::text("pop")),
        (0x2b, "03", HeadsetField::AudioMode, FieldValue::text("lc3")),
        (0x2c, "03", HeadsetField::AncLevel, FieldValue::Number(3)),
        (
            0x2d,
            "05",
            HeadsetField::TransparencyLevel,
            FieldValue::Number(5),
        ),
        (
            0x2e,
            "00",
            HeadsetField::PromptVolume,
            FieldValue::Number(0),
        ),
        (0x45, "02", HeadsetField::Gain, FieldValue::Number(2)),
        (
            0x46,
            "06",
            HeadsetField::HearingProtection,
            FieldValue::Number(6),
        ),
    ];
    for (type_id, raw, field, expected) in samples {
        assert_eq!(
            &expected,
            known(&read(type_id, raw), field),
            "{type_id:02x}"
        );
    }
    assert_eq!(
        &FieldValue::IntegerList(vec![-6, -3, -1, 0, 1, 2, 3, 4, 5, 6]),
        known(&read(0x3e, "86838100010203040506"), HeadsetField::CustomEq)
    );
    let keys_reply = read(1, "01080201030304041105120613071400");
    let FieldValue::TouchKeys(bindings) = known(&keys_reply, HeadsetField::TouchKeys) else {
        panic!("expected touch keys");
    };
    assert_eq!(Some("noise_cycle"), bindings[0].action.as_deref());
    assert_eq!(Some("none"), bindings[7].action.as_deref());
    assert_eq!(
        &FieldValue::NoiseCycle(BTreeMap::from([
            ("anc".to_string(), Some(true)),
            ("wind".to_string(), Some(false)),
            ("normal".to_string(), Some(true)),
            ("transparency".to_string(), Some(true)),
        ])),
        known(&read(0x36, "01000101"), HeadsetField::NoiseCycle)
    );
}

#[test]
fn unknown_only_or_invalid_fields_cannot_establish_readiness_and_reset_drops_fragments() {
    let mut i7 = codec(ModelProfile::I7RsV2);
    for (type_id, value) in [
        (0xfe, "01"),
        (0x99, "03"),
        (0x07, "02"),
        (0x07, "0000"),
        (0x0c, "64"),
        (0x01, "0108020103030404110512061307"),
        (0x2b, "0a"),
        (0x36, "02020202"),
        (0x01, "01ff02ff03ff04ff11ff12ff13ff14ff"),
    ] {
        assert!(
            i7.feed_notification(&synthetic(type_id, value))
                .iter()
                .all(|reply| !reply.accepted),
            "{type_id:02x} {value}"
        );
    }
    let frame = hex_bytes("dd00020901e9aa");
    assert!(i7.feed_notification(&frame[..4]).is_empty());
    i7.reset();
    assert!(
        i7.feed_notification(&frame[4..])
            .iter()
            .all(|reply| !reply.accepted)
    );
    let recovered = reply_hex(i7.as_mut(), "dd00002a012baa");
    assert_eq!(
        &FieldValue::text("pop"),
        known(&recovered, HeadsetField::Eq)
    );
}

#[test]
fn receive_buffer_is_cleared_with_a_warning_when_it_exceeds_its_limit() {
    let mut codec = codec(ModelProfile::I7RsV2);
    let replies = codec.feed_notification(&vec![0xdd; 8193]);
    assert_eq!(1, replies.len());
    assert!(!replies[0].accepted);
    assert_eq!(
        vec!["接收缓冲区超过 8192 字节，已清空".to_string()],
        replies[0].warnings
    );
    let recovered = reply_hex(codec.as_mut(), "dd00022a0009aa");
    assert_eq!(
        &FieldValue::text("hifi"),
        known(&recovered, HeadsetField::Eq)
    );
}

#[test]
fn bytes_before_the_header_and_streams_without_one_are_reported_and_dropped() {
    let mut codec = codec(ModelProfile::I7RsV2);
    let replies = codec.feed_notification(&hex_bytes("0102dd00022a0009aa"));
    assert_eq!(2, replies.len());
    assert_eq!(vec!["忽略帧前数据".to_string()], replies[0].warnings);
    assert_eq!("0102", replies[0].raw_hex);
    assert_eq!(
        &FieldValue::text("hifi"),
        known(&replies[1], HeadsetField::Eq)
    );
    let headerless = codec.feed_notification(&hex_bytes("010203"));
    assert_eq!(1, headerless.len());
    assert_eq!(vec!["没有 DD 接收帧头".to_string()], headerless[0].warnings);
    assert!(codec.feed_notification(&[]).is_empty());
}

#[test]
fn i7_sequence_belongs_to_the_codec_session_and_wraps_at_255() {
    let mut codec = codec(ModelProfile::I7RsV2);
    let mut requests = Vec::new();
    for _ in 0..=128 {
        requests.extend(codec.init_queries(512, connect_time()).expect("init queries"));
    }
    assert_eq!(
        vec![0, 1, 2],
        requests
            .iter()
            .take(3)
            .map(|q| q.bytes[1])
            .collect::<Vec<_>>()
    );
    assert_eq!(255, requests[255].bytes[1]);
    assert_eq!(0, requests[256].bytes[1]);
    codec.reset();
    assert_eq!(
        0,
        codec.init_queries(512, connect_time()).expect("init queries")[0].bytes[1]
    );
}

#[test]
fn supplied_i7_battery_bytes_keep_unavailable_current_case_and_zero_distinct_from_ff() {
    let mut i7 = codec(ModelProfile::I7RsV2);
    let out_of_case = single_reply(i7.as_mut(), &synthetic(0x0c, "643250"));
    assert!(matches!(
        out_of_case.readings.get(&HeadsetField::CaseBattery),
        Some(FieldReading::Unknown { .. })
    ));
    let all_unknown = single_reply(i7.as_mut(), &synthetic(0x0c, "00ffff"));
    assert!(matches!(
        all_unknown.readings.get(&HeadsetField::LeftBattery),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(matches!(
        all_unknown.readings.get(&HeadsetField::RightBattery),
        Some(FieldReading::NotProvided { .. })
    ));
    assert!(matches!(
        all_unknown.readings.get(&HeadsetField::RightInCase),
        Some(FieldReading::Unknown { .. })
    ));
    assert!(!all_unknown.accepted);
}

#[test]
fn unit_battery_does_not_require_an_earbud_in_the_case_and_keeps_missing_values_distinct() {
    let mut codec = codec(ModelProfile::Swift2RsV2);
    let battery = reply_hex(codec.as_mut(), "dd00040cffff503baa");
    assert_eq!(
        &FieldValue::Number(80),
        known(&battery, HeadsetField::CaseBattery)
    );
    assert!(matches!(
        battery.readings.get(&HeadsetField::LeftBattery),
        Some(FieldReading::NotProvided { .. })
    ));
    assert!(matches!(
        battery.readings.get(&HeadsetField::RightBattery),
        Some(FieldReading::NotProvided { .. })
    ));
    let missing = reply_hex(codec.as_mut(), "dd00040cffffffeaaa");
    assert!(matches!(
        missing.readings.get(&HeadsetField::CaseBattery),
        Some(FieldReading::NotProvided { .. })
    ));
    let zero = reply_hex(codec.as_mut(), "dd00040cffff00ebaa");
    assert!(matches!(
        zero.readings.get(&HeadsetField::CaseBattery),
        Some(FieldReading::Unknown { .. })
    ));
    let i7_unit = reply_hex(
        create_protocol_codec(ModelProfile::I7RsV2).as_mut(),
        "dd00040cffff503baa",
    );
    assert!(matches!(
        i7_unit.readings.get(&HeadsetField::CaseBattery),
        Some(FieldReading::Unknown { .. })
    ));
}

#[test]
fn i7_modes_match_independent_sender_literals_and_read_the_actual_mode() {
    for (mode, literal) in [
        ("anc", "ff000209010baa"),
        ("normal", "ff000209020caa"),
        ("transparency", "ff000209030daa"),
        ("wind", "ff000209040eaa"),
        ("auto", "ff000209050faa"),
        ("dynamic", "ff0002090711aa"),
    ] {
        let mut codec = codec(ModelProfile::I7RsV2);
        let setting = prepare(
            codec.as_mut(),
            HeadsetSetting::NoiseMode,
            &FieldValue::text(mode),
        )
        .expect(mode);
        assert_eq!(literal, write_hex(&setting), "{mode}");
        assert_eq!(
            "ff0102fa0905aa",
            encode_hex(&setting.readback.bytes),
            "{mode}"
        );
        assert_eq!(keys(&["09"]), setting.readback.response_keys);
        assert_eq!(
            BTreeMap::from([(HeadsetField::NoiseMode, FieldValue::text(mode))]),
            setting.expected_values
        );
    }
    let state = reply_hex(
        create_protocol_codec(ModelProfile::I7RsV2).as_mut(),
        "dd00020904ecaa",
    );
    assert_eq!(
        &FieldValue::text("wind"),
        known(&state, HeadsetField::NoiseMode)
    );
}

#[test]
fn i7_anc_and_transparency_use_their_own_level_mapping() {
    for (kind, field, literals) in [
        (
            HeadsetSetting::AncLevel,
            HeadsetField::AncLevel,
            ["ff00022c012eaa", "ff00022c0330aa", "ff00022c0532aa"],
        ),
        (
            HeadsetSetting::TransparencyLevel,
            HeadsetField::TransparencyLevel,
            ["ff00022d012faa", "ff00022d0331aa", "ff00022d0533aa"],
        ),
    ] {
        for (index, level) in [1, 3, 5].into_iter().enumerate() {
            let mut codec = codec(ModelProfile::I7RsV2);
            let setting =
                prepare(codec.as_mut(), kind, &FieldValue::Number(level)).expect("level write");
            assert_eq!(literals[index], write_hex(&setting), "{kind:?} {level}");
            assert_eq!(fields(&[field]), setting.readback.expected_fields);
            assert_eq!(
                BTreeMap::from([(field, FieldValue::Number(level))]),
                setting.expected_values
            );
        }
    }
}

#[test]
fn i7_cycle_flags_use_wire_order_and_keep_an_independent_expected_snapshot() {
    let mut flags = BTreeMap::from([
        ("normal".to_string(), Some(true)),
        ("transparency".to_string(), Some(false)),
        ("wind".to_string(), Some(false)),
        ("anc".to_string(), Some(true)),
    ]);
    let input = FieldValue::NoiseCycle(flags.clone());
    let mut codec = codec(ModelProfile::I7RsV2);
    let cycle = prepare(codec.as_mut(), HeadsetSetting::NoiseCycle, &input).expect("cycle");
    assert_eq!("ff000536010001003caa", write_hex(&cycle));
    assert_eq!("ff0102fa3632aa", encode_hex(&cycle.readback.bytes));
    flags.insert("normal".to_string(), Some(false));
    let Some(FieldValue::NoiseCycle(snapshot)) =
        cycle.expected_values.get(&HeadsetField::NoiseCycle)
    else {
        panic!("expected cycle snapshot");
    };
    assert_eq!(Some(&Some(true)), snapshot.get("normal"));
}

#[test]
fn invalid_noise_values_and_unsupported_modes_never_allocate_a_sequence() {
    for profile in RS_V2_MODELS {
        let mut codec = codec(profile);
        let writable = profile.capabilities(None, None).writable_settings;
        let modes = profile
            .capabilities(None, None)
            .noise_control
            .map(|noise| noise.modes)
            .unwrap_or_default();
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
            if !writable.contains(&kind) {
                continue;
            }
            assert!(
                is_invalid(&prepare(codec.as_mut(), kind, &value)),
                "{profile:?} {kind:?} {value:?}"
            );
        }
        if !modes.contains("deep") {
            assert!(is_unsupported(&prepare(
                codec.as_mut(),
                HeadsetSetting::NoiseMode,
                &FieldValue::text("deep")
            )));
        }
        assert!(is_unsupported(&prepare(
            codec.as_mut(),
            HeadsetSetting::Eq,
            &FieldValue::text("custom")
        )));
        if writable.contains(&HeadsetSetting::NoiseMode) {
            let anc = prepare(
                codec.as_mut(),
                HeadsetSetting::NoiseMode,
                &FieldValue::text("anc"),
            )
            .expect("anc");
            assert_eq!("ff000209010baa", write_hex(&anc), "{profile:?}");
        }
    }
}

#[test]
fn ie_neo_cycle_keeps_only_its_three_choices_and_encodes_the_unused_wind_byte() {
    let mut codec = codec(ModelProfile::EarFeelIeNeoRsV2);
    let cycle = FieldValue::NoiseCycle(BTreeMap::from([
        ("anc".to_string(), Some(true)),
        ("normal".to_string(), Some(false)),
        ("transparency".to_string(), Some(true)),
    ]));
    let setting = prepare(codec.as_mut(), HeadsetSetting::NoiseCycle, &cycle).expect("cycle");
    assert_eq!("ff000536010000013caa", write_hex(&setting));
    let reply = reply_hex(codec.as_mut(), "dd000536010000013daa");
    assert_eq!(&cycle, known(&reply, HeadsetField::NoiseCycle));
}

#[test]
fn i7_initialization_writes_and_readbacks_share_the_sequence_until_reset() {
    let mut codec = codec(ModelProfile::I7RsV2);
    codec.init_queries(20, connect_time()).expect("init queries");
    let dynamic = prepare(
        codec.as_mut(),
        HeadsetSetting::NoiseMode,
        &FieldValue::text("dynamic"),
    )
    .expect("dynamic");
    assert_eq!("ff0302090714aa", write_hex(&dynamic));
    assert_eq!("ff0402fa0908aa", encode_hex(&dynamic.readback.bytes));
    codec.reset();
    let anc = FieldValue::text("anc");
    for _ in 0..127 {
        prepare(codec.as_mut(), HeadsetSetting::NoiseMode, &anc).expect("anc");
    }
    let last = prepare(codec.as_mut(), HeadsetSetting::NoiseMode, &anc).expect("last");
    assert_eq!("fffe02090109aa", write_hex(&last));
    assert_eq!("ffff02fa0903aa", encode_hex(&last.readback.bytes));
    let wrapped = prepare(codec.as_mut(), HeadsetSetting::NoiseMode, &anc).expect("wrapped");
    assert_eq!("ff000209010baa", write_hex(&wrapped));
    codec.reset();
    let restarted = prepare(codec.as_mut(), HeadsetSetting::NoiseMode, &anc).expect("restart");
    assert_eq!("ff000209010baa", write_hex(&restarted));
}

#[test]
fn i7_preset_writes_swap_hifi_and_pop_and_use_the_eq_readback() {
    for (preset, literal) in [
        ("hifi", "ff00022a002baa"),
        ("pop", "ff00022a012caa"),
        ("rock", "ff00022a022daa"),
        ("light", "ff00022a032eaa"),
        ("eap", "ff00022a0631aa"),
    ] {
        let mut codec = codec(ModelProfile::I7RsV2);
        let setting = prepare_with(
            codec.as_mut(),
            HeadsetSetting::Eq,
            &FieldValue::text(preset),
            &fw(&[1, 5, 2]),
        )
        .expect(preset);
        assert_eq!(literal, write_hex(&setting));
        assert_eq!("ff0102fa2a26aa", encode_hex(&setting.readback.bytes));
        assert_eq!(keys(&["2a"]), setting.readback.response_keys);
        assert_eq!(
            BTreeMap::from([(HeadsetField::Eq, FieldValue::text(preset))]),
            setting.expected_values
        );
    }
}

#[test]
fn i7_game_uses_one_for_enabled_and_a_client_single_field_readback() {
    for (enabled, literal) in [(true, "ff00020e0110aa"), (false, "ff00020e000faa")] {
        let mut codec = codec(ModelProfile::I7RsV2);
        let setting = prepare(
            codec.as_mut(),
            HeadsetSetting::GameMode,
            &FieldValue::Toggle(enabled),
        )
        .expect("game mode");
        assert_eq!(literal, write_hex(&setting));
        assert_eq!("ff0102fa0e0aaa", encode_hex(&setting.readback.bytes));
        assert_eq!(keys(&["0e"]), setting.readback.response_keys);
        assert_eq!(
            BTreeMap::from([(HeadsetField::GameMode, FieldValue::Toggle(enabled))]),
            setting.expected_values
        );
    }
}

#[test]
fn invalid_types_and_unavailable_presets_are_rejected_before_allocating_a_sequence() {
    for profile in RS_V2_MODELS {
        let mut codec = codec(profile);
        for (kind, value) in [
            (HeadsetSetting::Eq, FieldValue::Number(0)),
            (HeadsetSetting::Eq, FieldValue::IntegerList(vec![0; 10])),
            (HeadsetSetting::GameMode, FieldValue::Number(1)),
            (HeadsetSetting::GameMode, FieldValue::text("true")),
        ] {
            assert!(
                is_invalid(&prepare(codec.as_mut(), kind, &value)),
                "{profile:?} {kind:?} {value:?}"
            );
        }
        for preset in ["custom", "custom_slot", "game1", "bass", "POP", "eap"] {
            assert!(
                is_unsupported(&prepare(
                    codec.as_mut(),
                    HeadsetSetting::Eq,
                    &FieldValue::text(preset)
                )),
                "{profile:?} {preset}"
            );
        }
        for version in [
            fw(&[1, 5, 1]),
            FirmwareVersion::new(vec![], "152"),
            FirmwareVersion::new(vec![1, 5, 2, 0], "152"),
            FirmwareVersion::new(vec![-1, 5, 2], "152"),
        ] {
            assert!(is_unsupported(&prepare_with(
                codec.as_mut(),
                HeadsetSetting::Eq,
                &FieldValue::text("eap"),
                &version
            )));
        }
        let eq_writable = profile
            .capabilities(None, None)
            .writable_settings
            .contains(&HeadsetSetting::Eq);
        if eq_writable {
            let hifi = prepare(
                codec.as_mut(),
                HeadsetSetting::Eq,
                &FieldValue::text("hifi"),
            )
            .expect("hifi");
            assert_eq!("ff00022a002baa", write_hex(&hifi), "{profile:?}");
        } else {
            assert!(is_unsupported(&prepare(
                codec.as_mut(),
                HeadsetSetting::Eq,
                &FieldValue::text("hifi")
            )));
            let game = prepare(
                codec.as_mut(),
                HeadsetSetting::GameMode,
                &FieldValue::Toggle(true),
            )
            .expect("game");
            assert_eq!("ff00020e0110aa", write_hex(&game), "{profile:?}");
        }
    }
}

#[test]
fn eq_and_game_continue_the_same_i7_initialization_and_anc_sequence() {
    let mut codec = codec(ModelProfile::I7RsV2);
    codec.init_queries(20, connect_time()).expect("init queries");
    let eq = prepare(
        codec.as_mut(),
        HeadsetSetting::Eq,
        &FieldValue::text("hifi"),
    )
    .expect("eq");
    assert_eq!("ff03022a002eaa", write_hex(&eq));
    assert_eq!("ff0402fa2a29aa", encode_hex(&eq.readback.bytes));
    let game = prepare(
        codec.as_mut(),
        HeadsetSetting::GameMode,
        &FieldValue::Toggle(true),
    )
    .expect("game");
    assert_eq!("ff05020e0115aa", write_hex(&game));
    assert_eq!("ff0602fa0e0faa", encode_hex(&game.readback.bytes));
    let anc = prepare(
        codec.as_mut(),
        HeadsetSetting::NoiseMode,
        &FieldValue::text("anc"),
    )
    .expect("anc");
    assert_eq!("ff0702090112aa", write_hex(&anc));
    assert_eq!("ff0802fa090caa", encode_hex(&anc.readback.bytes));
}

#[test]
fn synthetic_i7_readbacks_preserve_eq_formats_and_game_polarity() {
    let mut codec = codec(ModelProfile::I7RsV2);
    for (rx, preset) in [
        ("dd00022a0009aa", "hifi"),
        ("dd00022a010aaa", "pop"),
        ("dd00022a020baa", "rock"),
        ("dd00022a030caa", "light"),
        ("dd00022a060faa", "eap"),
        ("dd00032a00000aaa", "pop"),
        ("dd00032a01000baa", "hifi"),
    ] {
        let reply = reply_hex(codec.as_mut(), rx);
        assert_eq!(keys(&["2a"]), reply.response_keys);
        assert_eq!(
            &FieldValue::text(preset),
            known(&reply, HeadsetField::Eq),
            "{rx}"
        );
    }
    for (rx, enabled) in [("dd00020e01eeaa", true), ("dd00020e00edaa", false)] {
        let reply = reply_hex(codec.as_mut(), rx);
        assert_eq!(
            &FieldValue::Toggle(enabled),
            known(&reply, HeadsetField::GameMode)
        );
    }
    let unknown_game = reply_hex(codec.as_mut(), "dd00020e02efaa");
    assert!(matches!(
        unknown_game.readings.get(&HeadsetField::GameMode),
        Some(FieldReading::Unknown { .. })
    ));
    for (rx, text, eap) in [
        ("dd00040d010501f5aa", "151", false),
        ("dd00040d010502f6aa", "152", true),
        ("dd00040d001000feaa", "0160", true),
    ] {
        let reply = reply_hex(codec.as_mut(), rx);
        let FieldValue::Firmware(version) = known(&reply, HeadsetField::Firmware) else {
            panic!("expected firmware");
        };
        assert_eq!(text, version.display_text);
        let presets = ModelProfile::I7RsV2
            .capabilities(Some(version), None)
            .equalizer
            .map(|eq| eq.presets)
            .unwrap_or_default();
        assert_eq!(eap, presets.contains("eap"), "{rx}");
    }
}

#[test]
fn i7_audio_and_multipoint_write_and_confirm_their_independent_fields() {
    for (mode, literal) in [
        ("aac_sbc", "ff00022b002caa"),
        ("ldac", "ff00022b012daa"),
        ("lhdc", "ff00022b022eaa"),
        ("lc3", "ff00022b032faa"),
    ] {
        let mut codec = codec(ModelProfile::I7RsV2);
        let setting = prepare(
            codec.as_mut(),
            HeadsetSetting::AudioMode,
            &FieldValue::text(mode),
        )
        .expect(mode);
        assert_eq!(literal, write_hex(&setting));
        assert_eq!("ff0102fa2b27aa", encode_hex(&setting.readback.bytes));
        assert_eq!(keys(&["2b"]), setting.readback.response_keys);
        assert_eq!(
            BTreeMap::from([(HeadsetField::AudioMode, FieldValue::text(mode))]),
            setting.expected_values
        );
    }
    for (enabled, literal) in [(true, "ff0002320134aa"), (false, "ff0002320033aa")] {
        let mut codec = codec(ModelProfile::I7RsV2);
        let setting = prepare(
            codec.as_mut(),
            HeadsetSetting::Multipoint,
            &FieldValue::Toggle(enabled),
        )
        .expect("multipoint");
        assert_eq!(literal, write_hex(&setting));
        assert_eq!("ff0102fa322eaa", encode_hex(&setting.readback.bytes));
        assert_eq!(keys(&["32"]), setting.readback.response_keys);
        assert_eq!(
            BTreeMap::from([(HeadsetField::Multipoint, FieldValue::Toggle(enabled))]),
            setting.expected_values
        );
    }
}

#[test]
fn i7_touch_and_detection_use_zero_for_enabled() {
    for (kind, field, enabled, write, readback) in [
        (
            HeadsetSetting::TouchEnabled,
            HeadsetField::TouchEnabled,
            true,
            "ff0002070008aa",
            "ff0102fa0703aa",
        ),
        (
            HeadsetSetting::TouchEnabled,
            HeadsetField::TouchEnabled,
            false,
            "ff0002070109aa",
            "ff0102fa0703aa",
        ),
        (
            HeadsetSetting::EarDetection,
            HeadsetField::EarDetection,
            true,
            "ff0002080009aa",
            "ff0102fa0804aa",
        ),
        (
            HeadsetSetting::EarDetection,
            HeadsetField::EarDetection,
            false,
            "ff000208010aaa",
            "ff0102fa0804aa",
        ),
    ] {
        let mut codec = codec(ModelProfile::I7RsV2);
        let setting = prepare(codec.as_mut(), kind, &FieldValue::Toggle(enabled)).expect("toggle");
        assert_eq!(write, write_hex(&setting));
        assert_eq!(readback, encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeMap::from([(field, FieldValue::Toggle(enabled))]),
            setting.expected_values
        );
    }
}

#[test]
fn i7_single_slot_binding_uses_its_own_slot_ids_and_action_codes() {
    let request = FieldValue::touch_assignment("right", "long", "noise_cycle");
    let mut codec = codec(ModelProfile::I7RsV2);
    let setting = prepare(codec.as_mut(), HeadsetSetting::TouchBinding, &request).expect("binding");
    assert_eq!("ff00030114081faa", write_hex(&setting));
    assert_eq!("ff0102fa01fdaa", encode_hex(&setting.readback.bytes));
    assert_eq!(keys(&["01"]), setting.readback.response_keys);
    assert_eq!(
        BTreeMap::from([(HeadsetField::TouchKeys, request)]),
        setting.expected_values
    );
}

#[test]
fn i7_prompt_levels_use_their_own_query_and_mute_needs_a_newer_firmware() {
    let fw213 = fw(&[2, 1, 3]);
    for (level, literal) in [
        (1, "ff00022e0130aa"),
        (2, "ff00022e0231aa"),
        (3, "ff00022e0332aa"),
        (4, "ff00022e0433aa"),
        (5, "ff00022e0534aa"),
    ] {
        let mut codec = codec(ModelProfile::I7RsV2);
        let setting = prepare_with(
            codec.as_mut(),
            HeadsetSetting::PromptVolume,
            &FieldValue::Number(level),
            &fw213,
        )
        .expect("prompt volume");
        assert_eq!(literal, write_hex(&setting));
        assert_eq!("ff0102fa2e2aaa", encode_hex(&setting.readback.bytes));
        assert_eq!(keys(&["2e"]), setting.readback.response_keys);
        assert_eq!(
            BTreeMap::from([(HeadsetField::PromptVolume, FieldValue::Number(level))]),
            setting.expected_values
        );
    }
    assert!(is_unsupported(&prepare_with(
        codec(ModelProfile::I7RsV2).as_mut(),
        HeadsetSetting::PromptVolume,
        &FieldValue::Number(0),
        &fw213
    )));
}

#[test]
fn every_model_slot_uses_its_own_address_and_allows_the_configured_actions() {
    let slots = [
        ("left", "single"),
        ("left", "double"),
        ("left", "triple"),
        ("left", "long"),
        ("right", "single"),
        ("right", "double"),
        ("right", "triple"),
        ("right", "long"),
    ];
    let frames = [
        "ff000301010105aa",
        "ff000301020106aa",
        "ff000301030107aa",
        "ff000301040108aa",
        "ff000301110115aa",
        "ff000301120116aa",
        "ff000301130117aa",
        "ff000301140118aa",
    ];
    for profile in RS_V2_MODELS {
        let caps = profile.capabilities(None, None);
        if !caps
            .writable_settings
            .contains(&HeadsetSetting::TouchBinding)
        {
            continue;
        }
        let touch = caps.touch_control.expect("touch control");
        for (index, (side, gesture)) in slots.into_iter().enumerate() {
            assert!(touch.slots.contains(&TouchSlot::new(side, gesture)));
            let request = FieldValue::touch_assignment(side, gesture, "play_pause");
            let setting = prepare(
                create_protocol_codec(profile).as_mut(),
                HeadsetSetting::TouchBinding,
                &request,
            )
            .expect("binding");
            assert_eq!(
                frames[index],
                write_hex(&setting),
                "{profile:?} {side} {gesture}"
            );
            for action in &touch.actions {
                let assignment = FieldValue::touch_assignment(side, gesture, action.as_str());
                let setting = prepare(
                    create_protocol_codec(profile).as_mut(),
                    HeadsetSetting::TouchBinding,
                    &assignment,
                )
                .expect("action binding");
                assert_eq!(
                    BTreeMap::from([(HeadsetField::TouchKeys, assignment)]),
                    setting.expected_values,
                    "{profile:?} {action}"
                );
            }
        }
    }
}

#[test]
fn i7_action_numbers_are_specific_to_the_model() {
    for (action, literal) in [
        ("none", "ff000301010004aa"),
        ("play_pause", "ff000301010105aa"),
        ("previous_track", "ff000301010206aa"),
        ("next_track", "ff000301010307aa"),
        ("volume_up", "ff000301010408aa"),
        ("volume_down", "ff000301010509aa"),
        ("game_mode", "ff00030101060aaa"),
        ("voice_assistant", "ff00030101070baa"),
        ("noise_cycle", "ff00030101080caa"),
    ] {
        let request = FieldValue::touch_assignment("left", "single", action);
        let setting = prepare(
            create_protocol_codec(ModelProfile::I7RsV2).as_mut(),
            HeadsetSetting::TouchBinding,
            &request,
        )
        .expect(action);
        assert_eq!(literal, write_hex(&setting));
    }
}

#[test]
fn invalid_control_requests_are_rejected_before_i7_sequence_allocation() {
    for profile in RS_V2_MODELS {
        let mut codec = codec(profile);
        let caps = profile.capabilities(None, None);
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
        ] {
            if !caps.writable_settings.contains(&kind) {
                continue;
            }
            assert!(
                is_invalid(&prepare(codec.as_mut(), kind, &value)),
                "{profile:?} {kind:?} {value:?}"
            );
        }
        assert!(is_unsupported(&prepare(
            codec.as_mut(),
            HeadsetSetting::AudioMode,
            &FieldValue::text("opus")
        )));
        assert!(is_unsupported(&prepare(
            codec.as_mut(),
            HeadsetSetting::TouchBinding,
            &FieldValue::touch_assignment("left", "single", "head_tracking")
        )));
        if !caps.prompt_volume_levels.contains(&0) {
            assert!(is_unsupported(&prepare(
                codec.as_mut(),
                HeadsetSetting::PromptVolume,
                &FieldValue::Number(0)
            )));
        }
        for level in [-1, 6] {
            assert!(
                is_invalid(&prepare(
                    codec.as_mut(),
                    HeadsetSetting::PromptVolume,
                    &FieldValue::Number(level)
                )),
                "{profile:?} {level}"
            );
        }
        let multipoint = prepare(
            codec.as_mut(),
            HeadsetSetting::Multipoint,
            &FieldValue::Toggle(true),
        )
        .expect("multipoint");
        assert_eq!("ff0002320134aa", write_hex(&multipoint), "{profile:?}");
    }
}

#[test]
fn initialization_touch_switches_and_audio_keep_one_i7_sequence() {
    let mut codec = codec(ModelProfile::I7RsV2);
    codec.init_queries(20, connect_time()).expect("init queries");
    let touch = prepare(
        codec.as_mut(),
        HeadsetSetting::TouchBinding,
        &FieldValue::touch_assignment("left", "single", "none"),
    )
    .expect("touch");
    assert_eq!("ff030301010007aa", write_hex(&touch));
    assert_eq!("ff0402fa0100aa", encode_hex(&touch.readback.bytes));
    let detection = prepare(
        codec.as_mut(),
        HeadsetSetting::EarDetection,
        &FieldValue::Toggle(true),
    )
    .expect("detection");
    assert_eq!("ff050208000eaa", write_hex(&detection));
    assert_eq!("ff0602fa0809aa", encode_hex(&detection.readback.bytes));
    let audio = prepare(
        codec.as_mut(),
        HeadsetSetting::AudioMode,
        &FieldValue::text("lc3"),
    )
    .expect("audio");
    assert_eq!("ff07022b0336aa", write_hex(&audio));
    assert_eq!("ff0802fa2b2eaa", encode_hex(&audio.readback.bytes));
}

#[test]
fn synthetic_touch_readbacks_keep_other_unknown_slots_and_reject_missing_slots() {
    let mut i7 = codec(ModelProfile::I7RsV2);
    let reply = reply_hex(i7.as_mut(), "dd00110101ff02ff03ff04ff11ff12ff13ff140844aa");
    let FieldValue::TouchKeys(bindings) = known(&reply, HeadsetField::TouchKeys) else {
        panic!("expected touch keys");
    };
    let right_long = bindings
        .iter()
        .find(|binding| binding.side == "right" && binding.gesture == "long")
        .expect("right long");
    assert_eq!(Some("noise_cycle"), right_long.action.as_deref());
    assert_eq!(7, bindings.iter().filter(|b| b.action.is_none()).count());
    assert!(!reply_hex(i7.as_mut(), "dd0003010105e7aa").accepted);
}

#[test]
fn synthetic_switch_prompt_and_audio_readbacks_preserve_unknown_values() {
    let mut i7 = codec(ModelProfile::I7RsV2);
    for (rx, field, expected) in [
        (
            "dd00020700e6aa",
            HeadsetField::TouchEnabled,
            FieldValue::Toggle(true),
        ),
        (
            "dd00020801e8aa",
            HeadsetField::EarDetection,
            FieldValue::Toggle(false),
        ),
        (
            "dd00022b000aaa",
            HeadsetField::AudioMode,
            FieldValue::text("aac_sbc"),
        ),
        (
            "dd00022b030daa",
            HeadsetField::AudioMode,
            FieldValue::text("lc3"),
        ),
        (
            "dd0002320011aa",
            HeadsetField::Multipoint,
            FieldValue::Toggle(false),
        ),
        (
            "dd0002320112aa",
            HeadsetField::Multipoint,
            FieldValue::Toggle(true),
        ),
    ] {
        let reply = reply_hex(i7.as_mut(), rx);
        assert_eq!(1, reply.readings.len(), "{rx}");
        assert_eq!(&expected, known(&reply, field), "{rx}");
    }
    for (rx, field) in [
        ("dd00020802e9aa", HeadsetField::EarDetection),
        ("dd0002320213aa", HeadsetField::Multipoint),
    ] {
        let reply = reply_hex(i7.as_mut(), rx);
        assert!(matches!(
            reply.readings.get(&field),
            Some(FieldReading::Unknown { .. })
        ));
    }
}
