use async_trait::async_trait;
use openrose_control::audio::{
    AudioEvents as CoreAudioEvents, AudioFactsDriver as CoreAudioFactsDriver, RawAudioDevice,
    RawAudioEvent, RawAudioSnapshot,
};
use openrose_control::gatt::{
    GattCharacteristic, GattDriver as CoreGattDriver, GattError, GattEvent, GattFailureKind,
    GattService, GattSubscription,
};
use openrose_control::scan::{Advertisement, ScanDriver as CoreScanDriver};
use openrose_control::snapshot::ControlSnapshot;
use openrose_control::spp::{SppDriver as CoreSppDriver, SppError, SppFailureKind};
use openrose_control::transport::{ControlTransport as CoreControlTransport, TransportError};
use openrose_control::{
    ControlObserver as CoreControlObserver, LocalClock as CoreLocalClock,
};
use openrose_headset::{HeadsetId, LocalDateTime};
use std::fmt;
use std::sync::Arc;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum GattDriverError {
    Failed {
        kind: GattFailureKind,
        message: String,
    },
}

impl fmt::Display for GattDriverError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Failed { message, .. } => f.write_str(message),
        }
    }
}

impl std::error::Error for GattDriverError {}

impl From<uniffi::UnexpectedUniFFICallbackError> for GattDriverError {
    fn from(error: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed {
            kind: GattFailureKind::Unknown,
            message: error.reason,
        }
    }
}

impl From<GattDriverError> for GattError {
    fn from(error: GattDriverError) -> Self {
        match error {
            GattDriverError::Failed { kind, message } => Self::new(kind, message),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum SppDriverError {
    Failed {
        kind: SppFailureKind,
        message: String,
    },
}

impl fmt::Display for SppDriverError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Failed { message, .. } => f.write_str(message),
        }
    }
}

impl std::error::Error for SppDriverError {}

impl From<uniffi::UnexpectedUniFFICallbackError> for SppDriverError {
    fn from(error: uniffi::UnexpectedUniFFICallbackError) -> Self {
        Self::Failed {
            kind: SppFailureKind::Unknown,
            message: error.reason,
        }
    }
}

impl From<SppDriverError> for SppError {
    fn from(error: SppDriverError) -> Self {
        match error {
            SppDriverError::Failed { kind, message } => Self::new(kind, message),
        }
    }
}

#[uniffi::export(foreign)]
pub trait ControlObserver: Send + Sync + 'static {
    fn state_changed(&self, snapshot: ControlSnapshot);
}

#[uniffi::export(foreign)]
pub trait LocalClock: Send + Sync + 'static {
    fn now(&self) -> LocalDateTime;
}

#[uniffi::export(foreign)]
#[async_trait]
pub trait AudioEvents: Send + Sync + 'static {
    async fn next_event(&self) -> Option<RawAudioEvent>;
}

#[uniffi::export(foreign)]
#[async_trait]
pub trait AudioFactsDriver: Send + Sync + 'static {
    fn subscribe(&self) -> Arc<dyn AudioEvents>;

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError>;

    async fn snapshot(&self, target_addresses: Vec<String>) -> RawAudioSnapshot;
}

#[uniffi::export(foreign)]
#[async_trait]
pub trait ScanDriver: Send + Sync + 'static {
    async fn next_advertisement(&self) -> Result<Option<Advertisement>, TransportError>;

    async fn stop(&self);
}

#[uniffi::export(foreign)]
#[async_trait]
pub trait GattDriver: Send + Sync + 'static {
    async fn next_event(&self) -> Option<GattEvent>;

    async fn connect(&self) -> Result<(), GattDriverError>;

    async fn discover_services(&self) -> Result<Vec<GattService>, GattDriverError>;

    async fn request_mtu(&self, preferred: u16) -> Result<Option<u16>, GattDriverError>;

    async fn set_notification_routing(
        &self,
        characteristic: GattCharacteristic,
        enabled: bool,
    ) -> Result<(), GattDriverError>;

    async fn configure_subscription(
        &self,
        characteristic: GattCharacteristic,
        subscription: GattSubscription,
    ) -> Result<(), GattDriverError>;

    async fn write(
        &self,
        characteristic: GattCharacteristic,
        value: Vec<u8>,
        with_response: bool,
    ) -> Result<(), GattDriverError>;

    async fn close(&self) -> Result<(), GattDriverError>;
}

#[uniffi::export(foreign)]
#[async_trait]
pub trait SppDriver: Send + Sync + 'static {
    fn max_write_size(&self) -> u32;

    async fn connect(&self) -> Result<(), SppDriverError>;

    async fn read(&self) -> Result<Vec<u8>, SppDriverError>;

    async fn write(&self, value: Vec<u8>) -> Result<(), SppDriverError>;

    async fn close(&self) -> Result<(), SppDriverError>;
}

#[uniffi::export(foreign)]
pub trait ControlTransport: Send + Sync + 'static {
    fn open_gatt(&self, headset: HeadsetId) -> Result<Arc<dyn GattDriver>, TransportError>;

    fn start_scan(&self) -> Result<Arc<dyn ScanDriver>, TransportError>;

    fn open_spp(
        &self,
        headset: HeadsetId,
        address: String,
        service_uuid: String,
    ) -> Result<Arc<dyn SppDriver>, TransportError>;

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver>;

    fn supports_initialization_recovery(&self) -> bool;
}
pub(crate) struct ObserverAdapter(pub(crate) Arc<dyn ControlObserver>);

