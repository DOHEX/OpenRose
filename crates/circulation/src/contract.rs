use async_trait::async_trait;
use openrose_control::ControlErrorCode;
use openrose_headset::{
    BatteryLayout, HeadsetCommand, HeadsetId, HeadsetSetting, ModelProfile, NoiseMode,
    SettingPrerequisite,
};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;
use std::net::IpAddr;
use tokio::sync::watch;

pub const WIRE_VERSION: u32 = 1;
pub const DEFAULT_PORT: u16 = 21871;
pub const MDNS_SERVICE_TYPE: &str = "_openrose._tcp.local.";
pub const MAX_ADVERTISED_NAME_LEN: usize = 60;

pub const REMOTE_CONTROL_SETTINGS: &[HeadsetSetting] = &[
    HeadsetSetting::NoiseMode,
    HeadsetSetting::AncLevel,
    HeadsetSetting::TransparencyLevel,
    HeadsetSetting::Eq,
    HeadsetSetting::GameMode,
];

pub fn supports_circulation(profile: Option<ModelProfile>) -> bool {
    profile.is_some()
}

pub fn is_remote_control_setting(setting: HeadsetSetting) -> bool {
    REMOTE_CONTROL_SETTINGS.contains(&setting)
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct PeerId(pub String);

impl PeerId {
    pub fn new(id: impl Into<String>) -> Self {
        Self(id.into())
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    pub fn is_valid(&self) -> bool {
        self.0.len() == 64
            && self
                .0
                .bytes()
                .all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
    }
}

impl fmt::Display for PeerId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl From<String> for PeerId {
    fn from(value: String) -> Self {
        Self(value)
    }
}

impl From<&str> for PeerId {
    fn from(value: &str) -> Self {
        Self(value.to_owned())
    }
}

impl AsRef<str> for PeerId {
    fn as_ref(&self) -> &str {
        &self.0
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Peer {
    pub id: PeerId,
    pub name: String,
}

pub type PeerInfo = Peer;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NearbyPeer {
    pub id: PeerId,
    pub name: String,
    pub addresses: Vec<IpAddr>,
    pub port: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PairingOffer {
    pub code: String,
    pub qr: String,
    pub valid_for_millis: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteSessionReference {
    pub peer_id: PeerId,
    pub headset_id: HeadsetId,
    pub token: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RemoteCommand {
    Read,
    Set {
        session: RemoteSessionReference,
        command: HeadsetCommand,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum RemoteControlStatus {
    Waiting,
    Idle,
    Recovering,
    Initializing,
    Ready,
    Unavailable,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BatterySlots {
    pub left: Option<i32>,
    pub right: Option<i32>,
    pub case: Option<i32>,
    pub layout: BatteryLayout,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RemoteReading {
    pub status: RemoteControlStatus,
    pub headset_id: Option<HeadsetId>,
    pub headset_name: Option<String>,
    pub profile: Option<ModelProfile>,
    pub session: Option<RemoteSessionReference>,
    pub battery: Option<BatterySlots>,
    pub noise_mode: Option<NoiseMode>,
    pub noise_modes: Vec<NoiseMode>,
    pub anc_level: Option<i32>,
    pub anc_levels: Vec<i32>,
    pub transparency_level: Option<i32>,
    pub transparency_levels: Vec<i32>,
    pub eq_preset: Option<String>,
    pub eq_presets: Vec<String>,
    pub game_mode_supported: bool,
    pub game_mode: Option<bool>,
    pub blocked_reasons: BTreeMap<HeadsetSetting, SettingPrerequisite>,
}

impl RemoteReading {
    pub fn empty(status: RemoteControlStatus) -> Self {
        Self {
            status,
            headset_id: None,
            headset_name: None,
            profile: None,
            session: None,
            battery: None,
            noise_mode: None,
            noise_modes: Vec::new(),
            anc_level: None,
            anc_levels: Vec::new(),
            transparency_level: None,
            transparency_levels: Vec::new(),
            eq_preset: None,
            eq_presets: Vec::new(),
            game_mode_supported: false,
            game_mode: None,
            blocked_reasons: BTreeMap::new(),
        }
    }

    pub fn idle() -> Self {
        Self::empty(RemoteControlStatus::Idle)
    }

    pub fn unavailable() -> Self {
        Self::empty(RemoteControlStatus::Unavailable)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RemoteError {
    UnsupportedVersion,
    NotPaired,
    PeerOffline,
    ServiceClosed,
    OwnerInactive,
    PairingNotOpen,
    PairingExpired,
    PairingAttemptsExceeded,
    PairingRejected,
    InvalidPairingCode,
    InvalidQrPayload,
    SessionExpired,
    SessionNotReady,
    UnsupportedOperation,
    UnsupportedSetting,
    InvalidArgument,
    ControlFailed(ControlErrorCode),
    Protocol,
    Timeout,
    IdentityError,
    StorageError,
    DiscoveryError,
}

impl fmt::Display for RemoteError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{self:?}")
    }
}

impl std::error::Error for RemoteError {}

#[async_trait]
pub trait PeerDiscovery: Send + Sync {
    async fn start(&self, id: &PeerId, name: &str, port: u16) -> Result<(), RemoteError>;
    fn watch(&self) -> watch::Receiver<Vec<NearbyPeer>>;
    async fn close(&self);
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct YieldReceipt {
    pub address: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum YieldRefusal {
    InCall,
    MicInUse,
    Unavailable(String),
}

#[async_trait]
pub trait LocalAudio: Send + Sync {
    async fn is_connected(&self, address: &str) -> bool;
    async fn is_media_connected(&self, address: &str) -> bool;
    async fn yield_audio(&self, address: &str) -> Result<YieldReceipt, YieldRefusal>;
    async fn acquire(&self, address: &str) -> Result<(), YieldRefusal>;
}
