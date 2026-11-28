use super::bus::{
    ADAPTER, BluezBus, BluezError, BluezInterfaces, BluezObjects, BluezProperties, BluezSignal,
    BluezValue, CLASSIC_BEARER, DEVICE, LE_BEARER, MEDIA_TRANSPORT, adapter_of, device_path,
    has_media_transport, media_belongs_to, on_powered_adapter,
};
use super::gatt::BluezGattDriver;
use super::released::ReleasedBluezLinks;
use async_trait::async_trait;
use openrose_control::audio::{
    AudioConnectionState, AudioEvents, AudioFactsDriver, AudioProfile, RawAudioDevice,
    RawAudioEvent, RawAudioSnapshot, RawProfileSnapshot, normalize_bluetooth_address,
};
use openrose_control::gatt::GattDriver;
use openrose_control::scan::{Advertisement, ScanDriver};
use openrose_control::snapshot::{TransportAvailability, TransportUnavailableReason};
use openrose_control::transport::{ControlTransport, TransportError};
use openrose_headset::HeadsetId;
use std::collections::{BTreeMap, BTreeSet, HashMap, VecDeque};
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{Mutex as AsyncMutex, Notify, OnceCell, broadcast, watch};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

type BusOpener =
    Box<dyn FnOnce() -> Pin<Box<dyn Future<Output = Result<Arc<dyn BluezBus>, BluezError>> + Send>> + Send>;

pub struct BluezControlTransport {
    availability_tx: watch::Sender<TransportAvailability>,
    availability_rx: watch::Receiver<TransportAvailability>,
    bus: Arc<OnceCell<Arc<dyn BluezBus>>>,
    ready: Arc<Notify>,
    ready_done: Arc<std::sync::atomic::AtomicBool>,
    scan_adapter: Arc<Mutex<Option<String>>>,
    scan_lock: Arc<AsyncMutex<()>>,
    released_links: Arc<ReleasedBluezLinks>,
    shutdown: CancellationToken,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl BluezControlTransport {
    #[cfg(target_os = "linux")]
    pub fn open() -> Arc<Self> {
        Self::with_opener(|| Box::pin(async { super::native::NativeBluezBus::open().await }))
    }

    pub fn with_bus(bus: Arc<dyn BluezBus>) -> Arc<Self> {
        Self::with_opener(move || Box::pin(async move { Ok(bus) }))
    }

    pub fn with_opener<F, Fut>(opener: F) -> Arc<Self>
    where
        F: FnOnce() -> Fut + Send + 'static,
        Fut: Future<Output = Result<Arc<dyn BluezBus>, BluezError>> + Send + 'static,
    {
        let boxed: BusOpener = Box::new(move || Box::pin(opener()));
        let (availability_tx, availability_rx) = watch::channel(TransportAvailability::Unavailable {
            reason: TransportUnavailableReason::Initializing,
        });
        let transport = Arc::new(Self {
            availability_tx,
            availability_rx,
            bus: Arc::new(OnceCell::new()),
            ready: Arc::new(Notify::new()),
            ready_done: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            scan_adapter: Arc::new(Mutex::new(None)),
            scan_lock: Arc::new(AsyncMutex::new(())),
            released_links: Arc::new(ReleasedBluezLinks::new()),
            shutdown: CancellationToken::new(),
            worker: Mutex::new(None),
        });
        let runner = Arc::clone(&transport);
        let handle = tokio::spawn(async move {
            runner.run_lifecycle(boxed).await;
        });
        *transport.worker.lock().expect("worker lock") = Some(handle);
        transport
    }

    async fn run_lifecycle(self: Arc<Self>, opener: BusOpener) {
        let open_result = opener().await;
        let current = match open_result {
            Ok(bus) => bus,
            Err(error) => {
                let _ = self.availability_tx.send(TransportAvailability::Unavailable {
                    reason: error.into_transport_unavailable(),
                });
                self.mark_ready();
                return;
            }
        };
        let _ = self.bus.set(Arc::clone(&current));
        let mut signal_rx = current.signals();
        let release_links = Arc::clone(&self.released_links);
        let release_bus = Arc::clone(&current);
        let release_task = tokio::spawn(async move {
            release_links.observe(release_bus).await;
        });

        self.update_availability(&*current).await;
        self.mark_ready();

        loop {
            tokio::select! {
                _ = self.shutdown.cancelled() => break,
                signal = signal_rx.recv() => match signal {
                    Ok(sig) if affects(&sig, &[ADAPTER]) => {
                        self.update_availability(&*current).await;
                    }
                    Ok(_) => {}
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        self.update_availability(&*current).await;
                    }
                    Err(broadcast::error::RecvError::Closed) => break,
                }
            }
        }
        release_task.abort();
        current.close().await;
    }

