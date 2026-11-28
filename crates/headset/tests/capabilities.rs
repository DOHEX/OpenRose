use openrose_headset::ProtocolFamily;
use openrose_headset::{
    CustomEqualizerCapabilities, EqualizerCapabilities, FirmwareVersion, HeadsetCapabilities,
    HeadsetField, HeadsetSetting, ModelProfile, NoiseMode, firmware_at_least,
    firmware_comparison_integer, prompt_volume_levels,
};
use std::collections::BTreeSet;

fn fw(parts: &[i32]) -> FirmwareVersion {
    FirmwareVersion::from_parts(parts.to_vec())
}

#[test]
fn parses_and_validates_firmware_per_protocol_family() {
    assert_eq!(
        ProtocolFamily::Zt.parse_firmware("125").unwrap(),
        FirmwareVersion::new(vec![1, 2, 5], "125")
    );
    assert_eq!(
        ProtocolFamily::Zt.parse_firmware("1.2.5").unwrap(),
        FirmwareVersion::new(vec![1, 2, 5], "1.2.5")
    );
    assert_eq!(
        ProtocolFamily::Zt.parse_firmware("1a5").unwrap(),
        FirmwareVersion::new(vec![1, 10, 5], "1a5")
    );
    assert!(ProtocolFamily::Zt.parse_firmware("1160").is_err());
    assert!(ProtocolFamily::Zt.parse_firmware("1.16.0").is_err());
    assert_eq!(
        ProtocolFamily::RsCommonV2.parse_firmware("2.1.3").unwrap(),
        FirmwareVersion::new(vec![2, 1, 3], "2.1.3")
    );
    assert!(ProtocolFamily::RsCommonV2.parse_firmware("1.256.0").is_err());
    assert_eq!(
        ProtocolFamily::RsCommon.parse_firmware("1.200.5").unwrap(),
        FirmwareVersion::new(vec![1, 200, 5], "1.200.5")
    );
    assert!(ProtocolFamily::RsCommon.parse_firmware("16.0.0").is_err());
    assert_eq!(
        ProtocolFamily::F8.parse_firmware("300").unwrap(),
        FirmwareVersion::new(vec![300], "300")
    );
    assert!(ProtocolFamily::F8.parse_firmware("1.2.5").is_err());
    assert!(ProtocolFamily::F8.parse_firmware("").is_err());
    assert!(!ProtocolFamily::Zt.firmware_hint().is_empty());
}

#[test]
fn firmware_comparison_matches_roselink_decimal_concatenation_and_unknown_semantics() {
    assert_eq!(None, firmware_comparison_integer(None));
    assert_eq!(
        Some(213),
        firmware_comparison_integer(Some(&fw(&[2, 1, 3])))
    );
    assert_eq!(
        Some(151),
        firmware_comparison_integer(Some(&fw(&[0, 15, 1])))
    );
    assert_eq!(
        Some(14255),
        firmware_comparison_integer(Some(&fw(&[1, 4, 255])))
    );

    for (parts, expected) in [
        (vec![1, 5, 1], Some(false)),
        (vec![1, 5, 2], Some(true)),
        (vec![0, 15, 1], Some(false)),
        (vec![0, 15, 2], Some(true)),
        (vec![0, 16, 0], Some(true)),
        (vec![1, 4, 255], Some(true)),
        (vec![1, 5], None),
        (vec![1, 5, 2, 0], None),
        (vec![-1, 5, 2], None),
        (vec![256, 5, 2], None),
    ] {
        let bogus_label = if expected == Some(true) {
            "0"
        } else {
            "999999"
        };
        let version = FirmwareVersion::new(parts.clone(), bogus_label);
        assert_eq!(
            expected,
            firmware_at_least(Some(&version), 152),
            "{parts:?}"
        );
    }

    assert_eq!(BTreeSet::new(), prompt_volume_levels(None, 304));
    assert_eq!(
        BTreeSet::new(),
        prompt_volume_levels(Some(&fw(&[1, 2])), 304)
    );
    assert_eq!(
        (1..=5).collect::<BTreeSet<_>>(),
        prompt_volume_levels(Some(&fw(&[2, 1, 3])), 304)
    );
    assert_eq!(
        (0..=5).collect::<BTreeSet<_>>(),
        prompt_volume_levels(Some(&fw(&[3, 0, 4])), 304)
    );
}

