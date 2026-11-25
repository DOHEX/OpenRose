use crate::field::{FirmwareVersion, HeadsetField, HeadsetSetting, TouchSlot};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub enum NoiseMode {
    Normal,
    Anc,
    Transparency,
    Wind,
    Auto,
    Dynamic,
    Deep,
}

impl NoiseMode {
    pub const ALL: &'static [Self] = &[
        Self::Normal,
        Self::Anc,
        Self::Transparency,
        Self::Wind,
        Self::Auto,
        Self::Dynamic,
        Self::Deep,
    ];

    pub fn id(self) -> &'static str {
        match self {
            Self::Normal => "normal",
            Self::Anc => "anc",
            Self::Transparency => "transparency",
            Self::Wind => "wind",
            Self::Auto => "auto",
            Self::Dynamic => "dynamic",
            Self::Deep => "deep",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        Self::ALL.iter().copied().find(|mode| mode.id() == id)
    }

    pub fn display_order(id: &str) -> usize {
        Self::ALL
            .iter()
            .position(|mode| mode.id() == id)
            .unwrap_or(Self::ALL.len())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NoiseLevelCapabilities {
    pub levels: BTreeSet<i32>,
    pub visible_modes: BTreeSet<String>,
}

impl NoiseLevelCapabilities {
    pub fn is_visible_in(&self, mode: Option<&str>) -> bool {
        !self.levels.is_empty() && mode.is_some_and(|m| self.visible_modes.contains(m))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NoiseControlCapabilities {
    pub modes: BTreeSet<String>,
    pub cycle_modes: BTreeSet<String>,
    pub anc_level: Option<NoiseLevelCapabilities>,
    pub transparency_level: Option<NoiseLevelCapabilities>,
    pub minimum_cycle_selections: usize,
}

impl NoiseControlCapabilities {
    pub fn level_capabilities(&self, setting: HeadsetSetting) -> Option<&NoiseLevelCapabilities> {
        match setting {
            HeadsetSetting::AncLevel => self.anc_level.as_ref(),
            HeadsetSetting::TransparencyLevel => self.transparency_level.as_ref(),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CustomEqualizerCapabilities {
    pub bands_hz: Vec<i32>,
    pub min_gain: i32,
    pub max_gain: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EqualizerCapabilities {
    pub presets: BTreeSet<String>,
    pub custom: Option<CustomEqualizerCapabilities>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TouchControlCapabilities {
    pub slots: BTreeSet<TouchSlot>,
    pub actions: BTreeSet<String>,
    #[serde(with = "slot_labels_serde")]
    pub slot_labels: BTreeMap<TouchSlot, String>,
}

mod slot_labels_serde {
    use crate::field::TouchSlot;
    use serde::{Deserialize, Deserializer, Serialize, Serializer};
    use std::collections::BTreeMap;

    #[derive(Serialize, Deserialize)]
    struct SlotLabelEntry {
        slot: TouchSlot,
        label: String,
    }

    pub fn serialize<S>(map: &BTreeMap<TouchSlot, String>, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        let entries: Vec<SlotLabelEntry> = map
            .iter()
            .map(|(slot, label)| SlotLabelEntry {
                slot: slot.clone(),
                label: label.clone(),
            })
            .collect();
        entries.serialize(serializer)
    }

    pub fn deserialize<'de, D>(deserializer: D) -> Result<BTreeMap<TouchSlot, String>, D::Error>
    where
        D: Deserializer<'de>,
    {
        let entries = Vec::<SlotLabelEntry>::deserialize(deserializer)?;
        Ok(entries
            .into_iter()
            .map(|entry| (entry.slot, entry.label))
            .collect())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioControlCapabilities {
    pub modes: BTreeSet<String>,
    pub multipoint_coupled: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct HeadsetCapabilities {
    pub readable_fields: BTreeSet<HeadsetField>,
    pub writable_settings: BTreeSet<HeadsetSetting>,
    pub noise_control: Option<NoiseControlCapabilities>,
    pub equalizer: Option<EqualizerCapabilities>,
    pub touch_control: Option<TouchControlCapabilities>,
    pub tap_control: Option<TouchControlCapabilities>,
    pub audio_control: Option<AudioControlCapabilities>,
    pub prompt_volume_levels: BTreeSet<i32>,
    pub gain_levels: BTreeSet<i32>,
    pub hearing_protection_levels: BTreeSet<i32>,
    pub bass_boost_levels: BTreeSet<i32>,
    pub prompt_languages: BTreeSet<String>,
    pub spatial_modes: BTreeSet<String>,
    pub spatial_contents: BTreeSet<String>,
}

impl HeadsetCapabilities {
    pub fn without_writes_without_options(mut self) -> Self {
        let filtered: BTreeSet<HeadsetSetting> = self
            .writable_settings
            .iter()
            .copied()
            .filter(|&setting| self.setting_has_options(setting))
            .collect();
        self.writable_settings = filtered;
        self
    }

    fn setting_has_options(&self, setting: HeadsetSetting) -> bool {
        match setting {
            HeadsetSetting::NoiseMode => self
                .noise_control
                .as_ref()
                .is_some_and(|n| !n.modes.is_empty()),
            HeadsetSetting::NoiseCycle => self
                .noise_control
                .as_ref()
                .is_some_and(|n| !n.cycle_modes.is_empty()),
            HeadsetSetting::AncLevel => self
                .noise_control
                .as_ref()
                .and_then(|n| n.anc_level.as_ref())
                .is_some_and(|l| !l.levels.is_empty()),
            HeadsetSetting::TransparencyLevel => self
                .noise_control
                .as_ref()
                .and_then(|n| n.transparency_level.as_ref())
                .is_some_and(|l| !l.levels.is_empty()),
            HeadsetSetting::Eq => self
                .equalizer
                .as_ref()
                .is_some_and(|e| !e.presets.is_empty()),
            HeadsetSetting::CustomEq => self
                .equalizer
                .as_ref()
                .and_then(|e| e.custom.as_ref())
                .is_some_and(|c| !c.bands_hz.is_empty()),
            HeadsetSetting::TouchBinding => self
                .touch_control
                .as_ref()
                .is_some_and(|t| !t.slots.is_empty() && !t.actions.is_empty()),
            HeadsetSetting::TapBinding => self
                .tap_control
                .as_ref()
                .is_some_and(|t| !t.slots.is_empty() && !t.actions.is_empty()),
            HeadsetSetting::AudioMode => self
                .audio_control
                .as_ref()
                .is_some_and(|a| !a.modes.is_empty()),
            HeadsetSetting::PromptVolume => !self.prompt_volume_levels.is_empty(),
            HeadsetSetting::PromptLanguage => !self.prompt_languages.is_empty(),
            HeadsetSetting::Gain => !self.gain_levels.is_empty(),
            HeadsetSetting::HearingProtection => !self.hearing_protection_levels.is_empty(),
            HeadsetSetting::BassBoost => !self.bass_boost_levels.is_empty(),
            HeadsetSetting::SpatialMode => !self.spatial_modes.is_empty(),
            HeadsetSetting::SpatialContent => !self.spatial_contents.is_empty(),
            HeadsetSetting::GameMode
            | HeadsetSetting::Multipoint
            | HeadsetSetting::TouchEnabled
            | HeadsetSetting::EarDetection
            | HeadsetSetting::TapEnabled
            | HeadsetSetting::SpatialAudio
            | HeadsetSetting::EarAdaptive
            | HeadsetSetting::IndependentMicActivation
            | HeadsetSetting::IndependentMicMode => true,
        }
    }

    pub fn writable_noise_cycle(&self) -> Option<&NoiseControlCapabilities> {
        if !self.writable_settings.contains(&HeadsetSetting::NoiseCycle) {
            return None;
        }
        self.noise_control
            .as_ref()
            .filter(|n| !n.cycle_modes.is_empty())
    }

    pub fn writable_custom_curve(&self) -> Option<&CustomEqualizerCapabilities> {
        if !self.writable_settings.contains(&HeadsetSetting::CustomEq) {
            return None;
        }
        self.equalizer
            .as_ref()
            .and_then(|eq| eq.custom.as_ref())
            .filter(|custom| custom.min_gain <= custom.max_gain)
    }

    pub fn writable_presets(&self) -> BTreeSet<String> {
        if !self.writable_settings.contains(&HeadsetSetting::Eq) {
            return BTreeSet::new();
        }
        let mut presets = self
            .equalizer
            .as_ref()
            .map(|eq| eq.presets.clone())
            .unwrap_or_default();
        if self.writable_custom_curve().is_some() {
            presets.remove("custom");
        }
        presets
    }
}

pub fn firmware_comparison_integer(firmware: Option<&FirmwareVersion>) -> Option<i32> {
    let parts = &firmware?.parts;
    if parts.len() != 3 || !parts.iter().all(|part| (0..=255).contains(part)) {
        return None;
    }
    let joined = format!("{}{}{}", parts[0], parts[1], parts[2]);
    joined.parse::<i32>().ok()
}

pub fn firmware_at_least(firmware: Option<&FirmwareVersion>, version: i32) -> Option<bool> {
    let comparable = firmware_comparison_integer(firmware)?;
    Some(comparable >= version)
}

pub fn prompt_volume_levels(firmware: Option<&FirmwareVersion>, off_since: i32) -> BTreeSet<i32> {
    match firmware_at_least(firmware, off_since) {
        None => BTreeSet::new(),
        Some(true) => (0..=5).collect(),
        Some(false) => (1..=5).collect(),
    }
}

pub fn basic_noise_modes() -> BTreeSet<String> {
    ["normal", "transparency", "wind", "anc"]
        .into_iter()
        .map(str::to_string)
        .collect()
}

pub fn earbud_touch_slots() -> BTreeSet<TouchSlot> {
    let mut slots = BTreeSet::new();
    for side in ["left", "right"] {
        for gesture in ["single", "double", "triple", "long"] {
            slots.insert(TouchSlot::new(side, gesture));
        }
    }
    slots
}
