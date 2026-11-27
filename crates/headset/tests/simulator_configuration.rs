mod support;

use openrose_headset::simulator::SimulatedHeadset;
use openrose_headset::{
    FieldValue, FirmwareVersion, HeadsetCapabilities, HeadsetField, ModelProfile,
};
use std::collections::BTreeMap;

fn expect(found: &mut Vec<String>, allowed: bool, message: impl FnOnce() -> String) {
    if !allowed {
        found.push(message());
    }
}

fn violations(
    capabilities: &HeadsetCapabilities,
    readings: &BTreeMap<HeadsetField, FieldValue>,
) -> Vec<String> {
    let mut found = Vec::new();
    for (&field, value) in readings {
        expect(
            &mut found,
            capabilities.readable_fields.contains(&field),
            || format!("{field:?} is not a readable field of the model"),
        );
        let allowed = match (field, value) {
            (HeadsetField::NoiseMode, FieldValue::Text(mode)) => capabilities
                .noise_control
                .as_ref()
                .is_some_and(|noise| noise.modes.contains(mode)),
            (HeadsetField::AncLevel, FieldValue::Number(level)) => capabilities
                .noise_control
                .as_ref()
                .and_then(|noise| noise.anc_level.as_ref())
                .is_some_and(|levels| levels.levels.contains(level)),
            (HeadsetField::TransparencyLevel, FieldValue::Number(level)) => capabilities
                .noise_control
                .as_ref()
                .and_then(|noise| noise.transparency_level.as_ref())
                .is_some_and(|levels| levels.levels.contains(level)),
            (HeadsetField::NoiseCycle, FieldValue::NoiseCycle(flags)) => {
                capabilities.noise_control.as_ref().is_some_and(|noise| {
                    flags.keys().all(|mode| noise.cycle_modes.contains(mode))
                        && flags.values().flatten().filter(|&&on| on).count()
                            >= noise.minimum_cycle_selections as usize
                })
            }
            (HeadsetField::PromptVolume, FieldValue::Number(level)) => {
                capabilities.prompt_volume_levels.is_empty()
                    || capabilities.prompt_volume_levels.contains(level)
            }
            (HeadsetField::Eq, FieldValue::Text(preset)) => {
                let equalizer = capabilities.equalizer.as_ref();
                equalizer.is_some_and(|equalizer| equalizer.presets.contains(preset))
                    || (preset == "custom"
                        && equalizer.is_some_and(|equalizer| equalizer.custom.is_some()))
                    || (preset == "custom_slot" && !capabilities.bass_boost_levels.is_empty())
            }
            (HeadsetField::EqSlot, FieldValue::Number(level)) => {
                capabilities.bass_boost_levels.contains(level)
            }
            (HeadsetField::AudioMode, FieldValue::Text(mode)) => capabilities
                .audio_control
                .as_ref()
                .is_none_or(|audio| audio.modes.contains(mode)),
            (HeadsetField::TouchKeys, FieldValue::TouchKeys(bindings)) => {
                capabilities.touch_control.as_ref().is_some_and(|touch| {
                    bindings.iter().all(|binding| {
                        binding
                            .action
                            .as_ref()
                            .is_none_or(|action| touch.actions.contains(action))
                    })
                })
            }
            (HeadsetField::TapKeys, FieldValue::TouchKeys(bindings)) => {
                capabilities.tap_control.as_ref().is_some_and(|tap| {
                    bindings.iter().all(|binding| {
                        binding
                            .action
                            .as_ref()
                            .is_none_or(|action| tap.actions.contains(action))
                    })
                })
            }
            (HeadsetField::CustomEq, FieldValue::IntegerList(gains)) => capabilities
                .equalizer
                .as_ref()
                .and_then(|equalizer| equalizer.custom.as_ref())
                .is_none_or(|custom| {
                    gains.len() == custom.bands_hz.len()
                        && gains
                            .iter()
                            .all(|gain| (custom.min_gain..=custom.max_gain).contains(gain))
                }),
            (HeadsetField::Gain, FieldValue::Number(level)) => {
                capabilities.gain_levels.is_empty() || capabilities.gain_levels.contains(level)
            }
            (HeadsetField::HearingProtection, FieldValue::Number(level)) => {
                capabilities.hearing_protection_levels.is_empty()
                    || capabilities.hearing_protection_levels.contains(level)
            }
            (HeadsetField::PromptLanguage, FieldValue::Text(language)) => {
                capabilities.prompt_languages.contains(language)
            }
            (HeadsetField::SpatialMode, FieldValue::Text(mode)) => {
                capabilities.spatial_modes.contains(mode)
            }
            (HeadsetField::SpatialContent, FieldValue::Text(content)) => {
                capabilities.spatial_contents.contains(content)
            }
            _ => true,
        };
        expect(&mut found, allowed, || {
            format!("{field:?} = {value:?} is ruled out by the model configuration")
        });
    }
    found
}

