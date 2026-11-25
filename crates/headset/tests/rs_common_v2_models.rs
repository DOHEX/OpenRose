use openrose_headset::{
    BatteryLayout, FirmwareVersion, HeadsetField, HeadsetSetting, ModelIdentification,
    ModelProfile, ProtocolFamily, decode_hex,
};
use std::collections::{BTreeMap, BTreeSet};

const RS_V2_MODELS: [(ModelProfile, &str); 32] = [
    (ModelProfile::I7RsV2, "I7_RS_V2"),
    (ModelProfile::BudsFeelMk2RsV2, "BUDSFEEL_MK2_RS_V2"),
    (ModelProfile::OpenFeelMk2RsV2, "OPENFEEL_MK2_RS_V2"),
    (ModelProfile::Swift2RsV2, "SWIFT2_RS_V2"),
    (ModelProfile::JasmineRsV2, "JASMINE_RS_V2"),
    (ModelProfile::CeramicsMk2RsV2, "CERAMICS_MK2_RS_V2"),
    (
        ModelProfile::CeramicsMk28912fRsV2,
        "CERAMICS_MK2_8912F_RS_V2",
    ),
    (ModelProfile::CeramicsMk25736RsV2, "CERAMICS_MK2_5736_RS_V2"),
    (ModelProfile::CeramicsURsV2, "CERAMICS_U_RS_V2"),
    (
        ModelProfile::CeramicsUHsc2710RsV2,
        "CERAMICS_U_HSC_2710_RS_V2",
    ),
    (ModelProfile::SpeakFreeRsV2, "SPEAKFREE_RS_V2"),
    (ModelProfile::MagicianRsV2, "MAGICIAN_RS_V2"),
    (ModelProfile::FurinaRsV2, "FURINA_RS_V2"),
    (ModelProfile::ZirconRsV2, "ZIRCON_RS_V2"),
    (ModelProfile::HyacinthRsV2, "HYACINTH_RS_V2"),
    (ModelProfile::AstroXRsV2, "ASTRO_X_RS_V2"),
    (ModelProfile::OpalRsV2, "OPAL_RS_V2"),
    (ModelProfile::MirrorRsV2, "MIRROR_RS_V2"),
    (ModelProfile::Ky01RsV2, "KY01_RS_V2"),
    (ModelProfile::CoralRsV2, "CORAL_RS_V2"),
    (ModelProfile::PatamonRsV2, "PATAMON_RS_V2"),
    (ModelProfile::TailmonRsV2, "TAILMON_RS_V2"),
    (ModelProfile::GabumonRsV2, "GABUMON_RS_V2"),
    (ModelProfile::AgumonRsV2, "AGUMON_RS_V2"),
    (ModelProfile::SpeakFeel2RsV2, "SPEAKFEEL2_RS_V2"),
    (ModelProfile::OpenFeelLRsV2, "OPENFEEL_L_RS_V2"),
    (ModelProfile::FirstAngelRsV2, "FIRST_ANGEL_RS_V2"),
    (ModelProfile::BudsFeelAeNeoRsV2, "BUDSFEEL_AE_NEO_RS_V2"),
    (ModelProfile::EarFeelIeNeoRsV2, "EARFEEL_IE_NEO_RS_V2"),
    (ModelProfile::EarFeelIe2RsV2, "EARFEEL_IE2_RS_V2"),
    (ModelProfile::SportFeel8912fRsV2, "SPORTFEEL_8912F_RS_V2"),
    (ModelProfile::MokaRsV2, "MOKA_RS_V2"),
];

const SPP_CONTROL_UUID: &str = "0cf12d31-fac3-4553-bd80-d6832e7b3931";

fn fw(parts: &[i32]) -> FirmwareVersion {
    FirmwareVersion::from_parts(parts.to_vec())
}

fn manufacturer_data(entries: &[(u16, &str)]) -> BTreeMap<u16, Vec<u8>> {
    entries
        .iter()
        .map(|&(company, payload)| (company, decode_hex(payload).expect("valid hex")))
        .collect()
}

fn set(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|item| (*item).to_string()).collect()
}

