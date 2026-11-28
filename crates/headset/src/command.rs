use crate::capabilities::{
    HeadsetCapabilities, NoiseLevelCapabilities, NoiseMode, TouchControlCapabilities,
};
use crate::error::ValidationError;
use crate::field::{FieldReading, FieldValue, HeadsetField, HeadsetSetting, TouchSlot};
use crate::model::ModelProfile;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum HeadsetCommand {
    NoiseMode(NoiseMode),
    AncLevel(i32),
    TransparencyLevel(i32),
    NoiseCycle(BTreeMap<String, bool>),
    EqualizerPreset(String),
    CustomEqualizer(Vec<i32>),
    TouchBinding {
        side: String,
        gesture: String,
        action: String,
    },
    TapBinding {
        side: String,
        gesture: String,
        action: String,
    },
    PromptVolume(i32),
    Gain(i32),
    HearingProtection(i32),
    BassBoost(i32),
    GameMode(bool),
    Multipoint(bool),
    TouchEnabled(bool),
    EarDetection(bool),
    TapEnabled(bool),
    SpatialAudio(bool),
    EarAdaptive(bool),
    IndependentMicActivation(bool),
    IndependentMicMode(bool),
    AudioMode(String),
    PromptLanguage(String),
    SpatialMode(String),
    SpatialContent(String),
}

impl HeadsetCommand {
    pub fn setting(&self) -> HeadsetSetting {
        match self {
            Self::NoiseMode(_) => HeadsetSetting::NoiseMode,
            Self::AncLevel(_) => HeadsetSetting::AncLevel,
            Self::TransparencyLevel(_) => HeadsetSetting::TransparencyLevel,
            Self::NoiseCycle(_) => HeadsetSetting::NoiseCycle,
            Self::EqualizerPreset(_) => HeadsetSetting::Eq,
            Self::CustomEqualizer(_) => HeadsetSetting::CustomEq,
            Self::TouchBinding { .. } => HeadsetSetting::TouchBinding,
            Self::TapBinding { .. } => HeadsetSetting::TapBinding,
            Self::PromptVolume(_) => HeadsetSetting::PromptVolume,
            Self::Gain(_) => HeadsetSetting::Gain,
            Self::HearingProtection(_) => HeadsetSetting::HearingProtection,
            Self::BassBoost(_) => HeadsetSetting::BassBoost,
            Self::GameMode(_) => HeadsetSetting::GameMode,
            Self::Multipoint(_) => HeadsetSetting::Multipoint,
            Self::TouchEnabled(_) => HeadsetSetting::TouchEnabled,
            Self::EarDetection(_) => HeadsetSetting::EarDetection,
            Self::TapEnabled(_) => HeadsetSetting::TapEnabled,
            Self::SpatialAudio(_) => HeadsetSetting::SpatialAudio,
            Self::EarAdaptive(_) => HeadsetSetting::EarAdaptive,
            Self::IndependentMicActivation(_) => HeadsetSetting::IndependentMicActivation,
            Self::IndependentMicMode(_) => HeadsetSetting::IndependentMicMode,
            Self::AudioMode(_) => HeadsetSetting::AudioMode,
            Self::PromptLanguage(_) => HeadsetSetting::PromptLanguage,
            Self::SpatialMode(_) => HeadsetSetting::SpatialMode,
            Self::SpatialContent(_) => HeadsetSetting::SpatialContent,
        }
    }

