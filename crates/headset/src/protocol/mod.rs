pub mod f8;
pub mod zt;

use crate::capabilities::{CustomEqualizerCapabilities, TouchControlCapabilities};
use crate::error::ProtocolError;
use crate::field::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting, TouchSlot,
};
use crate::model::{ModelProfile, ModelProtocol};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProtocolQuery {
    pub key: String,
    pub bytes: Vec<u8>,
    pub response_keys: BTreeSet<String>,
    pub expected_fields: BTreeSet<HeadsetField>,
    pub delay_before_millis: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProtocolSetting {
    pub write_frames: Vec<Vec<u8>>,
    pub readback: ProtocolQuery,
    pub expected_values: BTreeMap<HeadsetField, FieldValue>,
    pub additional_readbacks: Vec<ProtocolQuery>,
}

impl ProtocolSetting {
    pub fn readbacks(&self) -> Vec<&ProtocolQuery> {
        let mut out = Vec::with_capacity(1 + self.additional_readbacks.len());
        out.push(&self.readback);
        out.extend(self.additional_readbacks.iter());
        out
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProtocolReply {
    pub raw_hex: String,
    pub response_keys: BTreeSet<String>,
    pub readings: BTreeMap<HeadsetField, FieldReading>,
    pub warnings: Vec<String>,
    pub accepted: bool,
}

impl ProtocolReply {
    pub fn new(
        raw_hex: String,
        response_keys: BTreeSet<String>,
        readings: BTreeMap<HeadsetField, FieldReading>,
        warnings: Vec<String>,
    ) -> Self {
        let accepted = readings.iter().any(|(field, reading)| {
            !field.is_battery_flag()
                && match reading {
                    FieldReading::Known { value, .. } => match value {
                        FieldValue::TouchKeys(bindings) => {
                            bindings.iter().any(|binding| binding.action.is_some())
                        }
                        FieldValue::NoiseCycle(flags) => flags.values().any(Option::is_some),
                        _ => true,
                    },
                    _ => false,
                }
        });
        Self {
            raw_hex,
            response_keys,
            readings,
            warnings,
            accepted,
        }
    }

    pub fn warning(raw_hex: String, message: impl Into<String>) -> Self {
        Self::new(
            raw_hex,
            BTreeSet::new(),
            BTreeMap::new(),
            vec![message.into()],
        )
    }

    pub fn for_readable_fields(
        &self,
        readable_fields: &BTreeSet<HeadsetField>,
    ) -> BTreeMap<HeadsetField, FieldReading> {
        self.readings
            .iter()
            .filter(|(field, _)| readable_fields.contains(field))
            .map(|(&field, reading)| (field, reading.clone()))
            .collect()
    }
}

pub trait ProtocolCodec: Send {
    fn profile(&self) -> ModelProfile;

    fn init_queries(&mut self, max_write_size: usize) -> Result<Vec<ProtocolQuery>, ProtocolError>;

    fn firmware_init_queries(&mut self, _firmware: Option<&FirmwareVersion>) -> Vec<ProtocolQuery> {
        Vec::new()
    }

    fn prepare_setting(
        &mut self,
        setting: HeadsetSetting,
        value: &FieldValue,
        firmware: Option<&FirmwareVersion>,
        current_fields: &BTreeMap<HeadsetField, FieldReading>,
    ) -> Result<ProtocolSetting, ProtocolError>;

    fn feed_notification(&mut self, bytes: &[u8]) -> Vec<ProtocolReply>;

    fn reset(&mut self);
}

pub fn create_protocol_codec(profile: ModelProfile) -> Box<dyn ProtocolCodec> {
    match profile.spec().protocol {
        ModelProtocol::Zt(protocol) => Box::new(zt::ZtCodec::new(profile, protocol)),
        ModelProtocol::F8(protocol) => Box::new(f8::F8Codec::new(profile, protocol)),
    }
}

pub fn validated_noise_cycle(
    value: &FieldValue,
    modes: &BTreeSet<String>,
) -> Result<BTreeMap<String, bool>, ProtocolError> {
    let FieldValue::NoiseCycle(flags) = value else {
        return Err(ProtocolError::InvalidArgument(
            "轮换需要四种模式的明确开关".to_string(),
        ));
    };
    if modes.is_empty()
        || flags.len() != modes.len()
        || !flags.keys().all(|key| modes.contains(key))
    {
        return Err(ProtocolError::InvalidArgument(
            "轮换必须包含该型号的全部可选模式".to_string(),
        ));
    }
    let mut concrete = BTreeMap::new();
    for (mode, flag) in flags {
        let Some(enabled) = flag else {
            return Err(ProtocolError::InvalidArgument(
                "轮换不能包含未知开关".to_string(),
            ));
        };
        concrete.insert(mode.clone(), *enabled);
    }
    Ok(concrete)
}

pub fn custom_curve_bytes(
    value: &FieldValue,
    capability: Option<&CustomEqualizerCapabilities>,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::IntegerList(gains) = value else {
        return Err(ProtocolError::InvalidArgument(
            "自定义均衡器需要整数曲线".to_string(),
        ));
    };
    let Some(cap) = capability else {
        return Err(ProtocolError::Unsupported(
            "该型号不支持自定义均衡器".to_string(),
        ));
    };
    if gains.len() != cap.bands_hz.len()
        || !gains
            .iter()
            .all(|&gain| (cap.min_gain..=cap.max_gain).contains(&gain))
    {
        return Err(ProtocolError::InvalidArgument(format!(
            "自定义均衡器需要 {} 段 {}..{} 的整数",
            cap.bands_hz.len(),
            cap.min_gain,
            cap.max_gain
        )));
    }
    Ok(gains
        .iter()
        .map(|&gain| {
            if gain < 0 {
                0x80u8 | ((-gain) as u8)
            } else {
                gain as u8
            }
        })
        .collect())
}

pub fn validated_touch_assignment<'a>(
    value: &'a FieldValue,
    capabilities: Option<&TouchControlCapabilities>,
) -> Result<(&'a str, &'a str, &'a str), ProtocolError> {
    let FieldValue::TouchAssignment {
        side,
        gesture,
        action,
    } = value
    else {
        return Err(ProtocolError::InvalidArgument(
            "触控绑定需要单个耳侧、手势和动作".to_string(),
        ));
    };
    let Some(cap) = capabilities else {
        return Err(ProtocolError::Unsupported(
            "该型号不支持此按键绑定".to_string(),
        ));
    };
    if !cap.slots.contains(&TouchSlot::new(side, gesture)) {
        return Err(ProtocolError::InvalidArgument("该触控槽位无效".to_string()));
    }
    if !cap.actions.contains(action) {
        return Err(ProtocolError::Unsupported(
            "该型号不支持此触控动作".to_string(),
        ));
    }
    Ok((side.as_str(), gesture.as_str(), action.as_str()))
}
