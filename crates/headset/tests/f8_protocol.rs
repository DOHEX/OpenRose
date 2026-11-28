use openrose_headset::{
    BatteryLayout, FieldReading, FieldValue, FirmwareVersion, HeadsetCommand, HeadsetField,
    HeadsetReadings, HeadsetSetting, ModelIdentification, ModelProfile, ProtocolCodec,
    ProtocolError, ProtocolFamily, ProtocolReply, ProtocolSetting, TouchBinding, TouchSlot,
    ValidationErrorKind, confirmation_value, create_protocol_codec, decode_hex, encode_hex,
};
use std::collections::{BTreeMap, BTreeSet};

fn connect_time() -> openrose_headset::LocalDateTime {
    openrose_headset::LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time")
}


const F8_PROFILES: [ModelProfile; 6] = [
    ModelProfile::AgateF8,
    ModelProfile::AgateXF8,
    ModelProfile::CeramicsF8,
    ModelProfile::CeramicsXF8,
    ModelProfile::CeramicsX7034F8,
    ModelProfile::ExplorerF8,
];

const SPP_UUID: &str = "0cf12d31-fac3-4553-bd80-d6832e7b3931";

fn manufacturer_data(entries: &[(u16, &str)]) -> BTreeMap<u16, Vec<u8>> {
    entries
        .iter()
        .map(|&(company, payload)| (company, decode_hex(payload).expect("valid hex")))
        .collect()
}

fn hex_bytes(hex: &str) -> Vec<u8> {
    decode_hex(hex).expect("valid test hex")
}

fn codec(profile: ModelProfile) -> Box<dyn ProtocolCodec> {
    create_protocol_codec(profile)
}

fn prepare(
    profile: ModelProfile,
    setting: HeadsetSetting,
    value: FieldValue,
) -> Result<ProtocolSetting, ProtocolError> {
    codec(profile).prepare_setting(setting, &value, None, &BTreeMap::new())
}

fn prepared(profile: ModelProfile, setting: HeadsetSetting, value: FieldValue) -> ProtocolSetting {
    prepare(profile, setting, value).expect("prepared setting")
}

fn single_reply(codec: &mut dyn ProtocolCodec, hex: &str) -> ProtocolReply {
    let mut replies = codec.feed_notification(&hex_bytes(hex));
    assert_eq!(1, replies.len(), "{hex}");
    replies.pop().expect("single reply")
}

fn reply_for(profile: ModelProfile, hex: &str) -> ProtocolReply {
    single_reply(codec(profile).as_mut(), hex)
}

fn known(reply: &ProtocolReply, field: HeadsetField) -> &FieldValue {
    match reply.readings.get(&field) {
        Some(FieldReading::Known { value, .. }) => value,
        other => panic!("expected Known reading for {field:?}, got {other:?}"),
    }
}

fn assert_unknown(reply: &ProtocolReply, field: HeadsetField) {
    assert!(
        matches!(
            reply.readings.get(&field),
            Some(FieldReading::Unknown { .. })
        ),
        "{field:?}: {:?}",
        reply.readings.get(&field)
    );
}

fn query_hexes(profile: ModelProfile) -> Vec<String> {
    codec(profile)
        .init_queries(6, connect_time())
        .expect("init queries")
        .iter()
        .map(|query| encode_hex(&query.bytes))
        .collect()
}

fn reply_frame(size: usize, tag: u8, bytes: &[(usize, u8)]) -> String {
    let mut frame = vec![0u8; size];
    frame[1] = 0x27;
    frame[2] = 1;
    frame[4] = (size - 5) as u8;
    frame[5] = tag;
    for &(index, value) in bytes {
        frame[index] = value;
    }
    encode_hex(&frame)
}

fn binding(side: &str, gesture: &str, action: Option<&str>, raw_action: i32) -> TouchBinding {
    TouchBinding {
        side: side.to_string(),
        gesture: gesture.to_string(),
        action: action.map(str::to_string),
        raw_action,
    }
}

#[test]
fn f8_models_are_identified_by_prefixes_names_and_stable_ids() {
    assert_eq!(Ok(()), ModelIdentification::validate_catalog());

    let prefix_cases = [
        (0x8418, "050599", ModelProfile::AgateF8),
        (0x8418, "050699", ModelProfile::AgateXF8),
        (0x8418, "010099", ModelProfile::CeramicsF8),
        (0x8418, "010399", ModelProfile::CeramicsF8),
        (0x8418, "010999", ModelProfile::CeramicsXF8),
        (0x8418, "011299", ModelProfile::CeramicsX7034F8),
        (0x8418, "010799", ModelProfile::ExplorerF8),
    ];
    for (company, payload, expected) in prefix_cases {
        assert_eq!(
            Some(expected),
            ModelIdentification::identify(&manufacturer_data(&[(company, payload)])),
            "{expected:?} {payload}"
        );
    }
    assert_eq!(
        None,
        ModelIdentification::identify(&manufacturer_data(&[(0x8418, "010199")]))
    );

    let mut prefixes = BTreeSet::new();
    for profile in F8_PROFILES {
        for &prefix in profile.advertisement_prefixes() {
            assert_eq!(8, prefix.len(), "{profile:?}");
            assert!(prefixes.insert(prefix), "duplicate prefix {prefix}");
        }
    }
    assert_eq!(7, prefixes.len());

    for (name, expected) in [
        ("AGATE", Some(ModelProfile::AgateF8)),
        ("ROSE AGATE", Some(ModelProfile::AgateF8)),
        ("ROSE AGATE X", Some(ModelProfile::AgateXF8)),
        ("AGATE X", Some(ModelProfile::AgateXF8)),
        ("CERAMICS", Some(ModelProfile::CeramicsF8)),
        ("ROSE Ceramics", Some(ModelProfile::CeramicsF8)),
        ("CERAMICS-X", None),
        ("ROSE Ceramics X", None),
        ("EXPLORER", Some(ModelProfile::ExplorerF8)),
        ("ROSE Explorer", Some(ModelProfile::ExplorerF8)),
    ] {
        assert_eq!(expected, ModelProfile::match_name(Some(name)), "{name}");
    }

    for (id, profile) in [
        ("AGATE_F8", ModelProfile::AgateF8),
        ("AGATE_X_F8", ModelProfile::AgateXF8),
        ("CERAMICS_F8", ModelProfile::CeramicsF8),
        ("CERAMICS_X_F8", ModelProfile::CeramicsXF8),
        ("CERAMICS_X_7034_F8", ModelProfile::CeramicsX7034F8),
        ("EXPLORER_F8", ModelProfile::ExplorerF8),
    ] {
        assert_eq!(id, profile.id());
        assert_eq!(Some(profile), ModelProfile::from_id(id));
        assert_eq!(ProtocolFamily::F8, profile.family());
        assert!(ModelProfile::ALL.contains(&profile));
        let json = serde_json::to_string(&profile).expect("serialize profile");
        assert_eq!(format!("\"{id}\""), json);
        assert_eq!(
            profile,
            serde_json::from_str::<ModelProfile>(&json).expect("deserialize profile")
        );
    }
}

