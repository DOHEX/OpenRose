use openrose_headset::{
    BatteryLayout, FieldReading, FieldValue, HeadsetCapabilities, HeadsetField, HeadsetSetting,
    ModelIdentification, ModelProfile, ModelProtocol, ProtocolCodec, ProtocolError, ProtocolFamily,
    ProtocolSetting, RsCommonInit, RsCommonProtocol, TouchBinding, TouchSlot,
    create_protocol_codec, decode_hex, encode_hex,
};
use std::collections::{BTreeMap, BTreeSet};

const SPP_UUID: &str = "0cf12d31-fac3-4553-bd80-d6832e7b3931";
const INFO_FRAME: &str = "ff000ffa010708090c0d0e122a2b2c2d2e33aa";
const SYNTHETIC_REPLIES: [&str; 13] = [
    "ff00110100010002000300000000000500060000aa",
    "ff00020700aa",
    "ff00020800aa",
    "ff00020904aa",
    "ff00040c32465aaa",
    "ff00040d010203aa",
    "ff00020e00aa",
    "ff00022a00aa",
    "ff00022b00aa",
    "ff00022c01aa",
    "ff00022d01aa",
    "ff00022e01aa",
    "ff00023300aa",
];

fn manufacturer_data(entries: &[(u16, &str)]) -> BTreeMap<u16, Vec<u8>> {
    entries
        .iter()
        .map(|&(company, payload)| (company, hex_bytes(payload)))
        .collect()
}

fn hex_bytes(hex: &str) -> Vec<u8> {
    decode_hex(hex).expect("valid test hex")
}

fn rs_common_profiles() -> Vec<ModelProfile> {
    ModelProfile::ALL
        .iter()
        .copied()
        .filter(|profile| profile.family() == ProtocolFamily::RsCommon)
        .collect()
}

fn rs_common_protocol(profile: ModelProfile) -> RsCommonProtocol {
    match profile.spec().protocol {
        ModelProtocol::RsCommon(protocol) => protocol,
        other => panic!("{profile:?} is not RS Common: {other:?}"),
    }
}

fn prepare(
    codec: &mut dyn ProtocolCodec,
    setting: HeadsetSetting,
    value: FieldValue,
) -> Result<ProtocolSetting, ProtocolError> {
    codec.prepare_setting(setting, &value, None, &BTreeMap::new())
}

fn write_hex(codec: &mut dyn ProtocolCodec, setting: HeadsetSetting, value: FieldValue) -> String {
    let prepared = prepare(codec, setting, value).expect("prepared setting");
    assert_eq!(1, prepared.write_frames.len());
    encode_hex(&prepared.write_frames[0])
}

fn init_frames(profile: ModelProfile) -> Vec<(String, u64)> {
    create_protocol_codec(profile)
        .init_queries(20)
        .expect("init queries")
        .iter()
        .map(|query| (encode_hex(&query.bytes), query.delay_before_millis))
        .collect()
}

fn single_reply(codec: &mut dyn ProtocolCodec, hex: &str) -> openrose_headset::ProtocolReply {
    let mut replies = codec.feed_notification(&hex_bytes(hex));
    assert_eq!(1, replies.len(), "{hex}");
    replies.pop().expect("single reply")
}

fn known(reply: &openrose_headset::ProtocolReply, field: HeadsetField) -> &FieldValue {
    match reply.readings.get(&field) {
        Some(FieldReading::Known { value, .. }) => value,
        other => panic!("expected Known reading for {field:?}, got {other:?}"),
    }
}

fn is_unknown(reply: &openrose_headset::ProtocolReply, field: HeadsetField) -> bool {
    matches!(
        reply.readings.get(&field),
        Some(FieldReading::Unknown { .. })
    )
}

fn invalid_argument<T: std::fmt::Debug>(result: Result<T, ProtocolError>) -> bool {
    matches!(result, Err(ProtocolError::InvalidArgument(_)))
}

fn unsupported<T: std::fmt::Debug>(result: Result<T, ProtocolError>) -> bool {
    matches!(result, Err(ProtocolError::Unsupported(_)))
}

#[test]
fn all_eleven_rs_common_models_are_registered_and_identified_by_prefix_and_name() {
    assert_eq!(Ok(()), ModelIdentification::validate_catalog());
    let profiles = rs_common_profiles();
    assert_eq!(11, profiles.len());

    let expected_prefixes = [
        (
            ModelProfile::BudsFreeIRsCommon,
            "12cc3e4c",
            "BUDSFREE_I_RS_COMMON",
        ),
        (
            ModelProfile::CambrianRsCommon,
            "0b0c0a0c",
            "CAMBRIAN_RS_COMMON",
        ),
        (
            ModelProfile::CambrianSRsCommon,
            "0b0c2b46",
            "CAMBRIAN_S_RS_COMMON",
        ),
        (
            ModelProfile::OpenFeelLdacRsCommon,
            "0b0a0a0b",
            "OPENFEEL_LDAC_RS_COMMON",
        ),
        (
            ModelProfile::OpenFeelLhdcRsCommon,
            "0b0a0a0c",
            "OPENFEEL_LHDC_RS_COMMON",
        ),
        (
            ModelProfile::OpenFeelDualRsCommon,
            "0b0a0a0d",
            "OPENFEEL_DUAL_RS_COMMON",
        ),
        (
            ModelProfile::OpenFunRsCommon,
            "0b0a0a0e",
            "OPENFUN_RS_COMMON",
        ),
        (
            ModelProfile::OpenFunTouchRsCommon,
            "0b0a0a10",
            "OPENFUN_TOUCH_RS_COMMON",
        ),
        (
            ModelProfile::OpenLinkRsCommon,
            "0b0a5b9d",
            "OPENLINK_RS_COMMON",
        ),
        (
            ModelProfile::SportFeelRsCommon,
            "0b0a0a0f",
            "SPORTFEEL_RS_COMMON",
        ),
        (ModelProfile::SwiftRsCommon, "0b0b0a0b", "SWIFT_RS_COMMON"),
    ];
    assert_eq!(
        profiles.iter().copied().collect::<BTreeSet<_>>(),
        expected_prefixes
            .iter()
            .map(|&(profile, _, _)| profile)
            .collect::<BTreeSet<_>>()
    );

    for (profile, prefix, id) in expected_prefixes {
        assert_eq!(id, profile.id());
        assert_eq!(Some(profile), ModelProfile::from_id(id));
        assert_eq!(
            format!("\"{id}\""),
            serde_json::to_string(&profile).expect("serialize profile")
        );
        assert_eq!(&[prefix], profile.advertisement_prefixes());
        let bytes = hex_bytes(&format!("{prefix}99"));
        let company = u16::from_le_bytes([bytes[0], bytes[1]]);
        let data = BTreeMap::from([(company, bytes[2..].to_vec())]);
        assert_eq!(Some(profile), ModelIdentification::identify(&data));
    }

    for (name, expected) in [
        ("BudsFree i", Some(ModelProfile::BudsFreeIRsCommon)),
        ("ROSE BudsFree-i", Some(ModelProfile::BudsFreeIRsCommon)),
        ("BudsFree", None),
        ("Cambrian", Some(ModelProfile::CambrianRsCommon)),
        ("ROSE Cambrian", Some(ModelProfile::CambrianRsCommon)),
        ("ROSE Cambrian S", Some(ModelProfile::CambrianSRsCommon)),
        ("Cambrian-S", Some(ModelProfile::CambrianSRsCommon)),
        ("OpenLink", Some(ModelProfile::OpenLinkRsCommon)),
        ("ROSE OpenLink", Some(ModelProfile::OpenLinkRsCommon)),
        ("SportFeel", None),
        ("SportFree", Some(ModelProfile::SportFeelRsCommon)),
        ("SWIFT", Some(ModelProfile::SwiftRsCommon)),
        ("ROSE Swift", Some(ModelProfile::SwiftRsCommon)),
        ("ROSE Swift 20", None),
        ("OpenFeel", None),
        ("OpenFree", None),
        ("ROSE OpenFeel LDAC", None),
        ("OpenFeel LHDC", None),
        ("OpenFun", None),
    ] {
        assert_eq!(expected, ModelProfile::match_name(Some(name)), "{name}");
    }

    let pattern = ModelProfile::name_pattern();
    for brand in [
        "budsfree",
        "cambrian",
        "openfeel",
        "openfree",
        "openfun",
        "openlink",
        "sportfeel",
        "sportfree",
        "swift",
    ] {
        let letters: Vec<String> = brand.chars().map(String::from).collect();
        assert!(
            pattern.contains(&letters.join("[\\s_-]*")),
            "{brand} missing from wake name pattern"
        );
    }
}