impl CoreControlObserver for ObserverAdapter {
    fn state_changed(&self, snapshot: &ControlSnapshot) {
        self.0.state_changed(snapshot.clone());
    }
}

pub(crate) struct ClockAdapter(pub(crate) Arc<dyn LocalClock>);

impl CoreLocalClock for ClockAdapter {
    fn now(&self) -> LocalDateTime {
        self.0.now()
    }
}

struct AudioEventsAdapter(Arc<dyn AudioEvents>);

#[async_trait]
impl CoreAudioEvents for AudioEventsAdapter {
    async fn next_event(&self) -> Option<RawAudioEvent> {
        self.0.next_event().await
    }
}

struct AudioFactsAdapter(Arc<dyn AudioFactsDriver>);

#[async_trait]
impl CoreAudioFactsDriver for AudioFactsAdapter {
    async fn subscribe(&self) -> Box<dyn CoreAudioEvents> {
        Box::new(AudioEventsAdapter(self.0.subscribe()))
    }

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError> {
        self.0.bonded_devices().await
    }

    async fn snapshot(&self, target_addresses: &[String]) -> RawAudioSnapshot {
        self.0.snapshot(target_addresses.to_vec()).await
    }
}

struct ScanDriverAdapter(Arc<dyn ScanDriver>);

#[async_trait]
impl CoreScanDriver for ScanDriverAdapter {
    async fn next_advertisement(&self) -> Result<Option<Advertisement>, TransportError> {
        self.0.next_advertisement().await
    }

    async fn stop(&self) {
        self.0.stop().await
    }
}

struct GattDriverAdapter(Arc<dyn GattDriver>);

#[async_trait]
impl CoreGattDriver for GattDriverAdapter {
    async fn next_event(&self) -> Option<GattEvent> {
        self.0.next_event().await
    }

    async fn connect(&self) -> Result<(), GattError> {
        self.0.connect().await.map_err(GattError::from)
    }

    async fn discover_services(&self) -> Result<Vec<GattService>, GattError> {
        self.0.discover_services().await.map_err(GattError::from)
    }

    async fn request_mtu(&self, preferred: u16) -> Result<Option<u16>, GattError> {
        self.0
            .request_mtu(preferred)
            .await
            .map_err(GattError::from)
    }

    async fn set_notification_routing(
        &self,
        characteristic: &GattCharacteristic,
        enabled: bool,
    ) -> Result<(), GattError> {
        self.0
            .set_notification_routing(characteristic.clone(), enabled)
            .await
            .map_err(GattError::from)
    }

    async fn configure_subscription(
        &self,
        characteristic: &GattCharacteristic,
        subscription: GattSubscription,
    ) -> Result<(), GattError> {
        self.0
            .configure_subscription(characteristic.clone(), subscription)
            .await
            .map_err(GattError::from)
    }

    async fn write(
        &self,
        characteristic: &GattCharacteristic,
        value: &[u8],
        with_response: bool,
    ) -> Result<(), GattError> {
        self.0
            .write(characteristic.clone(), value.to_vec(), with_response)
            .await
            .map_err(GattError::from)
    }

    async fn close(&self) -> Result<(), GattError> {
        self.0.close().await.map_err(GattError::from)
    }
}

struct SppDriverAdapter(Arc<dyn SppDriver>);

#[async_trait]
impl CoreSppDriver for SppDriverAdapter {
    fn max_write_size(&self) -> usize {
        self.0.max_write_size() as usize
    }

    async fn connect(&self) -> Result<(), SppError> {
        self.0.connect().await.map_err(SppError::from)
    }

    async fn read(&self) -> Result<Vec<u8>, SppError> {
        self.0.read().await.map_err(SppError::from)
    }

    async fn write(&self, value: &[u8]) -> Result<(), SppError> {
        self.0.write(value.to_vec()).await.map_err(SppError::from)
    }

    async fn close(&self) -> Result<(), SppError> {
        self.0.close().await.map_err(SppError::from)
    }
}

pub(crate) struct TransportAdapter(pub(crate) Arc<dyn ControlTransport>);

#[async_trait]
impl CoreControlTransport for TransportAdapter {
    async fn open_gatt(&self, headset: &HeadsetId) -> Result<Box<dyn CoreGattDriver>, TransportError> {
        let driver = self.0.open_gatt(headset.clone())?;
        Ok(Box::new(GattDriverAdapter(driver)))
    }

    async fn start_scan(&self) -> Result<Box<dyn CoreScanDriver>, TransportError> {
        let driver = self.0.start_scan()?;
        Ok(Box::new(ScanDriverAdapter(driver)))
    }

    async fn open_spp(
        &self,
        headset: &HeadsetId,
        address: &str,
        service_uuid: &str,
    ) -> Result<Box<dyn CoreSppDriver>, TransportError> {
        let driver = self.0.open_spp(
            headset.clone(),
            address.to_string(),
            service_uuid.to_string(),
        )?;
        Ok(Box::new(SppDriverAdapter(driver)))
    }

    fn audio_facts(&self) -> Arc<dyn CoreAudioFactsDriver> {
        Arc::new(AudioFactsAdapter(self.0.audio_facts()))
    }

    fn supports_initialization_recovery(&self) -> bool {
        self.0.supports_initialization_recovery()
    }
}
