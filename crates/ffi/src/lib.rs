uniffi::setup_scaffolding!();

mod drivers;
mod runtime;
mod simulator;

pub use drivers::{
    AudioEvents, AudioFactsDriver, ControlObserver, ControlTransport, GattDriver, GattDriverError,
    LocalClock, ScanDriver, SppDriver, SppDriverError, TransportDriverError,
};
pub use openrose_control::settings::ControlSettings;
pub use runtime::ControlRuntime;
pub use simulator::SimulatedHeadset;

use openrose_control::audio::normalize_bluetooth_address as core_normalize_bluetooth_address;
use openrose_control::snapshot::{ControlSnapshot, HeadsetCandidate, SessionReference};
use openrose_control::{ControlError, ControlErrorCode, SessionTiming};
use openrose_headset::{
    BatteryLayout, GattProfile, HeadsetSetting, LocalDateTime, ModelProfile, ProtocolFamily,
};
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

pub struct FixedClock {
    time: LocalDateTime,
}

impl FixedClock {
    pub fn new(year: u16, month: u8, day: u8, hour: u8, minute: u8, second: u8) -> Self {
        Self {
            time: LocalDateTime::new(year, month, day, hour, minute, second)
                .expect("valid fixed local date time"),
        }
    }
}

impl LocalClock for FixedClock {
    fn now(&self) -> LocalDateTime {
        self.time
    }
}

#[uniffi::export]
pub fn encode_snapshot(snapshot: ControlSnapshot) -> Vec<u8> {
    snapshot.encode()
}

#[uniffi::export]
pub fn decode_snapshot(bytes: Vec<u8>) -> Result<ControlSnapshot, ControlFailure> {
    ControlSnapshot::decode(&bytes).map_err(|error| ControlFailure::Failed {
        error: ControlError::new(ControlErrorCode::InvalidArgument, error.to_string()),
    })
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
pub fn session_for(
    snapshot: ControlSnapshot,
    setting: HeadsetSetting,
) -> Option<SessionReference> {
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
pub fn model_battery_layout(profile: ModelProfile) -> BatteryLayout {
    profile.battery_layout()
}

#[uniffi::export]
pub fn model_protocol_family(profile: ModelProfile) -> ProtocolFamily {
    profile.family()
}

#[uniffi::export]
pub fn normalize_bluetooth_address(value: String) -> Option<String> {
    core_normalize_bluetooth_address(&value)
}
