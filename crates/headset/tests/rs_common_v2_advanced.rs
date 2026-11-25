use openrose_headset::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting, LocalDateTime,
    ModelProfile, ProtocolCodec, ProtocolError, ProtocolReply, ProtocolSetting, TouchBinding,
    create_protocol_codec, decode_hex, encode_hex,
};
use std::collections::{BTreeMap, BTreeSet};

fn connect_time() -> openrose_headset::LocalDateTime {
    openrose_headset::LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time")
}


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

fn write_hexes(setting: &ProtocolSetting) -> Vec<String> {
    setting
        .write_frames
        .iter()
        .map(|frame| encode_hex(frame))
        .collect()
}

fn readback_hexes(setting: &ProtocolSetting) -> Vec<String> {
    setting
        .readbacks()
        .iter()
        .map(|query| encode_hex(&query.bytes))
        .collect()
}

fn reply_hex(codec: &mut dyn ProtocolCodec, hex: &str) -> ProtocolReply {
    let mut replies = codec.feed_notification(&hex_bytes(hex));
    assert_eq!(1, replies.len(), "{hex}");
    replies.pop().expect("single reply")
}

fn keys(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|item| (*item).to_string()).collect()
}

fn fields(items: &[HeadsetField]) -> BTreeSet<HeadsetField> {
    items.iter().copied().collect()
}

fn game_mode_state(mode: bool) -> BTreeMap<HeadsetField, FieldReading> {
    BTreeMap::from([(
        HeadsetField::GameMode,
        FieldReading::known(FieldValue::Toggle(mode), if mode { "01" } else { "00" }),
    )])
}

const REFERENCE_CURVE: [i32; 10] = [-6, -3, -1, 0, 1, 2, 3, 4, 5, 6];

