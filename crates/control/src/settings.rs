use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

const FORCE_BLE_KEY: &str = "core.force_ble";

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct ControlSettings {
    pub force_ble: bool,
}

impl ControlSettings {
    pub fn encode(&self) -> BTreeMap<String, String> {
        BTreeMap::from([(FORCE_BLE_KEY.to_string(), self.force_ble.to_string())])
    }

    pub fn decode(values: &BTreeMap<String, String>) -> Self {
        Self {
            force_ble: values
                .get(FORCE_BLE_KEY)
                .and_then(|value| value.parse().ok())
                .unwrap_or_default(),
        }
    }
}