#[test]
fn rs_v2_family_registers_all_thirty_two_models_with_stable_ids() {
    let in_family: BTreeSet<ModelProfile> = ModelProfile::ALL
        .iter()
        .copied()
        .filter(|profile| profile.family() == ProtocolFamily::RsCommonV2)
        .collect();
    let expected: BTreeSet<ModelProfile> =
        RS_V2_MODELS.iter().map(|&(profile, _)| profile).collect();
    assert_eq!(32, expected.len());
    assert_eq!(expected, in_family);
    for (profile, id) in RS_V2_MODELS {
        assert_eq!(id, profile.id());
        assert_eq!(Some(profile), ModelProfile::from_id(id));
        assert_eq!(
            format!("\"{id}\""),
            serde_json::to_string(&profile).expect("serialize profile")
        );
    }
    assert_eq!(Ok(()), ModelIdentification::validate_catalog());
}

#[test]
fn rs_v2_models_share_one_transport_and_expose_the_spp_control_identifier() {
    for (profile, _) in RS_V2_MODELS {
        let spec = profile.spec();
        assert_eq!(
            "000000fe-0000-1000-8000-00805f9b34fb", spec.gatt.service_uuid,
            "{profile:?}"
        );
        assert_eq!(
            "000000f1-0000-1000-8000-00805f9b34fb", spec.gatt.write_uuid,
            "{profile:?}"
        );
        assert_eq!(
            "000000f2-0000-1000-8000-00805f9b34fb", spec.gatt.notify_uuid,
            "{profile:?}"
        );
        assert_eq!(
            Some(SPP_CONTROL_UUID),
            profile.spp_control_uuid(),
            "{profile:?}"
        );
        assert_eq!(100, profile.write_interval_millis(), "{profile:?}");
        assert!(spec.advertised_classic_address.is_some(), "{profile:?}");
        assert_eq!(None, profile.version_name(), "{profile:?}");
        assert!(
            !profile.refresh_equalizer_after_game_mode()
                || matches!(
                    profile,
                    ModelProfile::FirstAngelRsV2 | ModelProfile::MokaRsV2
                )
        );
    }
    assert_eq!(
        Some(SPP_CONTROL_UUID),
        ModelProfile::I7RsV2.spp_control_uuid()
    );
    assert!(ModelProfile::FirstAngelRsV2.refresh_equalizer_after_game_mode());
    assert!(ModelProfile::MokaRsV2.refresh_equalizer_after_game_mode());
}

#[test]
fn rs_v2_battery_layouts_follow_each_models_battery_slots() {
    use BatteryLayout::{EarbudsAndCase, EarbudsOnly, SingleUnit};
    let single = [
        ModelProfile::Swift2RsV2,
        ModelProfile::FirstAngelRsV2,
        ModelProfile::MagicianRsV2,
        ModelProfile::MokaRsV2,
        ModelProfile::SpeakFeel2RsV2,
        ModelProfile::SpeakFreeRsV2,
    ];
    let earbuds_only = [
        ModelProfile::JasmineRsV2,
        ModelProfile::AgumonRsV2,
        ModelProfile::BudsFeelAeNeoRsV2,
        ModelProfile::CeramicsMk25736RsV2,
        ModelProfile::EarFeelIeNeoRsV2,
        ModelProfile::GabumonRsV2,
        ModelProfile::PatamonRsV2,
        ModelProfile::TailmonRsV2,
    ];
    for (profile, _) in RS_V2_MODELS {
        let expected = if single.contains(&profile) {
            SingleUnit
        } else if earbuds_only.contains(&profile) {
            EarbudsOnly
        } else {
            EarbudsAndCase
        };
        assert_eq!(expected, profile.battery_layout(), "{profile:?}");
    }
}

