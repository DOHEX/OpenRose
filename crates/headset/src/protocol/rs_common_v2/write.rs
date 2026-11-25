use super::readings::record_fields;
use super::wire::{
    ACTIONS, AUDIO_MODES, CYCLE_ORDER, GESTURES, NOISE_MODES, PRESET_CODES, PROMPT_LANGUAGES,
    SPATIAL_CONTENTS, SPATIAL_MODES,
};
use crate::capabilities::{HeadsetCapabilities, NoiseLevelCapabilities, TouchControlCapabilities};
use crate::error::ProtocolError;
use crate::field::{FieldValue, HeadsetField, HeadsetSetting};
use crate::model::ProtocolFamily;
use crate::protocol::{validated_noise_cycle, validated_touch_assignment};
use std::collections::{BTreeMap, BTreeSet};
use std::ops::RangeInclusive;

pub(super) struct RsV2Write {
    pub(super) type_id: u8,
    pub(super) payload: Vec<u8>,
    pub(super) expected: BTreeMap<HeadsetField, FieldValue>,
}

fn invalid(message: &str) -> ProtocolError {
    ProtocolError::InvalidArgument(message.to_string())
}

fn unsupported(message: &str) -> ProtocolError {
    ProtocolError::Unsupported(message.to_string())
}

pub(super) fn rs_v2_setting(
    capabilities: &HeadsetCapabilities,
    setting: HeadsetSetting,
    value: &FieldValue,
) -> Result<RsV2Write, ProtocolError> {
    let (type_id, payload, expected) = match setting {
        HeadsetSetting::NoiseMode => (0x09, noise_mode(capabilities, value)?, None),
        HeadsetSetting::AncLevel => (
            0x2c,
            noise_level(
                value,
                capabilities
                    .noise_control
                    .as_ref()
                    .and_then(|noise| noise.anc_level.as_ref()),
            )?,
            None,
        ),
        HeadsetSetting::TransparencyLevel => (
            0x2d,
            noise_level(
                value,
                capabilities
                    .noise_control
                    .as_ref()
                    .and_then(|noise| noise.transparency_level.as_ref()),
            )?,
            None,
        ),
        HeadsetSetting::NoiseCycle => (0x36, noise_cycle(capabilities, value)?, None),
        HeadsetSetting::Eq => (0x2a, preset(capabilities, value)?, None),
        HeadsetSetting::BassBoost => {
            let (payload, expected) = bass_boost(capabilities, value)?;
            (0x2a, payload, Some(expected))
        }
        HeadsetSetting::PromptLanguage => (0x31, prompt_language(capabilities, value)?, None),
        HeadsetSetting::Gain => (
            0x45,
            level_byte(value, "增益", 0..=2, &capabilities.gain_levels)?,
            None,
        ),
        HeadsetSetting::HearingProtection => (
            0x46,
            level_byte(
                value,
                "听力保护",
                0..=6,
                &capabilities.hearing_protection_levels,
            )?,
            None,
        ),
        HeadsetSetting::PromptVolume => (0x2e, prompt_volume(capabilities, value)?, None),
        HeadsetSetting::GameMode => (0x0e, toggle_byte(value, "游戏模式需要明确开关", 1)?, None),
        HeadsetSetting::AudioMode => (0x2b, audio_mode(capabilities, value)?, None),
        HeadsetSetting::Multipoint => (0x32, toggle_byte(value, "多点连接需要明确开关", 1)?, None),
        HeadsetSetting::TouchEnabled => (
            0x07,
            toggle_byte(value, "触控和检测功能需要明确开关", 0)?,
            None,
        ),
        HeadsetSetting::EarDetection => (
            0x08,
            toggle_byte(value, "触控和检测功能需要明确开关", 0)?,
            None,
        ),
        HeadsetSetting::TapEnabled => {
            if !capabilities
                .writable_settings
                .contains(&HeadsetSetting::TapEnabled)
            {
                return Err(unsupported("该型号不支持轻拍控制"));
            }
            (0x3b, toggle_byte(value, "轻拍控制需要明确开关", 1)?, None)
        }
        HeadsetSetting::TouchBinding => (
            0x01,
            binding_bytes(value, capabilities.touch_control.as_ref())?,
            None,
        ),
        HeadsetSetting::TapBinding => (
            0x3c,
            binding_bytes(value, capabilities.tap_control.as_ref())?,
            None,
        ),
        HeadsetSetting::SpatialMode => (
            0x38,
            spatial_byte(value, &capabilities.spatial_modes, &SPATIAL_MODES)?,
            None,
        ),
        HeadsetSetting::SpatialContent => (
            0x3d,
            spatial_byte(value, &capabilities.spatial_contents, &SPATIAL_CONTENTS)?,
            None,
        ),
        HeadsetSetting::EarAdaptive => (
            0x3a,
            supported_toggle(capabilities, HeadsetSetting::EarAdaptive, value)?,
            None,
        ),
        HeadsetSetting::IndependentMicActivation => (
            0x49,
            selector_toggle(
                capabilities,
                HeadsetSetting::IndependentMicActivation,
                0,
                value,
            )?,
            Some(BTreeMap::from([(
                HeadsetField::IndependentMicActivation,
                value.clone(),
            )])),
        ),
        HeadsetSetting::IndependentMicMode => (
            0x49,
            selector_toggle(capabilities, HeadsetSetting::IndependentMicMode, 1, value)?,
            Some(BTreeMap::from([(
                HeadsetField::IndependentMicMode,
                value.clone(),
            )])),
        ),
        HeadsetSetting::SpatialAudio => return Err(unsupported("该型号不支持空间音频总开关")),
        HeadsetSetting::CustomEq => return Err(unsupported("该型号不支持此设置")),
    };
    let expected = expected.unwrap_or_else(|| single_field_expectation(type_id, value));
    Ok(RsV2Write {
        type_id,
        payload,
        expected,
    })
}