#[test]
fn f8_classic_address_extracts_bytes_and_rejects_invalid_or_sentinel_values() {
    let raw = "1884010700a1b2c3d4e5f601";
    for profile in F8_PROFILES {
        assert_eq!(
            Some("A1:B2:C3:D4:E5:F6".to_string()),
            ModelIdentification::classic_address(raw, Some(profile)),
            "{profile:?}"
        );
    }
    assert_eq!(
        Some("11:22:33:44:55:66".to_string()),
        ModelIdentification::classic_address(
            "1884010900112233445566",
            Some(ModelProfile::CeramicsXF8)
        )
    );
    assert_eq!(
        Some("AA:BB:CC:DD:EE:0F".to_string()),
        ModelIdentification::classic_address(
            "1884010700AaBbCcDdEe0f",
            Some(ModelProfile::ExplorerF8)
        )
    );
    for invalid in [
        "188401090011223344556",
        "1884010900000000000000",
        "1884010900ffffffffffff",
        "1884010900FFFFFFFFFFFF",
        "18840109001122334455zz",
        "",
    ] {
        assert_eq!(
            None,
            ModelIdentification::classic_address(invalid, Some(ModelProfile::ExplorerF8)),
            "{invalid}"
        );
    }
    assert_eq!(None, ModelIdentification::classic_address(raw, None));
}

#[test]
fn f8_display_names_version_names_preset_labels_and_transport_come_from_model_spec() {
    assert_eq!("AGATE", ModelProfile::AgateF8.display_name());
    assert_eq!("AGATE", ModelProfile::AgateF8.short_label());
    assert_eq!(None, ModelProfile::AgateF8.version_name());
    assert_eq!(
        Some("Rock"),
        ModelProfile::AgateF8.equalizer_preset_label("pop")
    );
    assert_eq!(
        Some("HIFI"),
        ModelProfile::AgateF8.equalizer_preset_label("hifi")
    );
    assert_eq!(None, ModelProfile::AgateF8.equalizer_preset_label("rock"));

    assert_eq!("AGATE X", ModelProfile::AgateXF8.display_name());
    assert_eq!("AGATE X", ModelProfile::AgateXF8.short_label());
    assert_eq!(None, ModelProfile::AgateXF8.version_name());
    assert_eq!(
        Some("Rock"),
        ModelProfile::AgateXF8.equalizer_preset_label("pop")
    );
    assert_eq!(
        Some("POP"),
        ModelProfile::AgateXF8.equalizer_preset_label("hifi")
    );

    assert_eq!("CERAMICS", ModelProfile::CeramicsF8.display_name());
    assert_eq!(None, ModelProfile::CeramicsF8.version_name());
    for (preset, label) in [("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")] {
        assert_eq!(
            Some(label),
            ModelProfile::CeramicsF8.equalizer_preset_label(preset)
        );
    }

    assert_eq!("CERAMICS-X", ModelProfile::CeramicsXF8.display_name());
    assert_eq!("CERAMICS-X", ModelProfile::CeramicsXF8.short_label());
    assert_eq!(Some("标准版"), ModelProfile::CeramicsXF8.version_name());
    assert_eq!(None, ModelProfile::CeramicsXF8.spp_control_uuid());

    assert_eq!("CERAMICS-X", ModelProfile::CeramicsX7034F8.display_name());
    assert_eq!("CERAMICS-X", ModelProfile::CeramicsX7034F8.short_label());
    assert_eq!(Some("7034"), ModelProfile::CeramicsX7034F8.version_name());
    assert_eq!(
        Some(SPP_UUID),
        ModelProfile::CeramicsX7034F8.spp_control_uuid()
    );

    assert_eq!("EXPLORER", ModelProfile::ExplorerF8.display_name());
    assert_eq!(None, ModelProfile::ExplorerF8.version_name());
    assert_eq!(Some(SPP_UUID), ModelProfile::ExplorerF8.spp_control_uuid());

    for profile in F8_PROFILES {
        let gatt = profile.gatt();
        assert_eq!("0000fdb3-0000-1000-8000-00805f9b34fb", gatt.service_uuid);
        assert_eq!("0000ff16-0000-1000-8000-00805f9b34fb", gatt.write_uuid);
        assert_eq!("0000ff17-0000-1000-8000-00805f9b34fb", gatt.notify_uuid);
        assert_eq!(100, profile.write_interval_millis());
        assert!(!profile.refresh_equalizer_after_game_mode());
        let touch = profile
            .capabilities(None, None)
            .touch_control
            .expect("touch control");
        assert!(touch.slot_labels.is_empty(), "{profile:?}");
    }
}

#[test]
fn f8_battery_layout_touch_slots_and_actions_follow_each_model() {
    for basic in [
        ModelProfile::AgateF8,
        ModelProfile::AgateXF8,
        ModelProfile::CeramicsF8,
    ] {
        assert_eq!(BatteryLayout::EarbudsOnly, basic.battery_layout());
        assert_eq!(None, basic.spp_control_uuid());
        let touch = basic
            .capabilities(None, None)
            .touch_control
            .expect("touch control");
        assert_eq!(6, touch.slots.len());
        for side in ["left", "right"] {
            for gesture in ["single", "double", "long"] {
                assert!(touch.slots.contains(&TouchSlot::new(side, gesture)));
            }
            assert!(!touch.slots.contains(&TouchSlot::new(side, "triple")));
        }
    }
    for extended in [
        ModelProfile::CeramicsXF8,
        ModelProfile::CeramicsX7034F8,
        ModelProfile::ExplorerF8,
    ] {
        assert_eq!(BatteryLayout::EarbudsAndCase, extended.battery_layout());
        let touch = extended
            .capabilities(None, None)
            .touch_control
            .expect("touch control");
        assert_eq!(8, touch.slots.len());
        assert!(touch.slots.contains(&TouchSlot::new("right", "triple")));
    }

    let full_actions: BTreeSet<String> = [
        "none",
        "voice_assistant",
        "previous_track",
        "next_track",
        "volume_up",
        "volume_down",
        "play_pause",
        "noise_cycle",
    ]
    .into_iter()
    .map(str::to_string)
    .collect();
    let agate_x = ModelProfile::AgateXF8.capabilities(None, None);
    let mut without_noise = full_actions.clone();
    without_noise.remove("noise_cycle");
    assert_eq!(
        without_noise,
        agate_x.touch_control.expect("touch control").actions
    );
    assert_eq!(
        BTreeSet::from([HeadsetSetting::Eq, HeadsetSetting::TouchBinding]),
        ModelProfile::AgateXF8
            .capabilities(None, None)
            .writable_settings
    );
    for profile in F8_PROFILES
        .into_iter()
        .filter(|p| *p != ModelProfile::AgateXF8)
    {
        assert_eq!(
            full_actions,
            profile
                .capabilities(None, None)
                .touch_control
                .expect("touch control")
                .actions,
            "{profile:?}"
        );
    }
}

