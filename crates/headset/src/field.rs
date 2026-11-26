use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct HeadsetId(pub String);

#[cfg(feature = "uniffi")]
uniffi::custom_newtype!(HeadsetId, String);

impl HeadsetId {
    pub fn new(value: impl Into<String>) -> Self {
        Self(value.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum HeadsetField {
    LeftBattery,
    RightBattery,
    CaseBattery,
    LeftCharging,
    RightCharging,
    LeftInCase,
    RightInCase,
    CaseFlag,
    Firmware,
    NoiseMode,
    Eq,
    EqSlot,
    GameMode,
    AudioMode,
    Multipoint,
    TouchKeys,
    TapEnabled,
    TapKeys,
    TouchEnabled,
    NoiseCycle,
    EarDetection,
    AncLevel,
    TransparencyLevel,
    PromptVolume,
    CustomEq,
    Gain,
    HearingProtection,
    PromptLanguage,
    FindState,
    SpatialAudio,
    HideSearch,
    SpatialMode,
    SpatialContent,
    EarAdaptive,
    IndependentMicActivation,
    IndependentMicMode,
}

impl HeadsetField {
    pub fn display_name(self) -> &'static str {
        match self {
            Self::LeftBattery => "左耳电量",
            Self::RightBattery => "右耳电量",
            Self::CaseBattery => "盒电量",
            Self::LeftCharging => "左耳充电",
            Self::RightCharging => "右耳充电",
            Self::LeftInCase => "左耳盒内标志",
            Self::RightInCase => "右耳盒内标志",
            Self::CaseFlag => "盒原始标志",
            Self::Firmware => "固件",
            Self::NoiseMode => "降噪模式",
            Self::Eq => "均衡器",
            Self::EqSlot => "自定义 EQ 槽位",
            Self::GameMode => "游戏模式",
            Self::AudioMode => "音频模式",
            Self::Multipoint => "多点连接",
            Self::TouchKeys => "触摸绑定",
            Self::TapEnabled => "轻拍开关",
            Self::TapKeys => "轻拍绑定",
            Self::TouchEnabled => "触摸开关",
            Self::NoiseCycle => "降噪轮换",
            Self::EarDetection => "佩戴检测功能",
            Self::AncLevel => "降噪档位",
            Self::TransparencyLevel => "通透档位",
            Self::PromptVolume => "提示音档位",
            Self::CustomEq => "自定义 EQ",
            Self::Gain => "增益档位",
            Self::HearingProtection => "听力保护档位",
            Self::PromptLanguage => "提示音语言",
            Self::FindState => "寻机事件",
            Self::SpatialAudio => "空间音频",
            Self::HideSearch => "隐藏搜索",
            Self::SpatialMode => "空间模式",
            Self::SpatialContent => "空间音频内容",
            Self::EarAdaptive => "耳道自适应",
            Self::IndependentMicActivation => "独立麦克风激活",
            Self::IndependentMicMode => "独立麦克风模式",
        }
    }

    pub fn is_battery_flag(self) -> bool {
        matches!(
            self,
            Self::LeftCharging
                | Self::RightCharging
                | Self::LeftInCase
                | Self::RightInCase
                | Self::CaseFlag
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum HeadsetSetting {
    NoiseMode,
    AncLevel,
    TransparencyLevel,
    NoiseCycle,
    Eq,
    GameMode,
    AudioMode,
    Multipoint,
    TouchEnabled,
    EarDetection,
    TouchBinding,
    TapEnabled,
    TapBinding,
    PromptVolume,
    CustomEq,
    Gain,
    HearingProtection,
    BassBoost,
    PromptLanguage,
    SpatialAudio,
    SpatialMode,
    SpatialContent,
    EarAdaptive,
    IndependentMicActivation,
    IndependentMicMode,
}

impl HeadsetSetting {
    pub fn name(self) -> &'static str {
        match self {
            Self::NoiseMode => "NOISE_MODE",
            Self::AncLevel => "ANC_LEVEL",
            Self::TransparencyLevel => "TRANSPARENCY_LEVEL",
            Self::NoiseCycle => "NOISE_CYCLE",
            Self::Eq => "EQ",
            Self::GameMode => "GAME_MODE",
            Self::AudioMode => "AUDIO_MODE",
            Self::Multipoint => "MULTIPOINT",
            Self::TouchEnabled => "TOUCH_ENABLED",
            Self::EarDetection => "EAR_DETECTION",
            Self::TouchBinding => "TOUCH_BINDING",
            Self::TapEnabled => "TAP_ENABLED",
            Self::TapBinding => "TAP_BINDING",
            Self::PromptVolume => "PROMPT_VOLUME",
            Self::CustomEq => "CUSTOM_EQ",
            Self::Gain => "GAIN",
            Self::HearingProtection => "HEARING_PROTECTION",
            Self::BassBoost => "BASS_BOOST",
            Self::PromptLanguage => "PROMPT_LANGUAGE",
            Self::SpatialAudio => "SPATIAL_AUDIO",
            Self::SpatialMode => "SPATIAL_MODE",
            Self::SpatialContent => "SPATIAL_CONTENT",
            Self::EarAdaptive => "EAR_ADAPTIVE",
            Self::IndependentMicActivation => "INDEPENDENT_MIC_ACTIVATION",
            Self::IndependentMicMode => "INDEPENDENT_MIC_MODE",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct TouchSlot {
    pub side: String,
    pub gesture: String,
}

impl TouchSlot {
    pub fn new(side: impl Into<String>, gesture: impl Into<String>) -> Self {
        Self {
            side: side.into(),
            gesture: gesture.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct TouchBinding {
    pub side: String,
    pub gesture: String,
    pub action: Option<String>,
    pub raw_action: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct FirmwareVersion {
    pub parts: Vec<i32>,
    pub display_text: String,
}

impl FirmwareVersion {
    pub fn new(parts: Vec<i32>, display_text: impl Into<String>) -> Self {
        Self {
            parts,
            display_text: display_text.into(),
        }
    }

    pub fn from_parts(parts: Vec<i32>) -> Self {
        let display_text = parts.iter().map(i32::to_string).collect::<String>();
        Self {
            parts,
            display_text,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum FieldValue {
    Number(i32),
    Toggle(bool),
    Text(String),
    IntegerList(Vec<i32>),
    Firmware(FirmwareVersion),
    TouchKeys(Vec<TouchBinding>),
    TouchAssignment {
        side: String,
        gesture: String,
        action: String,
    },
    NoiseCycle(BTreeMap<String, Option<bool>>),
}

impl FieldValue {
    pub fn text(value: impl Into<String>) -> Self {
        Self::Text(value.into())
    }

    pub fn touch_assignment(
        side: impl Into<String>,
        gesture: impl Into<String>,
        action: impl Into<String>,
    ) -> Self {
        Self::TouchAssignment {
            side: side.into(),
            gesture: gesture.into(),
            action: action.into(),
        }
    }

    pub fn display_text(&self) -> String {
        match self {
            Self::Number(value) => value.to_string(),
            Self::Toggle(true) => "开启".to_string(),
            Self::Toggle(false) => "关闭".to_string(),
            Self::Text(value) => value.clone(),
            Self::IntegerList(values) => values
                .iter()
                .map(i32::to_string)
                .collect::<Vec<_>>()
                .join(", "),
            Self::Firmware(firmware) => firmware.display_text.clone(),
            Self::TouchKeys(bindings) => bindings
                .iter()
                .map(|binding| {
                    let action = binding
                        .action
                        .clone()
                        .unwrap_or_else(|| format!("未知({})", binding.raw_action));
                    format!("{}/{}: {action}", binding.side, binding.gesture)
                })
                .collect::<Vec<_>>()
                .join("；"),
            Self::TouchAssignment {
                side,
                gesture,
                action,
            } => format!("{side}/{gesture}: {action}"),
            Self::NoiseCycle(flags) => flags
                .iter()
                .map(|(mode, flag)| {
                    let state = match flag {
                        Some(true) => "开",
                        Some(false) => "关",
                        None => "未知",
                    };
                    format!("{mode}: {state}")
                })
                .collect::<Vec<_>>()
                .join("，"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum FieldReading {
    Loading,
    NotProvided { raw_hex: String },
    Unknown { raw_hex: String },
    Known { value: FieldValue, raw_hex: String },
}

impl FieldReading {
    pub fn not_provided(raw_hex: impl Into<String>) -> Self {
        Self::NotProvided {
            raw_hex: raw_hex.into(),
        }
    }

    pub fn unknown(raw_hex: impl Into<String>) -> Self {
        Self::Unknown {
            raw_hex: raw_hex.into(),
        }
    }

    pub fn known(value: FieldValue, raw_hex: impl Into<String>) -> Self {
        Self::Known {
            value,
            raw_hex: raw_hex.into(),
        }
    }

    pub fn raw_hex(&self) -> &str {
        match self {
            Self::Loading => "",
            Self::NotProvided { raw_hex }
            | Self::Unknown { raw_hex }
            | Self::Known { raw_hex, .. } => raw_hex,
        }
    }

    pub fn known_value(&self) -> Option<&FieldValue> {
        match self {
            Self::Known { value, .. } => Some(value),
            _ => None,
        }
    }
}