fn game_mode(readings: &BTreeMap<HeadsetField, FieldValue>) -> Option<bool> {
    match readings.get(&HeadsetField::GameMode) {
        Some(FieldValue::Toggle(on)) => Some(*on),
        _ => None,
    }
}

#[test]
fn every_simulated_model_starts_in_a_state_its_configuration_allows() {
    for profile in ModelProfile::ALL.iter().copied() {
        for firmware in support::firmwares(profile) {
            let headset = SimulatedHeadset::new(profile, firmware.clone())
                .unwrap_or_else(|error| panic!("{profile:?}: {error}"));
            let readings = headset.readings();
            let capabilities = profile.capabilities(Some(&firmware), game_mode(&readings));
            assert_eq!(
                Vec::<String>::new(),
                violations(&capabilities, &readings),
                "{profile:?} {}",
                firmware.display_text
            );
        }
    }
}

#[test]
fn a_simulated_model_refuses_device_changes_its_configuration_rules_out() {
    let firmware = || FirmwareVersion::new(vec![1, 2, 5], "125");
    let mut ceramics_u = SimulatedHeadset::new(ModelProfile::CeramicsURsV2, firmware())
        .expect("CERAMICS-U simulator");
    assert!(
        ceramics_u
            .set(HeadsetField::TransparencyLevel, FieldValue::Number(3))
            .is_err()
    );
    assert!(
        ceramics_u
            .set(HeadsetField::TransparencyLevel, FieldValue::Number(5))
            .is_ok()
    );
    assert_eq!(
        Some(&FieldValue::Number(5)),
        ceramics_u.readings().get(&HeadsetField::TransparencyLevel)
    );
    assert!(
        ceramics_u
            .set(HeadsetField::AncLevel, FieldValue::Number(3))
            .is_ok()
    );

    let mut i3 = SimulatedHeadset::new(ModelProfile::I3Zt, firmware()).expect("i3 simulator");
    for (field, value) in [
        (HeadsetField::NoiseMode, FieldValue::text("wind")),
        (HeadsetField::AncLevel, FieldValue::Number(3)),
        (HeadsetField::PromptVolume, FieldValue::Number(3)),
    ] {
        assert!(i3.set(field, value.clone()).is_err(), "{field:?} {value:?}");
    }
    assert!(
        i3.set(HeadsetField::NoiseMode, FieldValue::text("transparency"))
            .is_ok()
    );
}

#[test]
fn prompt_volume_off_is_offered_only_by_the_firmware_that_supports_it() {
    let mut before = SimulatedHeadset::new(
        ModelProfile::I7RsV2,
        FirmwareVersion::new(vec![1, 2, 5], "125"),
    )
    .expect("i7 on firmware 125");
    assert!(
        before
            .set(HeadsetField::PromptVolume, FieldValue::Number(0))
            .is_err()
    );
    assert!(
        before
            .set(HeadsetField::PromptVolume, FieldValue::Number(1))
            .is_ok()
    );

    let mut after = SimulatedHeadset::new(
        ModelProfile::I7RsV2,
        FirmwareVersion::new(vec![3, 0, 4], "304"),
    )
    .expect("i7 on firmware 304");
    assert!(
        after
            .set(HeadsetField::PromptVolume, FieldValue::Number(0))
            .is_ok()
    );
    assert_eq!(
        Some(&FieldValue::Number(0)),
        after.readings().get(&HeadsetField::PromptVolume)
    );
}
