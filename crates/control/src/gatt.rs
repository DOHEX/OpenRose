use crate::lifeline::Lifeline;
use crate::snapshot::ConnectionPhase;
use crate::transport::{
    ControlConnection, OpenFailure, OpenFailureKind, TransportError, TransportRecoveryReason,
};
use async_trait::async_trait;
use openrose_headset::GattProfile;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::watch;
use tokio_util::sync::CancellationToken;

const MTU_TIMEOUT: Duration = Duration::from_millis(1_500);
const GATT_OPERATION_TIMEOUT: Duration = Duration::from_millis(5_000);
const PREFERRED_MTU: u16 = 185;
const DISCOVERY_ATTEMPTS: u32 = 3;
const NOTIFICATION_BUFFER: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct GattCharacteristic {
    pub instance_id: u64,
    pub uuid: String,
    pub can_write: bool,
    pub can_write_without_response: bool,
    pub can_notify: bool,
    pub can_indicate: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct GattService {
    pub uuid: String,
    pub characteristics: Vec<GattCharacteristic>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum GattSubscription {
    Disabled,
    Notify,
    Indicate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum GattFailureKind {
    Permission,
    Unsupported,
    InvalidValue,
    Disconnected,
    Transient,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct GattError {
    pub kind: GattFailureKind,
    pub message: String,
}

impl GattError {
    pub fn new(kind: GattFailureKind, message: impl Into<String>) -> Self {
        Self {
            kind,
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum GattEvent {
    Notification { instance_id: u64, value: Vec<u8> },
    ServicesChanged,
    Disconnected(GattError),
}

#[async_trait]
pub trait GattDriver: Send + Sync {
    async fn next_event(&self) -> Option<GattEvent>;

    async fn connect(&self) -> Result<(), GattError>;

    async fn discover_services(&self) -> Result<Vec<GattService>, GattError>;

    async fn request_mtu(&self, preferred: u16) -> Result<Option<u16>, GattError>;

    async fn set_notification_routing(
        &self,
        characteristic: &GattCharacteristic,
        enabled: bool,
    ) -> Result<(), GattError>;

    async fn configure_subscription(
        &self,
        characteristic: &GattCharacteristic,
        subscription: GattSubscription,
    ) -> Result<(), GattError>;

    async fn write(
        &self,
        characteristic: &GattCharacteristic,
        value: &[u8],
        with_response: bool,
    ) -> Result<(), GattError>;

    async fn close(&self) -> Result<(), GattError>;
}

#[derive(Clone)]
struct Services {
    writer: GattCharacteristic,
    notifier: GattCharacteristic,
}

impl Services {
    fn require_control_features(&self) -> Result<(), TransportError> {
        let writes = self.writer.can_write || self.writer.can_write_without_response;
        let subscriptions = self.notifier.can_notify || self.notifier.can_indicate;
        if writes && subscriptions {
            Ok(())
        } else {
            Err(TransportError::Unsupported(
                "设备特征不提供所需的写入或订阅能力".to_string(),
            ))
        }
    }
}

struct Discovery {
    generation: u64,
    services: Option<Services>,
}

struct Inner {
    driver: Box<dyn GattDriver>,
    profile: GattProfile,
    operations: Arc<tokio::sync::Mutex<()>>,
    rollback: Mutex<Option<watch::Receiver<bool>>>,
    discovery: Mutex<Discovery>,
    capacity: AtomicUsize,
    lifeline: Lifeline,
}

pub struct GattConnection {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for GattConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("GattConnection")
            .field(
                "max_write_size",
                &self.inner.capacity.load(Ordering::SeqCst),
            )
            .finish()
    }
}

fn classify(error: GattError, reason: TransportRecoveryReason) -> TransportError {
    match error.kind {
        GattFailureKind::Transient => TransportError::Recoverable {
            reason,
            message: error.message,
        },
        GattFailureKind::Permission => TransportError::PermissionDenied(error.message),
        GattFailureKind::Unsupported => TransportError::Unsupported(error.message),
        GattFailureKind::InvalidValue => TransportError::InvalidArgument(error.message),
        GattFailureKind::Disconnected | GattFailureKind::Unknown => {
            TransportError::Terminal(error.message)
        }
    }
}

impl Inner {
    fn services(&self) -> Option<Services> {
        self.discovery
            .lock()
            .expect("discovery lock")
            .services
            .clone()
    }

    async fn native<T>(
        &self,
        reason: TransportRecoveryReason,
        operation: impl Future<Output = Result<T, GattError>>,
    ) -> Result<T, TransportError> {
        self.lifeline.check_open()?;
        let outcome = operation.await;
        self.lifeline.check_open()?;
        outcome.map_err(|error| classify(error, reason))
    }

    async fn release_driver(&self) -> Result<(), TransportError> {
        let rollback = self.rollback.lock().expect("rollback lock").clone();
        if let Some(mut rollback) = rollback {
            let _ = rollback.wait_for(|done| *done).await;
        }
        self.lifeline
            .release(async {
                self.driver
                    .close()
                    .await
                    .map_err(|error| classify(error, TransportRecoveryReason::ServiceDiscovery))
            })
            .await
    }

    async fn run_pump(self: Arc<Self>) {
        loop {
            let event = tokio::select! {
                _ = self.lifeline.ended() => break,
                event = self.driver.next_event() => event,
            };
            match event {
                None => {
                    self.lifeline
                        .end(TransportError::Terminal("蓝牙事件流已结束".to_string()));
                    break;
                }
                Some(GattEvent::Notification { instance_id, value }) => {
                    let selected = self
                        .services()
                        .is_some_and(|services| services.notifier.instance_id == instance_id);
                    if selected {
                        self.lifeline.deliver(value);
                    }
                }
                Some(GattEvent::ServicesChanged) => {
                    let invalidated = {
                        let mut discovery = self.discovery.lock().expect("discovery lock");
                        discovery.generation += 1;
                        discovery.services.is_some()
                    };
                    if invalidated {
                        self.lifeline.end(TransportError::Recoverable {
                            reason: TransportRecoveryReason::ServicesChanged,
                            message: "蓝牙服务数据库已变化；需要重新建立连接".to_string(),
                        });
                    }
                }
                Some(GattEvent::Disconnected(failure)) => {
                    self.lifeline
                        .end(classify(failure, TransportRecoveryReason::ServiceDiscovery));
                }
            }
        }
        let _ = self.release_driver().await;
    }

    async fn initialize(
        &self,
        on_phase: &(dyn Fn(ConnectionPhase) + Send + Sync),
    ) -> Result<(), TransportError> {
        self.native(
            TransportRecoveryReason::ServiceDiscovery,
            self.driver.connect(),
        )
        .await?;
        on_phase(ConnectionPhase::Discovering);
        for attempt in 1..=DISCOVERY_ATTEMPTS {
            match self.discover_services().await {
                Ok(()) => break,
                Err(TransportError::Recoverable { .. }) if attempt < DISCOVERY_ATTEMPTS => {}
                Err(error) => return Err(error),
            }
        }
        let mtu = match tokio::time::timeout(
            MTU_TIMEOUT,
            self.native(
                TransportRecoveryReason::ServiceDiscovery,
                self.driver.request_mtu(PREFERRED_MTU),
            ),
        )
        .await
        {
            Err(_) => None,
            Ok(Ok(mtu)) => mtu,
            Ok(Err(TransportError::Recoverable { .. } | TransportError::Unsupported(_))) => None,
            Ok(Err(error)) => return Err(error),
        };
        self.lifeline.check_open()?;
        let capacity = (usize::from(mtu.unwrap_or(23).max(23)) - 3).min(512);
        self.capacity.store(capacity, Ordering::SeqCst);
        Ok(())
    }

    async fn discover_services(&self) -> Result<(), TransportError> {
        let before = self.discovery.lock().expect("discovery lock").generation;
        let found = self
            .native(
                TransportRecoveryReason::ServiceDiscovery,
                self.driver.discover_services(),
            )
            .await?;
        let characteristic = |service: &Option<&GattService>, uuid: &str| {
            service.and_then(|service| {
                service
                    .characteristics
                    .iter()
                    .find(|item| item.uuid.eq_ignore_ascii_case(uuid))
                    .cloned()
            })
        };
        let service = found.iter().find(|service| {
            service
                .uuid
                .eq_ignore_ascii_case(&self.profile.service_uuid)
        });
        let writer = characteristic(&service, &self.profile.write_uuid);
        let notifier = characteristic(&service, &self.profile.notify_uuid);
        self.lifeline.check_open()?;
        let mut discovery = self.discovery.lock().expect("discovery lock");
        if before != discovery.generation {
            return Err(TransportError::Recoverable {
                reason: TransportRecoveryReason::ServicesChanged,
                message: "蓝牙服务在发现过程中发生变化".to_string(),
            });
        }
        let (Some(writer), Some(notifier)) = (writer, notifier) else {
            return Err(TransportError::Recoverable {
                reason: TransportRecoveryReason::ServicesUnavailable,
                message: "未找到此型号要求的服务或特征".to_string(),
            });
        };
        let services = Services { writer, notifier };
        services.require_control_features()?;
        discovery.services = Some(services);
        Ok(())
    }

    async fn terminal_on_failure<T>(
        &self,
        operation: impl Future<Output = Result<T, TransportError>>,
    ) -> Result<T, TransportError> {
        match operation.await {
            Ok(value) => Ok(value),
            Err(error) => {
                self.lifeline.end(error.clone());
                Err(error)
            }
        }
    }

    fn start_undo_subscription(
        self: &Arc<Self>,
        notifier: GattCharacteristic,
        operation: tokio::sync::OwnedMutexGuard<()>,
    ) -> watch::Receiver<bool> {
        let (done_tx, done_rx) = watch::channel(false);
        *self.rollback.lock().expect("rollback lock") = Some(done_rx.clone());
        let inner = Arc::clone(self);
        if let Ok(handle) = tokio::runtime::Handle::try_current() {
            handle.spawn(async move {
                struct Signal(watch::Sender<bool>);
                impl Drop for Signal {
                    fn drop(&mut self) {
                        self.0.send_replace(true);
                    }
                }
                let _signal = Signal(done_tx);
                let _operation = operation;
                inner.undo_subscription(&notifier).await;
            });
        } else {
            done_tx.send_replace(true);
        }
        done_rx
    }

    async fn undo_subscription(&self, notifier: &GattCharacteristic) {
        let _ = tokio::time::timeout(
            GATT_OPERATION_TIMEOUT,
            self.driver
                .configure_subscription(notifier, GattSubscription::Disabled),
        )
        .await;
        let _ = tokio::time::timeout(
            GATT_OPERATION_TIMEOUT,
            self.driver.set_notification_routing(notifier, false),
        )
        .await;
    }
}

struct SubscriptionGuard {
    inner: Arc<Inner>,
    notifier: GattCharacteristic,
    operation: Option<tokio::sync::OwnedMutexGuard<()>>,
    armed: bool,
}

impl SubscriptionGuard {
    fn rollback(&mut self) -> watch::Receiver<bool> {
        self.armed = false;
        let operation = self.operation.take().expect("rollback runs at most once");
        self.inner
            .start_undo_subscription(self.notifier.clone(), operation)
    }
}

impl Drop for SubscriptionGuard {
    fn drop(&mut self) {
        if self.armed {
            let _ = self.rollback();
        }
    }
}

impl GattConnection {
    pub async fn open(
        driver: Box<dyn GattDriver>,
        profile: GattProfile,
        on_phase: &(dyn Fn(ConnectionPhase) + Send + Sync),
        cancel: &CancellationToken,
    ) -> Result<Arc<Self>, OpenFailure> {
        let inner = Arc::new(Inner {
            driver,
            profile,
            operations: Arc::new(tokio::sync::Mutex::new(())),
            rollback: Mutex::new(None),
            discovery: Mutex::new(Discovery {
                generation: 0,
                services: None,
            }),
            capacity: AtomicUsize::new(20),
            lifeline: Lifeline::new(NOTIFICATION_BUFFER),
        });
        let connection = Arc::new(Self { inner });
        tokio::spawn(Arc::clone(&connection.inner).run_pump());
        let outcome = tokio::select! {
            outcome = connection.inner.initialize(on_phase) => Some(outcome),
            _ = cancel.cancelled() => None,
        };
        match outcome {
            Some(Ok(())) => Ok(connection),
            failed => {
                let kind = match failed {
                    Some(Err(error)) => OpenFailureKind::Transport(error),
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
impl ControlConnection for GattConnection {
    fn max_write_size(&self) -> usize {
        self.inner.capacity.load(Ordering::SeqCst)
    }

    async fn subscribe(&self) -> Result<(), TransportError> {
        let inner = &self.inner;
        let operation = Arc::clone(&inner.operations).lock_owned().await;
        inner.lifeline.check_open()?;
        let notifier = inner
            .services()
            .expect("subscribe follows a successful open")
            .notifier;
        inner
            .terminal_on_failure(async {
                let mut guard = SubscriptionGuard {
                    inner: Arc::clone(inner),
                    notifier: notifier.clone(),
                    operation: Some(operation),
                    armed: true,
                };
                let configured = tokio::time::timeout(GATT_OPERATION_TIMEOUT, async {
                    inner
                        .native(TransportRecoveryReason::Subscription, async {
                            inner
                                .driver
                                .set_notification_routing(&notifier, true)
                                .await?;
                            let subscription = if notifier.can_notify {
                                GattSubscription::Notify
                            } else {
                                GattSubscription::Indicate
                            };
                            inner
                                .driver
                                .configure_subscription(&notifier, subscription)
                                .await
                        })
                        .await?;
                    inner.lifeline.check_open()
                })
                .await;
                let outcome = match configured {
                    Ok(outcome) => outcome,
                    Err(_) => Err(TransportError::Recoverable {
                        reason: TransportRecoveryReason::Subscription,
                        message: "通知订阅超时".to_string(),
                    }),
                };
                if outcome.is_ok() {
                    guard.armed = false;
                } else {
                    let mut rollback = guard.rollback();
                    let _ = rollback.wait_for(|done| *done).await;
                }
                outcome
            })
            .await
    }

    async fn write(&self, bytes: &[u8]) -> Result<(), TransportError> {
        let inner = &self.inner;
        let _operation = inner.operations.lock().await;
        inner.lifeline.check_open()?;
        let writer = inner
            .services()
            .expect("write follows a successful open")
            .writer;
        inner
            .terminal_on_failure(async {
                let written = tokio::time::timeout(
                    GATT_OPERATION_TIMEOUT,
                    inner.native(
                        TransportRecoveryReason::WriteFailed,
                        inner
                            .driver
                            .write(&writer, bytes, !writer.can_write_without_response),
                    ),
                )
                .await;
                match written {
                    Ok(outcome) => outcome?,
                    Err(_) => {
                        return Err(TransportError::Recoverable {
                            reason: TransportRecoveryReason::WriteFailed,
                            message: "蓝牙写入超时".to_string(),
                        });
                    }
                }
                inner.lifeline.check_open()
            })
            .await
    }

    async fn next_notification(&self) -> Result<Vec<u8>, TransportError> {
        self.inner
            .lifeline
            .next_notification("蓝牙连接已关闭")
            .await
    }

    async fn close(&self) -> Result<(), TransportError> {
        self.inner
            .lifeline
            .end(TransportError::Terminal("蓝牙连接已关闭".to_string()));
        self.inner.release_driver().await
    }
}