#[test]
fn advertised_classic_address_extracts_documented_offsets_and_rejects_invalid_values() {
    let buds_hex = ModelIdentification::normalized_hex(&manufacturer_data(&[(
        0xcc12,
        "3e4c00000000a1b2c3d4e5f6",
    )]));
    assert_eq!("12cc3e4c00000000a1b2c3d4e5f6", buds_hex);
    assert_eq!(
        Some("A1:B2:C3:D4:E5:F6".to_string()),
        ModelIdentification::classic_address(&buds_hex, Some(ModelProfile::BudsFreeIRsCommon))
    );
    assert_eq!(
        None,
        ModelIdentification::classic_address(
            "12cc3e4c00000000a1b2c3d4e5",
            Some(ModelProfile::BudsFreeIRsCommon)
        )
    );

    let cambrian_hex =
        ModelIdentification::normalized_hex(&manufacturer_data(&[(0x0c0b, "0a0c00112233445566")]));
    assert_eq!(
        Some("11:22:33:44:55:66".to_string()),
        ModelIdentification::classic_address(&cambrian_hex, Some(ModelProfile::CambrianRsCommon))
    );

    for (profile, prefix) in [
        (ModelProfile::CambrianRsCommon, "0b0c0a0c"),
        (ModelProfile::CambrianSRsCommon, "0b0c2b46"),
        (ModelProfile::OpenFeelLdacRsCommon, "0b0a0a0b"),
        (ModelProfile::OpenFeelLhdcRsCommon, "0b0a0a0c"),
        (ModelProfile::OpenFeelDualRsCommon, "0b0a0a0d"),
        (ModelProfile::OpenFunRsCommon, "0b0a0a0e"),
        (ModelProfile::OpenFunTouchRsCommon, "0b0a0a10"),
        (ModelProfile::OpenLinkRsCommon, "0b0a5b9d"),
        (ModelProfile::SportFeelRsCommon, "0b0a0a0f"),
        (ModelProfile::SwiftRsCommon, "0b0b0a0b"),
    ] {
        assert_eq!(
            Some("11:22:33:44:55:66".to_string()),
            ModelIdentification::classic_address(&format!("{prefix}00112233445566"), Some(profile)),
            "{profile:?}"
        );
        assert_eq!(
            Some("AA:BB:CC:DD:EE:0F".to_string()),
            ModelIdentification::classic_address(&format!("{prefix}00aabbccddee0f"), Some(profile)),
            "{profile:?}"
        );
        assert_eq!(
            None,
            ModelIdentification::classic_address(&format!("{prefix}00000000000000"), Some(profile)),
            "{profile:?}"
        );
        assert_eq!(
            None,
            ModelIdentification::classic_address(&format!("{prefix}00ffffffffffff"), Some(profile)),
            "{profile:?}"
        );
        assert_eq!(
            None,
            ModelIdentification::classic_address(&format!("{prefix}0011223344556g"), Some(profile)),
            "{profile:?}"
        );
        assert_eq!(
            None,
            ModelIdentification::classic_address(&format!("{prefix}0011223344"), Some(profile)),
            "{profile:?}"
        );
    }
    assert_eq!(
        None,
        ModelIdentification::classic_address("0b0b0a0b00112233445566", None)
    );
}

#[test]
fn model_display_names_version_names_preset_labels_and_touch_slot_labels_come_from_spec() {
    assert_eq!(
        Some("LDAC"),
        ModelProfile::OpenFeelLdacRsCommon.version_name()
    );
    assert_eq!(
        Some("LHDC"),
        ModelProfile::OpenFeelLhdcRsCommon.version_name()
    );
    assert_eq!(
        Some("LDAC + LHDC"),
        ModelProfile::OpenFeelDualRsCommon.version_name()
    );
    assert_eq!(
        Some("无触控开关"),
        ModelProfile::OpenFunRsCommon.version_name()
    );
    assert_eq!(
        Some("有触控开关"),
        ModelProfile::OpenFunTouchRsCommon.version_name()
    );
    for profile in [
        ModelProfile::BudsFreeIRsCommon,
        ModelProfile::CambrianRsCommon,
        ModelProfile::CambrianSRsCommon,
        ModelProfile::OpenLinkRsCommon,
        ModelProfile::SportFeelRsCommon,
        ModelProfile::SwiftRsCommon,
    ] {
        assert_eq!(None, profile.version_name(), "{profile:?}");
    }

    for (profile, display, short) in [
        (ModelProfile::BudsFreeIRsCommon, "BudsFree i", "BudsFree i"),
        (ModelProfile::CambrianRsCommon, "Cambrian", "Cambrian"),
        (ModelProfile::CambrianSRsCommon, "Cambrian S", "Cambrian S"),
        (ModelProfile::OpenFeelLdacRsCommon, "OpenFeel", "OpenFeel"),
        (ModelProfile::OpenFeelLhdcRsCommon, "OpenFeel", "OpenFeel"),
        (ModelProfile::OpenFeelDualRsCommon, "OpenFeel", "OpenFeel"),
        (ModelProfile::OpenFunRsCommon, "OpenFun", "OpenFun"),
        (ModelProfile::OpenFunTouchRsCommon, "OpenFun", "OpenFun"),
        (ModelProfile::OpenLinkRsCommon, "OpenLink", "OpenLink"),
        (ModelProfile::SportFeelRsCommon, "SportFeel", "SportFeel"),
        (ModelProfile::SwiftRsCommon, "SWIFT", "SWIFT"),
    ] {
        assert_eq!(display, profile.display_name(), "{profile:?}");
        assert_eq!(short, profile.short_label(), "{profile:?}");
    }

    for profile in [
        ModelProfile::OpenFeelLdacRsCommon,
        ModelProfile::OpenFeelLhdcRsCommon,
        ModelProfile::OpenFeelDualRsCommon,
    ] {
        assert_eq!(Some("BASS"), profile.equalizer_preset_label("pop"));
        assert_eq!(Some("BALANCE"), profile.equalizer_preset_label("hifi"));
        assert_eq!(None, profile.equalizer_preset_label("rock"));
    }
    for profile in [
        ModelProfile::BudsFreeIRsCommon,
        ModelProfile::CambrianRsCommon,
        ModelProfile::CambrianSRsCommon,
    ] {
        assert_eq!(Some("POP"), profile.equalizer_preset_label("pop"));
        assert_eq!(Some("HIFI"), profile.equalizer_preset_label("hifi"));
        assert_eq!(Some("ROCK"), profile.equalizer_preset_label("rock"));
    }
    for profile in [
        ModelProfile::OpenFunRsCommon,
        ModelProfile::OpenFunTouchRsCommon,
        ModelProfile::OpenLinkRsCommon,
        ModelProfile::SportFeelRsCommon,
        ModelProfile::SwiftRsCommon,
    ] {
        assert_eq!(None, profile.equalizer_preset_label("pop"), "{profile:?}");
    }

    for profile in rs_common_profiles() {
        let touch = profile
            .capabilities(None, None)
            .touch_control
            .expect("RS Common touch control");
        let cambrian = matches!(
            profile,
            ModelProfile::CambrianRsCommon | ModelProfile::CambrianSRsCommon
        );
        if !cambrian {
            assert!(touch.slot_labels.is_empty(), "{profile:?}");
            continue;
        }
        assert_eq!(6, touch.slot_labels.len());
        for gesture in ["single", "double", "triple"] {
            assert_eq!(
                Some("音量−"),
                touch
                    .slot_labels
                    .get(&TouchSlot::new("left", gesture))
                    .map(String::as_str)
            );
            assert_eq!(
                Some("音量＋"),
                touch
                    .slot_labels
                    .get(&TouchSlot::new("right", gesture))
                    .map(String::as_str)
            );
        }
    }
}