fn single_field_expectation(type_id: u8, value: &FieldValue) -> BTreeMap<HeadsetField, FieldValue> {
    record_fields(type_id)
        .iter()
        .map(|&field| (field, value.clone()))
        .collect()
}

fn noise_mode(
    capabilities: &HeadsetCapabilities,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Text(mode) = value else {
        return Err(invalid("降噪模式需要模式名称"));
    };
    let wire = NOISE_MODES.iter().position(|name| name == mode);
    let offered = capabilities
        .noise_control
        .as_ref()
        .is_some_and(|noise| noise.modes.contains(mode));
    match wire {
        Some(index) if offered => Ok(vec![index as u8 + 1]),
        _ => Err(unsupported("该型号不支持该降噪模式")),
    }
}

fn noise_level(
    value: &FieldValue,
    capability: Option<&NoiseLevelCapabilities>,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Number(level) = value else {
        return Err(invalid("档位需要整数"));
    };
    let Some(capability) = capability else {
        return Err(unsupported("该型号不支持此档位设置"));
    };
    if !capability.levels.contains(level) {
        let options: Vec<String> = capability.levels.iter().map(i32::to_string).collect();
        return Err(ProtocolError::InvalidArgument(format!(
            "档位仅支持 {}",
            options.join("、")
        )));
    }
    Ok(vec![*level as u8])
}

fn noise_cycle(
    capabilities: &HeadsetCapabilities,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let modes = capabilities
        .noise_control
        .as_ref()
        .map(|noise| noise.cycle_modes.clone())
        .unwrap_or_default();
    let flags = validated_noise_cycle(value, &modes)?;
    Ok(CYCLE_ORDER
        .iter()
        .map(|mode| u8::from(flags.get(*mode) == Some(&true)))
        .collect())
}

fn preset(
    capabilities: &HeadsetCapabilities,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Text(name) = value else {
        return Err(invalid("均衡器需要预设名称"));
    };
    let offered = capabilities
        .equalizer
        .as_ref()
        .is_some_and(|eq| eq.presets.contains(name));
    let code = PRESET_CODES
        .iter()
        .find(|(preset, _)| preset == name)
        .map(|(_, code)| *code);
    match code {
        Some(code) if offered => Ok(vec![code]),
        _ => Err(unsupported("该型号当前固件不支持该均衡器预设")),
    }
}

fn bass_boost(
    capabilities: &HeadsetCapabilities,
    value: &FieldValue,
) -> Result<(Vec<u8>, BTreeMap<HeadsetField, FieldValue>), ProtocolError> {
    let FieldValue::Number(level) = value else {
        return Err(invalid("低音增强仅支持 0～4 档"));
    };
    if !(0..=4).contains(level) {
        return Err(invalid("低音增强仅支持 0～4 档"));
    }
    if !capabilities.bass_boost_levels.contains(level) {
        return Err(unsupported("该型号不支持低音增强"));
    }
    Ok((
        vec![5, *level as u8],
        BTreeMap::from([
            (HeadsetField::Eq, FieldValue::text("custom_slot")),
            (HeadsetField::EqSlot, value.clone()),
        ]),
    ))
}