fn invalid_curves() -> Vec<FieldValue> {
    vec![
        FieldValue::IntegerList(vec![]),
        FieldValue::IntegerList(vec![0; 9]),
        FieldValue::IntegerList(vec![0; 11]),
        FieldValue::IntegerList(vec![-7, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        FieldValue::IntegerList(vec![0, 0, 0, 0, 0, 0, 0, 0, 0, 7]),
        FieldValue::Number(0),
    ]
}

#[test]
fn custom_curve_uses_two_ordered_writes_and_two_independent_readbacks_after_initialization() {
    let mut codec = codec(ModelProfile::I7RsV2);
    assert_eq!(3, codec.init_queries(20, connect_time()).expect("init queries").len());
    let mut gains = REFERENCE_CURVE.to_vec();
    let setting = prepare(
        codec.as_mut(),
        HeadsetSetting::CustomEq,
        &FieldValue::IntegerList(gains.clone()),
    )
    .expect("custom curve");
    gains[0] = 6;
    assert_eq!(
        vec!["ff03022a0432aa", "ff040b3e86838100010203040506ebaa"],
        write_hexes(&setting)
    );
    assert_eq!(
        vec!["ff0502fa2a2aaa", "ff0602fa3e3faa"],
        readback_hexes(&setting)
    );
    assert_eq!(
        vec![keys(&["2a"]), keys(&["3e"])],
        setting
            .readbacks()
            .iter()
            .map(|query| query.response_keys.clone())
            .collect::<Vec<_>>()
    );
    assert_eq!(
        vec![
            fields(&[HeadsetField::Eq]),
            fields(&[HeadsetField::CustomEq])
        ],
        setting
            .readbacks()
            .iter()
            .map(|query| query.expected_fields.clone())
            .collect::<Vec<_>>()
    );
    assert_eq!(
        BTreeMap::from([
            (HeadsetField::Eq, FieldValue::text("custom")),
            (
                HeadsetField::CustomEq,
                FieldValue::IntegerList(REFERENCE_CURVE.to_vec())
            ),
        ]),
        setting.expected_values
    );
    let game = prepare(
        codec.as_mut(),
        HeadsetSetting::GameMode,
        &FieldValue::Toggle(true),
    )
    .expect("game");
    assert_eq!("ff07020e0117aa", write_hex(&game));
}

#[test]
fn invalid_custom_curves_are_rejected_before_any_i7_sequence_is_allocated() {
    for profile in [ModelProfile::I7RsV2, ModelProfile::OpenFeelMk2RsV2] {
        for curve in invalid_curves() {
            let mut codec = codec(profile);
            assert!(
                is_invalid(&prepare(codec.as_mut(), HeadsetSetting::CustomEq, &curve)),
                "{profile:?} {curve:?}"
            );
        }
    }
    let mut codec = codec(ModelProfile::I7RsV2);
    let valid = prepare(
        codec.as_mut(),
        HeadsetSetting::CustomEq,
        &FieldValue::IntegerList(vec![0; 10]),
    )
    .expect("valid curve");
    assert_eq!(
        vec!["ff00022a042faa", "ff010b3e0000000000000000000049aa"],
        write_hexes(&valid)
    );
    assert!(is_unsupported(&prepare(
        codec_without_custom_curve().as_mut(),
        HeadsetSetting::CustomEq,
        &FieldValue::IntegerList(vec![0; 10])
    )));
}

fn codec_without_custom_curve() -> Box<dyn ProtocolCodec> {
    codec(ModelProfile::AstroXRsV2)
}

#[test]
fn i7_gain_and_protection_use_independent_readbacks_from_firmware_213() {
    let fw213 = fw(&[2, 1, 3]);
    let mut gate = codec(ModelProfile::I7RsV2);
    assert!(is_unsupported(&prepare(
        gate.as_mut(),
        HeadsetSetting::Gain,
        &FieldValue::Number(0)
    )));
    assert!(is_unsupported(&prepare(
        gate.as_mut(),
        HeadsetSetting::HearingProtection,
        &FieldValue::Number(0)
    )));
    assert!(is_invalid(&prepare_with(
        gate.as_mut(),
        HeadsetSetting::Gain,
        &FieldValue::Number(3),
        &fw213
    )));
    assert!(is_invalid(&prepare_with(
        gate.as_mut(),
        HeadsetSetting::HearingProtection,
        &FieldValue::Number(7),
        &fw213
    )));
    let first_valid = prepare_with(
        gate.as_mut(),
        HeadsetSetting::Gain,
        &FieldValue::Number(2),
        &fw213,
    )
    .expect("gain");
    assert_eq!("ff0002450248aa", write_hex(&first_valid));

    for (level, literal) in [
        (0, "ff0002450046aa"),
        (1, "ff0002450147aa"),
        (2, "ff0002450248aa"),
    ] {
        let setting = prepare_with(
            codec(ModelProfile::I7RsV2).as_mut(),
            HeadsetSetting::Gain,
            &FieldValue::Number(level),
            &fw213,
        )
        .expect("gain");
        assert_eq!(literal, write_hex(&setting));
        assert_eq!("ff0102fa4541aa", encode_hex(&setting.readback.bytes));
        assert_eq!(keys(&["45"]), setting.readback.response_keys);
        assert_eq!(
            BTreeMap::from([(HeadsetField::Gain, FieldValue::Number(level))]),
            setting.expected_values
        );
    }
    for (level, literal) in [
        (0, "ff0002460047aa"),
        (1, "ff0002460148aa"),
        (2, "ff0002460249aa"),
        (3, "ff000246034aaa"),
        (4, "ff000246044baa"),
        (5, "ff000246054caa"),
        (6, "ff000246064daa"),
    ] {
        let setting = prepare_with(
            codec(ModelProfile::I7RsV2).as_mut(),
            HeadsetSetting::HearingProtection,
            &FieldValue::Number(level),
            &fw213,
        )
        .expect("protection");
        assert_eq!(literal, write_hex(&setting));
        assert_eq!("ff0102fa4642aa", encode_hex(&setting.readback.bytes));
        assert_eq!(keys(&["46"]), setting.readback.response_keys);
        assert_eq!(
            BTreeMap::from([(HeadsetField::HearingProtection, FieldValue::Number(level))]),
            setting.expected_values
        );
    }
}

#[test]
fn i7_prompt_volume_gates_only_mute_on_firmware_304() {
    let fw213 = fw(&[2, 1, 3]);
    let mut codec = codec(ModelProfile::I7RsV2);
    assert!(is_unsupported(&prepare_with(
        codec.as_mut(),
        HeadsetSetting::PromptVolume,
        &FieldValue::Number(0),
        &fw213
    )));
    let muted = prepare_with(
        codec.as_mut(),
        HeadsetSetting::PromptVolume,
        &FieldValue::Number(0),
        &fw(&[3, 0, 4]),
    )
    .expect("muted");
    assert_eq!("ff00022e002faa", write_hex(&muted));
    assert_eq!("ff0102fa2e2aaa", encode_hex(&muted.readback.bytes));
}

#[test]
fn i7_custom_mode_slot_and_curve_readbacks_keep_distinct_values_and_raw_bytes() {
    let mut codec = codec(ModelProfile::I7RsV2);
    let custom = reply_hex(codec.as_mut(), "dd00022a040daa");
    assert_eq!(
        Some(&FieldReading::known(FieldValue::text("custom"), "04")),
        custom.readings.get(&HeadsetField::Eq)
    );
    assert_eq!(None, custom.readings.get(&HeadsetField::EqSlot));
    assert_eq!(keys(&["2a"]), custom.response_keys);

    let slot = reply_hex(codec.as_mut(), "dd00032a050413aa");
    assert_eq!(
        Some(&FieldReading::known(
            FieldValue::text("custom_slot"),
            "0504"
        )),
        slot.readings.get(&HeadsetField::Eq)
    );
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Number(4), "0504")),
        slot.readings.get(&HeadsetField::EqSlot)
    );
    assert_eq!(keys(&["2a"]), slot.response_keys);

    let curve = reply_hex(codec.as_mut(), "dd000b3e86838100010203040506c5aa");
    assert_eq!(
        Some(&FieldReading::known(
            FieldValue::IntegerList(REFERENCE_CURVE.to_vec()),
            "86838100010203040506"
        )),
        curve.readings.get(&HeadsetField::CustomEq)
    );
    assert_eq!(keys(&["3e"]), curve.response_keys);
}

