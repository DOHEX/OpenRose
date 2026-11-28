uniffi::setup_scaffolding!();

mod circulation;
mod drivers;
mod runtime;

pub use circulation::{
    CirculationFailure, CirculationNode, LocalAudio, NearbySink, OwnerActive, PeerDiscovery,
    PeerRoute, PeerRouteDialer, circulation_discovery_contract, circulation_service_instance_name,
    circulation_wire_format_version, decode_circulation_command, decode_circulation_reply,
    encode_circulation_command, encode_circulation_reply,
};
pub use drivers::{
    AudioEvents, AudioFactsDriver, ControlObserver, ControlTransport, DriverError, GattDriver,
    LocalClock, ScanDriver, SppDriver, YieldRefusalKind,
};
pub use openrose_control::settings::ControlSettings;
pub use runtime::ControlRuntime;

use openrose_control::audio::normalize_bluetooth_address as core_normalize_bluetooth_address;
use openrose_control::codec::{CodecError, ControlCommand, WIRE_FORMAT_VERSION};
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
pub fn wire_format_version() -> u8 {
    WIRE_FORMAT_VERSION
}

#[uniffi::export]
pub fn encode_snapshot(snapshot: ControlSnapshot) -> Vec<u8> {
    snapshot.encode()
}

#[uniffi::export]
pub fn decode_snapshot(bytes: Vec<u8>) -> Result<ControlSnapshot, CodecError> {
    ControlSnapshot::decode(&bytes)
}

#[uniffi::export]
pub fn encode_command(command: ControlCommand) -> Vec<u8> {
    command.encode()
}

#[uniffi::export]
pub fn decode_command(bytes: Vec<u8>) -> Result<ControlCommand, CodecError> {
    ControlCommand::decode(&bytes)
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
