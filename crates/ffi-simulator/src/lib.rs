uniffi::setup_scaffolding!();

use openrose_headset::simulator::SimulatedHeadset as CoreSimulatedHeadset;
use openrose_headset::{FieldValue, FirmwareVersion, HeadsetField, ModelProfile};
use std::collections::HashMap;
use std::fmt;
use std::sync::{Arc, Mutex};

#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum SimulatorFailure {
    Rejected { reason: String },
}

impl fmt::Display for SimulatorFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Rejected { reason } => f.write_str(reason),
        }
    }
}

impl std::error::Error for SimulatorFailure {}

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
    ) -> Result<Arc<Self>, SimulatorFailure> {
        let inner = CoreSimulatedHeadset::new(profile, firmware).map_err(|error| {
            SimulatorFailure::Rejected {
                reason: error.to_string(),
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