    fn mark_ready(&self) {
        self.ready_done
            .store(true, std::sync::atomic::Ordering::SeqCst);
        self.ready.notify_waiters();
    }

    pub async fn await_initialization(&self) {
        while !self
            .ready_done
            .load(std::sync::atomic::Ordering::SeqCst)
        {
            self.ready.notified().await;
        }
    }

    pub fn availability(&self) -> watch::Receiver<TransportAvailability> {
        self.availability_rx.clone()
    }

    async fn update_availability(&self, bus: &dyn BluezBus) {
        let next = match bus.objects().await {
            Ok(objects) => {
                let adapters: Vec<&BluezProperties> = objects
                    .values()
                    .filter_map(|ifaces| ifaces.get(ADAPTER))
                    .collect();
                if adapters.is_empty() {
                    TransportAvailability::Unavailable {
                        reason: TransportUnavailableReason::NoAdapter,
                    }
                } else if adapters
                    .iter()
                    .all(|props| props.get("Powered").and_then(BluezValue::as_bool) != Some(true))
                {
                    TransportAvailability::Unavailable {
                        reason: TransportUnavailableReason::AdapterPoweredOff,
                    }
                } else {
                    TransportAvailability::Available
                }
            }
            Err(error) => TransportAvailability::Unavailable {
                reason: error.into_transport_unavailable(),
            },
        };
        let _ = self.availability_tx.send(next);
    }

    async fn active_bus(&self) -> Result<Arc<dyn BluezBus>, TransportError> {
        self.await_initialization().await;
        let current = self.bus.get().cloned().ok_or_else(|| {
            let reason = match &*self.availability_rx.borrow() {
                TransportAvailability::Unavailable { reason } => reason_summary(reason),
                _ => "BlueZ 不可用".to_string(),
            };
            TransportError::Terminal(reason)
        })?;
        match &*self.availability_rx.borrow() {
            TransportAvailability::Available => Ok(current),
            TransportAvailability::Disabled => {
                Err(TransportError::Terminal("蓝牙适配器已关闭".to_string()))
            }
            TransportAvailability::Unavailable { reason } => {
                Err(TransportError::Terminal(reason_summary(reason)))
            }
        }
    }

    pub fn audio_profiles(self: &Arc<Self>) -> BluezAudioProfiles {
        BluezAudioProfiles::new(Arc::clone(self))
    }

    pub fn close_now(&self) {
        self.shutdown.cancel();
        if let Some(handle) = self.worker.lock().expect("worker lock").take() {
            handle.abort();
        }
        let _ = self.availability_tx.send(TransportAvailability::Unavailable {
            reason: TransportUnavailableReason::Closed,
        });
    }

    pub async fn close(&self) {
        self.shutdown.cancel();
        let handle = self.worker.lock().expect("worker lock").take();
        if let Some(handle) = handle {
            let _ = handle.await;
        }
        let _ = self.availability_tx.send(TransportAvailability::Unavailable {
            reason: TransportUnavailableReason::Closed,
        });
    }
}

#[async_trait]
impl ControlTransport for BluezControlTransport {
    async fn open_gatt(&self, headset: &HeadsetId) -> Result<Box<dyn GattDriver>, TransportError> {
        let current = self.active_bus().await?;
        let address = normalize_bluetooth_address(headset.as_str())
            .ok_or_else(|| TransportError::InvalidArgument("蓝牙地址无效".to_string()))?;
        let objects = current
            .objects()
            .await
            .map_err(transport_error)?;
        let scan_adapter = self.scan_adapter.lock().expect("scan adapter lock").clone();
        let path = device_path(&objects, &address, false, scan_adapter.as_deref())
            .ok_or_else(|| TransportError::Terminal(format!("未找到蓝牙设备 {address}")))?;
        self.released_links.resume_local(&path).await;
        let driver = BluezGattDriver::new(current, path)
            .map_err(|error| TransportError::Terminal(error.message))?;
        Ok(Box::new(driver))
    }

