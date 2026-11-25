use crate::model::{BatteryLayout, ModelProfile};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub enum ReconnectGuidance {
    OpenCaseAutoConnect,
    WaitSystemBluetooth,
}

impl ModelProfile {
    pub fn reconnect_guidance(self) -> ReconnectGuidance {
        if self.battery_layout() == BatteryLayout::EarbudsAndCase {
            ReconnectGuidance::OpenCaseAutoConnect
        } else {
            ReconnectGuidance::WaitSystemBluetooth
        }
    }
}
