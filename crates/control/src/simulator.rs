use crate::core::LocalClock;
use crate::gatt::{
    GattCharacteristic, GattDriver, GattError, GattEvent, GattFailureKind, GattService,
    GattSubscription,
};
use crate::snapshot::HeadsetCandidate;
use crate::transport::{ControlTransport, TransportError};
use async_trait::async_trait;
use openrose_headset::simulator::{SimulatedHeadset, SimulatorError};
use openrose_headset::{
    FieldValue, FirmwareVersion, GattProfile, HeadsetField, HeadsetId, LocalDateTime, ModelProfile,
};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;
use tokio::sync::{mpsc, watch};
use tokio::time::Instant;

const WRITE_CHARACTERISTIC: u64 = 1;
const NOTIFY_CHARACTERISTIC: u64 = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoggedWrite {
    pub at: Instant,
    pub bytes: Vec<u8>,
}

impl LoggedWrite {
    pub fn hex(&self) -> String {
        openrose_headset::encode_hex(&self.bytes)
    }
}

struct Conditions {
    muted: bool,
    ignored_keys: BTreeSet<[u8; 2]>,
    open_delay: Duration,
    reply_latency: Duration,
    mtu: Option<u16>,
    missing_discoveries: usize,
    write_failure: Option<GattError>,
    write_gate: Option<watch::Sender<bool>>,
    refuse_close: bool,
}

struct Shared {
    id: HeadsetId,
    profile: ModelProfile,
    gatt: GattProfile,
    device: Mutex<SimulatedHeadset>,
    conditions: Mutex<Conditions>,
    links: Mutex<Links>,
    writes: Mutex<Vec<LoggedWrite>>,
}

#[derive(Default)]
struct Links {
    senders: Vec<mpsc::UnboundedSender<GattEvent>>,
    current: Option<usize>,
}

#[derive(Clone)]
pub struct SimulatedHeadsetHandle {
    shared: Arc<Shared>,
}

impl SimulatedHeadsetHandle {
    pub fn id(&self) -> HeadsetId {
        self.shared.id.clone()
    }

    pub fn profile(&self) -> ModelProfile {
        self.shared.profile
    }

    pub fn candidate(&self) -> HeadsetCandidate {
        HeadsetCandidate::supported(self.shared.id.clone(), self.shared.profile)
    }

    pub fn device(&self) -> MutexGuard<'_, SimulatedHeadset> {
        self.shared.device.lock().expect("device lock")
    }

    pub fn readings(&self) -> BTreeMap<HeadsetField, FieldValue> {
        self.device().readings()
    }

    pub fn change(&self, field: HeadsetField, value: FieldValue) -> Result<(), SimulatorError> {
        let frames = self.device().set(field, value)?;
        for frame in frames {
            self.inject(frame);
        }
        Ok(())
    }

    pub fn inject(&self, bytes: Vec<u8>) -> bool {
        self.shared.deliver(bytes)
    }

    pub fn ignore_key(&self, key_hex: &str) {
        let bytes = openrose_headset::decode_hex(key_hex).expect("key hex");
        self.conditions().ignored_keys.insert([bytes[0], bytes[1]]);
    }

    pub fn open_delay(&self, delay: Duration) {
        self.conditions().open_delay = delay;
    }

    pub fn mute(&self, muted: bool) {
        self.conditions().muted = muted;
    }

    pub fn reply_latency(&self, latency: Duration) {
        self.conditions().reply_latency = latency;
    }

    pub fn negotiated_mtu(&self, mtu: Option<u16>) {
        self.conditions().mtu = mtu;
    }

    pub fn missing_discoveries(&self, count: usize) {
        self.conditions().missing_discoveries = count;
    }

    pub fn fail_writes(&self, failure: Option<GattError>) {
        self.conditions().write_failure = failure;
    }

    pub fn stall_writes(&self) {
        self.conditions().write_gate = Some(watch::channel(false).0);
    }

    pub fn resume_writes(&self) {
        if let Some(gate) = self.conditions().write_gate.take() {
            gate.send_replace(true);
        }
    }

    pub fn refuse_close(&self, refuse: bool) {
        self.conditions().refuse_close = refuse;
    }

    pub fn inject_late(&self, link: usize, bytes: Vec<u8>) -> bool {
        self.shared.send(Some(link), notification(bytes))
    }

    pub fn disconnect(&self, failure: GattError) {
        self.shared.send(None, GattEvent::Disconnected(failure));
    }

    pub fn writes(&self) -> Vec<LoggedWrite> {
        self.shared.writes.lock().expect("writes lock").clone()
    }

    pub fn written_hex(&self) -> Vec<String> {
        self.writes().iter().map(LoggedWrite::hex).collect()
    }

    pub fn connections_opened(&self) -> usize {
        self.shared.links.lock().expect("links lock").senders.len()
    }

    pub fn link_open(&self) -> bool {
        self.shared
            .links
            .lock()
            .expect("links lock")
            .current
            .is_some()
    }

    fn conditions(&self) -> MutexGuard<'_, Conditions> {
        self.shared.conditions.lock().expect("conditions lock")
    }
}