    async fn start_scan(&self) -> Result<Box<dyn ScanDriver>, TransportError> {
        let current = self.active_bus().await?;
        let lock_guard = Arc::clone(&self.scan_lock).lock_owned().await;
        let objects = current.objects().await.map_err(|err| {
            self.mark_access_denied(&err);
            transport_error(err)
        })?;
        let adapter = objects
            .iter()
            .filter_map(|(path, ifaces)| {
                let powered = ifaces
                    .get(ADAPTER)?
                    .get("Powered")
                    .and_then(BluezValue::as_bool);
                (powered == Some(true)).then(|| path.clone())
            })
            .min()
            .ok_or_else(|| TransportError::Terminal("未找到已开启的蓝牙适配器".to_string()))?;

        let signal_rx = current.signals();
        if let Err(err) = current.start_discovery(&adapter).await {
            self.mark_access_denied(&err);
            return Err(transport_error(err));
        }
        *self.scan_adapter.lock().expect("scan adapter lock") = Some(adapter.clone());

        let initial_objects = match current.objects().await {
            Ok(objects) => objects,
            Err(err) => {
                self.mark_access_denied(&err);
                let message = match current.stop_discovery(&adapter).await {
                    Ok(()) => err.message().to_string(),
                    Err(cleanup_err) => {
                        format!("{} (cleanup failed: {})", err.message(), cleanup_err.message())
                    }
                };
                return Err(if err.is_access_denied() {
                    TransportError::PermissionDenied(message)
                } else {
                    TransportError::Terminal(message)
                });
            }
        };

        let mut devices = HashMap::new();
        let mut queue = VecDeque::new();
        for (path, ifaces) in initial_objects {
            if adapter_of(&path) == adapter
                && let Some(device) = ifaces.get(DEVICE)
            {
                if let Some(ad) = advertisement_of(device) {
                    queue.push_back(ad);
                }
                devices.insert(path, device.clone());
            }
        }

        Ok(Box::new(BluezScanDriver {
            bus: current,
            adapter,
            signal_rx: AsyncMutex::new(signal_rx),
            devices: Mutex::new(devices),
            initial: Mutex::new(queue),
            stopped: Arc::new(std::sync::atomic::AtomicBool::new(false)),
            _lock_guard: Mutex::new(Some(lock_guard)),
        }))
    }

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver> {
        Arc::new(BluezAudioDriver {
            bus: Arc::clone(&self.bus),
            ready: Arc::clone(&self.ready),
            ready_done: Arc::clone(&self.ready_done),
        })
    }

    fn supports_initialization_recovery(&self) -> bool {
        true
    }
}

impl BluezControlTransport {
    fn mark_access_denied(&self, error: &BluezError) {
        if error.is_access_denied() {
            let _ = self.availability_tx.send(TransportAvailability::Unavailable {
                reason: TransportUnavailableReason::AccessDenied,
            });
        }
    }
}

struct BluezScanDriver {
    bus: Arc<dyn BluezBus>,
    adapter: String,
    signal_rx: AsyncMutex<broadcast::Receiver<BluezSignal>>,
    devices: Mutex<HashMap<String, BluezProperties>>,
    initial: Mutex<VecDeque<Advertisement>>,
    stopped: Arc<std::sync::atomic::AtomicBool>,
    _lock_guard: Mutex<Option<tokio::sync::OwnedMutexGuard<()>>>,
}

