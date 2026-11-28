#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!();

pub mod capabilities;
pub mod command;
pub mod error;
#[cfg(feature = "uniffi")]
pub mod ffi;
pub mod field;
pub mod guidance;
pub mod hex;
pub mod local_time;
pub mod model;
pub mod protocol;
pub mod simulator;
pub mod state;

pub use capabilities::{
    AudioControlCapabilities, CustomEqualizerCapabilities, EqualizerCapabilities,
    HeadsetCapabilities, NoiseControlCapabilities, NoiseLevelCapabilities, NoiseMode,
    TouchControlCapabilities, basic_noise_modes, earbud_touch_slots, firmware_at_least,
    firmware_comparison_integer, prompt_volume_levels,
};
pub use command::{
    HeadsetCommand, SettingPrerequisite, hearing_protection_threshold_db, setting_prerequisite,
};
pub use error::{ProtocolError, ValidationError, ValidationErrorKind};
pub use field::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetId, HeadsetSetting,
    TouchBinding, TouchSlot,
};
pub use guidance::ReconnectGuidance;
pub use hex::{HexError, decode_hex, encode_hex, is_lower_hex};
pub use local_time::LocalDateTime;
pub use model::RsCommonV2Protocol;
pub use model::identification::ModelIdentification;
pub use model::rs_common::{RsCommonInit, RsCommonProtocol};
pub use model::{
    BatteryLayout, CapabilityFacts, F8Protocol, GattProfile, GattSpec, ModelProfile, ModelProtocol,
    ModelSpec, ProtocolFamily, ROSELINK_PREFIX_LENGTHS, ZtProtocol, rs_readings,
};
pub use protocol::{
    ProtocolCodec, ProtocolQuery, ProtocolReply, ProtocolSetting, create_protocol_codec,
    custom_curve_bytes, validated_noise_cycle, validated_touch_assignment,
};
pub use state::{HeadsetReadings, confirmation_value};