fn notification(value: Vec<u8>) -> GattEvent {
    GattEvent::Notification {
        instance_id: NOTIFY_CHARACTERISTIC,
        value,
    }
}

impl Shared {
    fn send(&self, link: Option<usize>, event: GattEvent) -> bool {
        let links = self.links.lock().expect("links lock");
        let Some(number) = link.or(links.current) else {
            return false;
        };
        links
            .senders
            .get(number - 1)
            .is_some_and(|sender| sender.send(event).is_ok())
    }

    fn deliver(&self, bytes: Vec<u8>) -> bool {
        self.send(None, notification(bytes))
    }
}

pub struct SimulatedTransport {
    headsets: Mutex<BTreeMap<HeadsetId, SimulatedHeadsetHandle>>,
    audio: watch::Sender<Option<HeadsetId>>,
}

impl Default for SimulatedTransport {
    fn default() -> Self {
        Self::new()
    }
}

impl SimulatedTransport {
    pub fn new() -> Self {
        Self {
            headsets: Mutex::new(BTreeMap::new()),
            audio: watch::channel(None).0,
        }
    }

    pub fn add_headset(
        &self,
        id: HeadsetId,
        profile: ModelProfile,
        firmware: FirmwareVersion,
    ) -> Result<SimulatedHeadsetHandle, SimulatorError> {
        let device = SimulatedHeadset::new(profile, firmware)?;
        let handle = SimulatedHeadsetHandle {
            shared: Arc::new(Shared {
                id: id.clone(),
                profile,
                gatt: profile.gatt(),
                device: Mutex::new(device),
                conditions: Mutex::new(Conditions {
                    muted: false,
                    ignored_keys: BTreeSet::new(),
                    open_delay: Duration::ZERO,
                    reply_latency: Duration::ZERO,
                    mtu: Some(185),
                    missing_discoveries: 0,
                    write_failure: None,
                    write_gate: None,
                    refuse_close: false,
                }),
                links: Mutex::new(Links::default()),
                writes: Mutex::new(Vec::new()),
            }),
        };
        self.headsets
            .lock()
            .expect("headsets lock")
            .insert(id, handle.clone());
        Ok(handle)
    }

    pub fn set_audio_connected(&self, headset: Option<HeadsetId>) {
        self.audio.send_replace(headset);
    }
}

#[async_trait]
impl ControlTransport for SimulatedTransport {
    async fn open_gatt(&self, headset: &HeadsetId) -> Result<Box<dyn GattDriver>, TransportError> {
        let handle = self
            .headsets
            .lock()
            .expect("headsets lock")
            .get(headset)
            .cloned()
            .ok_or_else(|| TransportError::Terminal("模拟传输没有这副耳机".to_string()))?;
        let (events, receiver) = mpsc::unbounded_channel();
        let number = {
            let mut links = handle.shared.links.lock().expect("links lock");
            links.senders.push(events);
            links.current = Some(links.senders.len());
            links.senders.len()
        };
        Ok(Box::new(SimulatedGattDriver {
            shared: Arc::clone(&handle.shared),
            number,
            events: tokio::sync::Mutex::new(receiver),
            discoveries: Mutex::new(0),
        }))
    }

    async fn bonded_headsets(&self) -> Vec<HeadsetCandidate> {
        self.headsets
            .lock()
            .expect("headsets lock")
            .values()
            .map(SimulatedHeadsetHandle::candidate)
            .collect()
    }

    fn audio_connected_headset(&self) -> watch::Receiver<Option<HeadsetId>> {
        self.audio.subscribe()
    }
}

struct SimulatedGattDriver {
    shared: Arc<Shared>,
    number: usize,
    events: tokio::sync::Mutex<mpsc::UnboundedReceiver<GattEvent>>,
    discoveries: Mutex<usize>,
}