#[test]
fn i7_gain_protection_and_prompt_readbacks_decode_wire_values_and_raw_bytes() {
    let reply = reply_hex(
        codec(ModelProfile::I7RsV2).as_mut(),
        "dd00024502024606022e00a4aa",
    );
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Number(2), "02")),
        reply.readings.get(&HeadsetField::Gain)
    );
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Number(6), "06")),
        reply.readings.get(&HeadsetField::HearingProtection)
    );
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Number(0), "00")),
        reply.readings.get(&HeadsetField::PromptVolume)
    );
    assert_eq!(keys(&["45", "46", "2e"]), reply.response_keys);
}

#[test]
fn bass_boost_uses_slot_readback_and_language_uses_its_own_record() {
    for profile in [ModelProfile::BudsFeelMk2RsV2, ModelProfile::OpenFeelMk2RsV2] {
        for (level, literal) in [
            (0, "ff00032a050031aa"),
            (1, "ff00032a050132aa"),
            (2, "ff00032a050233aa"),
            (3, "ff00032a050334aa"),
            (4, "ff00032a050435aa"),
        ] {
            let mut codec = codec(profile);
            let result = prepare(
                codec.as_mut(),
                HeadsetSetting::BassBoost,
                &FieldValue::Number(level),
            );
            if profile == ModelProfile::OpenFeelMk2RsV2 && level > 2 {
                assert!(is_unsupported(&result), "{profile:?} {level}");
                continue;
            }
            let setting = result.expect("bass boost");
            assert_eq!(literal, write_hex(&setting));
            assert_eq!("ff0102fa2a26aa", encode_hex(&setting.readback.bytes));
            assert_eq!(
                fields(&[HeadsetField::Eq, HeadsetField::EqSlot]),
                setting.readback.expected_fields
            );
            assert_eq!(
                BTreeMap::from([
                    (HeadsetField::Eq, FieldValue::text("custom_slot")),
                    (HeadsetField::EqSlot, FieldValue::Number(level)),
                ]),
                setting.expected_values
            );
        }
        assert!(is_invalid(&prepare(
            codec(profile).as_mut(),
            HeadsetSetting::BassBoost,
            &FieldValue::Number(5)
        )));
    }
    for (language, literal) in [("chinese", "ff0002310032aa"), ("english", "ff0002310133aa")] {
        let setting = prepare(
            codec(ModelProfile::JasmineRsV2).as_mut(),
            HeadsetSetting::PromptLanguage,
            &FieldValue::text(language),
        )
        .expect("language");
        assert_eq!(literal, write_hex(&setting));
        assert_eq!("ff0102fa312daa", encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeMap::from([(HeadsetField::PromptLanguage, FieldValue::text(language))]),
            setting.expected_values
        );
    }
    let mut jasmine = codec(ModelProfile::JasmineRsV2);
    assert_eq!(
        Some(&FieldReading::known(FieldValue::text("english"), "01")),
        reply_hex(jasmine.as_mut(), "dd0002310111aa")
            .readings
            .get(&HeadsetField::PromptLanguage)
    );
    assert_eq!(
        Some(&FieldReading::unknown("02")),
        reply_hex(jasmine.as_mut(), "dd0002310212aa")
            .readings
            .get(&HeadsetField::PromptLanguage)
    );
    assert!(is_invalid(&prepare(
        jasmine.as_mut(),
        HeadsetSetting::PromptLanguage,
        &FieldValue::text("french")
    )));
    assert!(is_unsupported(&prepare(
        codec(ModelProfile::I7RsV2).as_mut(),
        HeadsetSetting::PromptLanguage,
        &FieldValue::text("english")
    )));
}