#[test]
fn f8_capabilities_expose_each_models_readable_and_writable_surface() {
    let basic_readable = BTreeSet::from([
        HeadsetField::LeftBattery,
        HeadsetField::RightBattery,
        HeadsetField::LeftCharging,
        HeadsetField::RightCharging,
        HeadsetField::Firmware,
        HeadsetField::Eq,
        HeadsetField::TouchKeys,
        HeadsetField::GameMode,
    ]);
    for profile in [
        ModelProfile::AgateF8,
        ModelProfile::AgateXF8,
        ModelProfile::CeramicsF8,
    ] {
        let caps = profile.capabilities(None, None);
        assert_eq!(basic_readable, caps.readable_fields, "{profile:?}");
        assert_eq!(
            BTreeSet::from(["pop".to_string(), "hifi".to_string()]),
            caps.equalizer.expect("equalizer").presets
        );
        assert!(caps.noise_control.is_none());
        assert!(caps.audio_control.is_none());
        assert!(caps.prompt_volume_levels.is_empty());
        assert!(caps.prompt_languages.is_empty());
    }
    for profile in [ModelProfile::AgateF8, ModelProfile::CeramicsF8] {
        assert_eq!(
            BTreeSet::from([
                HeadsetSetting::Eq,
                HeadsetSetting::TouchBinding,
                HeadsetSetting::GameMode,
            ]),
            profile.capabilities(None, None).writable_settings
        );
    }

    let mut extended_readable = basic_readable.clone();
    extended_readable.extend([
        HeadsetField::CaseBattery,
        HeadsetField::EarDetection,
        HeadsetField::PromptLanguage,
        HeadsetField::AudioMode,
        HeadsetField::Multipoint,
        HeadsetField::NoiseMode,
        HeadsetField::PromptVolume,
    ]);
    for profile in [ModelProfile::CeramicsXF8, ModelProfile::CeramicsX7034F8] {
        let caps = profile.capabilities(None, None);
        assert_eq!(extended_readable, caps.readable_fields, "{profile:?}");
        assert_eq!(
            BTreeSet::from([
                HeadsetSetting::Eq,
                HeadsetSetting::TouchBinding,
                HeadsetSetting::GameMode,
                HeadsetSetting::NoiseMode,
                HeadsetSetting::AudioMode,
                HeadsetSetting::Multipoint,
                HeadsetSetting::PromptLanguage,
            ]),
            caps.writable_settings
        );
        let noise = caps.noise_control.expect("noise control");
        assert_eq!(
            BTreeSet::from([
                "anc".to_string(),
                "normal".to_string(),
                "transparency".to_string(),
                "wind".to_string(),
            ]),
            noise.modes
        );
        assert!(noise.cycle_modes.is_empty());
        assert!(noise.anc_level.is_none());
        assert!(noise.transparency_level.is_none());
        assert_eq!(
            BTreeSet::from(["pop".to_string(), "hifi".to_string(), "rock".to_string()]),
            caps.equalizer.expect("equalizer").presets
        );
        let audio = caps.audio_control.expect("audio control");
        assert!(audio.multipoint_coupled);
        assert_eq!(
            BTreeSet::from(["aac_sbc".to_string(), "ldac".to_string()]),
            audio.modes
        );
        assert_eq!(
            BTreeSet::from(["chinese".to_string(), "english".to_string()]),
            caps.prompt_languages
        );
        assert!(caps.prompt_volume_levels.is_empty());
    }

    let explorer = ModelProfile::ExplorerF8.capabilities(None, None);
    let mut explorer_readable = extended_readable;
    explorer_readable.extend([
        HeadsetField::AncLevel,
        HeadsetField::TransparencyLevel,
        HeadsetField::TouchEnabled,
    ]);
    assert_eq!(explorer_readable, explorer.readable_fields);
    assert_eq!(
        BTreeSet::from([
            HeadsetSetting::Eq,
            HeadsetSetting::TouchBinding,
            HeadsetSetting::GameMode,
            HeadsetSetting::NoiseMode,
            HeadsetSetting::AudioMode,
            HeadsetSetting::Multipoint,
            HeadsetSetting::PromptLanguage,
            HeadsetSetting::EarDetection,
            HeadsetSetting::TouchEnabled,
            HeadsetSetting::AncLevel,
            HeadsetSetting::TransparencyLevel,
            HeadsetSetting::PromptVolume,
        ]),
        explorer.writable_settings
    );
    let noise = explorer.noise_control.expect("noise control");
    assert_eq!(
        BTreeSet::from([
            "anc".to_string(),
            "normal".to_string(),
            "transparency".to_string()
        ]),
        noise.modes
    );
    let anc = noise.anc_level.expect("anc level");
    assert_eq!(BTreeSet::from([1, 3, 5]), anc.levels);
    assert_eq!(BTreeSet::from(["anc".to_string()]), anc.visible_modes);
    let transparency = noise.transparency_level.expect("transparency level");
    assert_eq!(BTreeSet::from([1, 3, 5]), transparency.levels);
    assert_eq!(
        BTreeSet::from(["transparency".to_string()]),
        transparency.visible_modes
    );
    assert_eq!(
        (1..=5).collect::<BTreeSet<_>>(),
        explorer.prompt_volume_levels
    );

    assert_eq!(
        BTreeSet::from([
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
            HeadsetField::LeftCharging,
            HeadsetField::RightCharging,
            HeadsetField::Firmware,
            HeadsetField::Eq,
            HeadsetField::TouchKeys,
            HeadsetField::GameMode,
            HeadsetField::EarDetection,
            HeadsetField::PromptLanguage,
            HeadsetField::AudioMode,
            HeadsetField::Multipoint,
            HeadsetField::NoiseMode,
            HeadsetField::AncLevel,
            HeadsetField::TransparencyLevel,
            HeadsetField::PromptVolume,
            HeadsetField::TouchEnabled,
        ]),
        ProtocolFamily::F8.readable_fields()
    );
    assert_eq!(0..=9, ProtocolFamily::F8.prompt_volume_levels());
}