#[test]
fn rs_common_models_derive_documented_battery_layouts_transports_and_capabilities() {
    for profile in [
        ModelProfile::CambrianRsCommon,
        ModelProfile::CambrianSRsCommon,
    ] {
        assert_eq!(BatteryLayout::SingleUnit, profile.battery_layout());
        assert_eq!(Some(SPP_UUID), profile.spp_control_uuid());
        let caps = profile.capabilities(None, None);
        assert!(caps.readable_fields.contains(&HeadsetField::CaseBattery));
        assert!(!caps.readable_fields.contains(&HeadsetField::LeftBattery));
        assert!(!caps.readable_fields.contains(&HeadsetField::RightBattery));
        assert!(
            caps.writable_settings
                .contains(&HeadsetSetting::TransparencyLevel)
        );
        assert!(
            caps.writable_settings
                .contains(&HeadsetSetting::PromptVolume)
        );
        assert_eq!((1..=5).collect::<BTreeSet<_>>(), caps.prompt_volume_levels);
        let noise = caps.noise_control.expect("cambrian noise control");
        assert_eq!(
            BTreeSet::from([1, 2, 3]),
            noise.transparency_level.expect("transparency level").levels
        );
    }

    assert_eq!(
        BatteryLayout::SingleUnit,
        ModelProfile::SwiftRsCommon.battery_layout()
    );
    assert_eq!(None, ModelProfile::SwiftRsCommon.spp_control_uuid());
    let swift_caps = ModelProfile::SwiftRsCommon.capabilities(None, None);
    assert_eq!(
        BTreeSet::from([
            HeadsetField::Firmware,
            HeadsetField::GameMode,
            HeadsetField::CaseBattery,
            HeadsetField::TouchKeys,
            HeadsetField::TouchEnabled,
        ]),
        swift_caps.readable_fields
    );
    assert_eq!(
        BTreeSet::from([HeadsetSetting::GameMode, HeadsetSetting::TouchBinding]),
        swift_caps.writable_settings
    );

    assert_eq!(
        BatteryLayout::EarbudsOnly,
        ModelProfile::OpenLinkRsCommon.battery_layout()
    );
    assert_eq!(
        Some(SPP_UUID),
        ModelProfile::OpenLinkRsCommon.spp_control_uuid()
    );
    let open_link_caps = ModelProfile::OpenLinkRsCommon.capabilities(None, None);
    assert!(
        !open_link_caps
            .readable_fields
            .contains(&HeadsetField::CaseBattery)
    );
    assert!(
        open_link_caps
            .readable_fields
            .contains(&HeadsetField::LeftBattery)
    );
    assert!(
        !open_link_caps
            .writable_settings
            .contains(&HeadsetSetting::NoiseMode)
    );
    assert!(
        open_link_caps
            .writable_settings
            .contains(&HeadsetSetting::TouchEnabled)
    );

    let open_fun_caps = ModelProfile::OpenFunRsCommon.capabilities(None, None);
    assert_eq!(
        BatteryLayout::EarbudsOnly,
        ModelProfile::OpenFunRsCommon.battery_layout()
    );
    assert!(
        open_fun_caps
            .readable_fields
            .contains(&HeadsetField::TouchEnabled)
    );
    assert!(
        !open_fun_caps
            .writable_settings
            .contains(&HeadsetSetting::TouchEnabled)
    );
    assert!(
        ModelProfile::OpenFunTouchRsCommon
            .capabilities(None, None)
            .writable_settings
            .contains(&HeadsetSetting::TouchEnabled)
    );

    let sport_caps = ModelProfile::SportFeelRsCommon.capabilities(None, None);
    assert_eq!(
        BatteryLayout::EarbudsOnly,
        ModelProfile::SportFeelRsCommon.battery_layout()
    );
    assert!(
        sport_caps
            .readable_fields
            .contains(&HeadsetField::SpatialAudio)
    );
    assert!(
        sport_caps
            .writable_settings
            .contains(&HeadsetSetting::SpatialAudio)
    );

    for (profile, modes, none_action) in [
        (
            ModelProfile::OpenFeelLdacRsCommon,
            BTreeSet::from(["aac_sbc", "ldac", "lc3"]),
            false,
        ),
        (
            ModelProfile::OpenFeelLhdcRsCommon,
            BTreeSet::from(["aac_sbc", "lhdc", "lc3"]),
            false,
        ),
        (
            ModelProfile::OpenFeelDualRsCommon,
            BTreeSet::from(["aac_sbc", "ldac", "lhdc", "lc3"]),
            true,
        ),
    ] {
        assert_eq!(BatteryLayout::EarbudsAndCase, profile.battery_layout());
        assert_eq!(None, profile.spp_control_uuid());
        let caps = profile.capabilities(None, None);
        let audio = caps.audio_control.expect("open feel audio control");
        assert!(audio.multipoint_coupled);
        assert_eq!(
            modes
                .into_iter()
                .map(str::to_string)
                .collect::<BTreeSet<_>>(),
            audio.modes,
            "{profile:?}"
        );
        assert_eq!(
            none_action,
            caps.touch_control
                .expect("open feel touch")
                .actions
                .contains("none"),
            "{profile:?}"
        );
        assert_eq!(
            BTreeSet::from(["pop".to_string(), "hifi".to_string()]),
            caps.equalizer.expect("open feel equalizer").presets
        );
    }

    let buds = ModelProfile::BudsFreeIRsCommon.capabilities(None, None);
    assert_eq!(
        BatteryLayout::EarbudsOnly,
        ModelProfile::BudsFreeIRsCommon.battery_layout()
    );
    assert_eq!(None, ModelProfile::BudsFreeIRsCommon.spp_control_uuid());
    let noise = buds.noise_control.expect("buds free noise control");
    assert_eq!(
        ["anc", "normal", "transparency"]
            .into_iter()
            .map(str::to_string)
            .collect::<BTreeSet<_>>(),
        noise.modes
    );
    assert!(noise.anc_level.is_none() && noise.transparency_level.is_none());
    let buds_actions: BTreeSet<String> = rs_common_protocol(ModelProfile::BudsFreeIRsCommon)
        .touch_actions
        .iter()
        .map(|&(name, _)| name.to_string())
        .collect();
    assert_eq!(
        buds_actions,
        buds.touch_control.expect("buds free touch").actions
    );
}

#[test]
fn every_model_touch_action_has_a_single_digit_code_and_every_slot_a_wire_gesture() {
    for profile in rs_common_profiles() {
        let protocol = rs_common_protocol(profile);
        assert!(protocol.touch_actions.iter().all(|&(_, code)| code <= 9));
        let names: BTreeSet<&str> = protocol.touch_actions.iter().map(|&(n, _)| n).collect();
        assert_eq!(protocol.touch_actions.len(), names.len(), "{profile:?}");
        let touch = profile
            .capabilities(None, None)
            .touch_control
            .expect("touch control");
        assert!(
            touch.actions.iter().all(|a| names.contains(a.as_str())),
            "{profile:?}"
        );
        let expected_slots: BTreeSet<TouchSlot> = ["left", "right"]
            .into_iter()
            .flat_map(|side| {
                ["single", "double", "triple"]
                    .into_iter()
                    .map(move |gesture| TouchSlot::new(side, gesture))
            })
            .collect();
        assert_eq!(expected_slots, touch.slots, "{profile:?}");
    }
}