#[test]
fn each_mk2_starts_with_its_own_panel_queries_at_default_ble_capacity() {
    let buds = codec(ModelProfile::BudsFeelMk2RsV2)
        .init_queries(20, connect_time())
        .expect("buds init");
    let open = codec(ModelProfile::OpenFeelMk2RsV2)
        .init_queries(20, connect_time())
        .expect("open init");
    assert_eq!(
        vec!["ff000dfa0d0c092a0e322b01072f2e3658aa"],
        buds.iter()
            .map(|q| encode_hex(&q.bytes))
            .collect::<Vec<_>>()
    );
    assert_eq!(
        vec!["ff000dfa0d0c2a45460e322b01072f2ea4aa", "ff0102fa3e3aaa"],
        open.iter()
            .map(|q| encode_hex(&q.bytes))
            .collect::<Vec<_>>()
    );
}

#[test]
fn open_feel_mk2_custom_eq_writes_mode_then_curve_and_reads_both_back() {
    let mut codec = codec(ModelProfile::OpenFeelMk2RsV2);
    let setting = prepare(
        codec.as_mut(),
        HeadsetSetting::CustomEq,
        &FieldValue::IntegerList(REFERENCE_CURVE.to_vec()),
    )
    .expect("custom curve");
    assert_eq!(
        vec!["ff00022a042faa", "ff010b3e86838100010203040506e8aa"],
        write_hexes(&setting)
    );
    assert_eq!(
        vec!["ff0202fa2a27aa", "ff0302fa3e3caa"],
        readback_hexes(&setting)
    );
    assert_eq!(
        vec![
            fields(&[HeadsetField::Eq]),
            fields(&[HeadsetField::CustomEq])
        ],
        setting
            .readbacks()
            .iter()
            .map(|query| query.expected_fields.clone())
            .collect::<Vec<_>>()
    );
    assert_eq!(
        BTreeMap::from([
            (HeadsetField::Eq, FieldValue::text("custom")),
            (
                HeadsetField::CustomEq,
                FieldValue::IntegerList(REFERENCE_CURVE.to_vec())
            ),
        ]),
        setting.expected_values
    );
    let mode = reply_hex(codec.as_mut(), "dd00022a040daa");
    assert_eq!(
        Some(&FieldReading::known(FieldValue::text("custom"), "04")),
        mode.readings.get(&HeadsetField::Eq)
    );
    let curve = reply_hex(codec.as_mut(), "dd000b3e86838100010203040506c5aa");
    assert_eq!(
        Some(&FieldReading::known(
            FieldValue::IntegerList(REFERENCE_CURVE.to_vec()),
            "86838100010203040506"
        )),
        curve.readings.get(&HeadsetField::CustomEq)
    );
}

#[test]
fn open_feel_mk2_bass_boost_and_custom_mode_keep_separate_expected_fields() {
    let mut codec = codec(ModelProfile::OpenFeelMk2RsV2);
    prepare(
        codec.as_mut(),
        HeadsetSetting::CustomEq,
        &FieldValue::IntegerList(vec![0; 10]),
    )
    .expect("custom curve");
    let bass = prepare(
        codec.as_mut(),
        HeadsetSetting::BassBoost,
        &FieldValue::Number(1),
    )
    .expect("bass");
    assert_eq!("ff04032a050136aa", write_hex(&bass));
    assert_eq!("ff0502fa2a2aaa", encode_hex(&bass.readback.bytes));
    assert_eq!(
        BTreeMap::from([
            (HeadsetField::Eq, FieldValue::text("custom_slot")),
            (HeadsetField::EqSlot, FieldValue::Number(1)),
        ]),
        bass.expected_values
    );
    assert_eq!(
        fields(&[HeadsetField::Eq, HeadsetField::EqSlot]),
        bass.readback.expected_fields
    );
}

#[test]
fn prompt_volume_writes_follow_each_models_close_option_without_a_firmware_gate() {
    let frames = [
        (0, "ff00022e002faa"),
        (1, "ff00022e0130aa"),
        (2, "ff00022e0231aa"),
        (3, "ff00022e0332aa"),
        (4, "ff00022e0433aa"),
        (5, "ff00022e0534aa"),
    ];
    for (profile, levels) in [
        (
            ModelProfile::OpenFeelMk2RsV2,
            (0..=5).collect::<BTreeSet<i32>>(),
        ),
        (ModelProfile::BudsFeelMk2RsV2, (1..=5).collect()),
        (ModelProfile::Swift2RsV2, (1..=5).collect()),
        (ModelProfile::JasmineRsV2, (1..=5).collect()),
    ] {
        for level in &levels {
            let setting = prepare(
                codec(profile).as_mut(),
                HeadsetSetting::PromptVolume,
                &FieldValue::Number(*level),
            )
            .expect("prompt volume");
            assert_eq!(
                frames[*level as usize].1,
                write_hex(&setting),
                "{profile:?} {level}"
            );
            assert_eq!(
                BTreeMap::from([(HeadsetField::PromptVolume, FieldValue::Number(*level))]),
                setting.expected_values
            );
            assert_eq!(
                fields(&[HeadsetField::PromptVolume]),
                setting.readback.expected_fields
            );
        }
        if !levels.contains(&0) {
            assert!(is_unsupported(&prepare(
                codec(profile).as_mut(),
                HeadsetSetting::PromptVolume,
                &FieldValue::Number(0)
            )));
        }
        for invalid in [-1, 6] {
            assert!(is_invalid(&prepare(
                codec(profile).as_mut(),
                HeadsetSetting::PromptVolume,
                &FieldValue::Number(invalid)
            )));
        }
        assert!(is_invalid(&prepare(
            codec(profile).as_mut(),
            HeadsetSetting::PromptVolume,
            &FieldValue::Toggle(true)
        )));
    }
    let mut open = codec(ModelProfile::OpenFeelMk2RsV2);
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Number(0), "00")),
        reply_hex(open.as_mut(), "dd00022e000daa")
            .readings
            .get(&HeadsetField::PromptVolume)
    );
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Number(5), "05")),
        reply_hex(open.as_mut(), "dd00022e0512aa")
            .readings
            .get(&HeadsetField::PromptVolume)
    );
}