#[test]
fn initialization_uses_documented_queries_and_capacity() {
    let mut agate = codec(ModelProfile::AgateF8);
    assert!(matches!(
        agate.init_queries(5, connect_time()),
        Err(ProtocolError::InvalidArgument(_))
    ));
    let queries = agate.init_queries(6, connect_time()).expect("init queries");
    assert_eq!(
        vec![
            "002701000101",
            "00270100010c",
            "002701000104",
            "002701000108",
            "00270100010b",
            "002701000102",
            "022701000105",
            "002701000109",
            "002701000113",
            "002701000111",
            "002701000116",
        ],
        queries
            .iter()
            .map(|query| encode_hex(&query.bytes))
            .collect::<Vec<_>>()
    );
    assert_eq!(
        vec![
            "01", "0c", "04", "08", "0b", "02", "05", "09", "13", "11", "16"
        ],
        queries
            .iter()
            .map(|query| query.key.as_str())
            .collect::<Vec<_>>()
    );
    for query in &queries {
        assert_eq!(BTreeSet::from([query.key.clone()]), query.response_keys);
        assert_eq!(0, query.delay_before_millis);
    }
    assert_eq!(
        BTreeSet::from([
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
            HeadsetField::LeftCharging,
            HeadsetField::RightCharging,
        ]),
        queries[0].expected_fields
    );
    assert_eq!(
        BTreeSet::from([HeadsetField::AudioMode, HeadsetField::Multipoint]),
        queries[4].expected_fields
    );
    assert_eq!(
        BTreeSet::from([HeadsetField::TouchKeys]),
        queries[6].expected_fields
    );

    for profile in [
        ModelProfile::AgateF8,
        ModelProfile::AgateXF8,
        ModelProfile::CeramicsF8,
    ] {
        assert_eq!(11, query_hexes(profile).len(), "{profile:?}");
    }
    for profile in [
        ModelProfile::CeramicsXF8,
        ModelProfile::CeramicsX7034F8,
        ModelProfile::ExplorerF8,
    ] {
        let hexes = query_hexes(profile);
        assert_eq!(12, hexes.len(), "{profile:?}");
        assert_eq!("00270100010a", hexes[11]);
    }
}

#[test]
fn settings_use_independent_documented_frames_and_readbacks() {
    let anc = prepared(
        ModelProfile::CeramicsXF8,
        HeadsetSetting::NoiseMode,
        FieldValue::text("anc"),
    );
    assert_eq!(
        vec!["002c01000101".to_string()],
        anc.write_frames
            .iter()
            .map(|f| encode_hex(f))
            .collect::<Vec<_>>()
    );
    assert_eq!("00270100010c", encode_hex(&anc.readback.bytes));
    assert_eq!(
        BTreeMap::from([(HeadsetField::NoiseMode, FieldValue::text("anc"))]),
        anc.expected_values
    );
    assert!(anc.additional_readbacks.is_empty());

    for (profile, mode, literal) in [
        (ModelProfile::ExplorerF8, "normal", "002c01000100"),
        (ModelProfile::ExplorerF8, "anc", "002c01000101"),
        (ModelProfile::ExplorerF8, "transparency", "002c01000102"),
        (ModelProfile::CeramicsXF8, "normal", "002c01000103"),
        (ModelProfile::CeramicsX7034F8, "normal", "002c01000103"),
        (ModelProfile::CeramicsXF8, "transparency", "002c01000102"),
        (ModelProfile::CeramicsXF8, "wind", "002c01000100"),
    ] {
        let setting = prepared(profile, HeadsetSetting::NoiseMode, FieldValue::text(mode));
        assert_eq!(
            literal,
            encode_hex(&setting.write_frames[0]),
            "{profile:?} {mode}"
        );
    }
    assert!(matches!(
        prepare(
            ModelProfile::ExplorerF8,
            HeadsetSetting::NoiseMode,
            FieldValue::text("wind")
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));

    for (preset, literal) in [
        ("hifi", "00200100020000"),
        ("pop", "00200100020001"),
        ("rock", "00200100020002"),
    ] {
        let setting = prepared(
            ModelProfile::CeramicsXF8,
            HeadsetSetting::Eq,
            FieldValue::text(preset),
        );
        assert_eq!(literal, encode_hex(&setting.write_frames[0]), "{preset}");
        assert_eq!("002701000104", encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeMap::from([(HeadsetField::Eq, FieldValue::text(preset))]),
            setting.expected_values
        );
    }
    assert!(matches!(
        prepare(
            ModelProfile::AgateF8,
            HeadsetSetting::Eq,
            FieldValue::text("rock")
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));
    assert!(matches!(
        prepare(
            ModelProfile::AgateF8,
            HeadsetSetting::Eq,
            FieldValue::Toggle(true)
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));

    let touch = prepared(
        ModelProfile::CeramicsXF8,
        HeadsetSetting::TouchBinding,
        FieldValue::touch_assignment("right", "long", "noise_cycle"),
    );
    assert_eq!("0022010003080109", encode_hex(&touch.write_frames[0]));
    assert_eq!("022701000105", encode_hex(&touch.readback.bytes));
    assert_eq!(
        BTreeMap::from([(
            HeadsetField::TouchKeys,
            FieldValue::touch_assignment("right", "long", "noise_cycle")
        )]),
        touch.expected_values
    );

    assert_eq!(
        Some(ProtocolError::Unsupported("该型号不支持此设置".to_string())),
        prepare(
            ModelProfile::AgateXF8,
            HeadsetSetting::GameMode,
            FieldValue::Toggle(true)
        )
        .err()
    );
}

#[test]
fn touch_binding_writes_encode_slot_gesture_side_and_action_codes() {
    for (side, gesture, action, literal) in [
        ("left", "single", "voice_assistant", "0022010003010102"),
        ("right", "single", "previous_track", "0022010003020103"),
        ("left", "double", "next_track", "0022010003030104"),
        ("right", "double", "volume_up", "0022010003040105"),
        ("left", "triple", "none", "0022010003050100"),
        ("right", "triple", "play_pause", "0022010003060107"),
        ("left", "long", "volume_down", "0022010003070106"),
        ("right", "long", "noise_cycle", "0022010003080109"),
    ] {
        let setting = prepared(
            ModelProfile::ExplorerF8,
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment(side, gesture, action),
        );
        assert_eq!(
            literal,
            encode_hex(&setting.write_frames[0]),
            "{side} {gesture} {action}"
        );
    }

    assert!(matches!(
        prepare(
            ModelProfile::AgateF8,
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("left", "triple", "none")
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));
    assert!(matches!(
        prepare(
            ModelProfile::AgateXF8,
            HeadsetSetting::TouchBinding,
            FieldValue::touch_assignment("left", "single", "noise_cycle")
        ),
        Err(ProtocolError::Unsupported(_))
    ));
    assert!(matches!(
        prepare(
            ModelProfile::AgateF8,
            HeadsetSetting::TouchBinding,
            FieldValue::Toggle(true)
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));
}

#[test]
fn toggle_settings_use_their_own_commands_and_readback_queries() {
    for (profile, setting, command, readback, field) in [
        (
            ModelProfile::AgateF8,
            HeadsetSetting::GameMode,
            "0025010001",
            "002701000108",
            HeadsetField::GameMode,
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetSetting::GameMode,
            "0025010001",
            "002701000108",
            HeadsetField::GameMode,
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetSetting::EarDetection,
            "0026010001",
            "002701000109",
            HeadsetField::EarDetection,
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetSetting::TouchEnabled,
            "0033010001",
            "002701000116",
            HeadsetField::TouchEnabled,
        ),
    ] {
        for (enabled, byte) in [(true, "01"), (false, "00")] {
            let prepared = prepared(profile, setting, FieldValue::Toggle(enabled));
            assert_eq!(
                format!("{command}{byte}"),
                encode_hex(&prepared.write_frames[0])
            );
            assert_eq!(readback, encode_hex(&prepared.readback.bytes));
            assert_eq!(
                BTreeMap::from([(field, FieldValue::Toggle(enabled))]),
                prepared.expected_values
            );
        }
        assert!(matches!(
            prepare(profile, setting, FieldValue::Number(1)),
            Err(ProtocolError::InvalidArgument(_))
        ));
    }
    for setting in [HeadsetSetting::EarDetection, HeadsetSetting::TouchEnabled] {
        assert!(matches!(
            prepare(ModelProfile::CeramicsXF8, setting, FieldValue::Toggle(true)),
            Err(ProtocolError::Unsupported(_))
        ));
    }
}

#[test]
fn synthetic_replies_are_buffered_and_decoded_without_checksum() {
    let frame = hex_bytes("0027010005010050da32");
    let mut codec = codec(ModelProfile::CeramicsXF8);
    assert!(codec.feed_notification(&frame[..4]).is_empty());
    let reply = codec
        .feed_notification(&frame[4..])
        .pop()
        .expect("buffered reply");
    assert_eq!(BTreeSet::from(["01".to_string()]), reply.response_keys);
    assert_eq!("0027010005010050da32", reply.raw_hex);
    assert_eq!(
        &FieldValue::Number(80),
        known(&reply, HeadsetField::LeftBattery)
    );
    assert_eq!(
        &FieldValue::Number(90),
        known(&reply, HeadsetField::RightBattery)
    );
    assert_eq!(
        &FieldValue::Number(50),
        known(&reply, HeadsetField::CaseBattery)
    );
    assert_eq!(
        &FieldValue::Toggle(false),
        known(&reply, HeadsetField::LeftCharging)
    );
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&reply, HeadsetField::RightCharging)
    );
    assert_eq!(
        Some(&FieldReading::known(FieldValue::Number(90), "da")),
        reply.readings.get(&HeadsetField::RightBattery)
    );
    assert!(reply.accepted);

    assert!(codec.feed_notification(&frame[..4]).is_empty());
    codec.reset();
    assert!(codec.feed_notification(&frame[4..]).is_empty());
}

#[test]
fn battery_reply_respects_layout_and_rejects_out_of_range_levels() {
    let frame = "0027010005010050da32";
    let agate = reply_for(ModelProfile::AgateF8, frame);
    assert!(!agate.readings.contains_key(&HeadsetField::CaseBattery));
    assert_eq!(
        &FieldValue::Number(80),
        known(&agate, HeadsetField::LeftBattery)
    );
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&agate, HeadsetField::RightCharging)
    );

    let over = reply_for(ModelProfile::CeramicsXF8, "00270100050100657f64");
    assert_unknown(&over, HeadsetField::LeftBattery);
    assert_eq!(
        Some(&FieldReading::unknown("65")),
        over.readings.get(&HeadsetField::LeftBattery)
    );
    assert_unknown(&over, HeadsetField::RightBattery);
    assert_eq!(
        &FieldValue::Number(100),
        known(&over, HeadsetField::CaseBattery)
    );

    let full = reply_for(ModelProfile::CeramicsXF8, "0027010005010000e4ff");
    assert_eq!(
        &FieldValue::Number(0),
        known(&full, HeadsetField::LeftBattery)
    );
    assert_eq!(
        &FieldValue::Number(100),
        known(&full, HeadsetField::RightBattery)
    );
    assert_unknown(&full, HeadsetField::CaseBattery);

    let short = reply_for(ModelProfile::CeramicsXF8, "00270100040100505a");
    assert!(short.readings.is_empty());
    assert!(!short.accepted);
}

#[test]
fn notifications_resynchronize_split_and_batched_frames() {
    let mut codec = codec(ModelProfile::CeramicsXF8);
    let replies = codec.feed_notification(&hex_bytes("ffee0027010005010050da320028010003080001"));
    assert_eq!(2, replies.len());
    assert_eq!(BTreeSet::from(["01".to_string()]), replies[0].response_keys);
    assert_eq!(BTreeSet::from(["08".to_string()]), replies[1].response_keys);
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&replies[1], HeadsetField::GameMode)
    );

    let tail = hex_bytes("0028010003040001");
    assert!(codec.feed_notification(&tail[..6]).is_empty());
    let replies = codec.feed_notification(&tail[6..]);
    assert_eq!(1, replies.len());
    assert_eq!(BTreeSet::from(["04".to_string()]), replies[0].response_keys);

    assert!(codec.feed_notification(&hex_bytes("ffffffff")).is_empty());
    assert!(codec.feed_notification(&[]).is_empty());
}