#[test]
#[should_panic(expected = "单个十进制数字")]
fn touch_action_codes_above_nine_are_rejected_at_construction() {
    let _ = RsCommonProtocol::new(RsCommonInit::Default, &[("play_pause", 10)]);
}

#[test]
fn initialization_uses_documented_fixed_frames_and_delay() {
    assert_eq!(
        vec![
            (INFO_FRAME.to_string(), 0),
            ("ff0508fa2a090c0e07012baa".to_string(), 0)
        ],
        init_frames(ModelProfile::BudsFreeIRsCommon)
    );
    for profile in [
        ModelProfile::OpenFeelLdacRsCommon,
        ModelProfile::OpenFeelLhdcRsCommon,
        ModelProfile::OpenFeelDualRsCommon,
    ] {
        assert_eq!(
            vec![(INFO_FRAME.to_string(), 0), ("ff0002fa2baa".to_string(), 0)],
            init_frames(profile),
            "{profile:?}"
        );
    }
    for profile in [
        ModelProfile::CambrianRsCommon,
        ModelProfile::CambrianSRsCommon,
    ] {
        assert_eq!(
            vec![(INFO_FRAME.to_string(), 0), (INFO_FRAME.to_string(), 500)],
            init_frames(profile),
            "{profile:?}"
        );
    }
    for profile in [
        ModelProfile::OpenLinkRsCommon,
        ModelProfile::OpenFunRsCommon,
        ModelProfile::OpenFunTouchRsCommon,
        ModelProfile::SportFeelRsCommon,
        ModelProfile::SwiftRsCommon,
    ] {
        assert_eq!(
            vec![(INFO_FRAME.to_string(), 0)],
            init_frames(profile),
            "{profile:?}"
        );
    }
}

#[test]
fn initialization_requires_the_documented_write_capacity() {
    for profile in rs_common_profiles() {
        let mut codec = create_protocol_codec(profile);
        assert!(invalid_argument(codec.init_queries(18)), "{profile:?}");
        assert!(codec.init_queries(19).is_ok(), "{profile:?}");
    }
}

#[test]
fn initial_read_covers_exactly_the_readable_fields_of_every_model() {
    for profile in rs_common_profiles() {
        let readable = profile.capabilities(None, None).readable_fields;
        let queries = create_protocol_codec(profile)
            .init_queries(20)
            .expect("init queries");
        let mut covered = BTreeSet::new();
        for query in &queries {
            assert!(
                query.expected_fields.is_subset(&readable),
                "{profile:?} {}",
                query.key
            );
            covered.extend(query.expected_fields.iter().copied());
        }
        assert_eq!(readable, covered, "{profile:?}");
        assert!(
            ProtocolFamily::RsCommon
                .readable_fields()
                .is_superset(&readable),
            "{profile:?}"
        );
    }
}

#[test]
fn queries_only_expect_the_replies_the_model_can_read() {
    let swift = create_protocol_codec(ModelProfile::SwiftRsCommon)
        .init_queries(20)
        .expect("swift init");
    assert_eq!("fa010708090c0d0e122a2b2c2d2e33", swift[0].key);
    assert_eq!(
        ["01", "07", "0c", "0d", "0e"]
            .into_iter()
            .map(str::to_string)
            .collect::<BTreeSet<_>>(),
        swift[0].response_keys
    );
    assert_eq!(
        BTreeSet::from([
            HeadsetField::TouchKeys,
            HeadsetField::TouchEnabled,
            HeadsetField::CaseBattery,
            HeadsetField::Firmware,
            HeadsetField::GameMode,
        ]),
        swift[0].expected_fields
    );

    let open_link = create_protocol_codec(ModelProfile::OpenLinkRsCommon)
        .init_queries(20)
        .expect("open link init");
    assert!(
        open_link[0]
            .expected_fields
            .contains(&HeadsetField::LeftBattery)
            && !open_link[0]
                .expected_fields
                .contains(&HeadsetField::CaseBattery)
    );

    let open_feel = create_protocol_codec(ModelProfile::OpenFeelLdacRsCommon)
        .init_queries(20)
        .expect("open feel init");
    assert_eq!("fa2b", open_feel[1].key);
    assert_eq!(
        BTreeSet::from(["2b".to_string()]),
        open_feel[1].response_keys
    );
    assert_eq!(
        BTreeSet::from([HeadsetField::AudioMode, HeadsetField::Multipoint]),
        open_feel[1].expected_fields
    );

    let buds = create_protocol_codec(ModelProfile::BudsFreeIRsCommon)
        .init_queries(20)
        .expect("buds free init");
    assert_eq!("fa2a090c0e07012b", buds[1].key);
    assert_eq!(
        ["01", "07", "09", "0c", "0e", "2a", "2b"]
            .into_iter()
            .map(str::to_string)
            .collect::<BTreeSet<_>>(),
        buds[1].response_keys
    );
}

#[test]
fn writes_use_legacy_commands_and_independent_readbacks() {
    let mut cambrian = create_protocol_codec(ModelProfile::CambrianRsCommon);
    for (setting, value, write, readback) in [
        (
            HeadsetSetting::NoiseMode,
            FieldValue::text("anc"),
            "ff00021202aa",
            "ff0002fa09aa",
        ),
        (
            HeadsetSetting::NoiseMode,
            FieldValue::text("normal"),
            "ff00021204aa",
            "ff0002fa09aa",
        ),
        (
            HeadsetSetting::NoiseMode,
            FieldValue::text("transparency"),
            "ff00021206aa",
            "ff0002fa09aa",
        ),
        (
            HeadsetSetting::NoiseMode,
            FieldValue::text("wind"),
            "ff00021208aa",
            "ff0002fa09aa",
        ),
        (
            HeadsetSetting::Eq,
            FieldValue::text("pop"),
            "ff00022a01aa",
            "ff0002fa2aaa",
        ),
        (
            HeadsetSetting::Eq,
            FieldValue::text("hifi"),
            "ff00022a00aa",
            "ff0002fa2aaa",
        ),
        (
            HeadsetSetting::Eq,
            FieldValue::text("rock"),
            "ff00022a02aa",
            "ff0002fa2aaa",
        ),
        (
            HeadsetSetting::TransparencyLevel,
            FieldValue::Number(3),
            "ff00025a03aa",
            "ff0002fa2daa",
        ),
        (
            HeadsetSetting::Multipoint,
            FieldValue::Toggle(true),
            "ff00022b04aa",
            "ff0002fa2baa",
        ),
        (
            HeadsetSetting::Multipoint,
            FieldValue::Toggle(false),
            "ff00022b00aa",
            "ff0002fa2baa",
        ),
        (
            HeadsetSetting::PromptVolume,
            FieldValue::Number(5),
            "ff00025c05aa",
            "ff0002fa2eaa",
        ),
        (
            HeadsetSetting::GameMode,
            FieldValue::Toggle(true),
            "ff00020e01aa",
            "ff0002fa0eaa",
        ),
        (
            HeadsetSetting::GameMode,
            FieldValue::Toggle(false),
            "ff00020e00aa",
            "ff0002fa0eaa",
        ),
        (
            HeadsetSetting::TouchEnabled,
            FieldValue::Toggle(true),
            "ff00020e00aa",
            "ff0002fa07aa",
        ),
        (
            HeadsetSetting::TouchEnabled,
            FieldValue::Toggle(false),
            "ff00020e02aa",
            "ff0002fa07aa",
        ),
        (
            HeadsetSetting::AudioMode,
            FieldValue::text("ldac"),
            "ff00022b01aa",
            "ff0002fa2baa",
        ),
    ] {
        let prepared = prepare(cambrian.as_mut(), setting, value.clone()).expect("prepared");
        assert_eq!(
            vec![write.to_string()],
            prepared
                .write_frames
                .iter()
                .map(|frame| encode_hex(frame))
                .collect::<Vec<_>>(),
            "{setting:?} {value:?}"
        );
        assert_eq!(readback, encode_hex(&prepared.readback.bytes));
        assert!(prepared.additional_readbacks.is_empty());
        assert_eq!(1, prepared.expected_values.len());
        assert_eq!(Some(&value), prepared.expected_values.values().next());
    }

    let mut sport = create_protocol_codec(ModelProfile::SportFeelRsCommon);
    let spatial = prepare(
        sport.as_mut(),
        HeadsetSetting::SpatialAudio,
        FieldValue::Toggle(true),
    )
    .expect("spatial audio");
    assert_eq!("ff00023301aa", encode_hex(&spatial.write_frames[0]));
    assert_eq!("ff0002fa33aa", encode_hex(&spatial.readback.bytes));
    assert_eq!(
        BTreeSet::from([HeadsetField::SpatialAudio]),
        spatial.readback.expected_fields
    );
    assert_eq!(
        "ff00023300aa",
        write_hex(
            sport.as_mut(),
            HeadsetSetting::SpatialAudio,
            FieldValue::Toggle(false)
        )
    );

    let touch = prepare(
        cambrian.as_mut(),
        HeadsetSetting::TouchBinding,
        FieldValue::touch_assignment("left", "single", "volume_up"),
    )
    .expect("touch binding");
    assert_eq!("ff0003010104aa", encode_hex(&touch.write_frames[0]));
    assert_eq!("ff0002fa01aa", encode_hex(&touch.readback.bytes));
    assert_eq!(
        BTreeMap::from([(
            HeadsetField::TouchKeys,
            FieldValue::touch_assignment("left", "single", "volume_up")
        )]),
        touch.expected_values
    );
}