impl SimulatedGattDriver {
    fn conditions(&self) -> MutexGuard<'_, Conditions> {
        self.shared.conditions.lock().expect("conditions lock")
    }
}

#[async_trait]
impl GattDriver for SimulatedGattDriver {
    async fn next_event(&self) -> Option<GattEvent> {
        self.events.lock().await.recv().await
    }

    async fn connect(&self) -> Result<(), GattError> {
        let delay = self.conditions().open_delay;
        if !delay.is_zero() {
            tokio::time::sleep(delay).await;
        }
        Ok(())
    }

    async fn discover_services(&self) -> Result<Vec<GattService>, GattError> {
        let attempt = {
            let mut discoveries = self.discoveries.lock().expect("discoveries lock");
            *discoveries += 1;
            *discoveries
        };
        if attempt <= self.conditions().missing_discoveries {
            return Ok(Vec::new());
        }
        Ok(vec![GattService {
            uuid: self.shared.gatt.service_uuid.clone(),
            characteristics: vec![
                GattCharacteristic {
                    instance_id: WRITE_CHARACTERISTIC,
                    uuid: self.shared.gatt.write_uuid.clone(),
                    can_write: true,
                    can_write_without_response: false,
                    can_notify: false,
                    can_indicate: false,
                },
                GattCharacteristic {
                    instance_id: NOTIFY_CHARACTERISTIC,
                    uuid: self.shared.gatt.notify_uuid.clone(),
                    can_write: false,
                    can_write_without_response: false,
                    can_notify: true,
                    can_indicate: false,
                },
            ],
        }])
    }

    async fn request_mtu(&self, _preferred: u16) -> Result<Option<u16>, GattError> {
        Ok(self.conditions().mtu)
    }

    async fn set_notification_routing(
        &self,
        _characteristic: &GattCharacteristic,
        _enabled: bool,
    ) -> Result<(), GattError> {
        Ok(())
    }

    async fn configure_subscription(
        &self,
        _characteristic: &GattCharacteristic,
        _subscription: GattSubscription,
    ) -> Result<(), GattError> {
        Ok(())
    }

    async fn write(
        &self,
        _characteristic: &GattCharacteristic,
        value: &[u8],
        _with_response: bool,
    ) -> Result<(), GattError> {
        let (failure, gate) = {
            let conditions = self.conditions();
            (
                conditions.write_failure.clone(),
                conditions.write_gate.as_ref().map(watch::Sender::subscribe),
            )
        };
        if let Some(failure) = failure {
            return Err(failure);
        }
        self.shared
            .writes
            .lock()
            .expect("writes lock")
            .push(LoggedWrite {
                at: Instant::now(),
                bytes: value.to_vec(),
            });
        if let Some(mut gate) = gate {
            let _ = gate.wait_for(|open| *open).await;
        }
        let (muted, latency, ignored) = {
            let conditions = self.conditions();
            let ignored =
                value.len() >= 7 && conditions.ignored_keys.contains(&[value[5], value[6]]);
            (conditions.muted, conditions.reply_latency, ignored)
        };
        if muted || ignored {
            return Ok(());
        }
        let replies = self
            .shared
            .device
            .lock()
            .expect("device lock")
            .receive(value);
        if latency.is_zero() {
            for reply in replies {
                self.shared.deliver(reply);
            }
        } else {
            let shared = Arc::clone(&self.shared);
            tokio::spawn(async move {
                tokio::time::sleep(latency).await;
                for reply in replies {
                    shared.deliver(reply);
                }
            });
        }
        Ok(())
    }

    async fn close(&self) -> Result<(), GattError> {
        if self.conditions().refuse_close {
            return Err(GattError::new(GattFailureKind::Unknown, "模拟传输拒绝关闭"));
        }
        let mut links = self.shared.links.lock().expect("links lock");
        if links.current == Some(self.number) {
            links.current = None;
        }
        Ok(())
    }
}

pub struct SimulatedClock {
    now: Mutex<LocalDateTime>,
}

impl SimulatedClock {
    pub fn new(now: LocalDateTime) -> Self {
        Self {
            now: Mutex::new(now),
        }
    }

    pub fn set(&self, now: LocalDateTime) {
        *self.now.lock().expect("clock lock") = now;
    }
}

impl LocalClock for SimulatedClock {
    fn now(&self) -> LocalDateTime {
        *self.now.lock().expect("clock lock")
    }
}
