use crate::error::ControlError;
use crate::selection::TransportSelection;
use crate::settings::ControlSettings;
use openrose_headset::{
    FieldValue, HeadsetField, HeadsetId, HeadsetReadings, HeadsetSetting, ModelProfile, NoiseMode,
    ReconnectGuidance,
};
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

const SETTING_HISTORY_LIMIT: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum HostPlatform {
    Unknown,
    Android,
    Windows,
    Linux,
    MacOs,
}

impl HostPlatform {
    pub fn is_desktop(self) -> bool {
        matches!(self, Self::Windows | Self::Linux | Self::MacOs)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PermissionState {
    NotRequired,
    Required,
    Granted,
    Denied,
}

impl PermissionState {
    pub fn missing(self) -> bool {
        matches!(self, Self::Required | Self::Denied)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransportUnavailableCode {
    UnsupportedPlatform,
    BleUnsupported,
    PermissionRequired,
    NoAdapter,
    AdapterPoweredOff,
    AdapterStateUnknown,
    AccessDenied,
    ServiceUnavailable,
    MissingDependency,
    Initializing,
    Recovering,
    ShuttingDown,
    Closed,
    BackendUnavailable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransportAvailability {
    Available,
    Disabled,
    Unavailable {
        code: TransportUnavailableCode,
        detail: Option<String>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConnectionBlockReason {
    BluetoothPermissionRequired,
    BluetoothDisabled,
    TransportUnavailable {
        code: TransportUnavailableCode,
        detail: Option<String>,
    },
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum PlatformApplicability {
    #[default]
    Unknown,
    NotApplicable,
    Applicable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlatformState {
    pub permission: PermissionState,
    pub transport: TransportAvailability,
    pub host_platform: HostPlatform,
    pub control_transport_applicability: PlatformApplicability,
}

impl PlatformState {
    pub fn new(permission: PermissionState, transport: TransportAvailability) -> Self {
        Self {
            permission,
            transport,
            host_platform: HostPlatform::Unknown,
            control_transport_applicability: PlatformApplicability::Unknown,
        }
    }

    pub fn on(mut self, host_platform: HostPlatform) -> Self {
        self.host_platform = host_platform;
        self
    }

    pub fn with_control_transport(mut self, applicability: PlatformApplicability) -> Self {
        self.control_transport_applicability = applicability;
        self
    }

    pub fn connection_block_reason(&self) -> Option<ConnectionBlockReason> {
        if self.permission.missing() {
            return Some(ConnectionBlockReason::BluetoothPermissionRequired);
        }
        match &self.transport {
            TransportAvailability::Available => None,
            TransportAvailability::Disabled => Some(ConnectionBlockReason::BluetoothDisabled),
            TransportAvailability::Unavailable { code, detail } => {
                Some(ConnectionBlockReason::TransportUnavailable {
                    code: *code,
                    detail: detail.clone(),
                })
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub enum ConnectionPhase {
    Connecting,
    Discovering,
    Subscribing,
    Initializing,
    Ready,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ConnectionState {
    Disconnected,
    Connecting {
        headset_id: HeadsetId,
        session_id: u64,
        phase: ConnectionPhase,
    },
    Connected {
        headset_id: HeadsetId,
        session_id: u64,
        profile: ModelProfile,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SessionReference {
    pub headset_id: HeadsetId,
    pub session_id: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HeadsetCandidate {
    pub id: HeadsetId,
    pub name: Option<String>,
    pub rssi: Option<i16>,
    pub profile: Option<ModelProfile>,
    pub manufacturer_data_hex: String,
    pub classic_address: Option<String>,
}

impl HeadsetCandidate {
    pub fn supported(id: HeadsetId, profile: ModelProfile) -> Self {
        Self {
            id,
            name: None,
            rssi: None,
            profile: Some(profile),
            manufacturer_data_hex: String::new(),
            classic_address: None,
        }
    }

    pub fn reconnect_guidance(&self) -> ReconnectGuidance {
        self.profile
            .map_or(ReconnectGuidance::WaitSystemBluetooth, |profile| {
                profile.reconnect_guidance()
            })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SettingStage {
    Submitted,
    PartiallyWritten,
    Written,
    Confirmed,
    NotApplied,
    TimedOut,
    Disconnected,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SettingOperation {
    pub id: u64,
    pub session_id: u64,
    pub setting: HeadsetSetting,
    pub requested_value: FieldValue,
    pub stage: SettingStage,
    pub error: Option<ControlError>,
    pub written_frames: u32,
    pub total_frames: u32,
    pub confirmed_fields: BTreeSet<HeadsetField>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct HeadsetDiagnostics {
    pub field_errors: BTreeMap<HeadsetField, ControlError>,
    pub raw_notifications: Vec<String>,
    pub unknown_notifications: Vec<String>,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HeadsetState {
    pub headset_id: HeadsetId,
    pub session_id: u64,
    pub readings: HeadsetReadings,
    pub diagnostics: HeadsetDiagnostics,
    pub classic_address: Option<String>,
    pub transport_selection: Option<TransportSelection>,
}

impl HeadsetState {
    pub fn profile(&self) -> ModelProfile {
        self.readings.profile
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ControlSnapshot {
    pub revision: u64,
    pub platform: PlatformState,
    pub settings: ControlSettings,
    pub connection: ConnectionState,
    pub bonded_headsets: Vec<HeadsetCandidate>,
    pub headset: Option<HeadsetState>,
    pub last_error: Option<ControlError>,
    pub setting_operations: Vec<SettingOperation>,
    pub audio_headset_id: Option<HeadsetId>,
    pub connection_block_reason: Option<ConnectionBlockReason>,
    pub reconnect_guidance: Option<ReconnectGuidance>,
}

#[derive(Debug)]
pub struct SnapshotDecodeError(serde_json::Error);

impl std::fmt::Display for SnapshotDecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "状态快照无法解码: {}", self.0)
    }
}

impl std::error::Error for SnapshotDecodeError {}

impl ControlSnapshot {
    pub(crate) fn initial(platform: PlatformState, settings: ControlSettings) -> Self {
        let mut snapshot = Self {
            revision: 0,
            platform,
            settings,
            connection: ConnectionState::Disconnected,
            bonded_headsets: Vec::new(),
            headset: None,
            last_error: None,
            setting_operations: Vec::new(),
            audio_headset_id: None,
            connection_block_reason: None,
            reconnect_guidance: None,
        };
        snapshot.refresh_reasons();
        snapshot
    }

    pub(crate) fn refresh_reasons(&mut self) {
        self.connection_block_reason = self.platform.connection_block_reason();
        self.reconnect_guidance = self
            .headset
            .as_ref()
            .map(|headset| headset.profile().reconnect_guidance());
    }

    pub fn encode(&self) -> Vec<u8> {
        serde_json::to_vec(self).expect("a control snapshot always serializes")
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, SnapshotDecodeError> {
        serde_json::from_slice(bytes).map_err(SnapshotDecodeError)
    }

    pub fn ready_session(&self) -> Option<SessionReference> {
        let ConnectionState::Connected {
            headset_id,
            session_id,
            profile,
        } = &self.connection
        else {
            return None;
        };
        let current = self.headset.as_ref()?;
        if current.headset_id != *headset_id
            || current.session_id != *session_id
            || current.profile() != *profile
        {
            return None;
        }
        Some(SessionReference {
            headset_id: headset_id.clone(),
            session_id: *session_id,
        })
    }

    pub fn session_for(&self, setting: HeadsetSetting) -> Option<SessionReference> {
        if setting == HeadsetSetting::NoiseMode {
            self.noise_mode_session()
        } else {
            self.ready_session()
        }
    }

    pub fn is_ready(&self, headset: &HeadsetState) -> bool {
        self.session_available(self.ready_session(), headset)
    }

    pub fn can_control_noise_mode(&self, headset: &HeadsetState) -> bool {
        self.session_available(self.noise_mode_session(), headset)
    }

    fn session_available(
        &self,
        reference: Option<SessionReference>,
        headset: &HeadsetState,
    ) -> bool {
        reference.is_some_and(|reference| {
            reference.headset_id == headset.headset_id && reference.session_id == headset.session_id
        }) && self.platform.transport == TransportAvailability::Available
            && !self.platform.permission.missing()
    }

    pub fn headset_has_system_audio(&self) -> bool {
        let (Some(current), Some(audio)) = (&self.headset, &self.audio_headset_id) else {
            return false;
        };
        *audio == current.headset_id
            || current
                .classic_address
                .as_deref()
                .is_some_and(|classic| audio.as_str() == classic)
    }

    pub fn noise_mode_session(&self) -> Option<SessionReference> {
        if matches!(self.connection, ConnectionState::Connected { .. }) {
            return self.ready_session();
        }
        let ConnectionState::Connecting {
            headset_id,
            session_id,
            phase,
        } = &self.connection
        else {
            return None;
        };
        let current = self.headset.as_ref()?;
        if !self.platform.host_platform.is_desktop()
            || current.profile() != ModelProfile::I5Zt
            || !self.headset_has_system_audio()
        {
            return None;
        }
        if *phase != ConnectionPhase::Initializing
            || *headset_id != current.headset_id
            || *session_id != current.session_id
        {
            return None;
        }
        let mode: NoiseMode = current.readings.confirmed_noise_mode()?;
        if !current.readings.noise_modes().contains(&mode) {
            return None;
        }
        Some(SessionReference {
            headset_id: current.headset_id.clone(),
            session_id: current.session_id,
        })
    }

    pub(crate) fn with_setting_operation(&mut self, operation: SettingOperation) {
        match self
            .setting_operations
            .iter_mut()
            .find(|existing| existing.id == operation.id)
        {
            Some(existing) => *existing = operation,
            None => self.setting_operations.push(operation),
        }
        while self.setting_operations.len() > SETTING_HISTORY_LIMIT {
            self.setting_operations.remove(0);
        }
    }
}