#[test]
fn unknown_short_and_truncated_replies_cannot_invent_state() {
    let mut codec = codec(ModelProfile::ExplorerF8);

    let unknown = single_reply(codec.as_mut(), "0027010003ee0001");
    assert!(unknown.response_keys.is_empty());
    assert!(unknown.readings.is_empty());
    assert!(unknown.warnings.is_empty());
    assert!(!unknown.accepted);

    let empty = single_reply(codec.as_mut(), "0027010000");
    assert!(empty.response_keys.is_empty());
    assert!(empty.readings.is_empty());
    assert!(!empty.accepted);

    let truncated = single_reply(codec.as_mut(), "00270100020800");
    assert_eq!(BTreeSet::from(["08".to_string()]), truncated.response_keys);
    assert!(truncated.readings.is_empty());
    assert_eq!(vec!["08 字段被截断".to_string()], truncated.warnings);
    assert!(!truncated.accepted);
}

#[test]
fn agate_readback_two_uses_the_same_preset_as_one() {
    for profile in [ModelProfile::AgateF8, ModelProfile::AgateXF8] {
        let mut codec = codec(profile);
        for reply in ["0028010003040001", "0028010003040002"] {
            let reply = single_reply(codec.as_mut(), reply);
            assert_eq!(&FieldValue::text("pop"), known(&reply, HeadsetField::Eq));
        }
        let reply = single_reply(codec.as_mut(), "0028010003040000");
        assert_eq!(&FieldValue::text("hifi"), known(&reply, HeadsetField::Eq));
        let fields = profile.capabilities(None, None).readable_fields;
        assert!(!fields.contains(&HeadsetField::CaseBattery));
        assert!(!fields.contains(&HeadsetField::TransparencyLevel));
        assert!(fields.contains(&HeadsetField::LeftBattery));
    }
    for profile in [
        ModelProfile::CeramicsXF8,
        ModelProfile::CeramicsX7034F8,
        ModelProfile::ExplorerF8,
    ] {
        let reply = reply_for(profile, "0028010003040002");
        assert_eq!(&FieldValue::text("rock"), known(&reply, HeadsetField::Eq));
    }
}

#[test]
fn synthetic_unknown_values_do_not_confirm_settings() {
    let ambiguous = reply_for(ModelProfile::CeramicsF8, "0028010003040002");
    assert_unknown(&ambiguous, HeadsetField::Eq);
    assert!(!ambiguous.accepted);

    let out_of_range = reply_for(ModelProfile::CeramicsXF8, "0028010003040003");
    assert_unknown(&out_of_range, HeadsetField::Eq);
    assert!(!out_of_range.accepted);

    let invalid = reply_for(ModelProfile::ExplorerF8, "00270100030c00ff");
    assert_unknown(&invalid, HeadsetField::NoiseMode);
    assert!(!invalid.accepted);

    let game = reply_for(ModelProfile::AgateF8, "0027010003080002");
    assert_unknown(&game, HeadsetField::GameMode);
    assert!(!game.accepted);
}

