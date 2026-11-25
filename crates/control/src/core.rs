use crate::confirmation::ReadProgress;
use crate::error::{ControlError, ControlErrorCode, ControlResult};
use crate::queue::Pending;
use crate::snapshot::{
    ControlSnapshot, HeadsetCandidate, HeadsetDiagnostics, HeadsetState, TransportAvailability,
};
use crate::state::StateCell;
use crate::transport::{ControlConnection, ControlTransport, OpenFailure, TransportError};
use openrose_headset::{HeadsetReadings, LocalDateTime, ModelProfile, ProtocolCodec};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::watch;
use tokio::task::JoinHandle;
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionTiming {
    pub connect_timeout: Duration,
    pub query_timeout: Duration,
    pub write_interval: Option<Duration>,
    pub write_timeout: Duration,
}

impl Default for SessionTiming {
    fn default() -> Self {
        Self {
            connect_timeout: Duration::from_millis(15_000),
            query_timeout: Duration::from_millis(1_500),
            write_interval: None,
            write_timeout: Duration::from_millis(5_000),
        }
    }
}

pub trait LocalClock: Send + Sync {
    fn now(&self) -> LocalDateTime;
}

pub(crate) enum Interrupt {
    Cancelled,
    Ended,
    Timeout,
    Transport(TransportError),
    Control(ControlError),
}

impl Interrupt {
    pub(crate) fn into_connection_error(self) -> ControlError {
        match self {
            Self::Cancelled | Self::Ended => {
                ControlError::new(ControlErrorCode::Disconnected, "设备连接已结束")
            }
            Self::Timeout => ControlError::new(ControlErrorCode::Timeout, "蓝牙连接或读取超时"),
            Self::Transport(error) => connection_failure(&error),
            Self::Control(error) => error,
        }
    }
}

pub(crate) fn connection_failure(error: &TransportError) -> ControlError {
    let code = match error {
        TransportError::Unsupported(_) | TransportError::InvalidArgument(_) => {
            ControlErrorCode::Unsupported
        }
        TransportError::PermissionDenied(_) => ControlErrorCode::PermissionRequired,
        TransportError::Recoverable { .. } | TransportError::Terminal(_) => {
            ControlErrorCode::Disconnected
        }
    };
    ControlError::new(code, error.message())
}

pub(crate) fn platform_error(snapshot: &ControlSnapshot) -> Option<ControlError> {
    if snapshot.platform.permission.missing() {
        Some(ControlError::new(
            ControlErrorCode::PermissionRequired,
            "需要蓝牙权限",
        ))
    } else if snapshot.platform.transport != TransportAvailability::Available {
        Some(ControlError::new(
            ControlErrorCode::TransportUnavailable,
            "平台蓝牙传输不可用",
        ))
    } else {
        None
    }
}

pub(crate) struct LinkInner {
    pub(crate) active: Option<Arc<Attempt>>,
    pub(crate) generation: u64,
    pub(crate) released: bool,
}

impl LinkInner {
    pub(crate) fn is_active(&self, attempt: &Arc<Attempt>) -> bool {
        self.active
            .as_ref()
            .is_some_and(|active| Arc::ptr_eq(active, attempt))
    }
}

pub(crate) struct Core {
    pub(crate) state: StateCell,
    pub(crate) transport: Arc<dyn ControlTransport>,
    pub(crate) clock: Arc<dyn LocalClock>,
    pub(crate) timing: SessionTiming,
    pub(crate) lifecycle: tokio::sync::Mutex<LinkInner>,
    pub(crate) connections: OwnedConnections,
    pub(crate) operation_ids: AtomicU64,
    pub(crate) shutdown: CancellationToken,
}

pub(crate) struct LinkSlot {
    pub(crate) connection: Option<Arc<dyn ControlConnection>>,
    pub(crate) codec: Box<dyn ProtocolCodec>,
    pub(crate) has_written: bool,
    pub(crate) next_write_at: Option<Instant>,
    pub(crate) ready: bool,
    pub(crate) observer: Option<(CancellationToken, JoinHandle<()>)>,
}

