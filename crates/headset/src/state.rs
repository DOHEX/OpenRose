use crate::capabilities::{CustomEqualizerCapabilities, HeadsetCapabilities, NoiseMode};
use crate::command::{HeadsetCommand, setting_prerequisite};
use crate::error::ValidationError;
use crate::field::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting, TouchBinding,
};
use crate::model::{CapabilityFacts, ModelProfile};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct HeadsetReadings {
    pub profile: ModelProfile,
    pub capabilities: HeadsetCapabilities,
    pub fields: BTreeMap<HeadsetField, FieldReading>,
}

impl HeadsetReadings {
    pub fn new(profile: ModelProfile) -> Self {
        let capabilities = profile.capabilities(None, None);
        let fields = capabilities
            .readable_fields
            .iter()
            .copied()
            .map(|field| (field, FieldReading::Loading))
            .collect();
        Self {
            profile,
            capabilities,
            fields,
        }
    }

    pub fn from_fields(
        profile: ModelProfile,
        fields: BTreeMap<HeadsetField, FieldReading>,
    ) -> Self {
        let capabilities = profile.capabilities_from_readings(&fields);
        Self {
            profile,
            capabilities,
            fields,
        }
    }

    pub fn with_readings(&self, readings: &BTreeMap<HeadsetField, FieldReading>) -> Self {
        if readings.is_empty() {
            return self.clone();
        }
        let mut fields = self.fields.clone();
        for (&field, reading) in readings {
            fields.insert(field, reading.clone());
        }
        if self.changes_preset_bank(readings) {
            fields.insert(HeadsetField::Eq, FieldReading::Loading);
        }

        let previous_facts = CapabilityFacts::from_readings(&self.fields);
        let next_facts = CapabilityFacts::from_readings(&fields);
        let next_capabilities = if next_facts == previous_facts {
            self.capabilities.clone()
        } else {
            let resolved = next_facts.resolve(self.profile);
            for field in self
                .capabilities
                .readable_fields
                .difference(&resolved.readable_fields)
            {
                if fields.get(field) == Some(&FieldReading::Loading) {
                    fields.remove(field);
                }
            }
            for &field in &resolved.readable_fields {
                fields.entry(field).or_insert(FieldReading::Loading);
            }
            resolved
        };

        Self {
            profile: self.profile,
            capabilities: next_capabilities,
            fields,
        }
    }

    fn changes_preset_bank(&self, readings: &BTreeMap<HeadsetField, FieldReading>) -> bool {
        if !self.profile.refresh_equalizer_after_game_mode()
            || readings.contains_key(&HeadsetField::Eq)
        {
            return false;
        }
        let Some(game) = readings.get(&HeadsetField::GameMode) else {
            return false;
        };
        let Some(previous) = self.reading(HeadsetField::GameMode) else {
            return false;
        };
        previous != &FieldReading::Loading && previous != game
    }

    pub fn reading(&self, field: HeadsetField) -> Option<&FieldReading> {
        self.fields.get(&field)
    }

    pub fn known_value(&self, field: HeadsetField) -> Option<&FieldValue> {
        self.reading(field).and_then(FieldReading::known_value)
    }

    pub fn confirmed_number(&self, field: HeadsetField) -> Option<i32> {
        match self.known_value(field) {
            Some(FieldValue::Number(value)) => Some(*value),
            _ => None,
        }
    }

    pub fn confirmed_text(&self, field: HeadsetField) -> Option<&str> {
        match self.known_value(field) {
            Some(FieldValue::Text(value)) => Some(value.as_str()),
            _ => None,
        }
    }

    pub fn confirmed_toggle(&self, field: HeadsetField) -> Option<bool> {
        match self.known_value(field) {
            Some(FieldValue::Toggle(value)) => Some(*value),
            _ => None,
        }
    }

    pub fn confirmed_firmware(&self) -> Option<&FirmwareVersion> {
        match self.known_value(HeadsetField::Firmware) {
            Some(FieldValue::Firmware(value)) => Some(value),
            _ => None,
        }
    }

    pub fn noise_modes(&self) -> Vec<NoiseMode> {
        self.capabilities
            .noise_control
            .as_ref()
            .map(|noise| {
                noise
                    .modes
                    .iter()
                    .filter_map(|id| NoiseMode::from_id(id))
                    .collect()
            })
            .unwrap_or_default()
    }