fn known_values(reply: &ProtocolReply) -> BTreeMap<HeadsetField, FieldValue> {
    reply
        .readings
        .iter()
        .map(|(&field, reading)| (field, reading.known_value().expect("known reading").clone()))
        .collect()
}

#[test]
fn firmware_reply_combines_two_big_endian_bytes_and_needs_the_full_frame() {
    let reply = reply_for(
        ModelProfile::CeramicsXF8,
        &reply_frame(11, 0x02, &[(9, 0x01), (10, 0x2c)]),
    );
    assert_eq!(BTreeSet::from(["02".to_string()]), reply.response_keys);
    assert_eq!(
        Some(&FieldReading::known(
            FieldValue::Firmware(FirmwareVersion::new(vec![300], "300")),
            "01"
        )),
        reply.readings.get(&HeadsetField::Firmware)
    );
    assert!(reply.accepted);

    let high = reply_for(
        ModelProfile::AgateF8,
        &reply_frame(11, 0x02, &[(9, 0x02), (10, 0x05)]),
    );
    assert_eq!(
        &FieldValue::Firmware(FirmwareVersion::new(vec![517], "517")),
        known(&high, HeadsetField::Firmware)
    );

    let short = reply_for(
        ModelProfile::CeramicsXF8,
        &reply_frame(10, 0x02, &[(9, 0x01)]),
    );
    assert_eq!(BTreeSet::from(["02".to_string()]), short.response_keys);
    assert!(short.readings.is_empty());
    assert!(!short.accepted);
}

#[test]
fn audio_and_multipoint_readback_confirms_both_coupled_fields() {
    let mut codec = codec(ModelProfile::CeramicsXF8);
    let empty = BTreeMap::new();
    let ldac = codec
        .prepare_setting(
            HeadsetSetting::AudioMode,
            &FieldValue::text("ldac"),
            None,
            &empty,
        )
        .expect("ldac");
    assert_eq!("002b01000101", encode_hex(&ldac.write_frames[0]));
    assert_eq!("00270100010b", encode_hex(&ldac.readback.bytes));
    let audio = single_reply(codec.as_mut(), "00270100030b0001");
    let expected_audio = BTreeMap::from([
        (HeadsetField::AudioMode, FieldValue::text("ldac")),
        (HeadsetField::Multipoint, FieldValue::Toggle(false)),
    ]);
    assert_eq!(expected_audio, known_values(&audio));
    assert_eq!(expected_audio, ldac.expected_values);

    let enabled = codec
        .prepare_setting(
            HeadsetSetting::Multipoint,
            &FieldValue::Toggle(true),
            None,
            &empty,
        )
        .expect("multipoint on");
    assert_eq!("002b01000100", encode_hex(&enabled.write_frames[0]));
    let both = single_reply(codec.as_mut(), "00270100030b0000");
    let expected_both = BTreeMap::from([
        (HeadsetField::AudioMode, FieldValue::text("aac_sbc")),
        (HeadsetField::Multipoint, FieldValue::Toggle(true)),
    ]);
    assert_eq!(expected_both, known_values(&both));
    assert_eq!(expected_both, enabled.expected_values);

    let disabled = codec
        .prepare_setting(
            HeadsetSetting::Multipoint,
            &FieldValue::Toggle(false),
            None,
            &empty,
        )
        .expect("multipoint off");
    assert_eq!("002b01000102", encode_hex(&disabled.write_frames[0]));
    assert_eq!(
        Some(&FieldValue::text("aac_sbc")),
        disabled.expected_values.get(&HeadsetField::AudioMode)
    );
    assert_eq!(
        Some(&FieldValue::Toggle(false)),
        disabled.expected_values.get(&HeadsetField::Multipoint)
    );
    let wire_two = single_reply(codec.as_mut(), "00270100030b0002");
    assert_eq!(
        BTreeMap::from([
            (HeadsetField::AudioMode, FieldValue::text("aac_sbc")),
            (HeadsetField::Multipoint, FieldValue::Toggle(false)),
        ]),
        known_values(&wire_two)
    );

    let unknown = single_reply(codec.as_mut(), "00270100030b0003");
    assert_unknown(&unknown, HeadsetField::AudioMode);
    assert_unknown(&unknown, HeadsetField::Multipoint);
    assert!(!unknown.accepted);

    assert!(matches!(
        prepare(
            ModelProfile::CeramicsXF8,
            HeadsetSetting::AudioMode,
            FieldValue::text("lhdc")
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));
    assert!(matches!(
        prepare(
            ModelProfile::CeramicsXF8,
            HeadsetSetting::Multipoint,
            FieldValue::text("on")
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));
    assert!(matches!(
        prepare(
            ModelProfile::AgateF8,
            HeadsetSetting::AudioMode,
            FieldValue::text("ldac")
        ),
        Err(ProtocolError::Unsupported(_))
    ));
}

#[test]
fn explorer_language_and_detection_use_independent_queries_and_reject_unknown_readings() {
    let mut codec = codec(ModelProfile::ExplorerF8);
    for (language, literal) in [("english", "002901000101"), ("chinese", "002901000100")] {
        let setting = prepared(
            ModelProfile::ExplorerF8,
            HeadsetSetting::PromptLanguage,
            FieldValue::text(language),
        );
        assert_eq!(literal, encode_hex(&setting.write_frames[0]));
        assert_eq!("00270100010a", encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeMap::from([(HeadsetField::PromptLanguage, FieldValue::text(language))]),
            setting.expected_values
        );
    }
    let english = single_reply(codec.as_mut(), "00270100030a0001");
    assert_eq!(
        &FieldValue::text("english"),
        known(&english, HeadsetField::PromptLanguage)
    );
    let chinese = single_reply(codec.as_mut(), "00270100030a0000");
    assert_eq!(
        &FieldValue::text("chinese"),
        known(&chinese, HeadsetField::PromptLanguage)
    );
    let invalid_language = single_reply(codec.as_mut(), "00270100030a0005");
    assert_unknown(&invalid_language, HeadsetField::PromptLanguage);
    assert!(!invalid_language.accepted);
    for value in [FieldValue::text("french"), FieldValue::Number(1)] {
        assert!(matches!(
            prepare(
                ModelProfile::ExplorerF8,
                HeadsetSetting::PromptLanguage,
                value
            ),
            Err(ProtocolError::InvalidArgument(_))
        ));
    }
    assert!(matches!(
        prepare(
            ModelProfile::AgateF8,
            HeadsetSetting::PromptLanguage,
            FieldValue::text("english")
        ),
        Err(ProtocolError::Unsupported(_))
    ));

    let enabled = single_reply(codec.as_mut(), "0027010003090001");
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&enabled, HeadsetField::EarDetection)
    );
    let invalid = single_reply(codec.as_mut(), "00270100030900ff");
    assert_unknown(&invalid, HeadsetField::EarDetection);

    let basic = reply_for(ModelProfile::AgateF8, "0027010003090001");
    assert_eq!(BTreeSet::from(["09".to_string()]), basic.response_keys);
    assert!(basic.readings.is_empty());
}