#[test]
fn rs_v2_advertisement_prefixes_match_independent_literals() {
    let expected = [
        (ModelProfile::I7RsV2, "b8e5290f"),
        (ModelProfile::BudsFeelMk2RsV2, "0b150a0b"),
        (ModelProfile::OpenFeelMk2RsV2, "0b0eb657"),
        (ModelProfile::Swift2RsV2, "5999064c"),
        (ModelProfile::JasmineRsV2, "0b0a0a11"),
        (ModelProfile::CeramicsMk2RsV2, "0b0e0a0c"),
        (ModelProfile::CeramicsMk28912fRsV2, "0b0e208b"),
        (ModelProfile::CeramicsMk25736RsV2, "45d7b286"),
        (ModelProfile::CeramicsURsV2, "0b0d0a0b"),
        (ModelProfile::CeramicsUHsc2710RsV2, "0b137957"),
        (ModelProfile::SpeakFreeRsV2, "0b0f0a0b"),
        (ModelProfile::MagicianRsV2, "0b0e0a0d"),
        (ModelProfile::FurinaRsV2, "0b110a0c"),
        (ModelProfile::ZirconRsV2, "0b110a0b"),
        (ModelProfile::HyacinthRsV2, "0b120a0b"),
        (ModelProfile::AstroXRsV2, "0b0e0a0b"),
        (ModelProfile::OpalRsV2, "0b0a0a12"),
        (ModelProfile::MirrorRsV2, "0b0a0a13"),
        (ModelProfile::Ky01RsV2, "0a0b0a0d"),
        (ModelProfile::CoralRsV2, "0b0e0a0e"),
        (ModelProfile::PatamonRsV2, "0b0ae916"),
        (ModelProfile::TailmonRsV2, "0b0a941f"),
        (ModelProfile::GabumonRsV2, "0b0a2aed"),
        (ModelProfile::AgumonRsV2, "0b0a34fd"),
        (ModelProfile::SpeakFeel2RsV2, "0b0e3ce6"),
        (ModelProfile::OpenFeelLRsV2, "45d7cbed"),
        (ModelProfile::FirstAngelRsV2, "0b0e64be"),
        (ModelProfile::BudsFeelAeNeoRsV2, "45d789b0"),
        (ModelProfile::EarFeelIeNeoRsV2, "45d7aa12"),
        (ModelProfile::EarFeelIe2RsV2, "0b154572"),
        (ModelProfile::SportFeel8912fRsV2, "5d2f61f0"),
        (ModelProfile::MokaRsV2, "68f5b8eb"),
    ];
    assert_eq!(32, expected.len());
    for (profile, prefix) in expected {
        assert_eq!(&[prefix], profile.advertisement_prefixes(), "{profile:?}");
        let bytes = decode_hex(&format!("{prefix}99")).expect("prefix hex");
        let company = u16::from_le_bytes([bytes[0], bytes[1]]);
        let data = BTreeMap::from([(company, bytes[2..].to_vec())]);
        assert_eq!(
            Some(profile),
            ModelIdentification::identify(&data),
            "{profile:?}"
        );
    }
    assert_eq!(
        Some(ModelProfile::I7RsV2),
        ModelIdentification::identify(&manufacturer_data(&[(0xe5b8, "290f0100")]))
    );
}

#[test]
fn rs_v2_classic_address_is_read_from_the_advertisement_and_rejects_placeholders() {
    let address = |hex: &str| ModelIdentification::classic_address(hex, Some(ModelProfile::I7RsV2));
    assert_eq!(
        Some("11:22:33:44:55:66".to_string()),
        address("b8e5290f0100aa01000000112233445566")
    );
    assert_eq!(None, address("b8e5290f0100aa01000000000000000000"));
    assert_eq!(None, address("b8e5290f0100aa01000000ffffffffffff"));
    assert_eq!(None, address("b8e5290f0100aa02000000112233445566"));
    assert_eq!(None, address("b8e5290f0100aa010000001122334455"));
    assert_eq!(None, address("b8e5290f0100aa0100000011223344zz66"));
    assert_eq!(
        None,
        ModelIdentification::classic_address("b8e5290f0100aa01000000112233445566", None)
    );
}

