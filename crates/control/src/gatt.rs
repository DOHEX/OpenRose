use crate::snapshot::ConnectionPhase;
use crate::transport::{
    ControlConnection, OpenFailure, OpenFailureKind, TransportError, TransportRecoveryReason,
};
use async_trait::async_trait;
use openrose_headset::GattProfile;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;

const MTU_TIMEOUT: Duration = Duration::from_millis(1_500);
const GATT_OPERATION_TIMEOUT: Duration = Duration::from_millis(5_000);
const PREFERRED_MTU: u16 = 185;
const DISCOVERY_ATTEMPTS: u32 = 3;
const NOTIFICATION_BUFFER: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GattCharacteristic {
    pub instance_id: u64,
    pub uuid: String,
    pub can_write: bool,
    pub can_write_without_response: bool,
    pub can_notify: bool,
    pub can_indicate: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GattService {
    pub uuid: String,
    pub characteristics: Vec<GattCharacteristic>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GattSubscription {
    Disabled,
    Notify,
    Indicate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GattFailureKind {
    Permission,
    Unsupported,
    InvalidValue,
    Disconnected,
    Transient,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
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
    operations: tokio::sync::Mutex<()>,
    release: tokio::sync::Mutex<bool>,
    discovery: Mutex<Discovery>,
    capacity: AtomicUsize,
    failure: Mutex<Option<TransportError>>,
    notifications_tx: Mutex<Option<mpsc::Sender<Vec<u8>>>>,
    notifications_rx: tokio::sync::Mutex<mpsc::Receiver<Vec<u8>>>,
    pump: CancellationToken,
}

pub struct GattConnection {
    inner: Arc<Inner>,
}

impl std::fmt::Debug for GattConnection {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("GattConnection")
            .field("max_write_size", &self.inner.capacity.load(Ordering::SeqCst))
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
    fn check_open(&self) -> Result<(), TransportError> {
        match self.failure.lock().expect("failure lock").clone() {
            Some(error) => Err(error),
            None => Ok(()),
        }
    }

    fn end(&self, error: TransportError) {
        let mut failure = self.failure.lock().expect("failure lock");
        if failure.is_none() {
            *failure = Some(error);
            self.notifications_tx.lock().expect("sender lock").take();
            self.pump.cancel();
        }
    }

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
        self.check_open()?;
        let outcome = operation.await;
        self.check_open()?;
        outcome.map_err(|error| classify(error, reason))
    }

    async fn release_driver(&self) -> Result<(), TransportError> {
        let mut released = self.release.lock().await;
        if !*released {
            self.driver
                .close()
                .await
                .map_err(|error| classify(error, TransportRecoveryReason::ServiceDiscovery))?;
            *released = true;
        }
        Ok(())
    }

    async fn run_pump(self: Arc<Self>) {
        loop {
            let event = tokio::select! {
                _ = self.pump.cancelled() => break,
                event = self.driver.next_event() => event,
            };
            match event {
                None => {
                    self.end(TransportError::Terminal("蓝牙事件流已结束".to_string()));
                    break;
                }
                Some(GattEvent::Notification { instance_id, value }) => {
                    let selected = self
                        .services()
                        .is_some_and(|services| services.notifier.instance_id == instance_id);
                    if selected {
                        let sent = self
                            .notifications_tx
                            .lock()
                            .expect("sender lock")
                            .as_ref()
                            .is_some_and(|sender| sender.try_send(value).is_ok());
                        if !sent {
                            self.end(TransportError::Terminal(
                                "通知接收缓冲区已满或已关闭".to_string(),
                            ));
                        }
                    }
                }
                Some(GattEvent::ServicesChanged) => {
                    let invalidated = {
                        let mut discovery = self.discovery.lock().expect("discovery lock");
                        discovery.generation += 1;
                        discovery.services.is_some()
                    };
                    if invalidated {
                        self.end(TransportError::Recoverable {
                            reason: TransportRecoveryReason::ServicesChanged,
                            message: "蓝牙服务数据库已变化；需要重新建立连接".to_string(),
                        });
                    }
                }
                Some(GattEvent::Disconnected(failure)) => {
                    self.end(classify(failure, TransportRecoveryReason::ServiceDiscovery));
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
        self.check_open()?;
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
        let service = found
            .iter()
            .find(|service| service.uuid.eq_ignore_ascii_case(&self.profile.service_uuid));
        let writer = characteristic(&service, &self.profile.write_uuid);
        let notifier = characteristic(&service, &self.profile.notify_uuid);
        self.check_open()?;
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
                self.end(error.clone());
                Err(error)
            }
        }
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

impl GattConnection {
    pub async fn open(
        driver: Box<dyn GattDriver>,
        profile: GattProfile,
        on_phase: &(dyn Fn(ConnectionPhase) + Send + Sync),
        cancel: &CancellationToken,
    ) -> Result<Arc<Self>, OpenFailure> {
        let (sender, receiver) = mpsc::channel(NOTIFICATION_BUFFER);
        let inner = Arc::new(Inner {
            driver,
            profile,
            operations: tokio::sync::Mutex::new(()),
            release: tokio::sync::Mutex::new(false),
            discovery: Mutex::new(Discovery {
                generation: 0,
                services: None,
            }),
            capacity: AtomicUsize::new(20),
            failure: Mutex::new(None),
            notifications_tx: Mutex::new(Some(sender)),
            notifications_rx: tokio::sync::Mutex::new(receiver),
            pump: CancellationToken::new(),
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
        let _operation = inner.operations.lock().await;
        inner.check_open()?;
        let notifier = inner
            .services()
            .expect("subscribe follows a successful open")
            .notifier;
        inner
            .terminal_on_failure(async {
                let configured = tokio::time::timeout(GATT_OPERATION_TIMEOUT, async {
                    inner
                        .native(TransportRecoveryReason::Subscription, async {
                            inner.driver.set_notification_routing(&notifier, true).await?;
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
                    inner.check_open()
                })
                .await;
                let outcome = match configured {
                    Ok(outcome) => outcome,
                    Err(_) => Err(TransportError::Recoverable {
                        reason: TransportRecoveryReason::Subscription,
                        message: "通知订阅超时".to_string(),
                    }),
                };
                if outcome.is_err() {
                    inner.undo_subscription(&notifier).await;
                }
                outcome
            })
            .await
    }

    async fn write(&self, bytes: &[u8]) -> Result<(), TransportError> {
        let inner = &self.inner;
        let _operation = inner.operations.lock().await;
        inner.check_open()?;
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
                inner.check_open()
            })
            .await
    }

    async fn next_notification(&self) -> Result<Vec<u8>, TransportError> {
        let mut receiver = self.inner.notifications_rx.lock().await;
        match receiver.recv().await {
            Some(bytes) => Ok(bytes),
            None => Err(self
                .inner
                .failure
                .lock()
                .expect("failure lock")
                .clone()
                .unwrap_or_else(|| TransportError::Terminal("蓝牙连接已关闭".to_string()))),
        }
    }

    async fn close(&self) -> Result<(), TransportError> {
        self.inner
            .end(TransportError::Terminal("蓝牙连接已关闭".to_string()));
        self.inner.release_driver().await
    }
}