fn prompt_language(
    capabilities: &HeadsetCapabilities,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Text(language) = value else {
        return Err(invalid("提示音语言无效"));
    };
    let Some(index) = PROMPT_LANGUAGES.iter().position(|name| name == language) else {
        return Err(invalid("提示音语言无效"));
    };
    if !capabilities.prompt_languages.contains(language) {
        return Err(unsupported("该型号不支持提示音语言"));
    }
    Ok(vec![index as u8])
}

fn level_byte(
    value: &FieldValue,
    name: &str,
    range: RangeInclusive<i32>,
    supported: &BTreeSet<i32>,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Number(level) = value else {
        return Err(ProtocolError::InvalidArgument(format!(
            "{name}需要档位整数"
        )));
    };
    if !range.contains(level) {
        return Err(ProtocolError::InvalidArgument(format!(
            "RS Common V2 {name}档位仅支持 {}～{}",
            range.start(),
            range.end()
        )));
    }
    if !supported.contains(level) {
        return Err(ProtocolError::Unsupported(format!(
            "该型号当前固件不支持{name}设置"
        )));
    }
    Ok(vec![*level as u8])
}

fn prompt_volume(
    capabilities: &HeadsetCapabilities,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Number(level) = value else {
        return Err(invalid("提示音需要档位整数"));
    };
    if !ProtocolFamily::RsCommonV2
        .prompt_volume_levels()
        .contains(level)
    {
        return Err(invalid("RS Common V2 提示音仅支持 0～5 档"));
    }
    if !capabilities.prompt_volume_levels.contains(level) {
        return Err(unsupported("该型号当前固件不支持此提示音档位"));
    }
    Ok(vec![*level as u8])
}

fn audio_mode(
    capabilities: &HeadsetCapabilities,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Text(mode) = value else {
        return Err(invalid("音频模式需要配置名称"));
    };
    let wire = AUDIO_MODES.iter().position(|name| name == mode);
    let offered = capabilities
        .audio_control
        .as_ref()
        .is_some_and(|audio| audio.modes.contains(mode));
    match wire {
        Some(index) if offered => Ok(vec![index as u8]),
        _ => Err(unsupported("该型号不支持该音频配置")),
    }
}

fn toggle_byte(value: &FieldValue, requirement: &str, on: u8) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Toggle(enabled) = value else {
        return Err(invalid(requirement));
    };
    Ok(vec![if *enabled { on } else { 1 - on }])
}

fn supported_toggle(
    capabilities: &HeadsetCapabilities,
    setting: HeadsetSetting,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    if !capabilities.writable_settings.contains(&setting) {
        return Err(unsupported("该型号不支持此设置"));
    }
    toggle_byte(value, "需要明确开关", 1)
}

fn selector_toggle(
    capabilities: &HeadsetCapabilities,
    setting: HeadsetSetting,
    selector: u8,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let mut payload = vec![selector];
    payload.extend(supported_toggle(capabilities, setting, value)?);
    Ok(payload)
}

fn binding_bytes(
    value: &FieldValue,
    capability: Option<&TouchControlCapabilities>,
) -> Result<Vec<u8>, ProtocolError> {
    let (side, gesture, action) = validated_touch_assignment(value, capability)?;
    let gesture_index = GESTURES.iter().position(|name| *name == gesture);
    let action_code = ACTIONS.iter().position(|name| *name == action);
    let (Some(gesture_index), Some(action_code)) = (gesture_index, action_code) else {
        return Err(unsupported("该型号不支持此触控动作"));
    };
    let side_offset = if side == "right" { 0x10 } else { 0 };
    Ok(vec![
        gesture_index as u8 + 1 + side_offset,
        action_code as u8,
    ])
}

fn spatial_byte(
    value: &FieldValue,
    supported: &BTreeSet<String>,
    wire_order: &[&str],
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Text(option) = value else {
        return Err(invalid("该型号不支持此空间音频选项"));
    };
    if !supported.contains(option) {
        return Err(invalid("该型号不支持此空间音频选项"));
    }
    let Some(wire) = wire_order.iter().position(|name| name == option) else {
        return Err(invalid("未知空间音频选项"));
    };
    Ok(vec![wire as u8])
}