#[test]
fn explorer_touch_enabled_is_decoded_only_for_models_that_expose_it() {
    let mut explorer = codec(ModelProfile::ExplorerF8);
    let on = single_reply(explorer.as_mut(), "0027010003160001");
    assert_eq!(
        &FieldValue::Toggle(true),
        known(&on, HeadsetField::TouchEnabled)
    );
    let off = single_reply(explorer.as_mut(), "0027010003160000");
    assert_eq!(
        &FieldValue::Toggle(false),
        known(&off, HeadsetField::TouchEnabled)
    );
    let invalid = single_reply(explorer.as_mut(), "0027010003160002");
    assert_unknown(&invalid, HeadsetField::TouchEnabled);

    for profile in [ModelProfile::CeramicsXF8, ModelProfile::CeramicsX7034F8] {
        let reply = reply_for(profile, "0027010003160001");
        assert_eq!(BTreeSet::from(["16".to_string()]), reply.response_keys);
        assert!(reply.readings.is_empty(), "{profile:?}");
        assert!(!reply.accepted);
    }
}

#[test]
fn explorer_prompt_volume_uses_ding_level_and_leaves_off_closed() {
    assert_eq!(
        (1..=5).collect::<BTreeSet<_>>(),
        ModelProfile::ExplorerF8
            .capabilities(None, None)
            .prompt_volume_levels
    );
    for level in 1..=5 {
        let setting = prepared(
            ModelProfile::ExplorerF8,
            HeadsetSetting::PromptVolume,
            FieldValue::Number(level),
        );
        assert_eq!(
            format!("00320100010{level}"),
            encode_hex(&setting.write_frames[0])
        );
        assert_eq!("002701000113", encode_hex(&setting.readback.bytes));
        assert_eq!(
            BTreeMap::from([(HeadsetField::PromptVolume, FieldValue::Number(level))]),
            setting.expected_values
        );
    }
    let reply = reply_for(ModelProfile::ExplorerF8, "0027010003130003");
    assert_eq!(
        &FieldValue::Number(3),
        known(&reply, HeadsetField::PromptVolume)
    );
    let widest = reply_for(ModelProfile::ExplorerF8, "0027010003130009");
    assert_eq!(
        &FieldValue::Number(9),
        known(&widest, HeadsetField::PromptVolume)
    );
    let beyond = reply_for(ModelProfile::ExplorerF8, "002701000313000a");
    assert_eq!(BTreeSet::from(["13".to_string()]), beyond.response_keys);
    assert!(beyond.readings.is_empty());

    for level in [0, 6] {
        assert!(
            matches!(
                prepare(
                    ModelProfile::ExplorerF8,
                    HeadsetSetting::PromptVolume,
                    FieldValue::Number(level)
                ),
                Err(ProtocolError::Unsupported(_))
            ),
            "level {level}"
        );
    }
    for value in [
        FieldValue::Number(10),
        FieldValue::Number(-1),
        FieldValue::text("3"),
    ] {
        assert!(matches!(
            prepare(
                ModelProfile::ExplorerF8,
                HeadsetSetting::PromptVolume,
                value
            ),
            Err(ProtocolError::InvalidArgument(_))
        ));
    }
}