#[test]
fn astro_spatial_mode_and_ear_adaptation_stay_consistent() {
    let astro = ModelProfile::AstroXRsV2;
    let fixed = prepare(
        codec(astro).as_mut(),
        HeadsetSetting::SpatialMode,
        &FieldValue::text("fixed"),
    )
    .expect("spatial mode");
    assert_eq!(
        vec!["ff000238023baa", "ff01023a013daa"],
        write_hexes(&fixed)
    );
    assert_eq!(
        vec!["ff0202fa3835aa", "ff0302fa3a38aa"],
        readback_hexes(&fixed)
    );
    assert_eq!(
        BTreeMap::from([
            (HeadsetField::SpatialMode, FieldValue::text("fixed")),
            (HeadsetField::EarAdaptive, FieldValue::Toggle(true)),
        ]),
        fixed.expected_values
    );
    let disable = prepare(
        codec(astro).as_mut(),
        HeadsetSetting::EarAdaptive,
        &FieldValue::Toggle(false),
    )
    .expect("ear adaptive");
    assert_eq!(
        vec!["ff00023a003baa", "ff010238003aaa"],
        write_hexes(&disable)
    );
    assert_eq!(
        BTreeMap::from([
            (HeadsetField::EarAdaptive, FieldValue::Toggle(false)),
            (HeadsetField::SpatialMode, FieldValue::text("off")),
        ]),
        disable.expected_values
    );
    let already_on = BTreeMap::from([(
        HeadsetField::EarAdaptive,
        FieldReading::known(FieldValue::Toggle(true), "01"),
    )]);
    let single = codec(astro)
        .prepare_setting(
            HeadsetSetting::SpatialMode,
            &FieldValue::text("fixed"),
            None,
            &already_on,
        )
        .expect("single write");
    assert_eq!(vec!["ff000238023baa"], write_hexes(&single));
    let already_off = BTreeMap::from([(
        HeadsetField::SpatialMode,
        FieldReading::known(FieldValue::text("off"), "00"),
    )]);
    let single_disable = codec(astro)
        .prepare_setting(
            HeadsetSetting::EarAdaptive,
            &FieldValue::Toggle(false),
            None,
            &already_off,
        )
        .expect("single disable");
    assert_eq!(vec!["ff00023a003baa"], write_hexes(&single_disable));
    let off = prepare(
        codec(astro).as_mut(),
        HeadsetSetting::SpatialMode,
        &FieldValue::text("off"),
    )
    .expect("spatial off");
    assert_eq!(1, off.write_frames.len());

    let mut codec = codec(astro);
    let game = prepare(
        codec.as_mut(),
        HeadsetSetting::SpatialContent,
        &FieldValue::text("game"),
    )
    .expect("content");
    assert_eq!("ff00023d0341aa", write_hex(&game));
    let reply = reply_hex(codec.as_mut(), "dd00023801023d03023a0197aa");
    assert_eq!(
        Some(&FieldReading::known(
            FieldValue::text("head_tracking"),
            "01"
        )),
        reply.readings.get(&HeadsetField::SpatialMode)
    );
    assert_eq!(
        Some(&FieldReading::known(FieldValue::text("game"), "03")),
        reply.readings.get(&HeadsetField::SpatialContent)
    );
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Toggle(true), "01")),
        reply.readings.get(&HeadsetField::EarAdaptive)
    );
    assert!(is_invalid(&prepare(
        codec.as_mut(),
        HeadsetSetting::SpatialMode,
        &FieldValue::text("surround")
    )));
    assert!(is_unsupported(&prepare(
        codec.as_mut(),
        HeadsetSetting::SpatialAudio,
        &FieldValue::Toggle(true)
    )));
}