#[async_trait]
impl ScanDriver for BluezScanDriver {
    async fn next_advertisement(&self) -> Result<Option<Advertisement>, TransportError> {
        if let Some(ad) = self.initial.lock().expect("initial scan lock").pop_front() {
            return Ok(Some(ad));
        }
        let mut rx = self.signal_rx.lock().await;
        loop {
            match rx.recv().await {
                Ok(signal) => {
                    if !affects(&signal, &[ADAPTER, DEVICE]) {
                        continue;
                    }
                    if let Some(reason) = discovery_failure(&signal, &self.adapter) {
                        return Err(TransportError::Terminal(reason));
                    }
                    let mut devices = self.devices.lock().expect("scan devices lock");
                    if let Some(ad) = update_advertisement(&mut devices, signal, &self.adapter) {
                        return Ok(Some(ad));
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(broadcast::error::RecvError::Closed) => return Ok(None),
            }
        }
    }

    async fn stop(&self) {
        if self
            .stopped
            .swap(true, std::sync::atomic::Ordering::SeqCst)
        {
            return;
        }
        let _ = self.bus.stop_discovery(&self.adapter).await;
        self._lock_guard.lock().expect("scan guard lock").take();
    }
}

struct BluezAudioDriver {
    bus: Arc<OnceCell<Arc<dyn BluezBus>>>,
    ready: Arc<Notify>,
    ready_done: Arc<std::sync::atomic::AtomicBool>,
}

impl BluezAudioDriver {
    async fn wait_ready(&self) {
        while !self
            .ready_done
            .load(std::sync::atomic::Ordering::SeqCst)
        {
            self.ready.notified().await;
        }
    }
}

#[async_trait]
impl AudioFactsDriver for BluezAudioDriver {
    async fn subscribe(&self) -> Box<dyn AudioEvents> {
        self.wait_ready().await;
        let Some(current) = self.bus.get().cloned() else {
            return Box::new(ClosedAudioEvents);
        };
        let signal_rx = current.signals();
        let initial_objects = current.objects().await.ok();
        let powered = initial_objects
            .as_ref()
            .is_some_and(|objs| objs.values().any(|ifaces| {
                ifaces
                    .get(ADAPTER)
                    .and_then(|props| props.get("Powered"))
                    .and_then(BluezValue::as_bool)
                    == Some(true)
            }));
        let previous = initial_objects
            .as_ref()
            .map(|objs| {
                devices(objs)
                    .into_iter()
                    .map(|dev| (dev.address.clone(), dev))
                    .collect()
            })
            .unwrap_or_default();
        Box::new(BluezAudioEvents {
            bus: current,
            signal_rx: AsyncMutex::new(signal_rx),
            state: AsyncMutex::new(AudioEventsState {
                previous,
                powered,
                sleeping: false,
                queue: VecDeque::new(),
            }),
        })
    }

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError> {
        self.wait_ready().await;
        let current = self
            .bus
            .get()
            .ok_or_else(|| TransportError::Terminal("BlueZ 不可用".to_string()))?;
        let objects = current.objects().await.map_err(transport_error)?;
        Ok(devices(&objects))
    }

    async fn snapshot(&self, target_addresses: &[String]) -> RawAudioSnapshot {
        self.wait_ready().await;
        let Some(current) = self.bus.get() else {
            return empty_audio_snapshot();
        };
        let Ok(objects) = current.objects().await else {
            return empty_audio_snapshot();
        };
        let targets: BTreeSet<String> = target_addresses
            .iter()
            .filter_map(|addr| normalize_bluetooth_address(addr))
            .collect();
        audio_snapshot(&objects, &targets)
    }
}

struct ClosedAudioEvents;

#[async_trait]
impl AudioEvents for ClosedAudioEvents {
    async fn next_event(&self) -> Option<RawAudioEvent> {
        std::future::pending::<()>().await;
        None
    }
}

struct AudioEventsState {
    previous: BTreeMap<String, RawAudioDevice>,
    powered: bool,
    sleeping: bool,
    queue: VecDeque<RawAudioEvent>,
}

struct BluezAudioEvents {
    bus: Arc<dyn BluezBus>,
    signal_rx: AsyncMutex<broadcast::Receiver<BluezSignal>>,
    state: AsyncMutex<AudioEventsState>,
}

#[async_trait]
impl AudioEvents for BluezAudioEvents {
    async fn next_event(&self) -> Option<RawAudioEvent> {
        loop {
            {
                let mut state = self.state.lock().await;
                if let Some(event) = state.queue.pop_front() {
                    return Some(event);
                }
            }
            let signal = {
                let mut rx = self.signal_rx.lock().await;
                loop {
                    match rx.recv().await {
                        Ok(sig) if affects_audio(&sig) => break sig,
                        Ok(_) => continue,
                        Err(broadcast::error::RecvError::Lagged(_)) => {
                            break BluezSignal::Owner { present: true };
                        }
                        Err(broadcast::error::RecvError::Closed) => return None,
                    }
                }
            };

            let mut state = self.state.lock().await;
            if let BluezSignal::PrepareForSleep { sleeping } = signal {
                state.sleeping = sleeping;
            }
            if state.sleeping || matches!(signal, BluezSignal::Owner { present: false }) {
                state.previous.clear();
                state.powered = false;
                state.queue.push_back(RawAudioEvent::AdapterOff);
                continue;
            }
            let Ok(objects) = self.bus.objects().await else {
                continue;
            };
            let snapshot = audio_snapshot(&objects, &BTreeSet::new());
            if state.powered != snapshot.enabled {
                let powered = snapshot.enabled;
                state.powered = powered;
                state.queue.push_back(if powered {
                    RawAudioEvent::AdapterOn
                } else {
                    RawAudioEvent::AdapterOff
                });
            }
            let now: BTreeMap<String, RawAudioDevice> = snapshot
                .bonded_devices
                .iter()
                .map(|dev| (dev.address.clone(), dev.clone()))
                .collect();
            let bonded_changed = now != state.previous;
            let previous = std::mem::take(&mut state.previous);
            for (address, device) in &previous {
                if !now.contains_key(address) {
                    for profile in [AudioProfile::A2dp, AudioProfile::Headset] {
                        state.queue.push_back(RawAudioEvent::ProfileChanged {
                            profile,
                            device: device.clone(),
                            bonded: false,
                            connected: false,
                        });
                    }
                }
            }
            for device in now.values() {
                for profile in [AudioProfile::A2dp, AudioProfile::Headset] {
                    let connected = snapshot
                        .profiles
                        .get(&profile)
                        .and_then(Option::as_ref)
                        .is_some_and(|p| p.connected_addresses.contains(&device.address));
                    state.queue.push_back(RawAudioEvent::ProfileChanged {
                        profile,
                        device: device.clone(),
                        bonded: true,
                        connected,
                    });
                }
            }
            if bonded_changed {
                state.queue.push_back(RawAudioEvent::BondedDevicesChanged);
            }
            state.previous = now;
        }
    }
}

pub struct BluezAudioProfiles {
    transport: Arc<BluezControlTransport>,
}

impl BluezAudioProfiles {
    pub fn new(transport: Arc<BluezControlTransport>) -> Self {
        Self { transport }
    }

    pub async fn is_connected(&self, address: &str) -> Result<bool, String> {
        let bus = self
            .transport
            .active_bus()
            .await
            .map_err(|err| err.to_string())?;
        let objects = bus.objects().await.map_err(|err| err.to_string())?;
        Ok(!connected_paths(&objects, address)?.is_empty())
    }

    pub async fn is_media_connected(&self, address: &str) -> Result<bool, String> {
        let bus = self
            .transport
            .active_bus()
            .await
            .map_err(|err| err.to_string())?;
        let objects = bus.objects().await.map_err(|err| err.to_string())?;
        Ok(has_media(&objects, address))
    }

    pub async fn acquire(&self, address: &str) -> Result<(), String> {
        let current = self
            .transport
            .active_bus()
            .await
            .map_err(|err| err.to_string())?;
        let objects = current.objects().await.map_err(|err| err.to_string())?;
        let path = device_path(&objects, address, true, None)
            .ok_or_else(|| "headset is not paired on a powered adapter".to_string())?;
        self.transport.released_links.resume_local(&path).await;
        let objects = current.objects().await.map_err(|err| err.to_string())?;
        if has_media(&objects, address) {
            return Ok(());
        }
        let interfaces = objects.get(&path).cloned().unwrap_or_default();
        let classic = interfaces
            .get(CLASSIC_BEARER)
            .and_then(|props| props.get("Connected"))
            .and_then(BluezValue::as_bool)
            .or_else(|| {
                interfaces
                    .get(DEVICE)
                    .and_then(|props| props.get("Connected"))
                    .and_then(BluezValue::as_bool)
            });
        let le_connected = interfaces
            .get(LE_BEARER)
            .and_then(|props| props.get("Connected"))
            .and_then(BluezValue::as_bool);
        let start_le = classic == Some(false) && le_connected == Some(false);
        let le_owner = current.owner();
        let mut opened_le = false;

        let outcome: Result<(), String> = async {
            if start_le {
                let _owner = le_owner
                    .as_ref()
                    .ok_or_else(|| "BlueZ service unavailable".to_string())?;
                opened_le = match current.connect_gatt(&path, true).await {
                    Ok(()) => true,
                    Err(BluezError::AlreadyConnected(_) | BluezError::InProgress(_)) => false,
                    Err(err) => return Err(err.to_string()),
                };
                await_connection("timed out bringing up the LE link", || async {
                    let objs = current.objects().await.ok()?;
                    let connected = objs
                        .get(&path)
                        .and_then(|ifaces| ifaces.get(LE_BEARER))
                        .and_then(|props| props.get("Connected"))
                        .and_then(BluezValue::as_bool)
                        == Some(true);
                    Some(connected)
                })
                .await?;
            }
            let accepted = std::sync::atomic::AtomicBool::new(false);
            await_connection("timed out bringing up the audio link", || async {
                let objs = current.objects().await.ok()?;
                let ready = has_media(&objs, address);
                if !ready && !accepted.load(std::sync::atomic::Ordering::SeqCst) {
                    accepted.store(
                        match current.connect_audio(&path).await {
                            Ok(()) | Err(BluezError::AlreadyConnected(_)) => true,
                            Err(BluezError::InProgress(_)) => false,
                            Err(_) => return None,
                        },
                        std::sync::atomic::Ordering::SeqCst,
                    );
                }
                Some(ready)
            })
            .await
        }
        .await;

        if let Err(err) = outcome {
            if opened_le && let Some(owner) = le_owner {
                let _ = current.disconnect_le(&path, &owner).await;
            }
            return Err(err);
        }
        Ok(())
    }

    pub async fn release(&self, address: &str) -> Result<(), String> {
        let current = self
            .transport
            .active_bus()
            .await
            .map_err(|err| err.to_string())?;
        let objects = current.objects().await.map_err(|err| err.to_string())?;
        if device_path(&objects, address, true, None).is_none() {
            return Err("headset to release not found".to_string());
        }
        let paths = connected_paths(&objects, address)?;
        self.transport
            .released_links
            .release(&*current, &paths, || async {
                await_connection("timed out waiting for the audio or Bluetooth link to drop", || async {
                    let objs = current.objects().await.ok()?;
                    let remaining = connected_paths(&objs, address).ok()?;
                    Some(remaining.is_empty())
                })
                .await
            })
            .await
    }
}

async fn await_connection<F, Fut>(timeout_message: &str, mut check: F) -> Result<(), String>
where
    F: FnMut() -> Fut,
    Fut: Future<Output = Option<bool>>,
{
    let ready = tokio::time::timeout(Duration::from_millis(10_000), async {
        loop {
            if check().await == Some(true) {
                return true;
            }
            tokio::time::sleep(Duration::from_millis(150)).await;
        }
    })
    .await;
    match ready {
        Ok(true) => Ok(()),
        _ => Err(timeout_message.to_string()),
    }
}

pub fn connected_paths(objects: &BluezObjects, address: &str) -> Result<Vec<String>, String> {
    let normalized = normalize_bluetooth_address(address);
    let mut paths = Vec::new();
    for (path, interfaces) in objects {
        let Some(device) = interfaces.get(DEVICE) else {
            continue;
        };
        if !on_powered_adapter(objects, path) {
            continue;
        }
        let dev_addr = device
            .get("Address")
            .and_then(BluezValue::as_str)
            .and_then(normalize_bluetooth_address);
        if dev_addr.is_none() || dev_addr != normalized {
            continue;
        }
        if has_media_transport(objects, path) || has_connected_bearer(interfaces, device)? {
            paths.push(path.clone());
        }
    }
    Ok(paths)
}

pub fn has_connected_bearer(
    interfaces: &BluezInterfaces,
    device: &BluezProperties,
) -> Result<bool, String> {
    let classic = interfaces
        .get(CLASSIC_BEARER)
        .and_then(|props| props.get("Connected"))
        .and_then(BluezValue::as_bool);
    let le = interfaces
        .get(LE_BEARER)
        .and_then(|props| props.get("Connected"))
        .and_then(BluezValue::as_bool);
    match (classic, le) {
        (Some(true), _) | (_, Some(true)) => Ok(true),
        (Some(false), Some(false)) => Ok(false),
        _ => device
            .get("Connected")
            .and_then(BluezValue::as_bool)
            .ok_or_else(|| "headset link state unreadable".to_string()),
    }
}

pub fn has_media(objects: &BluezObjects, address: &str) -> bool {
    let Some(normalized) = normalize_bluetooth_address(address) else {
        return false;
    };
    let mut targets = BTreeSet::new();
    targets.insert(normalized.clone());
    let snapshot = audio_snapshot(objects, &targets);
    snapshot
        .profiles
        .get(&AudioProfile::A2dp)
        .and_then(Option::as_ref)
        .is_some_and(|p| p.connected_addresses.contains(&normalized))
}

pub fn audio_snapshot(objects: &BluezObjects, targets: &BTreeSet<String>) -> RawAudioSnapshot {
    let paired = devices(objects);
    let addresses: BTreeMap<String, String> = objects
        .iter()
        .filter_map(|(path, ifaces)| {
            let raw = ifaces.get(DEVICE)?.get("Address")?.as_str()?;
            Some((path.clone(), normalize_bluetooth_address(raw)?))
        })
        .collect();
    let mut connected: BTreeMap<AudioProfile, BTreeSet<String>> = [
        (AudioProfile::A2dp, BTreeSet::new()),
        (AudioProfile::Headset, BTreeSet::new()),
        (AudioProfile::LeAudio, BTreeSet::new()),
    ]
    .into_iter()
    .collect();

    for (path, interfaces) in objects {
        let Some(transport) = interfaces.get(MEDIA_TRANSPORT) else {
            continue;
        };
        if let Some((profile, addr)) = connected_audio_profile(objects, path, transport, &addresses)
            && let Some(set) = connected.get_mut(&profile)
        {
            set.insert(addr);
        }
    }

    let mut all_known: BTreeSet<String> = targets.iter().cloned().collect();
    all_known.extend(addresses.values().cloned());
    let le_audio_observable = !connected[&AudioProfile::LeAudio].is_empty();

    let profiles = connected
        .into_iter()
        .map(|(profile, list)| {
            let states = all_known
                .iter()
                .map(|addr| {
                    let state = if list.contains(addr) {
                        AudioConnectionState::Connected
                    } else {
                        AudioConnectionState::Disconnected
                    };
                    (addr.clone(), state)
                })
                .collect();
            (
                profile,
                Some(RawProfileSnapshot {
                    connected_addresses: list.into_iter().collect(),
                    states,
                }),
            )
        })
        .collect();

    let enabled = objects.values().any(|ifaces| {
        ifaces
            .get(ADAPTER)
            .and_then(|props| props.get("Powered"))
            .and_then(BluezValue::as_bool)
            == Some(true)
    });

    RawAudioSnapshot {
        enabled,
        spp_available: false,
        le_audio_observable,
        bonded_devices: paired,
        profiles,
    }
}

fn empty_audio_snapshot() -> RawAudioSnapshot {
    RawAudioSnapshot {
        enabled: false,
        spp_available: false,
        le_audio_observable: false,
        bonded_devices: Vec::new(),
        profiles: [
            (AudioProfile::A2dp, None),
            (AudioProfile::Headset, None),
            (AudioProfile::LeAudio, None),
        ]
        .into_iter()
        .collect(),
    }
}

fn connected_audio_profile(
    objects: &BluezObjects,
    path: &str,
    transport: &BluezProperties,
    addresses: &BTreeMap<String, String>,
) -> Option<(AudioProfile, String)> {
    let owner = transport
        .get("Device")
        .and_then(BluezValue::as_str)
        .map(str::to_string)
        .or_else(|| {
            addresses
                .keys()
                .find(|dev_path| media_belongs_to(dev_path, path, transport))
                .cloned()
        })?;
    let address = addresses.get(&owner)?.clone();
    if objects
        .get(adapter_of(&owner))
        .and_then(|ifaces| ifaces.get(ADAPTER))
        .and_then(|props| props.get("Powered"))
        .and_then(BluezValue::as_bool)
        == Some(false)
    {
        return None;
    }
    let uuid = transport.get("UUID").and_then(BluezValue::as_str)?;
    let profile = profile_for_uuid(uuid)?;
    let advertised = objects
        .get(&owner)
        .and_then(|ifaces| ifaces.get(DEVICE))
        .and_then(|props| props.get("UUIDs"))
        .and_then(BluezValue::as_strings);
    match advertised {
        None => Some((profile, address)),
        Some(uuids)
            if uuids
                .iter()
                .any(|item| profile_for_uuid(item) == Some(profile)) =>
        {
            Some((profile, address))
        }
        _ => None,
    }
}

fn profile_for_uuid(uuid: &str) -> Option<AudioProfile> {
    match uuid.to_ascii_lowercase().as_str() {
        "0000110a-0000-1000-8000-00805f9b34fb" | "0000110b-0000-1000-8000-00805f9b34fb" => {
            Some(AudioProfile::A2dp)
        }
        "0000184e-0000-1000-8000-00805f9b34fb"
        | "0000184f-0000-1000-8000-00805f9b34fb"
        | "00001850-0000-1000-8000-00805f9b34fb" => Some(AudioProfile::LeAudio),
        _ => None,
    }
}

fn devices(objects: &BluezObjects) -> Vec<RawAudioDevice> {
    let mut seen = BTreeSet::new();
    let mut result = Vec::new();
    for (path, interfaces) in objects {
        let Some(properties) = interfaces.get(DEVICE) else {
            continue;
        };
        if properties.get("Paired").and_then(BluezValue::as_bool) != Some(true)
            || !on_powered_adapter(objects, path)
        {
            continue;
        }
        let Some(address) = properties
            .get("Address")
            .and_then(BluezValue::as_str)
            .and_then(normalize_bluetooth_address)
        else {
            continue;
        };
        if !seen.insert(address.clone()) {
            continue;
        }
        result.push(RawAudioDevice {
            name: properties
                .get("Name")
                .and_then(BluezValue::as_str)
                .map(str::to_string),
            address,
            alias: properties
                .get("Alias")
                .and_then(BluezValue::as_str)
                .map(str::to_string),
        });
    }
    result
}

fn transport_error(error: BluezError) -> TransportError {
    if error.is_access_denied() {
        TransportError::PermissionDenied(error.message().to_string())
    } else {
        TransportError::Terminal(error.message().to_string())
    }
}

fn reason_summary(reason: &TransportUnavailableReason) -> String {
    match reason {
        TransportUnavailableReason::BackendUnavailable { detail } => {
            detail.clone().unwrap_or_else(|| "BACKEND_UNAVAILABLE".to_string())
        }
        TransportUnavailableReason::MissingDependency { detail } => {
            detail.clone().unwrap_or_else(|| "MISSING_DEPENDENCY".to_string())
        }
        TransportUnavailableReason::UnsupportedPlatform { platform } => {
            platform.clone().unwrap_or_else(|| "UNSUPPORTED_PLATFORM".to_string())
        }
        other => format!("{other:?}"),
    }
}

fn discovery_failure(signal: &BluezSignal, adapter: &str) -> Option<String> {
    match signal {
        BluezSignal::Owner { present } if !present => Some("BlueZ 服务已停止".to_string()),
        BluezSignal::Changed {
            path,
            interface_name,
            values,
        } if path == adapter
            && interface_name == ADAPTER
            && values.get("Powered") == Some(&Some(BluezValue::Bool(false))) =>
        {
            Some("蓝牙适配器已关闭".to_string())
        }
        BluezSignal::Removed { path, interfaces }
            if path == adapter && interfaces.contains(ADAPTER) =>
        {
            Some("蓝牙适配器已移除".to_string())
        }
        _ => None,
    }
}

fn update_advertisement(
    devices: &mut HashMap<String, BluezProperties>,
    signal: BluezSignal,
    adapter: &str,
) -> Option<Advertisement> {
    let advertisement_keys = ["Address", "Name", "Alias", "RSSI", "ManufacturerData"];
    let (path, props) = match signal {
        BluezSignal::Added { path, interfaces } => {
            let device = interfaces.get(DEVICE)?.clone();
            (path, device)
        }
        BluezSignal::Changed {
            path,
            interface_name,
            values,
        } => {
            if interface_name != DEVICE
                || !values
                    .keys()
                    .any(|key| advertisement_keys.contains(&key.as_str()))
            {
                return None;
            }
            let mut merged = devices.get(&path).cloned().unwrap_or_default();
            for (k, v) in values {
                match v {
                    Some(val) => {
                        merged.insert(k, val);
                    }
                    None => {
                        merged.remove(&k);
                    }
                }
            }
            (path, merged)
        }
        BluezSignal::Removed { path, interfaces } => {
            if interfaces.contains(DEVICE) {
                devices.remove(&path);
            }
            return None;
        }
        BluezSignal::Owner { .. } | BluezSignal::PrepareForSleep { .. } => return None,
    };
    if adapter_of(&path) != adapter {
        return None;
    }
    let ad = advertisement_of(&props)?;
    devices.insert(path, props);
    Some(ad)
}

fn advertisement_of(properties: &BluezProperties) -> Option<Advertisement> {
    let address = properties
        .get("Address")
        .and_then(BluezValue::as_str)
        .and_then(normalize_bluetooth_address)?;
    let manufacturer_data = properties
        .get("ManufacturerData")
        .and_then(BluezValue::as_manufacturer_data)
        .cloned()
        .unwrap_or_default();
    let name = properties
        .get("Name")
        .and_then(BluezValue::as_str)
        .or_else(|| properties.get("Alias").and_then(BluezValue::as_str))
        .map(str::to_string);
    let rssi = properties.get("RSSI").and_then(BluezValue::as_i16);
    Some(Advertisement {
        headset_id: HeadsetId::new(address),
        name,
        rssi,
        manufacturer_data,
    })
}

fn affects_audio(signal: &BluezSignal) -> bool {
    let audio_device_properties = ["Address", "Name", "Alias", "Paired", "Connected", "UUIDs"];
    match signal {
        BluezSignal::Changed {
            interface_name,
            values,
            ..
        } if interface_name == DEVICE => values
            .keys()
            .any(|key| audio_device_properties.contains(&key.as_str())),
        _ => affects(signal, &[ADAPTER, DEVICE, MEDIA_TRANSPORT]),
    }
}

fn affects(signal: &BluezSignal, interfaces: &[&str]) -> bool {
    match signal {
        BluezSignal::PrepareForSleep { .. } => interfaces.contains(&MEDIA_TRANSPORT),
        BluezSignal::Owner { .. } => true,
        BluezSignal::Changed { interface_name, .. } => {
            interfaces.contains(&interface_name.as_str())
        }
        BluezSignal::Added {
            interfaces: added, ..
        } => added.keys().any(|k| interfaces.contains(&k.as_str())),
        BluezSignal::Removed {
            interfaces: removed,
            ..
        } => removed.iter().any(|k| interfaces.contains(&k.as_str())),
    }
}
