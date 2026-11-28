mod f8;
mod rs_battery;
mod rs_common;
mod rs_common_v2;
mod zt;

use crate::command::HeadsetCommand;
use crate::field::{FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting};
use crate::model::{ModelProfile, ModelProtocol};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SimulatorError {
    InvalidFirmware(String),
    InvalidValue(String),
}

impl SimulatorError {
    fn refused(field: HeadsetField, value: &FieldValue) -> Self {
        Self::InvalidValue(format!("模拟耳机不能把 {field:?} 设为 {value:?}"))
    }
}

impl fmt::Display for SimulatorError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidFirmware(message) | Self::InvalidValue(message) => f.write_str(message),
        }
    }
}

impl std::error::Error for SimulatorError {}

trait HeadsetSide: Send {
    fn acknowledge_writes(&mut self, enabled: bool);
    fn receive(&mut self, frame: &[u8]) -> Vec<Vec<u8>>;
    fn readings(&self) -> BTreeMap<HeadsetField, FieldValue>;
    fn set(
        &mut self,
        field: HeadsetField,
        value: FieldValue,
    ) -> Result<Vec<Vec<u8>>, SimulatorError>;
}

pub struct SimulatedHeadset {
    profile: ModelProfile,
    firmware: FirmwareVersion,
    side: Box<dyn HeadsetSide>,
}

impl SimulatedHeadset {
    pub fn new(profile: ModelProfile, firmware: FirmwareVersion) -> Result<Self, SimulatorError> {
        let capabilities = profile.capabilities(Some(&firmware), Some(false));
        let side: Box<dyn HeadsetSide> = match profile.spec().protocol {
            ModelProtocol::Zt(protocol) => Box::new(zt::ZtHeadsetSide::new(
                profile,
                protocol,
                &firmware,
                &capabilities,
            )?),
            ModelProtocol::RsCommonV2(_) => Box::new(rs_common_v2::RsCommonV2HeadsetSide::new(
                profile,
                &firmware,
                &capabilities,
            )?),
            ModelProtocol::RsCommon(protocol) => Box::new(rs_common::RsCommonHeadsetSide::new(
                profile,
                protocol,
                &firmware,
                &capabilities,
            )?),
            ModelProtocol::F8(protocol) => Box::new(f8::F8HeadsetSide::new(
                profile,
                protocol,
                &firmware,
                &capabilities,
            )?),
        };
        Ok(Self {
            profile,
            firmware,
            side,
        })
    }

    pub fn profile(&self) -> ModelProfile {
        self.profile
    }

    pub fn firmware(&self) -> &FirmwareVersion {
        &self.firmware
    }

    pub fn acknowledge_writes(&mut self, enabled: bool) {
        self.side.acknowledge_writes(enabled);
    }

    pub fn receive(&mut self, frame: &[u8]) -> Vec<Vec<u8>> {
        self.side.receive(frame)
    }

    pub fn readings(&self) -> BTreeMap<HeadsetField, FieldValue> {
        let readable = self
            .profile
            .capabilities(Some(&self.firmware), None)
            .readable_fields;
        let mut readings = self.side.readings();
        readings.retain(|field, _| readable.contains(field));
        readings
    }

    pub fn set(
        &mut self,
        field: HeadsetField,
        value: FieldValue,
    ) -> Result<Vec<Vec<u8>>, SimulatorError> {
        if !self.permits(field, &value) {
            return Err(SimulatorError::refused(field, &value));
        }
        self.side.set(field, value)
    }

    fn permits(&self, field: HeadsetField, value: &FieldValue) -> bool {
        let game_mode = match self.side.readings().get(&HeadsetField::GameMode) {
            Some(FieldValue::Toggle(on)) => Some(*on),
            _ => None,
        };
        let capabilities = self.profile.capabilities(Some(&self.firmware), game_mode);
        if !capabilities.readable_fields.contains(&field) {
            return false;
        }
        match setting_of(field) {
            Some(setting) => HeadsetCommand::decode(setting, value)
                .is_some_and(|command| command.check_options(self.profile, &capabilities).is_ok()),
            None => true,
        }
    }
}

fn setting_of(field: HeadsetField) -> Option<HeadsetSetting> {
    Some(match field {
        HeadsetField::NoiseMode => HeadsetSetting::NoiseMode,
        HeadsetField::AncLevel => HeadsetSetting::AncLevel,
        HeadsetField::TransparencyLevel => HeadsetSetting::TransparencyLevel,
        HeadsetField::NoiseCycle => HeadsetSetting::NoiseCycle,
        HeadsetField::Eq => HeadsetSetting::Eq,
        HeadsetField::GameMode => HeadsetSetting::GameMode,
        HeadsetField::AudioMode => HeadsetSetting::AudioMode,
        HeadsetField::Multipoint => HeadsetSetting::Multipoint,
        HeadsetField::TouchEnabled => HeadsetSetting::TouchEnabled,
        HeadsetField::EarDetection => HeadsetSetting::EarDetection,
        HeadsetField::TouchKeys => HeadsetSetting::TouchBinding,
        HeadsetField::TapEnabled => HeadsetSetting::TapEnabled,
        HeadsetField::TapKeys => HeadsetSetting::TapBinding,
        HeadsetField::PromptVolume => HeadsetSetting::PromptVolume,
        HeadsetField::CustomEq => HeadsetSetting::CustomEq,
        HeadsetField::Gain => HeadsetSetting::Gain,
        HeadsetField::HearingProtection => HeadsetSetting::HearingProtection,
        HeadsetField::EqSlot => HeadsetSetting::BassBoost,
        HeadsetField::PromptLanguage => HeadsetSetting::PromptLanguage,
        HeadsetField::SpatialAudio => HeadsetSetting::SpatialAudio,
        HeadsetField::SpatialMode => HeadsetSetting::SpatialMode,
        HeadsetField::SpatialContent => HeadsetSetting::SpatialContent,
        HeadsetField::EarAdaptive => HeadsetSetting::EarAdaptive,
        HeadsetField::IndependentMicActivation => HeadsetSetting::IndependentMicActivation,
        HeadsetField::IndependentMicMode => HeadsetSetting::IndependentMicMode,
        HeadsetField::LeftBattery
        | HeadsetField::RightBattery
        | HeadsetField::CaseBattery
        | HeadsetField::LeftCharging
        | HeadsetField::RightCharging
        | HeadsetField::LeftInCase
        | HeadsetField::RightInCase
        | HeadsetField::CaseFlag
        | HeadsetField::Firmware
        | HeadsetField::FindState
        | HeadsetField::HideSearch => return None,
    })
}

fn preferred_index(table: &[&str], preferred: usize, allowed: &BTreeSet<String>) -> usize {
    if allowed.contains(table[preferred]) {
        return preferred;
    }
    table
        .iter()
        .position(|name| allowed.contains(*name))
        .unwrap_or(preferred)
}

fn preferred_level_index(table: &[i32], preferred: usize, allowed: &BTreeSet<i32>) -> usize {
    if allowed.contains(&table[preferred]) {
        return preferred;
    }
    table
        .iter()
        .position(|level| allowed.contains(level))
        .unwrap_or(preferred)
}

fn preferred_level(preferred: i32, allowed: &BTreeSet<i32>) -> i32 {
    if allowed.contains(&preferred) {
        preferred
    } else {
        allowed.first().copied().unwrap_or(preferred)
    }
}