#[test]
fn independent_mic_writes_use_selectors_and_readbacks_use_two_flags() {
    let profile = ModelProfile::SpeakFeel2RsV2;
    let activate = prepare(
        codec(profile).as_mut(),
        HeadsetSetting::IndependentMicActivation,
        &FieldValue::Toggle(true),
    )
    .expect("activation");
    assert_eq!("ff00034900014caa", write_hex(&activate));
    assert_eq!(
        fields(&[HeadsetField::IndependentMicActivation]),
        activate.readback.expected_fields
    );
    let mut codec = codec(profile);
    let mode = prepare(
        codec.as_mut(),
        HeadsetSetting::IndependentMicMode,
        &FieldValue::Toggle(true),
    )
    .expect("mode");
    assert_eq!("ff00034901014daa", write_hex(&mode));
    assert_eq!(
        fields(&[HeadsetField::IndependentMicMode]),
        mode.readback.expected_fields
    );
    let active = reply_hex(codec.as_mut(), "dd00034901012baa");
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Toggle(true), "0101")),
        active.readings.get(&HeadsetField::IndependentMicMode)
    );
    let inactive = reply_hex(codec.as_mut(), "dd00034900012aaa");
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Toggle(false), "0001")),
        inactive.readings.get(&HeadsetField::IndependentMicMode)
    );
    assert!(!reply_hex(codec.as_mut(), "dd00034902012caa").accepted);
    assert!(!reply_hex(codec.as_mut(), "dd000549123456783faa").accepted);
    assert!(is_unsupported(&prepare(
        create_protocol_codec(ModelProfile::I7RsV2).as_mut(),
        HeadsetSetting::IndependentMicMode,
        &FieldValue::Toggle(true)
    )));
}

#[test]
fn new_rose_link_labels_keep_the_same_equalizer_wire_values() {
    let openfeel = ModelProfile::OpenFeelLRsV2;
    assert_eq!(
        vec![("pop", "BASS"), ("hifi", "BALANCE")],
        openfeel.spec().equalizer_preset_labels.to_vec()
    );
    let pop = prepare(
        codec(openfeel).as_mut(),
        HeadsetSetting::Eq,
        &FieldValue::text("pop"),
    )
    .expect("pop");
    assert_eq!("ff00022a012caa", write_hex(&pop));
}

#[test]
fn tap_toggle_and_assignment_query_their_own_fields() {
    let profile = ModelProfile::AstroXRsV2;
    let switch = prepare(
        codec(profile).as_mut(),
        HeadsetSetting::TapEnabled,
        &FieldValue::Toggle(true),
    )
    .expect("tap switch");
    assert_eq!("ff00023b013daa", write_hex(&switch));
    assert_eq!(vec!["ff0102fa3b37aa"], readback_hexes(&switch));
    assert_eq!(
        BTreeMap::from([(HeadsetField::TapEnabled, FieldValue::Toggle(true))]),
        switch.expected_values
    );
    let mut codec = codec(profile);
    assert!(is_invalid(&prepare(
        codec.as_mut(),
        HeadsetSetting::TapBinding,
        &FieldValue::touch_assignment("right", "single", "voice_assistant")
    )));
    assert!(is_unsupported(&prepare(
        codec.as_mut(),
        HeadsetSetting::TapBinding,
        &FieldValue::touch_assignment("right", "triple", "noise_cycle")
    )));
    let binding = FieldValue::touch_assignment("right", "triple", "voice_assistant");
    let write = prepare(codec.as_mut(), HeadsetSetting::TapBinding, &binding).expect("tap binding");
    assert_eq!("ff00033c130758aa", write_hex(&write));
    assert_eq!(vec!["ff0102fa3c38aa"], readback_hexes(&write));
    assert_eq!(
        BTreeMap::from([(HeadsetField::TapKeys, binding)]),
        write.expected_values
    );
    assert!(
        !ModelProfile::I7RsV2
            .capabilities(None, None)
            .writable_settings
            .contains(&HeadsetSetting::TapEnabled)
    );
    assert!(is_unsupported(&prepare(
        create_protocol_codec(ModelProfile::I7RsV2).as_mut(),
        HeadsetSetting::TapBinding,
        &FieldValue::touch_assignment("right", "triple", "voice_assistant")
    )));
}