pub(crate) struct Attempt {
    pub(crate) id: u64,
    pub(crate) candidate: HeadsetCandidate,
    pub(crate) profile: ModelProfile,
    pub(crate) force_ble: bool,
    pub(crate) cancel: CancellationToken,
    pub(crate) link_epoch: AtomicU64,
    pub(crate) outcome: watch::Sender<Option<ControlResult>>,
    pub(crate) progress: watch::Sender<ReadProgress>,
    pub(crate) link: Mutex<LinkSlot>,
    pub(crate) queue: Pending,
}

impl Attempt {
    pub(crate) fn new(
        id: u64,
        candidate: HeadsetCandidate,
        profile: ModelProfile,
        cancel: CancellationToken,
        force_ble: bool,
    ) -> Self {
        Self {
            id,
            candidate,
            profile,
            force_ble,
            queue: Pending::new(cancel.child_token()),
            cancel,
            link_epoch: AtomicU64::new(0),
            outcome: watch::channel(None).0,
            progress: watch::channel(ReadProgress::default()).0,
            link: Mutex::new(LinkSlot {
                connection: None,
                codec: openrose_headset::create_protocol_codec(profile),
                has_written: false,
                next_write_at: None,
                ready: false,
                observer: None,
            }),
        }
    }

    pub(crate) fn epoch(&self) -> u64 {
        self.link_epoch.load(Ordering::SeqCst)
    }

    pub(crate) fn bump_epoch(&self) -> u64 {
        self.link_epoch.fetch_add(1, Ordering::SeqCst) + 1
    }

    pub(crate) fn matches(&self, snapshot: &ControlSnapshot) -> bool {
        snapshot
            .headset
            .as_ref()
            .is_some_and(|headset| headset.session_id == self.id)
            && !matches!(
                snapshot.connection,
                crate::snapshot::ConnectionState::Disconnected
            )
    }

    pub(crate) fn is_ready(&self) -> bool {
        self.link.lock().expect("link lock").ready
    }

    pub(crate) fn connection(&self) -> Option<Arc<dyn ControlConnection>> {
        self.link.lock().expect("link lock").connection.clone()
    }

    pub(crate) fn loading_headset(&self) -> HeadsetState {
        HeadsetState {
            headset_id: self.candidate.id.clone(),
            session_id: self.id,
            readings: HeadsetReadings::new(self.profile),
            diagnostics: HeadsetDiagnostics::default(),
            classic_address: self.candidate.classic_address.clone(),
            transport_selection: None,
        }
    }

    pub(crate) async fn wait(&self) -> ControlResult {
        let mut outcome = self.outcome.subscribe();
        let finished = outcome
            .wait_for(Option::is_some)
            .await
            .expect("the attempt owns its outcome sender");
        finished.clone().expect("waited for a finished outcome")
    }
}

#[derive(Default)]
pub(crate) struct OwnedConnections {
    held: tokio::sync::Mutex<Vec<Arc<dyn ControlConnection>>>,
}

impl OwnedConnections {
    pub(crate) async fn open<Create, Opened>(
        &self,
        create: Create,
    ) -> Result<Arc<dyn ControlConnection>, OpenFailure>
    where
        Create: FnOnce() -> Opened,
        Opened: Future<Output = Result<Arc<dyn ControlConnection>, OpenFailure>>,
    {
        let mut held = self.held.lock().await;
        match create().await {
            Ok(connection) => {
                held.push(Arc::clone(&connection));
                Ok(connection)
            }
            Err(failure) => {
                if let Some(unreleased) = &failure.unreleased {
                    held.push(Arc::clone(unreleased));
                }
                Err(failure)
            }
        }
    }

    pub(crate) async fn close(&self, connection: &Arc<dyn ControlConnection>) -> bool {
        let mut held = self.held.lock().await;
        if !held.iter().any(|item| Arc::ptr_eq(item, connection)) {
            return true;
        }
        Self::release(&mut held, connection).await
    }

    pub(crate) async fn close_all(&self) -> bool {
        let mut held = self.held.lock().await;
        for connection in held.clone() {
            Self::release(&mut held, &connection).await;
        }
        held.is_empty()
    }

    async fn release(
        held: &mut Vec<Arc<dyn ControlConnection>>,
        connection: &Arc<dyn ControlConnection>,
    ) -> bool {
        match connection.close().await {
            Ok(()) => {
                held.retain(|item| !Arc::ptr_eq(item, connection));
                true
            }
            Err(_) => false,
        }
    }
}
