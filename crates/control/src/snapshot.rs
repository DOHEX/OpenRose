use crate::error::ControlError;
use crate::transport::TransportRecoveryReason;
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

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PlatformState {
    pub permission: PermissionState,
    pub transport: TransportAvailability,
    pub host_platform: HostPlatform,
    pub user_unlocked: bool,
    pub scan_permission: PermissionState,
}

impl PlatformState {
    pub fn new(permission: PermissionState, transport: TransportAvailability) -> Self {
        Self {
            permission,
            transport,
            host_platform: HostPlatform::Unknown,
            user_unlocked: true,
            scan_permission: PermissionState::NotRequired,
        }
    }

    pub fn on(mut self, host_platform: HostPlatform) -> Self {
        self.host_platform = host_platform;
        self
    }

    pub fn with_user_unlocked(mut self, user_unlocked: bool) -> Self {
        self.user_unlocked = user_unlocked;
        self
    }

    pub fn with_scan_permission(mut self, scan_permission: PermissionState) -> Self {
        self.scan_permission = scan_permission;
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

    pub fn unidentified(id: HeadsetId) -> Self {
        Self {
            id,
            name: None,
            rssi: None,
            profile: None,
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

    pub fn matches_audio(&self, audio: Option<&HeadsetId>) -> bool {
        audio.is_some_and(|audio| {
            *audio == self.id || self.classic_address.as_deref() == Some(audio.as_str())
        })
    }

    pub(crate) fn matches_identity(&self, id: &HeadsetId, classic: Option<&str>) -> bool {
        self.id == *id
            || classic.is_some_and(|classic| {
                self.id.as_str() == classic || self.classic_address.as_deref() == Some(classic)
            })
    }

    pub(crate) fn with_evidence(mut self, previous: Option<&HeadsetCandidate>) -> Self {
        let Some(previous) = previous else {
            return self;
        };
        if self.profile.is_some() && previous.profile != self.profile {
            return self;
        }
        if self.profile.is_none() && previous.profile.is_some() {
            self.manufacturer_data_hex = previous.manufacturer_data_hex.clone();
        }
        self.name = self.name.or_else(|| previous.name.clone());
        self.profile = self.profile.or(previous.profile);
        self.classic_address = self
            .classic_address
            .or_else(|| previous.classic_address.clone());
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum ScanState {
    Idle,
    Scanning,
    Failed(ControlError),
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConnectionRecovery {
    pub session_id: u64,
    pub reason: TransportRecoveryReason,
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
    pub connection: ConnectionState,
    pub bonded_headsets: Vec<HeadsetCandidate>,
    pub candidates: Vec<HeadsetCandidate>,
    pub scan: ScanState,
    pub headset: Option<HeadsetState>,
    pub last_error: Option<ControlError>,
    pub setting_operations: Vec<SettingOperation>,
    pub audio_headset_id: Option<HeadsetId>,
    pub connection_target: Option<HeadsetCandidate>,
    pub selected_headset_id: Option<HeadsetId>,
    pub connection_recovery: Option<ConnectionRecovery>,
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
    pub(crate) fn initial(platform: PlatformState) -> Self {
        let mut snapshot = Self {
            revision: 0,
            platform,
            connection: ConnectionState::Disconnected,
            bonded_headsets: Vec::new(),
            candidates: Vec::new(),
            scan: ScanState::Idle,
            headset: None,
            last_error: None,
            setting_operations: Vec::new(),
            audio_headset_id: None,
            connection_target: None,
            selected_headset_id: None,
            connection_recovery: None,
            connection_block_reason: None,
            reconnect_guidance: None,
        };
        snapshot.refresh_reasons();
        snapshot
    }

    pub(crate) fn refresh_reasons(&mut self) {
        self.connection_block_reason = self.platform.connection_block_reason();
        self.reconnect_guidance = self
            .current_headset()
            .map(|headset| headset.reconnect_guidance());
    }

    pub fn current_headset(&self) -> Option<HeadsetCandidate> {
        if let Some(target) = &self.connection_target {
            return Some(target.clone());
        }
        let (ConnectionState::Connecting { headset_id, .. }
        | ConnectionState::Connected { headset_id, .. }) = &self.connection
        else {
            return self
                .selected_headset_id
                .as_ref()
                .and_then(|chosen| self.bonded_headsets.iter().find(|c| c.id == *chosen))
                .or_else(|| self.bonded_headsets.first())
                .cloned();
        };
        self.bonded_headsets
            .iter()
            .chain(&self.candidates)
            .find(|candidate| candidate.id == *headset_id)
            .cloned()
    }

    pub(crate) fn known_candidate(&self, id: &HeadsetId) -> Option<HeadsetCandidate> {
        let active = self
            .headset
            .as_ref()
            .filter(|headset| headset.headset_id == *id)
            .map(|headset| HeadsetCandidate {
                profile: Some(headset.profile()),
                classic_address: headset.classic_address.clone(),
                ..HeadsetCandidate::unidentified(id.clone())
            });
        let known = self
            .bonded_headsets
            .iter()
            .find(|candidate| candidate.id == *id)
            .cloned()
            .into_iter()
            .chain(active)
            .chain(
                self.connection_target
                    .clone()
                    .filter(|target| target.id == *id),
            )
            .find(|candidate| candidate.profile.is_some());
        match self.candidates.iter().find(|candidate| candidate.id == *id) {
            Some(scanned) => Some(scanned.clone().with_evidence(known.as_ref())),
            None => known,
        }
    }

    pub(crate) fn select_current_headset(&mut self, headset: &HeadsetId) {
        self.selected_headset_id = self
            .bonded_headsets
            .iter()
            .find(|candidate| candidate.id == *headset)
            .map(|candidate| candidate.id.clone());
    }

    pub(crate) fn apply_bonded_headsets(&mut self, bonded: Vec<HeadsetCandidate>) {
        if let Some(target) = self.connection_target.as_mut() {
            let refreshed_name = bonded
                .iter()
                .find(|candidate| {
                    candidate.matches_identity(&target.id, target.classic_address.as_deref())
                })
                .and_then(|candidate| candidate.name.clone())
                .filter(|name| !name.trim().is_empty());
            if let Some(name) = refreshed_name
                && target.name.as_ref() != Some(&name)
            {
                target.name = Some(name);
            }
        }
        if bonded != self.bonded_headsets {
            self.selected_headset_id = None;
        }
        self.bonded_headsets = bonded;
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
        let target = self.connection_target.as_ref();
        if target.is_some_and(|target| target.id != current.headset_id) {
            return false;
        }
        *audio == current.headset_id
            || current.classic_address.as_deref() == Some(audio.as_str())
            || target.is_some_and(|target| target.matches_audio(Some(audio)))
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