#[test]
fn tap_notifications_require_all_four_slots_and_preserve_unknown_actions() {
    let mut codec = codec(ModelProfile::AstroXRsV2);
    let enabled = reply_hex(codec.as_mut(), "dd00023b011baa");
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Toggle(true), "01")),
        enabled.readings.get(&HeadsetField::TapEnabled)
    );
    let complete = reply_hex(codec.as_mut(), "dd00093c13070201030212045aaa");
    let Some(FieldReading::Known {
        value: FieldValue::TouchKeys(bindings),
        ..
    }) = complete.readings.get(&HeadsetField::TapKeys)
    else {
        panic!("expected tap keys");
    };
    assert_eq!(
        vec![
            Some("play_pause"),
            Some("previous_track"),
            Some("volume_up"),
            Some("voice_assistant")
        ],
        bindings
            .iter()
            .map(|binding| binding.action.as_deref())
            .collect::<Vec<_>>()
    );
    assert!(!complete.readings.contains_key(&HeadsetField::TouchKeys));
    let incomplete = reply_hex(codec.as_mut(), "dd00073c0201030212043eaa");
    assert!(!incomplete.readings.contains_key(&HeadsetField::TapKeys));
    let unexpected = reply_hex(codec.as_mut(), "dd00093c020803021204130761aa");
    let Some(FieldReading::Known {
        value: FieldValue::TouchKeys(unknown),
        ..
    }) = unexpected.readings.get(&HeadsetField::TapKeys)
    else {
        panic!("expected tap keys");
    };
    assert_eq!(
        &TouchBinding {
            side: "left".to_string(),
            gesture: "double".to_string(),
            action: None,
            raw_action: 8,
        },
        &unknown[0]
    );
}

#[test]
fn clock_sync_uses_the_supplied_local_calendar_bytes_and_only_the_model_that_requests_it() {
    let now = LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time");
    let commands = codec(ModelProfile::AstroXRsV2)
        .init_queries(512, now)
        .expect("init queries");
    assert_eq!(2, commands.len());
    assert_eq!("ff010835141a0b14112609caaa", encode_hex(&commands[1].bytes));
    assert_eq!("35", commands[1].key);
    assert!(commands[1].response_keys.is_empty());
    assert!(commands[1].expected_fields.is_empty());
    assert_eq!(
        1,
        codec(ModelProfile::Ky01RsV2)
            .init_queries(512, now)
            .expect("ky01 init")
            .len()
    );
}

#[test]
fn clock_sync_carries_the_time_of_each_connection_without_any_hidden_state() {
    let first = LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time");
    let second = LocalDateTime::new(2027, 1, 2, 3, 4, 5).expect("valid time");
    let mut astro = codec(ModelProfile::AstroXRsV2);
    let queries = astro.init_queries(512, first).expect("first connection");
    assert_eq!(0, queries[0].bytes[1]);
    assert_eq!("ff010835141a0b14112609caaa", encode_hex(&queries[1].bytes));
    let again = astro.init_queries(512, second).expect("second connection");
    assert_eq!("ff030835141b01020304057daa", encode_hex(&again[1].bytes));
}

#[test]
fn local_time_accepts_only_real_calendar_times() {
    assert!(LocalDateTime::new(2024, 2, 29, 23, 59, 59).is_ok());
    assert!(LocalDateTime::new(2000, 2, 29, 0, 0, 0).is_ok());
    for (year, month, day, hour, minute, second) in [
        (2023, 2, 29, 0, 0, 0),
        (2100, 2, 29, 0, 0, 0),
        (2026, 13, 1, 0, 0, 0),
        (2026, 0, 1, 0, 0, 0),
        (2026, 4, 31, 0, 0, 0),
        (2026, 1, 0, 0, 0, 0),
        (2026, 1, 1, 24, 0, 0),
        (2026, 1, 1, 0, 60, 0),
        (2026, 1, 1, 0, 0, 60),
        (10000, 1, 1, 0, 0, 0),
    ] {
        assert!(
            is_invalid(&LocalDateTime::new(year, month, day, hour, minute, second)),
            "{year}-{month}-{day} {hour}:{minute}:{second}"
        );
    }
    let time = LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time");
    assert_eq!(
        (2026, 11, 20, 17, 38, 9),
        (
            time.year(),
            time.month(),
            time.day(),
            time.hour(),
            time.minute(),
            time.second()
        )
    );
}