#[test]
fn prepared_readback_keeps_only_fields_the_model_can_read() {
    let mut swift = create_protocol_codec(ModelProfile::SwiftRsCommon);
    let touch = prepare(
        swift.as_mut(),
        HeadsetSetting::TouchBinding,
        FieldValue::touch_assignment("right", "triple", "next_track"),
    )
    .expect("swift touch binding");
    assert_eq!("ff0003011303aa", encode_hex(&touch.write_frames[0]));
    assert_eq!(
        BTreeSet::from([HeadsetField::TouchKeys]),
        touch.readback.expected_fields
    );
    assert_eq!(
        BTreeSet::from(["01".to_string()]),
        touch.readback.response_keys
    );
    assert_eq!(
        "ff00020e01aa",
        write_hex(
            swift.as_mut(),
            HeadsetSetting::GameMode,
            FieldValue::Toggle(true)
        )
    );
}

#[test]
fn settings_outside_the_model_or_with_bad_values_are_rejected_before_any_frame() {
    let mut swift = create_protocol_codec(ModelProfile::SwiftRsCommon);
    for setting in [
        HeadsetSetting::NoiseMode,
        HeadsetSetting::Eq,
        HeadsetSetting::AudioMode,
        HeadsetSetting::PromptVolume,
        HeadsetSetting::TouchEnabled,
        HeadsetSetting::SpatialAudio,
        HeadsetSetting::AncLevel,
        HeadsetSetting::NoiseCycle,
        HeadsetSetting::CustomEq,
    ] {
        assert!(
            unsupported(prepare(swift.as_mut(), setting, FieldValue::Toggle(true))),
            "{setting:?}"
        );
    }
    assert!(unsupported(prepare(
        create_protocol_codec(ModelProfile::OpenFunRsCommon).as_mut(),
        HeadsetSetting::TouchEnabled,
        FieldValue::Toggle(true)
    )));
    assert!(unsupported(prepare(
        create_protocol_codec(ModelProfile::CambrianRsCommon).as_mut(),
        HeadsetSetting::AncLevel,
        FieldValue::Number(1)
    )));

    let mut buds = create_protocol_codec(ModelProfile::BudsFreeIRsCommon);
    assert!(invalid_argument(prepare(
        buds.as_mut(),
        HeadsetSetting::NoiseMode,
        FieldValue::text("wind")
    )));
    assert!(invalid_argument(prepare(
        buds.as_mut(),
        HeadsetSetting::NoiseMode,
        FieldValue::Toggle(true)
    )));
    assert!(invalid_argument(prepare(
        buds.as_mut(),
        HeadsetSetting::Eq,
        FieldValue::text("light")
    )));
    assert!(invalid_argument(prepare(
        buds.as_mut(),
        HeadsetSetting::AudioMode,
        FieldValue::text("lhdc")
    )));
    assert!(invalid_argument(prepare(
        buds.as_mut(),
        HeadsetSetting::GameMode,
        FieldValue::Number(1)
    )));
    assert!(invalid_argument(prepare(
        buds.as_mut(),
        HeadsetSetting::GameMode,
        FieldValue::text("on")
    )));

    let mut cambrian = create_protocol_codec(ModelProfile::CambrianRsCommon);
    for level in [0, 4, 6] {
        assert!(
            invalid_argument(prepare(
                cambrian.as_mut(),
                HeadsetSetting::TransparencyLevel,
                FieldValue::Number(level)
            )),
            "transparency {level}"
        );
    }
    for level in [0, 6] {
        assert!(
            invalid_argument(prepare(
                cambrian.as_mut(),
                HeadsetSetting::PromptVolume,
                FieldValue::Number(level)
            )),
            "prompt volume {level}"
        );
    }
    assert!(invalid_argument(prepare(
        cambrian.as_mut(),
        HeadsetSetting::PromptVolume,
        FieldValue::Toggle(true)
    )));
}

#[test]
fn every_advertised_option_of_every_model_can_be_written() {
    fn option_values(caps: &HeadsetCapabilities, setting: HeadsetSetting) -> Vec<FieldValue> {
        let texts = |set: &BTreeSet<String>| {
            set.iter()
                .map(|item| FieldValue::text(item.as_str()))
                .collect::<Vec<_>>()
        };
        let numbers = |set: &BTreeSet<i32>| -> Vec<FieldValue> {
            set.iter().copied().map(FieldValue::Number).collect()
        };
        match setting {
            HeadsetSetting::GameMode
            | HeadsetSetting::TouchEnabled
            | HeadsetSetting::Multipoint
            | HeadsetSetting::SpatialAudio => {
                vec![FieldValue::Toggle(true), FieldValue::Toggle(false)]
            }
            HeadsetSetting::NoiseMode => texts(&caps.noise_control.as_ref().expect("noise").modes),
            HeadsetSetting::Eq => texts(&caps.equalizer.as_ref().expect("equalizer").presets),
            HeadsetSetting::AudioMode => texts(&caps.audio_control.as_ref().expect("audio").modes),
            HeadsetSetting::TransparencyLevel => numbers(
                &caps
                    .noise_control
                    .as_ref()
                    .and_then(|noise| noise.transparency_level.as_ref())
                    .expect("transparency level")
                    .levels,
            ),
            HeadsetSetting::PromptVolume => numbers(&caps.prompt_volume_levels),
            HeadsetSetting::TouchBinding => {
                let touch = caps.touch_control.as_ref().expect("touch");
                touch
                    .slots
                    .iter()
                    .flat_map(|slot| {
                        touch.actions.iter().map(move |action| {
                            FieldValue::touch_assignment(
                                slot.side.as_str(),
                                slot.gesture.as_str(),
                                action.as_str(),
                            )
                        })
                    })
                    .collect()
            }
            other => panic!("unexpected writable setting {other:?}"),
        }
    }

    for profile in rs_common_profiles() {
        let caps = profile.capabilities(None, None);
        let mut codec = create_protocol_codec(profile);
        assert!(!caps.writable_settings.is_empty());
        for &setting in &caps.writable_settings {
            let values = option_values(&caps, setting);
            assert!(!values.is_empty(), "{profile:?} {setting:?}");
            for value in values {
                prepare(codec.as_mut(), setting, value.clone())
                    .unwrap_or_else(|error| panic!("{profile:?} {setting:?} {value:?}: {error}"));
            }
        }
    }
}