#[test]
fn explorer_noise_levels_invert_on_write_and_decode_on_fresh_readback() {
    for (level, wire) in [(1, 2), (3, 1), (5, 0)] {
        let anc = prepared(
            ModelProfile::ExplorerF8,
            HeadsetSetting::AncLevel,
            FieldValue::Number(level),
        );
        assert_eq!(
            format!("00300100010{wire}"),
            encode_hex(&anc.write_frames[0])
        );
        assert_eq!("002701000111", encode_hex(&anc.readback.bytes));
        assert_eq!(
            BTreeMap::from([(HeadsetField::AncLevel, FieldValue::Number(level))]),
            anc.expected_values
        );
        let transparency = prepared(
            ModelProfile::ExplorerF8,
            HeadsetSetting::TransparencyLevel,
            FieldValue::Number(level),
        );
        assert_eq!(
            format!("00310100010{wire}"),
            encode_hex(&transparency.write_frames[0])
        );
        assert_eq!("002701000112", encode_hex(&transparency.readback.bytes));
        assert_eq!(
            BTreeMap::from([(HeadsetField::TransparencyLevel, FieldValue::Number(level))]),
            transparency.expected_values
        );
    }
    let mut codec = codec(ModelProfile::ExplorerF8);
    for (wire, level) in [(0, 5), (1, 3), (2, 1)] {
        let anc = single_reply(codec.as_mut(), &format!("00270100031100{wire:02x}"));
        assert_eq!(
            &FieldValue::Number(level),
            known(&anc, HeadsetField::AncLevel)
        );
        assert!(anc.accepted);
        let transparency = single_reply(codec.as_mut(), &format!("00270100031200{wire:02x}"));
        assert_eq!(
            &FieldValue::Number(level),
            known(&transparency, HeadsetField::TransparencyLevel)
        );
        assert!(transparency.accepted);
    }
    let out_of_range = single_reply(codec.as_mut(), "0027010003110003");
    assert_unknown(&out_of_range, HeadsetField::AncLevel);
    assert!(!out_of_range.accepted);

    assert!(matches!(
        prepare(
            ModelProfile::ExplorerF8,
            HeadsetSetting::AncLevel,
            FieldValue::Number(2)
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));
    assert!(matches!(
        prepare(
            ModelProfile::ExplorerF8,
            HeadsetSetting::TransparencyLevel,
            FieldValue::Number(0)
        ),
        Err(ProtocolError::InvalidArgument(_))
    ));
}

#[test]
fn ceramics_x_models_keep_noise_levels_and_prompt_volume_closed() {
    for profile in [ModelProfile::CeramicsXF8, ModelProfile::CeramicsX7034F8] {
        for setting in [
            HeadsetSetting::AncLevel,
            HeadsetSetting::TransparencyLevel,
            HeadsetSetting::PromptVolume,
        ] {
            assert!(
                matches!(
                    prepare(profile, setting, FieldValue::Number(3)),
                    Err(ProtocolError::Unsupported(_))
                ),
                "{profile:?} {setting:?}"
            );
        }
        for (tag, field) in [
            ("11", HeadsetField::AncLevel),
            ("12", HeadsetField::TransparencyLevel),
        ] {
            let reply = reply_for(profile, &format!("0027010003{tag}0001"));
            assert_unknown(&reply, field);
            assert!(!reply.accepted);
        }
    }
}

#[test]
fn noise_mode_replies_decode_normal_by_each_models_wire_value() {
    for (profile, wire, expected) in [
        (ModelProfile::CeramicsXF8, 0, Some("wind")),
        (ModelProfile::CeramicsXF8, 1, Some("anc")),
        (ModelProfile::CeramicsXF8, 2, Some("transparency")),
        (ModelProfile::CeramicsXF8, 6, Some("normal")),
        (ModelProfile::CeramicsXF8, 3, None),
        (ModelProfile::CeramicsX7034F8, 6, Some("normal")),
        (ModelProfile::ExplorerF8, 0, Some("normal")),
        (ModelProfile::ExplorerF8, 1, Some("anc")),
        (ModelProfile::ExplorerF8, 2, Some("transparency")),
        (ModelProfile::ExplorerF8, 6, None),
        (ModelProfile::ExplorerF8, 3, None),
    ] {
        let reply = reply_for(profile, &format!("00270100030c00{wire:02x}"));
        match expected {
            Some(mode) => assert_eq!(
                &FieldValue::text(mode),
                known(&reply, HeadsetField::NoiseMode),
                "{profile:?} {wire}"
            ),
            None => assert_unknown(&reply, HeadsetField::NoiseMode),
        }
    }
}

#[test]
fn extended_touch_reply_decodes_eight_slots_from_low_nibbles() {
    let hex = reply_frame(
        32,
        0x05,
        &[
            (7, 0x01),
            (9, 0xf7),
            (15, 0x03),
            (21, 0x04),
            (27, 0x09),
            (12, 0x05),
            (18, 0x06),
            (24, 0x02),
            (30, 0x08),
        ],
    );
    for profile in [
        ModelProfile::CeramicsXF8,
        ModelProfile::CeramicsX7034F8,
        ModelProfile::ExplorerF8,
    ] {
        let reply = reply_for(profile, &hex);
        assert_eq!(BTreeSet::from(["05".to_string()]), reply.response_keys);
        assert_eq!(
            Some(&FieldReading::known(
                FieldValue::TouchKeys(vec![
                    binding("left", "single", Some("play_pause"), 7),
                    binding("left", "double", Some("previous_track"), 3),
                    binding("left", "triple", Some("next_track"), 4),
                    binding("left", "long", Some("noise_cycle"), 9),
                    binding("right", "single", Some("volume_up"), 5),
                    binding("right", "double", Some("volume_down"), 6),
                    binding("right", "triple", Some("voice_assistant"), 2),
                    binding("right", "long", None, 8),
                ]),
                "01"
            )),
            reply.readings.get(&HeadsetField::TouchKeys),
            "{profile:?}"
        );
        assert!(reply.accepted);
    }

    let short = reply_for(
        ModelProfile::CeramicsXF8,
        &reply_frame(30, 0x05, &[(7, 0x01)]),
    );
    assert_eq!(BTreeSet::from(["05".to_string()]), short.response_keys);
    assert!(short.readings.is_empty());
}

#[test]
fn basic_touch_reply_decodes_six_slots_without_triple_gesture() {
    let hex = reply_frame(
        13,
        0x05,
        &[
            (7, 0x07),
            (9, 0x03),
            (11, 0x04),
            (8, 0x05),
            (10, 0x06),
            (12, 0x09),
        ],
    );
    for profile in [
        ModelProfile::AgateF8,
        ModelProfile::AgateXF8,
        ModelProfile::CeramicsF8,
    ] {
        let reply = reply_for(profile, &hex);
        assert_eq!(
            Some(&FieldReading::known(
                FieldValue::TouchKeys(vec![
                    binding("left", "single", Some("play_pause"), 7),
                    binding("left", "double", Some("previous_track"), 3),
                    binding("left", "long", Some("next_track"), 4),
                    binding("right", "single", Some("volume_up"), 5),
                    binding("right", "double", Some("volume_down"), 6),
                    binding("right", "long", Some("noise_cycle"), 9),
                ]),
                "07"
            )),
            reply.readings.get(&HeadsetField::TouchKeys),
            "{profile:?}"
        );
        assert!(reply.accepted);
    }

    let short = reply_for(ModelProfile::AgateF8, &reply_frame(12, 0x05, &[(7, 0x07)]));
    assert_eq!(BTreeSet::from(["05".to_string()]), short.response_keys);
    assert!(short.readings.is_empty());
}

#[test]
fn touch_readback_confirms_only_the_requested_binding() {
    let setting = prepared(
        ModelProfile::CeramicsXF8,
        HeadsetSetting::TouchBinding,
        FieldValue::touch_assignment("right", "long", "noise_cycle"),
    );
    let expected = setting
        .expected_values
        .get(&HeadsetField::TouchKeys)
        .expect("touch expectation");
    let frame_with_right_long = |code: u8| {
        reply_frame(
            32,
            0x05,
            &[
                (7, 0x01),
                (9, 0x07),
                (15, 0x03),
                (21, 0x04),
                (27, 0x09),
                (12, 0x05),
                (18, 0x06),
                (24, 0x02),
                (30, code),
            ],
        )
    };
    let confirmed = reply_for(ModelProfile::CeramicsXF8, &frame_with_right_long(0x09));
    assert_eq!(
        Some(FieldValue::touch_assignment("right", "long", "noise_cycle")),
        confirmation_value(confirmed.readings.get(&HeadsetField::TouchKeys), expected)
    );
    let unchanged = reply_for(ModelProfile::CeramicsXF8, &frame_with_right_long(0x07));
    assert_eq!(
        Some(FieldValue::touch_assignment("right", "long", "play_pause")),
        confirmation_value(unchanged.readings.get(&HeadsetField::TouchKeys), expected)
    );
    let unmapped = reply_for(ModelProfile::CeramicsXF8, &frame_with_right_long(0x08));
    assert_eq!(
        None,
        confirmation_value(unmapped.readings.get(&HeadsetField::TouchKeys), expected)
    );
}

#[test]
fn f8_command_validation_separates_firmware_range_unsupported_and_invalid_arguments() {
    let explorer = HeadsetReadings::new(ModelProfile::ExplorerF8);
    assert_eq!(Ok(()), explorer.validate(&HeadsetCommand::PromptVolume(3)));
    for level in [0, 6] {
        let error = explorer
            .validate(&HeadsetCommand::PromptVolume(level))
            .expect_err("level outside the model range");
        assert_eq!(ValidationErrorKind::Unsupported, error.kind, "{level}");
    }
    let error = explorer
        .validate(&HeadsetCommand::PromptVolume(10))
        .expect_err("level outside the family range");
    assert_eq!(ValidationErrorKind::InvalidArgument, error.kind);

    let agate_x = HeadsetReadings::new(ModelProfile::AgateXF8);
    let error = agate_x
        .validate(&HeadsetCommand::GameMode(true))
        .expect_err("game mode is not writable on AGATE X");
    assert_eq!(ValidationErrorKind::Unsupported, error.kind);

    let ceramics_x = HeadsetReadings::new(ModelProfile::CeramicsXF8);
    let error = ceramics_x
        .validate(&HeadsetCommand::PromptVolume(3))
        .expect_err("prompt volume is not writable on CERAMICS-X");
    assert_eq!(ValidationErrorKind::Unsupported, error.kind);
}
