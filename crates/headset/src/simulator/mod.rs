mod rs_common_v2;
mod zt;

use crate::field::{FieldValue, FirmwareVersion, HeadsetField};
use crate::model::{ModelProfile, ProtocolFamily};
use std::collections::BTreeMap;
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SimulatorError {
    UnsupportedFamily(ProtocolFamily),
    InvalidFirmware(String),
    InvalidValue(String),
}

impl fmt::Display for SimulatorError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::UnsupportedFamily(family) => {
                write!(f, "模拟耳机尚不支持 {} 协议族", family.display_name())
            }
            Self::InvalidFirmware(message) | Self::InvalidValue(message) => f.write_str(message),
        }
    }
}

impl std::error::Error for SimulatorError {}

enum Device {
    Zt(zt::ZtDevice),
    RsV2(rs_common_v2::RsV2Device),
}

pub struct SimulatedHeadset {
    device: Device,
}

impl SimulatedHeadset {
    pub fn new(profile: ModelProfile, firmware: FirmwareVersion) -> Result<Self, SimulatorError> {
        match profile.family() {
            ProtocolFamily::Zt => Ok(Self {
                device: Device::Zt(zt::ZtDevice::new(profile, firmware)?),
            }),
            ProtocolFamily::RsCommonV2 => Ok(Self {
                device: Device::RsV2(rs_common_v2::RsV2Device::new(profile, firmware)?),
            }),
            family @ (ProtocolFamily::F8 | ProtocolFamily::RsCommon) => {
                Err(SimulatorError::UnsupportedFamily(family))
            }
        }
    }

    pub fn profile(&self) -> ModelProfile {
        match &self.device {
            Device::Zt(device) => device.profile(),
            Device::RsV2(device) => device.profile(),
        }
    }

    pub fn firmware(&self) -> &FirmwareVersion {
        match &self.device {
            Device::Zt(device) => device.firmware(),
            Device::RsV2(device) => device.firmware(),
        }
    }

    pub fn acknowledge_writes(&mut self, enabled: bool) {
        match &mut self.device {
            Device::Zt(device) => device.acknowledge_writes(enabled),
            Device::RsV2(device) => device.acknowledge_writes(enabled),
        }
    }

    pub fn receive(&mut self, frame: &[u8]) -> Vec<Vec<u8>> {
        match &mut self.device {
            Device::Zt(device) => device.receive(frame),
            Device::RsV2(device) => device.receive(frame),
        }
    }

    pub fn readings(&self) -> BTreeMap<HeadsetField, FieldValue> {
        match &self.device {
            Device::Zt(device) => device.readings(),
            Device::RsV2(device) => device.readings(),
        }
    }

    pub fn set(
        &mut self,
        field: HeadsetField,
        value: FieldValue,
    ) -> Result<Vec<Vec<u8>>, SimulatorError> {
        match &mut self.device {
            Device::Zt(device) => device.set(field, value),
            Device::RsV2(device) => device.set(field, value),
        }
    }
}
