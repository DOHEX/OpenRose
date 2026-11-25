use crate::lifeline::Lifeline;
use crate::snapshot::ConnectionPhase;
use crate::transport::{ControlConnection, OpenFailure, OpenFailureKind, TransportError};
use async_trait::async_trait;
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

const NOTIFICATION_BUFFER: usize = 64;
const CLOSED: &str = "SPP 连接已关闭";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SppFailureKind {
    Permission,
    Unsupported,
    Disconnected,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SppError {
    pub kind: SppFailureKind,
    pub message: String,
}

impl SppError {
    pub fn new(kind: SppFailureKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}

#[async_trait]
pub trait SppDriver: Send + Sync {
    fn max_write_size(&self) -> usize;

    async fn connect(&self) -> Result<(), SppError>;

    async fn read(&self) -> Result<Vec<u8>, SppError>;

    async fn write(&self, value: &[u8]) -> Result<(), SppError>;

    async fn close(&self) -> Result<(), SppError>;
}

fn classify(error: SppError) -> TransportError {
    match error.kind {
        SppFailureKind::Permission => TransportError::PermissionDenied(error.message),
        SppFailureKind::Unsupported => TransportError::Unsupported(error.message),
        SppFailureKind::Disconnected | SppFailureKind::Unknown => {
            TransportError::Terminal(error.message)
        }
    }
}

struct Inner {
    driver: Box<dyn SppDriver>,
    capacity: usize,
    writes: tokio::sync::Mutex<()>,
    lifeline: Lifeline,
}

impl Inner {
    async fn release_driver(&self) -> Result<(), TransportError> {
        self.lifeline
            .release(async { self.driver.close().await.map_err(classify) })
            .await
    }

    async fn run_pump(self: Arc<Self>) {
        loop {
            let read = tokio::select! {
                _ = self.lifeline.ended() => break,
                read = self.driver.read() => read,
            };
            match read {
                Ok(chunk) if chunk.is_empty() => {}
                Ok(chunk) => self.lifeline.deliver(chunk),
                Err(error) => self.lifeline.end(classify(error)),
            }
        }
        let _ = self.release_driver().await;
    }
}

pub struct SppConnection {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for SppConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SppConnection")
            .field("max_write_size", &self.inner.capacity)
            .finish()
    }
}

impl SppConnection {
    pub async fn open(
        driver: Box<dyn SppDriver>,
        on_phase: &(dyn Fn(ConnectionPhase) + Send + Sync),
        cancel: &CancellationToken,
    ) -> Result<Arc<Self>, OpenFailure> {
        let connection = Arc::new(Self {
            inner: Arc::new(Inner {
                capacity: driver.max_write_size(),
                driver,
                writes: tokio::sync::Mutex::new(()),
                lifeline: Lifeline::new(NOTIFICATION_BUFFER),
            }),
        });
        on_phase(ConnectionPhase::Discovering);
        let outcome = tokio::select! {
            outcome = connection.inner.driver.connect() => Some(outcome),
            _ = cancel.cancelled() => None,
        };
        match outcome {
            Some(Ok(())) => {
                tokio::spawn(Arc::clone(&connection.inner).run_pump());
                Ok(connection)
            }
            failed => {
                let kind = match failed {
                    Some(Err(error)) => OpenFailureKind::Transport(classify(error)),
                    _ => OpenFailureKind::Cancelled,
                };
                let unreleased = connection
                    .close()
                    .await
                    .err()
                    .map(|_| Arc::clone(&connection) as Arc<dyn ControlConnection>);
                Err(OpenFailure { kind, unreleased })
            }
        }
    }
}

#[async_trait]
impl ControlConnection for SppConnection {
    fn max_write_size(&self) -> usize {
        self.inner.capacity
    }

    async fn subscribe(&self) -> Result<(), TransportError> {
        self.inner.lifeline.check_open()
    }

    async fn write(&self, bytes: &[u8]) -> Result<(), TransportError> {
        let inner = &self.inner;
        let _write = inner.writes.lock().await;
        inner.lifeline.check_open()?;
        if let Err(error) = inner.driver.write(bytes).await {
            let error = classify(error);
            inner.lifeline.end(error.clone());
            return Err(error);
        }
        inner.lifeline.check_open()
    }

    async fn next_notification(&self) -> Result<Vec<u8>, TransportError> {
        self.inner.lifeline.next_notification(CLOSED).await
    }

    async fn close(&self) -> Result<(), TransportError> {
        self.inner
            .lifeline
            .end(TransportError::Terminal(CLOSED.to_string()));
        self.inner.release_driver().await
    }
}
