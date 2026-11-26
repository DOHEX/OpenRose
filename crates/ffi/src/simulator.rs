use crate::ControlFailure;
use openrose_control::{ControlError, ControlErrorCode};
use openrose_headset::simulator::SimulatedHeadset as CoreSimulatedHeadset;
use openrose_headset::{FieldValue, FirmwareVersion, HeadsetField, ModelProfile};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
#[derive(uniffi::Object)]
pub struct SimulatedHeadset {
    inner: Mutex<CoreSimulatedHeadset>,
}

#[uniffi::export]
impl SimulatedHeadset {
    #[uniffi::constructor]
    pub fn new(
        profile: ModelProfile,
        firmware: FirmwareVersion,
    ) -> Result<Arc<Self>, ControlFailure> {
        let inner = CoreSimulatedHeadset::new(profile, firmware).map_err(|error| {
            ControlFailure::Failed {
                error: ControlError::new(ControlErrorCode::Unsupported, error.to_string()),
            }
        })?;
        Ok(Arc::new(Self {
            inner: Mutex::new(inner),
        }))
    }

    pub fn receive(&self, frame: Vec<u8>) -> Vec<Vec<u8>> {
        self.inner
            .lock()
            .expect("simulated headset lock")
            .receive(&frame)
    }
    pub fn readings(&self) -> HashMap<HeadsetField, FieldValue> {
        self.inner
            .lock()
            .expect("simulated headset lock")
            .readings()
            .into_iter()
            .collect()
    }
}