#[test]
fn a_writable_setting_without_options_is_never_writable() {
    let firmwares = [None, Some(fw(&[0, 0, 1])), Some(fw(&[9, 9, 9]))];
    for &profile in ModelProfile::ALL {
        for firmware in &firmwares {
            let capabilities = profile.capabilities(firmware.as_ref(), None);
            let writable = &capabilities.writable_settings;
            let check = |setting: HeadsetSetting, has_options: bool| {
                if writable.contains(&setting) {
                    assert!(has_options, "{profile:?} {firmware:?} {setting:?}");
                }
            };
            check(
                HeadsetSetting::NoiseMode,
                capabilities
                    .noise_control
                    .as_ref()
                    .is_some_and(|n| !n.modes.is_empty()),
            );
            check(
                HeadsetSetting::NoiseCycle,
                capabilities
                    .noise_control
                    .as_ref()
                    .is_some_and(|n| !n.cycle_modes.is_empty()),
            );
            check(
                HeadsetSetting::Eq,
                capabilities
                    .equalizer
                    .as_ref()
                    .is_some_and(|e| !e.presets.is_empty()),
            );
            check(
                HeadsetSetting::CustomEq,
                capabilities
                    .equalizer
                    .as_ref()
                    .and_then(|e| e.custom.as_ref())
                    .is_some_and(|c| !c.bands_hz.is_empty()),
            );
            check(
                HeadsetSetting::TouchBinding,
                capabilities
                    .touch_control
                    .as_ref()
                    .is_some_and(|t| !t.actions.is_empty()),
            );
            check(
                HeadsetSetting::AudioMode,
                capabilities
                    .audio_control
                    .as_ref()
                    .is_some_and(|a| !a.modes.is_empty()),
            );
            check(
                HeadsetSetting::PromptVolume,
                !capabilities.prompt_volume_levels.is_empty(),
            );
            check(
                HeadsetSetting::PromptLanguage,
                !capabilities.prompt_languages.is_empty(),
            );
            check(HeadsetSetting::Gain, !capabilities.gain_levels.is_empty());
            check(
                HeadsetSetting::HearingProtection,
                !capabilities.hearing_protection_levels.is_empty(),
            );
            check(
                HeadsetSetting::BassBoost,
                !capabilities.bass_boost_levels.is_empty(),
            );
        }
    }
}

#[test]
fn every_known_noise_mode_has_display_order_and_unknown_sorts_last() {
    let mut ids = vec![
        "mystery",
        "deep",
        "dynamic",
        "auto",
        "wind",
        "transparency",
        "anc",
        "normal",
    ];
    ids.sort_by_key(|id| NoiseMode::display_order(id));
    assert_eq!(
        vec![
            "normal",
            "anc",
            "transparency",
            "wind",
            "auto",
            "dynamic",
            "deep",
            "mystery",
        ],
        ids
    );
}

#[test]
fn custom_preset_is_offered_as_preset_only_when_no_curve_editor_is_writable() {
    let curve = CustomEqualizerCapabilities {
        bands_hz: vec![100, 1_000, 10_000],
        min_gain: -6,
        max_gain: 6,
    };
    let make = |writable: &[HeadsetSetting], custom: Option<CustomEqualizerCapabilities>| {
        HeadsetCapabilities {
            readable_fields: BTreeSet::from([HeadsetField::Eq]),
            writable_settings: writable.iter().copied().collect(),
            equalizer: Some(EqualizerCapabilities {
                presets: BTreeSet::from(["hifi".to_string(), "custom".to_string()]),
                custom,
            }),
            ..HeadsetCapabilities::default()
        }
    };

    let with_editor = make(
        &[HeadsetSetting::Eq, HeadsetSetting::CustomEq],
        Some(curve.clone()),
    );
    assert_eq!(Some(&curve), with_editor.writable_custom_curve());
    assert_eq!(
        BTreeSet::from(["hifi".to_string()]),
        with_editor.writable_presets()
    );

    let presets_only = make(&[HeadsetSetting::Eq], Some(curve.clone()));
    assert_eq!(None, presets_only.writable_custom_curve());
    assert_eq!(
        BTreeSet::from(["hifi".to_string(), "custom".to_string()]),
        presets_only.writable_presets()
    );

    let curve_only = make(&[HeadsetSetting::CustomEq], Some(curve.clone()));
    assert_eq!(Some(&curve), curve_only.writable_custom_curve());
    assert_eq!(BTreeSet::new(), curve_only.writable_presets());

    let inverted = make(
        &[HeadsetSetting::Eq, HeadsetSetting::CustomEq],
        Some(CustomEqualizerCapabilities {
            bands_hz: vec![100, 1_000, 10_000],
            min_gain: 6,
            max_gain: -6,
        }),
    );
    assert_eq!(None, inverted.writable_custom_curve());
    assert_eq!(
        BTreeSet::from(["hifi".to_string(), "custom".to_string()]),
        inverted.writable_presets()
    );
}