#[test]
fn custom_curve_read_follows_the_firmware_gate_and_confirmations_use_both_tags() {
    let mut ceramic = codec(ModelProfile::CeramicsMk2RsV2);
    assert_eq!(1, ceramic.init_queries(512, connect_time()).expect("init").len());
    assert!(
        ceramic
            .firmware_init_queries(Some(&fw(&[1, 5, 3])))
            .is_empty()
    );
    assert!(ceramic.firmware_init_queries(None).is_empty());
    let unlocked = ceramic.firmware_init_queries(Some(&fw(&[1, 5, 4])));
    assert_eq!(1, unlocked.len());
    assert_eq!("ff0102fa3e3aaa", encode_hex(&unlocked[0].bytes));
    let curve = prepare_with(
        ceramic.as_mut(),
        HeadsetSetting::CustomEq,
        &FieldValue::IntegerList(vec![0; 10]),
        &fw(&[1, 5, 4]),
    )
    .expect("curve");
    assert_eq!(
        vec![
            fields(&[HeadsetField::Eq]),
            fields(&[HeadsetField::CustomEq])
        ],
        curve
            .readbacks()
            .iter()
            .map(|query| query.expected_fields.clone())
            .collect::<Vec<_>>()
    );
    assert!(is_unsupported(&prepare_with(
        codec(ModelProfile::CeramicsMk2RsV2).as_mut(),
        HeadsetSetting::CustomEq,
        &FieldValue::IntegerList(vec![0; 10]),
        &fw(&[1, 5, 3]),
    )));
    let mut newer_branch = codec(ModelProfile::CeramicsMk25736RsV2);
    assert_eq!(
        "fa3e",
        newer_branch
            .init_queries(512, connect_time())
            .expect("init")
            .last()
            .expect("curve query")
            .key
    );
    assert!(
        newer_branch
            .firmware_init_queries(Some(&fw(&[1, 5, 4])))
            .is_empty()
    );
}

#[test]
fn first_angel_preset_bank_requires_confirmed_game_mode() {
    let mut codec = codec(ModelProfile::FirstAngelRsV2);
    let game_1 = FieldValue::text("game_1");
    for state in [Some(false), None] {
        let current = state.map(game_mode_state).unwrap_or_default();
        assert!(
            is_unsupported(&codec.prepare_setting(HeadsetSetting::Eq, &game_1, None, &current)),
            "{state:?}"
        );
    }
    let allowed = codec
        .prepare_setting(HeadsetSetting::Eq, &game_1, None, &game_mode_state(true))
        .expect("game preset");
    assert_eq!("ff00022aa0cbaa", write_hex(&allowed));
    let reply = reply_hex(codec.as_mut(), "dd00022aa0ccaa");
    assert_eq!(
        &FieldValue::text("game_1"),
        match reply.readings.get(&HeadsetField::Eq) {
            Some(FieldReading::Known { value, .. }) => value,
            other => panic!("expected Known EQ, got {other:?}"),
        }
    );
}

#[test]
fn aptx_and_deep_anc_are_offered_only_on_models_whose_panel_supports_them() {
    let mut speak = codec(ModelProfile::SpeakFreeRsV2);
    let audio = prepare(
        speak.as_mut(),
        HeadsetSetting::AudioMode,
        &FieldValue::text("aptx_adaptive"),
    )
    .expect("aptx adaptive");
    assert_eq!("ff00022b0632aa", write_hex(&audio));
    let reply = reply_hex(speak.as_mut(), "dd00022b0633aa");
    assert_eq!(
        Some(&FieldReading::known(
            FieldValue::text("aptx_adaptive"),
            "06"
        )),
        reply.readings.get(&HeadsetField::AudioMode)
    );
    let mut furina = codec(ModelProfile::FurinaRsV2);
    let deep = prepare(
        furina.as_mut(),
        HeadsetSetting::NoiseMode,
        &FieldValue::text("deep"),
    )
    .expect("deep");
    assert_eq!("ff0002090610aa", write_hex(&deep));
    assert!(is_unsupported(&prepare(
        furina.as_mut(),
        HeadsetSetting::AudioMode,
        &FieldValue::text("aptx_hd")
    )));
}

#[test]
fn every_model_writes_and_reads_back_game_mode_and_refreshes_the_equalizer_only_where_needed() {
    for &profile in ModelProfile::ALL {
        if profile.family() != openrose_headset::ProtocolFamily::RsCommonV2 {
            continue;
        }
        let mut codec = codec(profile);
        let setting = prepare(
            codec.as_mut(),
            HeadsetSetting::GameMode,
            &FieldValue::Toggle(false),
        )
        .expect("game mode");
        assert_eq!(&[0x0e, 0x00], &setting.write_frames[0][3..5], "{profile:?}");
        assert_eq!(&[0xfa, 0x0e], &setting.readback.bytes[3..5], "{profile:?}");
        let refresh = matches!(
            profile,
            ModelProfile::FirstAngelRsV2 | ModelProfile::MokaRsV2
        );
        assert_eq!(
            if refresh { 1 } else { 0 },
            setting.additional_readbacks.len(),
            "{profile:?}"
        );
        if refresh {
            assert_eq!(
                &[0xfa, 0x2a],
                &setting.additional_readbacks[0].bytes[3..5],
                "{profile:?}"
            );
            assert_eq!(
                fields(&[HeadsetField::Eq]),
                setting.additional_readbacks[0].expected_fields
            );
        }
    }
}