#[test]
fn budsfree_touch_bindings_use_documented_slots_and_action_codes() {
    let profile = ModelProfile::BudsFreeIRsCommon;
    let mut codec = create_protocol_codec(profile);
    assert_eq!(
        "ff0003010101aa",
        write_hex(
            codec.as_mut(),
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("left", "single", "play_pause")
        )
    );
    assert_eq!(
        "ff0003011101aa",
        write_hex(
            codec.as_mut(),
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("right", "single", "play_pause")
        )
    );
    for (action, tx) in [
        ("none", "ff0003010300aa"),
        ("play_pause", "ff0003010301aa"),
        ("previous_track", "ff0003010302aa"),
        ("next_track", "ff0003010303aa"),
        ("volume_up", "ff0003010305aa"),
        ("volume_down", "ff0003010306aa"),
    ] {
        let assignment = FieldValue::touch_assignment("left", "triple", action);
        let prepared = prepare(
            codec.as_mut(),
            HeadsetSetting::TouchBinding,
            assignment.clone(),
        )
        .expect("buds free touch binding");
        assert_eq!(tx, encode_hex(&prepared.write_frames[0]), "{action}");
        assert_eq!(
            BTreeMap::from([(HeadsetField::TouchKeys, assignment)]),
            prepared.expected_values
        );
    }
    assert_eq!(
        "ff0003011206aa",
        write_hex(
            codec.as_mut(),
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("right", "double", "volume_down")
        )
    );
    for side in ["left", "right"] {
        assert!(invalid_argument(prepare(
            codec.as_mut(),
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment(side, "long", "none")
        )));
    }
    assert!(invalid_argument(prepare(
        codec.as_mut(),
        HeadsetSetting::TouchBinding,
        FieldValue::touch_assignment("middle", "single", "none")
    )));
    assert!(invalid_argument(prepare(
        codec.as_mut(),
        HeadsetSetting::TouchBinding,
        FieldValue::Toggle(true)
    )));
    for action in ["voice_assistant", "noise_cycle", "game_mode"] {
        assert!(
            unsupported(prepare(
                codec.as_mut(),
                HeadsetSetting::TouchBinding,
                FieldValue::touch_assignment("left", "single", action)
            )),
            "{action}"
        );
    }
}

#[test]
fn default_touch_action_codes_follow_the_shared_table() {
    let mut codec = create_protocol_codec(ModelProfile::OpenFeelDualRsCommon);
    for (action, tx) in [
        ("none", "ff0003011100aa"),
        ("play_pause", "ff0003011101aa"),
        ("previous_track", "ff0003011102aa"),
        ("next_track", "ff0003011103aa"),
        ("volume_up", "ff0003011104aa"),
        ("volume_down", "ff0003011105aa"),
    ] {
        assert_eq!(
            tx,
            write_hex(
                codec.as_mut(),
                HeadsetSetting::TouchBinding,
                FieldValue::touch_assignment("right", "single", action)
            ),
            "{action}"
        );
    }
    let mut ldac = create_protocol_codec(ModelProfile::OpenFeelLdacRsCommon);
    assert!(unsupported(prepare(
        ldac.as_mut(),
        HeadsetSetting::TouchBinding,
        FieldValue::touch_assignment("left", "single", "none")
    )));
}

#[test]
fn synthetic_budsfree_touch_reply_decodes_every_binding() {
    let mut codec = create_protocol_codec(ModelProfile::BudsFreeIRsCommon);
    let reply = single_reply(codec.as_mut(), "ff00110100010002000300000000000500060000aa");
    assert_eq!(BTreeSet::from(["01".to_string()]), reply.response_keys);
    let FieldValue::TouchKeys(bindings) = known(&reply, HeadsetField::TouchKeys) else {
        panic!("expected touch keys");
    };
    let binding = |side: &str, gesture: &str, action: Option<&str>, raw_action: i32| TouchBinding {
        side: side.to_string(),
        gesture: gesture.to_string(),
        action: action.map(str::to_string),
        raw_action,
    };
    assert_eq!(
        &vec![
            binding("left", "single", Some("play_pause"), 1),
            binding("left", "double", Some("previous_track"), 2),
            binding("left", "triple", Some("next_track"), 3),
            binding("right", "single", Some("volume_up"), 5),
            binding("right", "double", Some("volume_down"), 6),
            binding("right", "triple", Some("none"), 0),
        ],
        bindings
    );
    assert!(reply.accepted);
    assert_eq!(
        "00010002000300000000000500060000",
        reply
            .readings
            .get(&HeadsetField::TouchKeys)
            .map(FieldReading::raw_hex)
            .expect("touch reading")
    );
}

#[test]
fn touch_replies_on_default_models_leave_unmapped_codes_unnamed() {
    let mut codec = create_protocol_codec(ModelProfile::SwiftRsCommon);
    let reply = single_reply(codec.as_mut(), "ff00110100010002000300000000000500060000aa");
    let FieldValue::TouchKeys(bindings) = known(&reply, HeadsetField::TouchKeys) else {
        panic!("expected touch keys");
    };
    let actions: Vec<(Option<&str>, i32)> = bindings
        .iter()
        .map(|binding| (binding.action.as_deref(), binding.raw_action))
        .collect();
    assert_eq!(
        vec![
            (Some("play_pause"), 1),
            (Some("previous_track"), 2),
            (Some("next_track"), 3),
            (Some("volume_down"), 5),
            (None, 6),
            (Some("none"), 0),
        ],
        actions
    );

    let nibbles = single_reply(codec.as_mut(), "ff00110100f100f200f30000000000f500f600f0aa");
    let FieldValue::TouchKeys(bindings) = known(&nibbles, HeadsetField::TouchKeys) else {
        panic!("expected touch keys");
    };
    assert_eq!(
        vec![1, 2, 3, 5, 6, 0],
        bindings
            .iter()
            .map(|binding| binding.raw_action)
            .collect::<Vec<_>>()
    );

    let short = single_reply(codec.as_mut(), "ff001001000100020003000000000005000600aa");
    assert!(short.readings.is_empty());
    assert!(short.response_keys.is_empty());
}

#[test]
fn hardware_branches_reject_commands_they_cannot_offer() {
    let mut ldac = create_protocol_codec(ModelProfile::OpenFeelLdacRsCommon);
    let mut lhdc = create_protocol_codec(ModelProfile::OpenFeelLhdcRsCommon);
    let mut dual = create_protocol_codec(ModelProfile::OpenFeelDualRsCommon);
    assert_eq!(
        "ff00022b01aa",
        write_hex(
            ldac.as_mut(),
            HeadsetSetting::AudioMode,
            FieldValue::text("ldac")
        )
    );
    assert_eq!(
        "ff00022b02aa",
        write_hex(
            lhdc.as_mut(),
            HeadsetSetting::AudioMode,
            FieldValue::text("lhdc")
        )
    );
    assert_eq!(
        "ff00022b03aa",
        write_hex(
            dual.as_mut(),
            HeadsetSetting::AudioMode,
            FieldValue::text("lc3")
        )
    );
    assert_eq!(
        "ff00022b00aa",
        write_hex(
            dual.as_mut(),
            HeadsetSetting::AudioMode,
            FieldValue::text("aac_sbc")
        )
    );
    assert!(invalid_argument(prepare(
        ldac.as_mut(),
        HeadsetSetting::AudioMode,
        FieldValue::text("lhdc")
    )));
    assert!(invalid_argument(prepare(
        lhdc.as_mut(),
        HeadsetSetting::AudioMode,
        FieldValue::text("ldac")
    )));
    assert!(unsupported(prepare(
        create_protocol_codec(ModelProfile::OpenFunRsCommon).as_mut(),
        HeadsetSetting::TouchEnabled,
        FieldValue::Toggle(true)
    )));
    assert_eq!(
        "ff00020e00aa",
        write_hex(
            create_protocol_codec(ModelProfile::OpenFunTouchRsCommon).as_mut(),
            HeadsetSetting::TouchEnabled,
            FieldValue::Toggle(true)
        )
    );
}