    pub fn confirmed_noise_mode(&self) -> Option<NoiseMode> {
        self.confirmed_text(HeadsetField::NoiseMode)
            .and_then(NoiseMode::from_id)
    }

    pub fn confirmed_curve(&self, custom: &CustomEqualizerCapabilities) -> Option<Vec<i32>> {
        let gains = match self.known_value(HeadsetField::CustomEq) {
            Some(FieldValue::IntegerList(values)) => values,
            _ => return None,
        };
        if gains.len() == custom.bands_hz.len()
            && gains
                .iter()
                .all(|&g| (custom.min_gain..=custom.max_gain).contains(&g))
        {
            Some(gains.clone())
        } else {
            None
        }
    }

    pub fn confirmed_bass_boost_level(&self) -> Option<i32> {
        let (mode_val, mode_hex) = match self.reading(HeadsetField::Eq) {
            Some(FieldReading::Known { value, raw_hex }) => (value, raw_hex),
            _ => return None,
        };
        let (slot_val, slot_hex) = match self.reading(HeadsetField::EqSlot) {
            Some(FieldReading::Known { value, raw_hex }) => (value, raw_hex),
            _ => return None,
        };
        if mode_val != &FieldValue::Text("custom_slot".to_string()) || mode_hex != slot_hex {
            return None;
        }
        match slot_val {
            FieldValue::Number(level) if self.capabilities.bass_boost_levels.contains(level) => {
                Some(*level)
            }
            _ => None,
        }
    }

    pub fn setting_prerequisite(&self, setting: HeadsetSetting) -> Option<&'static str> {
        setting_prerequisite(&self.fields, setting)
    }

    pub fn validate(&self, command: &HeadsetCommand) -> Result<(), ValidationError> {
        command.validate(self.profile, &self.capabilities, &self.fields)
    }

    pub fn simulate_readback(
        &self,
        expected: &BTreeMap<HeadsetField, FieldValue>,
    ) -> BTreeMap<HeadsetField, FieldReading> {
        expected
            .iter()
            .map(|(&field, value)| {
                let resolved = match value {
                    FieldValue::TouchAssignment {
                        side,
                        gesture,
                        action,
                    } => {
                        let current = match self.known_value(field) {
                            Some(FieldValue::TouchKeys(bindings)) => bindings.as_slice(),
                            _ => &[],
                        };
                        FieldValue::TouchKeys(assign_touch_binding(current, side, gesture, action))
                    }
                    other => other.clone(),
                };
                (field, FieldReading::known(resolved, ""))
            })
            .collect()
    }
}

pub fn confirmation_value(
    reading: Option<&FieldReading>,
    expected: &FieldValue,
) -> Option<FieldValue> {
    let actual = reading.and_then(FieldReading::known_value)?;
    match (expected, actual) {
        (FieldValue::TouchAssignment { side, gesture, .. }, FieldValue::TouchKeys(bindings)) => {
            let matched = bindings
                .iter()
                .find(|binding| binding.side == *side && binding.gesture == *gesture)?;
            let action = matched.action.clone()?;
            Some(FieldValue::TouchAssignment {
                side: side.clone(),
                gesture: gesture.clone(),
                action,
            })
        }
        (_, FieldValue::NoiseCycle(flags)) if flags.values().any(Option::is_none) => None,
        _ => Some(actual.clone()),
    }
}

fn assign_touch_binding(
    existing: &[TouchBinding],
    side: &str,
    gesture: &str,
    action: &str,
) -> Vec<TouchBinding> {
    let matches_slot = |binding: &TouchBinding| binding.side == side && binding.gesture == gesture;
    if existing.iter().any(matches_slot) {
        existing
            .iter()
            .map(|binding| {
                if matches_slot(binding) {
                    TouchBinding {
                        side: side.to_string(),
                        gesture: gesture.to_string(),
                        action: Some(action.to_string()),
                        raw_action: binding.raw_action,
                    }
                } else {
                    binding.clone()
                }
            })
            .collect()
    } else {
        let mut next = existing.to_vec();
        next.push(TouchBinding {
            side: side.to_string(),
            gesture: gesture.to_string(),
            action: Some(action.to_string()),
            raw_action: -1,
        });
        next
    }
}
