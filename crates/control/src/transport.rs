use crate::gatt::GattDriver;
use crate::snapshot::HeadsetCandidate;
use async_trait::async_trait;
use openrose_headset::HeadsetId;
use std::fmt;
use std::sync::Arc;
use tokio::sync::watch;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportRecoveryReason {
    ServiceDiscovery,
    ServicesUnavailable,
    Subscription,
    WriteFailed,
    ServicesChanged,
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

    async fn bonded_headsets(&self) -> Vec<HeadsetCandidate> {
        Vec::new()
    }

    fn audio_connected_headset(&self) -> watch::Receiver<Option<HeadsetId>> {
        watch::channel(None).1
    }
}