#[test]
fn rs_v2_model_names_are_matched_without_crossing_hardware_branches() {
    for (name, expected) in [
        ("ROSE i7", Some(ModelProfile::I7RsV2)),
        ("roseselsa i7", Some(ModelProfile::I7RsV2)),
        ("EarFeel i7", Some(ModelProfile::I7RsV2)),
        ("EarFree i7", Some(ModelProfile::I7RsV2)),
        ("EarFeel", Some(ModelProfile::I7RsV2)),
        ("EarFeel-i5", Some(ModelProfile::I5Zt)),
        ("EarFeeli5", Some(ModelProfile::I5Zt)),
        ("ROSESELSAEarFeeli5", Some(ModelProfile::I5Zt)),
        ("EarFree", Some(ModelProfile::I5Zt)),
        ("BudsFeel MK2", Some(ModelProfile::BudsFeelMk2RsV2)),
        ("ROSE BudsFeel-MK2", Some(ModelProfile::BudsFeelMk2RsV2)),
        ("budsfeel mk2", Some(ModelProfile::BudsFeelMk2RsV2)),
        ("OpenFeel MK2", Some(ModelProfile::OpenFeelMk2RsV2)),
        ("ROSE OpenFeel-MK2", Some(ModelProfile::OpenFeelMk2RsV2)),
        ("openfeel mk2", Some(ModelProfile::OpenFeelMk2RsV2)),
        ("SWIFT 2", Some(ModelProfile::Swift2RsV2)),
        ("SWIFT2", Some(ModelProfile::Swift2RsV2)),
        ("ROSE Swift 2", Some(ModelProfile::Swift2RsV2)),
        ("Jasmine", Some(ModelProfile::JasmineRsV2)),
        ("ROSE OpenFeel-L", Some(ModelProfile::OpenFeelLRsV2)),
        ("OpenFeel L", Some(ModelProfile::OpenFeelLRsV2)),
        ("Mirror", Some(ModelProfile::MirrorRsV2)),
        ("EarFeel iE Neo", Some(ModelProfile::EarFeelIeNeoRsV2)),
        ("BudsFeel AE Neo", Some(ModelProfile::BudsFeelAeNeoRsV2)),
        ("ROSE EARFEEL iE2", Some(ModelProfile::EarFeelIe2RsV2)),
        ("FIRST ANGEL", Some(ModelProfile::FirstAngelRsV2)),
        ("Astro X", Some(ModelProfile::AstroXRsV2)),
        ("MOKA", Some(ModelProfile::MokaRsV2)),
        ("ROSE CERAMICS MK2", None),
        ("CERAMICS U", None),
        ("SpeakFeel", None),
        ("ROSE MK2", None),
        ("OpenFeel", None),
        ("BudsFeel", None),
        ("ROSE Swift 20", None),
        ("ROSE EarFeel iE20", None),
        ("EarFree i8", None),
        ("Screen Mirroring", None),
        ("Opalescent Speaker", None),
        ("Mirrorless Camera", None),
        ("Acoustic Coraline", None),
        ("ThinkPad i7", None),
    ] {
        assert_eq!(expected, ModelProfile::match_name(Some(name)), "{name}");
    }
}

#[test]
fn rs_v2_names_are_admitted_by_the_wake_name_pattern() {
    let pattern = ModelProfile::name_pattern();
    for brand in [
        "earfeel",
        "budsfeel",
        "openfeel",
        "ceramics",
        "astro",
        "hyacinth",
        "speakfeel",
        "firstangel",
        "jasmine",
    ] {
        let spaced = brand
            .chars()
            .map(|c| c.to_string())
            .collect::<Vec<_>>()
            .join("[\\s_-]*");
        assert!(pattern.contains(&spaced), "{brand}");
    }
}