#[test]
fn synthetic_replies_decode_and_buffer_across_fragments() {
    let mut codec = create_protocol_codec(ModelProfile::OpenFeelDualRsCommon);
    assert!(codec.feed_notification(&hex_bytes("ff00020e")).is_empty());
    let reply = single_reply(codec.as_mut(), "01aa");
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&reply, HeadsetField::GameMode)
    );
    assert_eq!(BTreeSet::from(["0e".to_string()]), reply.response_keys);

    let multi = single_reply(codec.as_mut(), "ff00022b04aa");
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&multi, HeadsetField::Multipoint)
    );
    assert!(is_unknown(&multi, HeadsetField::AudioMode));

    let battery = single_reply(codec.as_mut(), "ff00040c32465aaa");
    assert_eq!(
        &FieldValue::Number(90),
        known(&battery, HeadsetField::CaseBattery)
    );
    assert_eq!(
        &FieldValue::Number(50),
        known(&battery, HeadsetField::LeftBattery)
    );
    assert_eq!(
        &FieldValue::Number(70),
        known(&battery, HeadsetField::RightBattery)
    );

    let single = single_reply(
        create_protocol_codec(ModelProfile::SwiftRsCommon).as_mut(),
        "ff00040c32465aaa",
    );
    assert_eq!(
        BTreeSet::from([HeadsetField::CaseBattery]),
        single.readings.keys().copied().collect::<BTreeSet<_>>()
    );
}

#[test]
fn notification_stream_resynchronizes_on_frame_start_and_ignores_malformed_frames() {
    let mut codec = create_protocol_codec(ModelProfile::CambrianRsCommon);
    let two = codec.feed_notification(&hex_bytes("0102ff00020e01aaff00022b04aa"));
    assert_eq!(2, two.len());
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&two[0], HeadsetField::GameMode)
    );
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&two[1], HeadsetField::Multipoint)
    );

    assert!(codec.feed_notification(&hex_bytes("0102030405")).is_empty());
    assert!(
        codec
            .feed_notification(&hex_bytes("ff00020e01bb"))
            .is_empty()
    );
    assert!(codec.feed_notification(&hex_bytes("ff0000aa")).is_empty());
    let after = single_reply(codec.as_mut(), "ff00020e00aa");
    assert_eq!(
        &FieldValue::Toggle(false),
        known(&after, HeadsetField::GameMode)
    );

    assert!(codec.feed_notification(&hex_bytes("ff00")).is_empty());
    assert!(codec.feed_notification(&hex_bytes("02")).is_empty());
    codec.reset();
    let fresh = single_reply(codec.as_mut(), "ff00020e01aa");
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&fresh, HeadsetField::GameMode)
    );

    assert!(codec.feed_notification(&hex_bytes("ff00020e")).is_empty());
    codec.reset();
    assert!(codec.feed_notification(&hex_bytes("01aa")).is_empty());
}

#[test]
fn replies_without_a_value_or_with_unknown_tags_carry_no_readings() {
    let mut codec = create_protocol_codec(ModelProfile::CambrianRsCommon);
    let empty = single_reply(codec.as_mut(), "ff00010eaa");
    assert!(empty.readings.is_empty() && empty.response_keys.is_empty() && !empty.accepted);
    assert_eq!("ff00010eaa", empty.raw_hex);

    let unknown = single_reply(codec.as_mut(), "ff00021201aa");
    assert!(unknown.readings.is_empty() && unknown.response_keys.is_empty());
    let unknown = single_reply(codec.as_mut(), "ff00027f01aa");
    assert!(unknown.readings.is_empty() && unknown.response_keys.is_empty());
    assert!(unknown.warnings.is_empty() && !unknown.accepted);

    let short_battery = single_reply(codec.as_mut(), "ff00030c3246aa");
    assert!(short_battery.readings.is_empty() && short_battery.response_keys.is_empty());
    let short_firmware = single_reply(codec.as_mut(), "ff00030d0102aa");
    assert!(short_firmware.readings.is_empty());
}

#[test]
fn battery_replies_follow_each_layout_and_flag_semantics() {
    let mut open_feel = create_protocol_codec(ModelProfile::OpenFeelLdacRsCommon);
    let battery = single_reply(open_feel.as_mut(), "ff00040cb2e55aaa");
    assert_eq!(
        &FieldValue::Number(50),
        known(&battery, HeadsetField::LeftBattery)
    );
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&battery, HeadsetField::LeftInCase)
    );
    assert!(is_unknown(&battery, HeadsetField::RightBattery));
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&battery, HeadsetField::RightInCase)
    );
    assert_eq!(
        &FieldValue::Number(90),
        known(&battery, HeadsetField::CaseBattery)
    );
    assert!(battery.accepted);

    let out_of_range = single_reply(open_feel.as_mut(), "ff00040c00ff65aa");
    assert!(is_unknown(&out_of_range, HeadsetField::LeftBattery));
    assert_eq!(
        &FieldValue::Toggle(false),
        known(&out_of_range, HeadsetField::LeftInCase)
    );
    assert!(is_unknown(&out_of_range, HeadsetField::RightBattery));
    assert!(is_unknown(&out_of_range, HeadsetField::RightInCase));
    assert!(is_unknown(&out_of_range, HeadsetField::CaseBattery));

    let mut earbuds_only = create_protocol_codec(ModelProfile::OpenLinkRsCommon);
    let reply = single_reply(earbuds_only.as_mut(), "ff00040c32c65aaa");
    assert_eq!(
        BTreeSet::from([
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::LeftInCase,
            HeadsetField::RightInCase,
        ]),
        reply.readings.keys().copied().collect::<BTreeSet<_>>()
    );
    assert_eq!(
        &FieldValue::Number(70),
        known(&reply, HeadsetField::RightBattery)
    );
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&reply, HeadsetField::RightInCase)
    );
    assert_eq!(
        &FieldValue::Toggle(false),
        known(&reply, HeadsetField::LeftInCase)
    );
    assert_eq!(
        Some("32"),
        match reply.readings.get(&HeadsetField::LeftBattery) {
            Some(FieldReading::Known { raw_hex, .. }) => Some(raw_hex.as_str()),
            _ => None,
        }
    );

    let mut single_unit = create_protocol_codec(ModelProfile::CambrianSRsCommon);
    let unknown_case = single_reply(single_unit.as_mut(), "ff00040c3246ffaa");
    assert_eq!(
        BTreeSet::from([HeadsetField::CaseBattery]),
        unknown_case
            .readings
            .keys()
            .copied()
            .collect::<BTreeSet<_>>()
    );
    assert!(is_unknown(&unknown_case, HeadsetField::CaseBattery));
    assert!(!unknown_case.accepted);
}