    pub fn value(&self) -> FieldValue {
        match self {
            Self::NoiseMode(mode) => FieldValue::Text(mode.id().to_string()),
            Self::AncLevel(level)
            | Self::TransparencyLevel(level)
            | Self::PromptVolume(level)
            | Self::Gain(level)
            | Self::HearingProtection(level)
            | Self::BassBoost(level) => FieldValue::Number(*level),
            Self::NoiseCycle(flags) => {
                FieldValue::NoiseCycle(flags.iter().map(|(k, &v)| (k.clone(), Some(v))).collect())
            }
            Self::EqualizerPreset(v)
            | Self::AudioMode(v)
            | Self::PromptLanguage(v)
            | Self::SpatialMode(v)
            | Self::SpatialContent(v) => FieldValue::Text(v.clone()),
            Self::CustomEqualizer(gains) => FieldValue::IntegerList(gains.clone()),
            Self::TouchBinding {
                side,
                gesture,
                action,
            }
            | Self::TapBinding {
                side,
                gesture,
                action,
            } => FieldValue::TouchAssignment {
                side: side.clone(),
                gesture: gesture.clone(),
                action: action.clone(),
            },
            Self::GameMode(enabled)
            | Self::Multipoint(enabled)
            | Self::TouchEnabled(enabled)
            | Self::EarDetection(enabled)
            | Self::TapEnabled(enabled)
            | Self::SpatialAudio(enabled)
            | Self::EarAdaptive(enabled)
            | Self::IndependentMicActivation(enabled)
            | Self::IndependentMicMode(enabled) => FieldValue::Toggle(*enabled),
        }
    }

    pub fn decode(setting: HeadsetSetting, value: &FieldValue) -> Option<Self> {
        match value {
            FieldValue::Toggle(enabled) => match setting {
                HeadsetSetting::GameMode => Some(Self::GameMode(*enabled)),
                HeadsetSetting::Multipoint => Some(Self::Multipoint(*enabled)),
                HeadsetSetting::TouchEnabled => Some(Self::TouchEnabled(*enabled)),
                HeadsetSetting::EarDetection => Some(Self::EarDetection(*enabled)),
                HeadsetSetting::TapEnabled => Some(Self::TapEnabled(*enabled)),
                HeadsetSetting::SpatialAudio => Some(Self::SpatialAudio(*enabled)),
                HeadsetSetting::EarAdaptive => Some(Self::EarAdaptive(*enabled)),
                HeadsetSetting::IndependentMicActivation => {
                    Some(Self::IndependentMicActivation(*enabled))
                }
                HeadsetSetting::IndependentMicMode => Some(Self::IndependentMicMode(*enabled)),
                _ => None,
            },
            FieldValue::Number(level) => match setting {
                HeadsetSetting::AncLevel => Some(Self::AncLevel(*level)),
                HeadsetSetting::TransparencyLevel => Some(Self::TransparencyLevel(*level)),
                HeadsetSetting::PromptVolume => Some(Self::PromptVolume(*level)),
                HeadsetSetting::Gain => Some(Self::Gain(*level)),
                HeadsetSetting::HearingProtection => Some(Self::HearingProtection(*level)),
                HeadsetSetting::BassBoost => Some(Self::BassBoost(*level)),
                _ => None,
            },
            FieldValue::Text(choice) => match setting {
                HeadsetSetting::NoiseMode => NoiseMode::from_id(choice).map(Self::NoiseMode),
                HeadsetSetting::Eq => Some(Self::EqualizerPreset(choice.clone())),
                HeadsetSetting::AudioMode => Some(Self::AudioMode(choice.clone())),
                HeadsetSetting::PromptLanguage => Some(Self::PromptLanguage(choice.clone())),
                HeadsetSetting::SpatialMode => Some(Self::SpatialMode(choice.clone())),
                HeadsetSetting::SpatialContent => Some(Self::SpatialContent(choice.clone())),
                _ => None,
            },
            FieldValue::IntegerList(gains) => {
                if setting == HeadsetSetting::CustomEq {
                    Some(Self::CustomEqualizer(gains.clone()))
                } else {
                    None
                }
            }
            FieldValue::NoiseCycle(flags) => {
                if setting != HeadsetSetting::NoiseCycle || flags.values().any(Option::is_none) {
                    return None;
                }
                let concrete = flags
                    .iter()
                    .map(|(k, v)| (k.clone(), v.unwrap_or(false)))
                    .collect();
                Some(Self::NoiseCycle(concrete))
            }
            FieldValue::TouchAssignment {
                side,
                gesture,
                action,
            } => match setting {
                HeadsetSetting::TouchBinding => Some(Self::TouchBinding {
                    side: side.clone(),
                    gesture: gesture.clone(),
                    action: action.clone(),
                }),
                HeadsetSetting::TapBinding => Some(Self::TapBinding {
                    side: side.clone(),
                    gesture: gesture.clone(),
                    action: action.clone(),
                }),
                _ => None,
            },
            FieldValue::Firmware(_) | FieldValue::TouchKeys(_) => None,
        }
    }