#[test]
fn rs_v2_display_names_and_preset_labels_come_from_model_spec() {
    assert_eq!("EarFeel i7", ModelProfile::I7RsV2.display_name());
    assert_eq!("i7", ModelProfile::I7RsV2.short_label());
    assert_eq!("RS Common V2", ModelProfile::I7RsV2.family().display_name());
    for (preset, label) in [
        ("pop", "弱水经典"),
        ("hifi", "原声再现"),
        ("rock", "古典乐器"),
        ("light", "日系甜美"),
        ("eap", "欧美流行"),
    ] {
        assert_eq!(
            Some(label),
            ModelProfile::I7RsV2.equalizer_preset_label(preset)
        );
    }
    assert_eq!(None, ModelProfile::I7RsV2.equalizer_preset_label("custom"));
    for (profile, labels) in [
        (
            ModelProfile::AstroXRsV2,
            vec![("pop", "BASS"), ("hifi", "BALANCE"), ("rock", "BASS+")],
        ),
        (
            ModelProfile::OpenFeelLRsV2,
            vec![("pop", "BASS"), ("hifi", "BALANCE")],
        ),
        (ModelProfile::CeramicsURsV2, vec![("light", "经典调音")]),
        (
            ModelProfile::CeramicsUHsc2710RsV2,
            vec![("light", "经典调音")],
        ),
        (
            ModelProfile::FirstAngelRsV2,
            vec![("pop", "POP"), ("hifi", "HIFI"), ("game_1", "战术调律")],
        ),
        (
            ModelProfile::MirrorRsV2,
            vec![("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
        ),
        (ModelProfile::BudsFeelMk2RsV2, vec![]),
        (ModelProfile::Swift2RsV2, vec![]),
    ] {
        assert_eq!(
            labels,
            profile.spec().equalizer_preset_labels.to_vec(),
            "{profile:?}"
        );
    }
    for (profile, short) in [
        (ModelProfile::SpeakFeel2RsV2, "SPEAKFEEL (speakfeel2)"),
        (ModelProfile::SpeakFreeRsV2, "SpeakFeel (speakfree)"),
        (ModelProfile::AstroXRsV2, "Astro X"),
        (ModelProfile::FirstAngelRsV2, "FIRST ANGEL"),
        (ModelProfile::CeramicsMk25736RsV2, "CERAMICS-MK2 5736"),
    ] {
        assert_eq!(short, profile.display_name());
        assert_eq!(short, profile.short_label());
    }
    for (profile, _) in RS_V2_MODELS {
        let caps = profile.capabilities(None, None);
        if let Some(touch) = caps.touch_control {
            assert!(touch.slot_labels.is_empty(), "{profile:?}");
        }
    }
}

#[test]
fn unread_firmware_leaves_gated_prompt_volume_unknown_but_still_readable() {
    for profile in [
        ModelProfile::FirstAngelRsV2,
        ModelProfile::CeramicsURsV2,
        ModelProfile::CeramicsMk2RsV2,
        ModelProfile::I7RsV2,
    ] {
        let unread = profile.capabilities(None, None);
        assert!(unread.prompt_volume_levels.is_empty(), "{profile:?}");
        assert!(
            !unread
                .writable_settings
                .contains(&HeadsetSetting::PromptVolume),
            "{profile:?}"
        );
        assert!(
            unread.readable_fields.contains(&HeadsetField::PromptVolume),
            "{profile:?}"
        );
        assert_eq!(
            unread,
            profile.capabilities(Some(&fw(&[1, 2])), None),
            "{profile:?} malformed firmware is also unread"
        );
    }
    let old = ModelProfile::FirstAngelRsV2.capabilities(Some(&fw(&[5, 0, 9])), None);
    assert_eq!((1..=5).collect::<BTreeSet<_>>(), old.prompt_volume_levels);
    assert!(
        old.writable_settings
            .contains(&HeadsetSetting::PromptVolume)
    );
    assert_eq!(
        (0..=5).collect::<BTreeSet<_>>(),
        ModelProfile::FirstAngelRsV2
            .capabilities(Some(&fw(&[5, 1, 0])), None)
            .prompt_volume_levels
    );
}

#[test]
fn unread_firmware_offers_no_firmware_gated_controls_and_reaching_firmware_does() {
    let unread_u = ModelProfile::CeramicsURsV2.capabilities(None, None);
    assert!(
        unread_u
            .equalizer
            .as_ref()
            .is_some_and(|eq| eq.custom.is_none())
    );
    assert!(unread_u.gain_levels.is_empty());
    let read_u = ModelProfile::CeramicsURsV2.capabilities(Some(&fw(&[0, 0, 33])), None);
    assert!(
        read_u
            .equalizer
            .as_ref()
            .is_some_and(|eq| eq.custom.is_some())
    );
    assert_eq!((0..=2).collect::<BTreeSet<_>>(), read_u.gain_levels);

    let unread_mk2 = ModelProfile::CeramicsMk2RsV2.capabilities(None, None);
    assert!(
        unread_mk2
            .equalizer
            .as_ref()
            .is_some_and(|eq| eq.custom.is_none())
    );
    let read_mk2 = ModelProfile::CeramicsMk2RsV2.capabilities(Some(&fw(&[1, 5, 4])), None);
    assert!(
        read_mk2
            .equalizer
            .as_ref()
            .is_some_and(|eq| eq.custom.is_some())
    );
    let older_mk2 = ModelProfile::CeramicsMk2RsV2.capabilities(Some(&fw(&[1, 5, 3])), None);
    assert!(
        older_mk2
            .equalizer
            .as_ref()
            .is_some_and(|eq| eq.custom.is_none())
    );
    assert_eq!(
        (0..=5).collect::<BTreeSet<_>>(),
        read_mk2.prompt_volume_levels
    );

    let astro = ModelProfile::AstroXRsV2;
    assert!(
        !astro
            .capabilities(None, None)
            .writable_settings
            .contains(&HeadsetSetting::Gain)
    );
    assert!(
        astro
            .capabilities(Some(&fw(&[1, 5, 2])), None)
            .writable_settings
            .contains(&HeadsetSetting::Gain)
    );
    assert!(
        !astro
            .capabilities(None, None)
            .writable_settings
            .contains(&HeadsetSetting::SpatialAudio)
    );
}

#[test]
fn i7_capabilities_gate_equalizer_gain_protection_and_prompt_volume_by_firmware() {
    let base = set(&["pop", "hifi", "rock", "light"]);
    let unread = ModelProfile::I7RsV2.capabilities(None, None);
    assert_eq!(base, unread.equalizer.as_ref().expect("equalizer").presets);
    assert!(unread.gain_levels.is_empty());
    assert!(unread.hearing_protection_levels.is_empty());
    assert!(unread.readable_fields.contains(&HeadsetField::Gain));
    assert!(
        unread
            .readable_fields
            .contains(&HeadsetField::HearingProtection)
    );
    let custom = unread
        .equalizer
        .as_ref()
        .and_then(|eq| eq.custom.as_ref())
        .expect("custom curve");
    assert_eq!(
        vec![20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000],
        custom.bands_hz
    );
    assert_eq!((-6, 6), (custom.min_gain, custom.max_gain));

    for (parts, eap) in [
        (vec![1, 5, 1], false),
        (vec![1, 5, 2], true),
        (vec![0, 15, 1], false),
        (vec![0, 15, 2], true),
        (vec![0, 16, 0], true),
        (vec![1, 4, 255], true),
        (vec![1, 5], false),
        (vec![1, 5, 2, 0], false),
        (vec![-1, 5, 2], false),
        (vec![256, 5, 2], false),
    ] {
        let version = FirmwareVersion::new(parts.clone(), if eap { "0" } else { "999999" });
        let mut expected = base.clone();
        if eap {
            expected.insert("eap".to_string());
        }
        let caps = ModelProfile::I7RsV2.capabilities(Some(&version), None);
        assert_eq!(
            expected,
            caps.equalizer.expect("equalizer").presets,
            "{parts:?}"
        );
        assert_eq!(
            base,
            ModelProfile::I5Zt
                .capabilities(Some(&version), None)
                .equalizer
                .expect("i5 equalizer")
                .presets
        );
    }

    for (parts, gain, mute) in [
        (vec![2, 1, 2], false, false),
        (vec![2, 1, 3], true, false),
        (vec![3, 0, 3], true, false),
        (vec![3, 0, 4], true, true),
        (vec![0, 21, 3], true, false),
        (vec![0, 30, 4], true, true),
        (vec![1, 4, 255], true, true),
        (vec![0, 16, 0], false, false),
        (vec![2, 1], false, false),
        (vec![2, 1, 3, 0], false, false),
        (vec![-1, 1, 3], false, false),
        (vec![256, 1, 3], false, false),
    ] {
        let version = FirmwareVersion::new(parts.clone(), "999999");
        let caps = ModelProfile::I7RsV2.capabilities(Some(&version), None);
        let well_formed = parts.len() == 3 && parts.iter().all(|part| (0..=255).contains(part));
        assert_eq!(
            if gain {
                (0..=2).collect()
            } else {
                BTreeSet::new()
            },
            caps.gain_levels,
            "{parts:?}"
        );
        assert_eq!(
            if gain {
                (0..=6).collect()
            } else {
                BTreeSet::new()
            },
            caps.hearing_protection_levels,
            "{parts:?}"
        );
        let expected_prompt: BTreeSet<i32> = if mute {
            (0..=5).collect()
        } else if well_formed {
            (1..=5).collect()
        } else {
            BTreeSet::new()
        };
        assert_eq!(expected_prompt, caps.prompt_volume_levels, "{parts:?}");
        let i5 = ModelProfile::I5Zt.capabilities(Some(&version), None);
        assert!(i5.gain_levels.is_empty());
        assert!(i5.hearing_protection_levels.is_empty());
        assert_eq!((1..=5).collect::<BTreeSet<_>>(), i5.prompt_volume_levels);
    }
}

#[test]
fn i7_touch_actions_and_audio_modes_extend_the_i5_set_without_coupling_multipoint() {
    let caps = ModelProfile::I7RsV2.capabilities(None, None);
    let touch = caps.touch_control.expect("touch control");
    assert_eq!(
        set(&[
            "volume_up",
            "volume_down",
            "previous_track",
            "next_track",
            "voice_assistant",
            "play_pause",
            "none",
            "noise_cycle",
            "game_mode",
        ]),
        touch.actions
    );
    assert_eq!(8, touch.slots.len());
    let audio = caps.audio_control.expect("audio control");
    assert_eq!(set(&["aac_sbc", "ldac", "lhdc", "lc3"]), audio.modes);
    assert!(!audio.multipoint_coupled);
    assert_eq!(
        set(&["normal", "transparency", "wind", "anc", "auto", "dynamic"]),
        caps.noise_control.expect("noise").modes
    );
}

#[test]
fn open_feel_mk2_offers_only_a_custom_curve_and_bass_boost_for_equalizer() {
    let caps = ModelProfile::OpenFeelMk2RsV2.capabilities(None, None);
    assert!(caps.writable_settings.contains(&HeadsetSetting::CustomEq));
    assert!(!caps.writable_settings.contains(&HeadsetSetting::Eq));
    assert!(caps.writable_settings.contains(&HeadsetSetting::BassBoost));
    assert_eq!(
        BTreeSet::new(),
        caps.equalizer.as_ref().expect("equalizer").presets
    );
    assert_eq!((0..=2).collect::<BTreeSet<_>>(), caps.bass_boost_levels);
    assert_eq!((0..=5).collect::<BTreeSet<_>>(), caps.prompt_volume_levels);
    for version in [fw(&[0, 1, 0]), fw(&[3, 0, 4])] {
        let gated = ModelProfile::OpenFeelMk2RsV2.capabilities(Some(&version), None);
        assert!(gated.writable_settings.contains(&HeadsetSetting::CustomEq));
        assert_eq!(caps.equalizer, gated.equalizer);
    }
    let buds = ModelProfile::BudsFeelMk2RsV2.capabilities(None, None);
    assert_eq!((0..=4).collect::<BTreeSet<_>>(), buds.bass_boost_levels);
    assert!(buds.equalizer.is_none());
    assert!(buds.readable_fields.contains(&HeadsetField::EqSlot));
}

#[test]
fn prompt_volume_levels_follow_each_models_close_option_without_a_firmware_gate() {
    for (profile, levels) in [
        (
            ModelProfile::OpenFeelMk2RsV2,
            (0..=5).collect::<BTreeSet<_>>(),
        ),
        (ModelProfile::BudsFeelMk2RsV2, (1..=5).collect()),
        (ModelProfile::Swift2RsV2, (1..=5).collect()),
        (ModelProfile::JasmineRsV2, (1..=5).collect()),
    ] {
        for version in [None, Some(fw(&[0, 1, 0])), Some(fw(&[3, 0, 4]))] {
            let caps = profile.capabilities(version.as_ref(), None);
            assert!(
                caps.writable_settings
                    .contains(&HeadsetSetting::PromptVolume),
                "{profile:?} {version:?}"
            );
            assert_eq!(levels, caps.prompt_volume_levels, "{profile:?} {version:?}");
        }
    }
}

#[test]
fn first_angel_preset_bank_requires_confirmed_game_mode() {
    let profile = ModelProfile::FirstAngelRsV2;
    let presets = |game_mode: Option<bool>| {
        profile
            .capabilities(None, game_mode)
            .equalizer
            .expect("equalizer")
            .presets
    };
    assert_eq!(BTreeSet::new(), presets(None));
    assert_eq!(set(&["hifi", "pop"]), presets(Some(false)));
    assert_eq!(set(&["game_1"]), presets(Some(true)));
}

#[test]
fn rs_v2_variants_keep_their_documented_noise_levels_and_cycles() {
    for (profile, _) in RS_V2_MODELS {
        let caps = profile.capabilities(Some(&fw(&[9, 9, 9])), None);
        let noise = caps.noise_control.as_ref();
        let transparency = noise.and_then(|n| n.transparency_level.as_ref());
        let anc = noise.and_then(|n| n.anc_level.as_ref());
        assert_eq!(anc.is_some(), transparency.is_some(), "{profile:?}");
        if let (Some(anc), Some(transparency)) = (anc, transparency) {
            assert_eq!(BTreeSet::from([1, 3, 5]), anc.levels, "{profile:?}");
            let expected = if matches!(
                profile,
                ModelProfile::CeramicsURsV2 | ModelProfile::CeramicsUHsc2710RsV2
            ) {
                BTreeSet::from([1, 5])
            } else {
                BTreeSet::from([1, 3, 5])
            };
            assert_eq!(expected, transparency.levels, "{profile:?}");
        }
    }
    let ie_neo = ModelProfile::EarFeelIeNeoRsV2
        .capabilities(None, None)
        .noise_control
        .expect("noise");
    assert_eq!(set(&["anc", "normal", "transparency"]), ie_neo.modes);
    assert_eq!(set(&["anc", "normal", "transparency"]), ie_neo.cycle_modes);
    let opal = ModelProfile::OpalRsV2
        .capabilities(None, None)
        .noise_control
        .expect("noise");
    assert_eq!(set(&["normal", "transparency", "anc"]), opal.modes);
    assert!(opal.cycle_modes.is_empty());
    let coral = ModelProfile::CoralRsV2
        .capabilities(None, None)
        .noise_control
        .expect("noise");
    assert_eq!(set(&["anc", "normal"]), coral.modes);
    assert!(coral.cycle_modes.is_empty());
}

#[test]
fn rs_v2_variant_capabilities_match_the_documented_panels() {
    let astro = ModelProfile::AstroXRsV2.capabilities(None, None);
    assert_eq!(set(&["off", "head_tracking", "fixed"]), astro.spatial_modes);
    assert_eq!(
        set(&["tv", "music", "movie", "game"]),
        astro.spatial_contents
    );
    assert_eq!(
        set(&["aac_sbc", "ldac", "lhdc"]),
        astro.audio_control.expect("audio").modes
    );
    let tap = astro.tap_control.expect("tap control");
    assert_eq!(4, tap.slots.len());
    assert!(!tap.actions.contains("noise_cycle"));
    for setting in [
        HeadsetSetting::SpatialMode,
        HeadsetSetting::SpatialContent,
        HeadsetSetting::EarAdaptive,
        HeadsetSetting::TapEnabled,
        HeadsetSetting::TapBinding,
        HeadsetSetting::EarDetection,
    ] {
        assert!(astro.writable_settings.contains(&setting), "{setting:?}");
    }
    let speak = ModelProfile::SpeakFeel2RsV2.capabilities(None, None);
    for setting in [
        HeadsetSetting::IndependentMicActivation,
        HeadsetSetting::IndependentMicMode,
        HeadsetSetting::BassBoost,
        HeadsetSetting::Gain,
        HeadsetSetting::HearingProtection,
    ] {
        assert!(speak.writable_settings.contains(&setting), "{setting:?}");
    }
    assert_eq!((0..=1).collect::<BTreeSet<_>>(), speak.bass_boost_levels);
    let jasmine = ModelProfile::JasmineRsV2.capabilities(None, None);
    assert_eq!(set(&["chinese", "english"]), jasmine.prompt_languages);
    let magician = ModelProfile::MagicianRsV2.capabilities(None, None);
    assert_eq!(
        set(&["aac_sbc", "ldac", "aptx", "aptx_hd"]),
        magician.audio_control.expect("audio").modes
    );
    let speak_free = ModelProfile::SpeakFreeRsV2.capabilities(None, None);
    assert_eq!(
        set(&["aac_sbc", "ldac", "aptx", "aptx_hd", "aptx_adaptive"]),
        speak_free.audio_control.expect("audio").modes
    );
    let moka = ModelProfile::MokaRsV2.capabilities(None, None);
    assert_eq!(
        set(&["pop", "hifi", "rock"]),
        moka.equalizer.as_ref().expect("equalizer").presets
    );
    assert!(moka.touch_control.is_none());
    assert!(!moka.readable_fields.contains(&HeadsetField::FindState));
    let sport = ModelProfile::SportFeel8912fRsV2.capabilities(None, None);
    assert_eq!(set(&["hifi"]), sport.equalizer.expect("equalizer").presets);
}
