use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum BluetoothTransport {
    Classic,
    Le,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AudioLayer {
    None,
    DeviceLevelOnly,
    ClassicAudio,
    A2dp,
    Hfp,
    LeAudio,
    AudioUnknown,
}

impl AudioLayer {
    pub fn is_audio_connected(self) -> bool {
        matches!(
            self,
            Self::ClassicAudio | Self::A2dp | Self::Hfp | Self::LeAudio | Self::AudioUnknown
        )
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::DeviceLevelOnly => "device-level",
            Self::ClassicAudio => "classic-audio",
            Self::A2dp => "a2dp",
            Self::Hfp => "hfp",
            Self::LeAudio => "le-audio",
            Self::AudioUnknown => "audio-unknown",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum EvidenceStrength {
    Direct,
    Inferred,
}

impl EvidenceStrength {
    pub fn label(self) -> &'static str {
        match self {
            Self::Direct => "direct",
            Self::Inferred => "inferred",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LayerAssessment {
    pub layer: AudioLayer,
    pub strength: EvidenceStrength,
    pub direct_layer: AudioLayer,
    pub layers_present: Vec<AudioLayer>,
    pub reasons: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct BluetoothStatus {
    pub available: bool,
    pub state: String,
    pub detail: Option<String>,
}

impl BluetoothStatus {
    pub fn available() -> Self {
        Self {
            available: true,
            state: "available".to_owned(),
            detail: None,
        }
    }

    pub fn unavailable(state: impl Into<String>, detail: impl Into<String>) -> Self {
        Self {
            available: false,
            state: state.into(),
            detail: Some(detail.into()),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DeviceSnapshot {
    pub id: String,
    pub name: String,
    pub address: Option<String>,
    pub container_id: Option<String>,
    pub transports: Vec<BluetoothTransport>,
    pub device_connected: bool,
    pub assessment: LayerAssessment,
    pub endpoints: Vec<String>,
    pub advertised_profiles: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProbeSnapshot {
    pub platform: String,
    pub bluetooth: BluetoothStatus,
    pub devices: Vec<DeviceSnapshot>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum WindowsEndpointFlow {
    Render,
    Capture,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum WindowsEndpointState {
    Active,
    Unplugged,
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WindowsDeviceObservation {
    pub id: String,
    pub name: String,
    pub address: Option<String>,
    pub container_id: Option<String>,
    pub transport: BluetoothTransport,
    pub is_connected: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WindowsAepServiceObservation {
    pub container_id: Option<String>,
    pub address: Option<String>,
    pub service_class_id: String,
    pub is_connected: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WindowsAudioEndpointObservation {
    pub id: String,
    pub container_id: Option<String>,
    pub friendly_name: String,
    pub device_desc: Option<String>,
    pub flow: WindowsEndpointFlow,
    pub state: WindowsEndpointState,
    pub topology_filters: Vec<WindowsTopologyFilter>,
    pub capture_session_active: bool,
    pub sample_rate_hz: Option<u32>,
    pub channels: Option<u16>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WindowsTopologyFilter {
    pub id: String,
    pub enumerator: Option<String>,
    pub instance_id: Option<String>,
    pub friendly_name: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MacAudioTransport {
    BluetoothClassic,
    BluetoothLe,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MacBluetoothObservation {
    pub name: String,
    pub address: String,
    pub connected: bool,
    pub hands_free_gateway: bool,
    pub hands_free_device: bool,
    pub sdp_profiles: Vec<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MacCoreAudioObservation {
    pub device_id: u32,
    pub uid: String,
    pub name: String,
    pub transport: MacAudioTransport,
    pub alive: bool,
    pub output_channels: u32,
    pub input_channels: u32,
    pub nominal_sample_rate_hz: Option<f64>,
    pub output_running: bool,
    pub input_running: bool,
}
