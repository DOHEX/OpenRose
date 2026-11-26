use crate::audio::{AudioConnectionState, AudioProfile, RawProfileSnapshot};
use crate::error::ControlError;
use crate::transport::TransportError;
use openrose_headset::{HeadsetField, ffi_sorted_map, ffi_sorted_set};

ffi_sorted_set!(ConfirmedFields, HeadsetField);
ffi_sorted_map!(FieldErrors, HeadsetField, ControlError);
ffi_sorted_map!(AudioStates, String, AudioConnectionState);
ffi_sorted_map!(AudioProfiles, AudioProfile, Option<RawProfileSnapshot>);
ffi_sorted_map!(ManufacturerData, u16, Vec<u8>);

impl From<uniffi::UnexpectedUniFFICallbackError> for TransportError {
    fn from(error: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Terminal(error.reason)
    }
}