#[test]
fn scalar_replies_map_wire_values_to_field_values() {
    let mut codec = create_protocol_codec(ModelProfile::CambrianRsCommon);

    let firmware = single_reply(codec.as_mut(), "ff00040d210203aa");
    let FieldValue::Firmware(version) = known(&firmware, HeadsetField::Firmware) else {
        panic!("expected firmware");
    };
    assert_eq!(vec![1, 2, 3], version.parts);
    assert_eq!("123", version.display_text);
    let big = single_reply(codec.as_mut(), "ff00040d01ff10aa");
    let FieldValue::Firmware(version) = known(&big, HeadsetField::Firmware) else {
        panic!("expected firmware");
    };
    assert_eq!(vec![1, 255, 16], version.parts);
    assert_eq!("125516", version.display_text);

    for (hex, field, expected) in [
        ("ff00020700aa", HeadsetField::TouchEnabled, false),
        ("ff00020701aa", HeadsetField::TouchEnabled, true),
        ("ff00020801aa", HeadsetField::EarDetection, true),
        ("ff00020e01aa", HeadsetField::GameMode, true),
        ("ff00023301aa", HeadsetField::SpatialAudio, true),
        ("ff00023300aa", HeadsetField::SpatialAudio, false),
    ] {
        let reply = single_reply(codec.as_mut(), hex);
        assert_eq!(&FieldValue::Toggle(expected), known(&reply, field), "{hex}");
    }
    for (hex, field) in [
        ("ff00020702aa", HeadsetField::TouchEnabled),
        ("ff000208ffaa", HeadsetField::EarDetection),
        ("ff00020e02aa", HeadsetField::GameMode),
        ("ff00023302aa", HeadsetField::SpatialAudio),
    ] {
        assert!(
            is_unknown(&single_reply(codec.as_mut(), hex), field),
            "{hex}"
        );
    }

    for (hex, expected) in [
        ("ff00020901aa", Some("anc")),
        ("ff00020902aa", Some("normal")),
        ("ff00020903aa", Some("transparency")),
        ("ff00020904aa", Some("wind")),
        ("ff00020900aa", None),
        ("ff00020905aa", None),
    ] {
        let reply = single_reply(codec.as_mut(), hex);
        match expected {
            Some(mode) => assert_eq!(
                &FieldValue::text(mode),
                known(&reply, HeadsetField::NoiseMode),
                "{hex}"
            ),
            None => assert!(is_unknown(&reply, HeadsetField::NoiseMode), "{hex}"),
        }
    }

    for (hex, expected) in [
        ("ff00022a00aa", Some("hifi")),
        ("ff00022a01aa", Some("pop")),
        ("ff00022a02aa", Some("rock")),
        ("ff00022a03aa", None),
    ] {
        let reply = single_reply(codec.as_mut(), hex);
        match expected {
            Some(preset) => assert_eq!(
                &FieldValue::text(preset),
                known(&reply, HeadsetField::Eq),
                "{hex}"
            ),
            None => assert!(is_unknown(&reply, HeadsetField::Eq), "{hex}"),
        }
    }

    for (value, audio, multipoint) in [
        (0, Some("aac_sbc"), Some(false)),
        (1, Some("ldac"), Some(false)),
        (2, Some("lhdc"), Some(false)),
        (3, Some("lc3"), Some(false)),
        (4, None, Some(true)),
        (5, None, None),
    ] {
        let reply = single_reply(codec.as_mut(), &format!("ff00022b{value:02x}aa"));
        match audio {
            Some(mode) => assert_eq!(
                &FieldValue::text(mode),
                known(&reply, HeadsetField::AudioMode)
            ),
            None => assert!(is_unknown(&reply, HeadsetField::AudioMode), "{value}"),
        }
        match multipoint {
            Some(on) => assert_eq!(
                &FieldValue::Toggle(on),
                known(&reply, HeadsetField::Multipoint)
            ),
            None => assert!(is_unknown(&reply, HeadsetField::Multipoint), "{value}"),
        }
    }

    for (tag, field) in [
        ("2c", HeadsetField::AncLevel),
        ("2d", HeadsetField::TransparencyLevel),
        ("2e", HeadsetField::PromptVolume),
    ] {
        for level in 1..=5 {
            let reply = single_reply(codec.as_mut(), &format!("ff0002{tag}{level:02x}aa"));
            assert_eq!(&FieldValue::Number(level), known(&reply, field));
        }
        for level in [0, 6] {
            let reply = single_reply(codec.as_mut(), &format!("ff0002{tag}{level:02x}aa"));
            assert!(is_unknown(&reply, field), "{tag} {level}");
        }
    }
}

#[test]
fn synthetic_initial_replies_make_every_readable_field_known_with_the_documented_battery_layout() {
    for profile in rs_common_profiles() {
        let caps = profile.capabilities(None, None);
        let mut codec = create_protocol_codec(profile);
        let mut readings = BTreeMap::new();
        for hex in SYNTHETIC_REPLIES {
            let reply = single_reply(codec.as_mut(), hex);
            readings.extend(reply.for_readable_fields(&caps.readable_fields));
        }
        let known_fields: BTreeSet<HeadsetField> = readings
            .iter()
            .filter(|(_, reading)| matches!(reading, FieldReading::Known { .. }))
            .map(|(&field, _)| field)
            .collect();
        assert_eq!(caps.readable_fields, known_fields, "{profile:?}");

        let battery = |field| match readings.get(&field) {
            Some(FieldReading::Known { value, .. }) => Some(value.clone()),
            _ => None,
        };
        match profile.battery_layout() {
            BatteryLayout::SingleUnit => {
                assert_eq!(
                    Some(FieldValue::Number(90)),
                    battery(HeadsetField::CaseBattery)
                );
                assert_eq!(None, battery(HeadsetField::LeftBattery));
                assert_eq!(None, battery(HeadsetField::RightBattery));
            }
            BatteryLayout::EarbudsOnly => {
                assert_eq!(
                    Some(FieldValue::Number(50)),
                    battery(HeadsetField::LeftBattery)
                );
                assert_eq!(
                    Some(FieldValue::Number(70)),
                    battery(HeadsetField::RightBattery)
                );
                assert_eq!(None, battery(HeadsetField::CaseBattery));
            }
            BatteryLayout::EarbudsAndCase => {
                assert_eq!(
                    Some(FieldValue::Number(50)),
                    battery(HeadsetField::LeftBattery)
                );
                assert_eq!(
                    Some(FieldValue::Number(70)),
                    battery(HeadsetField::RightBattery)
                );
                assert_eq!(
                    Some(FieldValue::Number(90)),
                    battery(HeadsetField::CaseBattery)
                );
            }
        }
    }
}

#[test]
fn cambrian_s_repeats_the_batch_after_half_a_second_and_open_link_reads_it_once() {
    assert_eq!(
        vec![0, 500],
        init_frames(ModelProfile::CambrianSRsCommon)
            .into_iter()
            .map(|(_, delay)| delay)
            .collect::<Vec<_>>()
    );
    assert_eq!(
        vec![INFO_FRAME.to_string(), INFO_FRAME.to_string()],
        init_frames(ModelProfile::CambrianSRsCommon)
            .into_iter()
            .map(|(frame, _)| frame)
            .collect::<Vec<_>>()
    );
    assert_eq!(
        vec![(INFO_FRAME.to_string(), 0)],
        init_frames(ModelProfile::OpenLinkRsCommon)
    );
}

#[test]
fn spatial_audio_write_is_confirmed_by_its_own_readback_reply() {
    let mut sport = create_protocol_codec(ModelProfile::SportFeelRsCommon);
    let prepared = prepare(
        sport.as_mut(),
        HeadsetSetting::SpatialAudio,
        FieldValue::Toggle(true),
    )
    .expect("spatial audio");
    let unrelated = single_reply(sport.as_mut(), "ff00020e01aa");
    assert!(
        prepared
            .readback
            .response_keys
            .is_disjoint(&unrelated.response_keys)
    );
    let confirmation = single_reply(sport.as_mut(), "ff00023301aa");
    assert_eq!(prepared.readback.response_keys, confirmation.response_keys);
    assert_eq!(
        prepared.expected_values.get(&HeadsetField::SpatialAudio),
        confirmation
            .readings
            .get(&HeadsetField::SpatialAudio)
            .and_then(FieldReading::known_value)
    );
}
