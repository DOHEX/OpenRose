uniffi::setup_scaffolding!();

mod drivers;
mod runtime;

pub use drivers::{
    AudioEvents, AudioFactsDriver, ControlObserver, ControlTransport, DriverError, GattDriver,
    LocalClock, ScanDriver, SppDriver,
};
pub use openrose_control::settings::ControlSettings;
pub use runtime::ControlRuntime;

use openrose_control::audio::normalize_bluetooth_address as core_normalize_bluetooth_address;
use openrose_control::snapshot::{ControlSnapshot, HeadsetCandidate, SessionReference};
use openrose_control::{ControlError, SessionTiming};
#[cfg(feature = "simulator")]
use openrose_ffi_simulator as _;
use openrose_headset::{GattProfile, HeadsetSetting, ModelProfile};
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum ControlFailure {
    Failed { error: ControlError },
}

impl fmt::Display for ControlFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Failed { error } => f.write_str(&error.message),
        }
    }
}

impl std::error::Error for ControlFailure {}

impl From<ControlError> for ControlFailure {
    fn from(error: ControlError) -> Self {
        Self::Failed { error }
    }
}

#[uniffi::export]
pub fn current_headset(snapshot: ControlSnapshot) -> Option<HeadsetCandidate> {
    snapshot.current_headset()
}

#[uniffi::export]
pub fn ready_session(snapshot: ControlSnapshot) -> Option<SessionReference> {
    snapshot.ready_session()
}

#[uniffi::export]
pub fn session_for(snapshot: ControlSnapshot, setting: HeadsetSetting) -> Option<SessionReference> {
    snapshot.session_for(setting)
}

#[uniffi::export]
pub fn headset_has_system_audio(snapshot: ControlSnapshot) -> bool {
    snapshot.headset_has_system_audio()
}

#[uniffi::export]
pub fn default_session_timing() -> SessionTiming {
    SessionTiming::default()
}

#[uniffi::export]
pub fn model_display_name(profile: ModelProfile) -> String {
    profile.display_name().to_string()
}

#[uniffi::export]
pub fn model_gatt_profile(profile: ModelProfile) -> GattProfile {
    profile.gatt()
}

#[uniffi::export]
pub fn normalize_bluetooth_address(value: String) -> Option<String> {
    core_normalize_bluetooth_address(&value)
}
