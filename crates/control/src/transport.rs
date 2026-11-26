use crate::audio::{self, AudioFactsDriver};
use crate::gatt::GattDriver;
use crate::scan::ScanDriver;
use crate::spp::SppDriver;
use async_trait::async_trait;
use openrose_headset::HeadsetId;
use serde::{Deserialize, Serialize};
use std::fmt;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum TransportRecoveryReason {
    ServiceDiscovery,
    ServicesUnavailable,
    Subscription,
    WriteFailed,
    ServicesChanged,
    NoInitialResponse,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum AudioProfileEvidence {
    #[default]
    Unknown,
    Classic,
    LeAudio,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct TransportFacts {
    pub spp_available: bool,
    pub audio_profile: AudioProfileEvidence,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransportError {
    Recoverable {
        reason: TransportRecoveryReason,
        message: String,
    },
    Terminal(String),
    PermissionDenied(String),
    Unsupported(String),
    InvalidArgument(String),
}

impl TransportError {
    pub fn message(&self) -> &str {
        match self {
            Self::Recoverable { message, .. } => message,
            Self::Terminal(message)
            | Self::PermissionDenied(message)
            | Self::Unsupported(message)
            | Self::InvalidArgument(message) => message,
        }
    }
}

impl fmt::Display for TransportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for TransportError {}

#[async_trait]
pub trait ControlConnection: Send + Sync {
    fn max_write_size(&self) -> usize;

    async fn subscribe(&self) -> Result<(), TransportError>;

    async fn write(&self, bytes: &[u8]) -> Result<(), TransportError>;

    async fn next_notification(&self) -> Result<Vec<u8>, TransportError>;

    async fn close(&self) -> Result<(), TransportError>;
}

#[derive(Debug)]
pub enum OpenFailureKind {
    Cancelled,
    Transport(TransportError),
}

pub struct OpenFailure {
    pub kind: OpenFailureKind,
    pub unreleased: Option<Arc<dyn ControlConnection>>,
}

impl fmt::Debug for OpenFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("OpenFailure")
            .field("kind", &self.kind)
            .field("unreleased", &self.unreleased.is_some())
            .finish()
    }
}

#[async_trait]
pub trait ControlTransport: Send + Sync {
    async fn open_gatt(&self, headset: &HeadsetId) -> Result<Box<dyn GattDriver>, TransportError>;

    async fn start_scan(&self) -> Result<Box<dyn ScanDriver>, TransportError>;

    async fn open_spp(
        &self,
        _headset: &HeadsetId,
        _address: &str,
        _service_uuid: &str,
    ) -> Result<Box<dyn SppDriver>, TransportError> {
        Err(TransportError::Unsupported(
            "此平台不支持 SPP 控制连接".to_string(),
        ))
    }

    async fn connection_facts(
        &self,
        headset: &HeadsetId,
        classic_address: &str,
    ) -> Result<TransportFacts, TransportError> {
        Ok(audio::connection_facts(&*self.audio_facts(), headset, Some(classic_address)).await)
    }

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver>;

    fn supports_initialization_recovery(&self) -> bool {
        false
    }
}