    pub fn validate(
        &self,
        profile: ModelProfile,
        capabilities: &HeadsetCapabilities,
        fields: &BTreeMap<HeadsetField, FieldReading>,
    ) -> Result<(), ValidationError> {
        let setting = self.setting();
        if !capabilities.writable_settings.contains(&setting) {
            return Err(ValidationError::unsupported(format!(
                "{} 尚不支持写入",
                setting.name()
            )));
        }
        if let Some(prerequisite) = setting_prerequisite(fields, setting) {
            let detail = match prerequisite {
                SettingPrerequisite::SpatialModeRequired => "请先开启空间模式",
                SettingPrerequisite::IndependentMicActivationRequired => "请先激活独立麦克风",
            };
            return Err(ValidationError::not_ready(detail));
        }
        self.check_options(profile, capabilities)
    }

    pub fn check_options(
        &self,
        profile: ModelProfile,
        capabilities: &HeadsetCapabilities,
    ) -> Result<(), ValidationError> {
        let invalid_unless = |condition: bool| -> Result<(), ValidationError> {
            if condition {
                Ok(())
            } else {
                Err(ValidationError::invalid_argument(format!(
                    "{} 参数无效",
                    self.setting().name()
                )))
            }
        };
        match self {
            Self::NoiseMode(mode) => {
                if capabilities
                    .noise_control
                    .as_ref()
                    .is_some_and(|n| n.modes.contains(mode.id()))
                {
                    Ok(())
                } else {
                    Err(ValidationError::unsupported(format!(
                        "该型号不支持 {} 模式",
                        mode.id()
                    )))
                }
            }
            Self::AncLevel(level) => check_noise_level(
                *level,
                capabilities
                    .noise_control
                    .as_ref()
                    .and_then(|n| n.anc_level.as_ref()),
                self.setting(),
            ),
            Self::TransparencyLevel(level) => check_noise_level(
                *level,
                capabilities
                    .noise_control
                    .as_ref()
                    .and_then(|n| n.transparency_level.as_ref()),
                self.setting(),
            ),
            Self::NoiseCycle(flags) => {
                let Some(noise) = capabilities.noise_control.as_ref() else {
                    return Err(ValidationError::unsupported("该型号不支持降噪轮换"));
                };
                let keys_match = flags.len() == noise.cycle_modes.len()
                    && flags.keys().all(|k| noise.cycle_modes.contains(k));
                let enabled_count = flags.values().filter(|&&v| v).count();
                invalid_unless(keys_match && enabled_count >= noise.minimum_cycle_selections as usize)
            }
            Self::EqualizerPreset(preset) => {
                if capabilities
                    .equalizer
                    .as_ref()
                    .is_some_and(|eq| eq.presets.contains(preset))
                {
                    Ok(())
                } else {
                    Err(ValidationError::unsupported(format!(
                        "该型号与固件不支持 {preset} EQ"
                    )))
                }
            }
            Self::CustomEqualizer(gains) => {
                let Some(custom) = capabilities
                    .equalizer
                    .as_ref()
                    .and_then(|eq| eq.custom.as_ref())
                else {
                    return Err(ValidationError::unsupported("该型号不支持自定义均衡器"));
                };
                invalid_unless(
                    gains.len() == custom.bands_hz.len()
                        && gains
                            .iter()
                            .all(|&g| (custom.min_gain..=custom.max_gain).contains(&g)),
                )
            }
            Self::TouchBinding {
                side,
                gesture,
                action,
            } => check_binding(capabilities.touch_control.as_ref(), side, gesture, action),
            Self::TapBinding {
                side,
                gesture,
                action,
            } => check_binding(capabilities.tap_control.as_ref(), side, gesture, action),
            Self::PromptVolume(level) => {
                if capabilities.prompt_volume_levels.contains(level) {
                    Ok(())
                } else if profile.family().prompt_volume_levels().contains(level) {
                    let message = if *level == 0 {
                        "当前固件不支持关闭提示音"
                    } else {
                        "当前固件不支持此提示音档位"
                    };
                    Err(ValidationError::unsupported(message))
                } else {
                    Err(ValidationError::invalid_argument(format!(
                        "{} 参数无效",
                        self.setting().name()
                    )))
                }
            }
            Self::Gain(level) => invalid_unless(capabilities.gain_levels.contains(level)),
            Self::HearingProtection(level) => {
                invalid_unless(capabilities.hearing_protection_levels.contains(level))
            }
            Self::BassBoost(level) => {
                invalid_unless(capabilities.bass_boost_levels.contains(level))
            }
            Self::AudioMode(mode) => {
                if capabilities
                    .audio_control
                    .as_ref()
                    .is_some_and(|a| a.modes.contains(mode))
                {
                    Ok(())
                } else {
                    Err(ValidationError::unsupported(format!(
                        "该型号不支持 {mode} 音频配置"
                    )))
                }
            }
            Self::PromptLanguage(language) => {
                invalid_unless(capabilities.prompt_languages.contains(language))
            }
            Self::SpatialMode(mode) => invalid_unless(capabilities.spatial_modes.contains(mode)),
            Self::SpatialContent(content) => {
                invalid_unless(capabilities.spatial_contents.contains(content))
            }
            Self::GameMode(_)
            | Self::Multipoint(_)
            | Self::TouchEnabled(_)
            | Self::EarDetection(_)
            | Self::TapEnabled(_)
            | Self::SpatialAudio(_)
            | Self::EarAdaptive(_)
            | Self::IndependentMicActivation(_)
            | Self::IndependentMicMode(_) => Ok(()),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum SettingPrerequisite {
    SpatialModeRequired,
    IndependentMicActivationRequired,
}

pub fn hearing_protection_threshold_db(level: i32) -> Option<i32> {
    (1..=6).contains(&level).then_some(70 + level * 5)
}

pub fn setting_prerequisite(
    fields: &BTreeMap<HeadsetField, FieldReading>,
    setting: HeadsetSetting,
) -> Option<SettingPrerequisite> {
    let requirement = match setting {
        HeadsetSetting::SpatialContent => HeadsetField::SpatialMode,
        HeadsetSetting::IndependentMicMode => HeadsetField::IndependentMicActivation,
        _ => return None,
    };
    let current = fields.get(&requirement).and_then(FieldReading::known_value);
    match requirement {
        HeadsetField::SpatialMode => match current {
            Some(FieldValue::Text(mode)) if matches!(mode.as_str(), "head_tracking" | "fixed") => {
                None
            }
            _ => Some(SettingPrerequisite::SpatialModeRequired),
        },
        _ => match current {
            Some(FieldValue::Toggle(true)) => None,
            _ => Some(SettingPrerequisite::IndependentMicActivationRequired),
        },
    }
}

fn check_noise_level(
    level: i32,
    capability: Option<&NoiseLevelCapabilities>,
    setting: HeadsetSetting,
) -> Result<(), ValidationError> {
    let Some(cap) = capability else {
        return Err(ValidationError::unsupported("该型号不支持此档位设置"));
    };
    if cap.levels.contains(&level) {
        Ok(())
    } else {
        Err(ValidationError::invalid_argument(format!(
            "{} 参数无效",
            setting.name()
        )))
    }
}

fn check_binding(
    touch: Option<&TouchControlCapabilities>,
    side: &str,
    gesture: &str,
    action: &str,
) -> Result<(), ValidationError> {
    let Some(cap) = touch else {
        return Err(ValidationError::unsupported("该型号不支持触控绑定"));
    };
    if !cap.slots.contains(&TouchSlot::new(side, gesture)) {
        return Err(ValidationError::invalid_argument("触控作用耳或手势无效"));
    }
    if !cap.actions.contains(action) {
        return Err(ValidationError::unsupported(format!(
            "该型号不支持 {action} 触控动作"
        )));
    }
    Ok(())
}
