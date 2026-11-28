mod discovery;

pub use discovery::{SimulatedAudio, SimulatedScan};

use crate::audio::{AudioFactsDriver, RawAudioDevice};
use crate::core::LocalClock;
use crate::gatt::{
    GattCharacteristic, GattDriver, GattError, GattEvent, GattFailureKind, GattService,
    GattSubscription,
};
use crate::scan::ScanDriver;
use crate::selection::TransportEndpoint;
use crate::snapshot::HeadsetCandidate;
use crate::spp::{SppDriver, SppError, SppFailureKind};
use crate::transport::{ControlTransport, TransportError, TransportFacts};
use async_trait::async_trait;
use openrose_headset::simulator::{SimulatedHeadset, SimulatorError};
use openrose_headset::{
    FieldValue, FirmwareVersion, GattProfile, HeadsetField, HeadsetId, LocalDateTime, ModelProfile,
};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;
use tokio::sync::{mpsc, watch};
use tokio::time::Instant;

const WRITE_CHARACTERISTIC: u64 = 1;
const NOTIFY_CHARACTERISTIC: u64 = 2;
const SPP_WRITE_CAPACITY: usize = 260;

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
    write_duration: Duration,
    mtu: Option<u16>,
    missing_discoveries: usize,
    write_failure: Option<GattError>,
    write_gate: Option<watch::Sender<bool>>,
    refuse_close: bool,
    classic_address: Option<String>,
    facts: TransportFacts,
    facts_gate: Option<watch::Sender<bool>>,
    facts_failure: bool,
    spp_capacity: usize,
    spp_connect_failure: Option<SppError>,
    spp_write_failure: Option<SppError>,
}

struct Shared {
    id: HeadsetId,
    profile: ModelProfile,
    gatt: GattProfile,
    device: Mutex<SimulatedHeadset>,
    conditions: Mutex<Conditions>,
    links: Mutex<Links>,
    writes: Mutex<Vec<LoggedWrite>>,
    endpoints: Mutex<Vec<TransportEndpoint>>,
    facts_lookups: Mutex<Vec<String>>,
    facts_cancelled: AtomicBool,
}

enum LinkSender {
    Gatt(mpsc::UnboundedSender<GattEvent>),
    Spp(mpsc::UnboundedSender<Result<Vec<u8>, SppError>>),
}

impl LinkSender {
    fn notify(&self, bytes: Vec<u8>) -> bool {
        match self {
            Self::Gatt(sender) => sender.send(notification(bytes)).is_ok(),
            Self::Spp(sender) => sender.send(Ok(bytes)).is_ok(),
        }
    }

    fn fail_gatt(&self, failure: GattError) -> bool {
        match self {
            Self::Gatt(sender) => sender.send(GattEvent::Disconnected(failure)).is_ok(),
            Self::Spp(_) => false,
        }
    }

    fn fail_spp(&self, failure: SppError) -> bool {
        match self {
            Self::Gatt(_) => false,
            Self::Spp(sender) => sender.send(Err(failure)).is_ok(),
        }
    }
}

#[derive(Default)]
struct Links {
    senders: Vec<LinkSender>,
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
        HeadsetCandidate {
            classic_address: self.conditions().classic_address.clone(),
            ..HeadsetCandidate::supported(self.shared.id.clone(), self.shared.profile)
        }
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

    pub fn answer_key(&self, key_hex: &str) {
        let bytes = openrose_headset::decode_hex(key_hex).expect("key hex");
        self.conditions().ignored_keys.remove(&[bytes[0], bytes[1]]);
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

    pub fn write_duration(&self, duration: Duration) {
        self.conditions().write_duration = duration;
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
        self.shared
            .with_link(Some(link), |sender| sender.notify(bytes))
    }

    pub fn disconnect(&self, failure: GattError) {
        self.shared
            .with_link(None, |sender| sender.fail_gatt(failure));
    }

    pub fn set_classic_address(&self, address: Option<&str>) {
        self.conditions().classic_address = address.map(str::to_string);
    }

    pub fn set_connection_facts(&self, facts: TransportFacts) {
        self.conditions().facts = facts;
    }

    pub fn stall_facts(&self) {
        self.conditions().facts_gate = Some(watch::channel(false).0);
    }

    pub fn resume_facts(&self) {
        if let Some(gate) = self.conditions().facts_gate.take() {
            gate.send_replace(true);
        }
    }

    pub fn fail_facts(&self, fail: bool) {
        self.conditions().facts_failure = fail;
    }

    pub fn spp_max_write_size(&self, capacity: usize) {
        self.conditions().spp_capacity = capacity;
    }

    pub fn fail_spp_connect(&self, failure: Option<SppError>) {
        self.conditions().spp_connect_failure = failure;
    }

    pub fn fail_spp_writes(&self, failure: Option<SppError>) {
        self.conditions().spp_write_failure = failure;
    }

    pub fn disconnect_spp(&self, failure: SppError) {
        self.shared
            .with_link(None, |sender| sender.fail_spp(failure));
    }

    pub fn endpoints(&self) -> Vec<TransportEndpoint> {
        self.shared
            .endpoints
            .lock()
            .expect("endpoints lock")
            .clone()
    }

    pub fn facts_lookups(&self) -> Vec<String> {
        self.shared
            .facts_lookups
            .lock()
            .expect("facts lock")
            .clone()
    }

    pub fn facts_cancelled(&self) -> bool {
        self.shared.facts_cancelled.load(Ordering::SeqCst)
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
    fn with_link(&self, link: Option<usize>, act: impl FnOnce(&LinkSender) -> bool) -> bool {
        let links = self.links.lock().expect("links lock");
        let Some(number) = link.or(links.current) else {
            return false;
        };
        links.senders.get(number - 1).is_some_and(act)
    }

    fn deliver(&self, bytes: Vec<u8>) -> bool {
        self.with_link(None, |sender| sender.notify(bytes))
    }

    fn conditions(&self) -> MutexGuard<'_, Conditions> {
        self.conditions.lock().expect("conditions lock")
    }

    fn open_link(&self, sender: LinkSender) -> usize {
        let mut links = self.links.lock().expect("links lock");
        links.senders.push(sender);
        links.current = Some(links.senders.len());
        links.senders.len()
    }

    fn close_link(&self, number: usize) {
        let mut links = self.links.lock().expect("links lock");
        if links.current == Some(number) {
            links.current = None;
        }
    }

    async fn accept(self: &Arc<Self>, value: &[u8]) {
        let gate = self
            .conditions()
            .write_gate
            .as_ref()
            .map(watch::Sender::subscribe);
        self.writes.lock().expect("writes lock").push(LoggedWrite {
            at: Instant::now(),
            bytes: value.to_vec(),
        });
        if let Some(mut gate) = gate {
            let _ = gate.wait_for(|open| *open).await;
        }
        let duration = self.conditions().write_duration;
        if !duration.is_zero() {
            tokio::time::sleep(duration).await;
        }
        let (muted, latency, ignored) = {
            let conditions = self.conditions();
            let ignored =
                value.len() >= 7 && conditions.ignored_keys.contains(&[value[5], value[6]]);
            (conditions.muted, conditions.reply_latency, ignored)
        };
        if muted || ignored {
            return;
        }
        let replies = self.device.lock().expect("device lock").receive(value);
        if latency.is_zero() {
            for reply in replies {
                self.deliver(reply);
            }
        } else {
            let shared = Arc::clone(self);
            tokio::spawn(async move {
                tokio::time::sleep(latency).await;
                for reply in replies {
                    shared.deliver(reply);
                }
            });
        }
    }
}

pub struct SimulatedTransport {
    headsets: Mutex<BTreeMap<HeadsetId, SimulatedHeadsetHandle>>,
    audio: Arc<SimulatedAudio>,
    scan: Arc<SimulatedScan>,
    initialization_recovery: AtomicBool,
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
            audio: Arc::new(SimulatedAudio::new()),
            scan: Arc::new(SimulatedScan::default()),
            initialization_recovery: AtomicBool::new(false),
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
                    write_duration: Duration::ZERO,
                    mtu: Some(185),
                    missing_discoveries: 0,
                    write_failure: None,
                    write_gate: None,
                    refuse_close: false,
                    classic_address: None,
                    facts: TransportFacts::default(),
                    facts_gate: None,
                    facts_failure: false,
                    spp_capacity: SPP_WRITE_CAPACITY,
                    spp_connect_failure: None,
                    spp_write_failure: None,
                }),
                links: Mutex::new(Links::default()),
                writes: Mutex::new(Vec::new()),
                endpoints: Mutex::new(Vec::new()),
                facts_lookups: Mutex::new(Vec::new()),
                facts_cancelled: AtomicBool::new(false),
            }),
        };
        self.audio.pair(RawAudioDevice::new(
            Some(profile.display_name()),
            id.as_str(),
        ));
        self.headsets
            .lock()
            .expect("headsets lock")
            .insert(id, handle.clone());
        Ok(handle)
    }

    pub fn audio(&self) -> &SimulatedAudio {
        &self.audio
    }

    pub fn scan(&self) -> &SimulatedScan {
        &self.scan
    }

    pub fn set_audio_connected(&self, headset: Option<HeadsetId>) {
        self.audio
            .set_audio_connected(headset.as_ref().map(HeadsetId::as_str));
    }

    pub fn support_initialization_recovery(&self, supported: bool) {
        self.initialization_recovery
            .store(supported, Ordering::SeqCst);
    }

    fn handle(&self, headset: &HeadsetId) -> Result<SimulatedHeadsetHandle, TransportError> {
        self.headsets
            .lock()
            .expect("headsets lock")
            .get(headset)
            .cloned()
            .ok_or_else(|| TransportError::Terminal("模拟传输没有这副耳机".to_string()))
    }
}

struct FactsLookup<'a> {
    cancelled: &'a AtomicBool,
    finished: bool,
}

impl Drop for FactsLookup<'_> {
    fn drop(&mut self) {
        if !self.finished {
            self.cancelled.store(true, Ordering::SeqCst);
        }
    }
}

#[async_trait]
impl ControlTransport for SimulatedTransport {
    async fn open_gatt(&self, headset: &HeadsetId) -> Result<Box<dyn GattDriver>, TransportError> {
        let handle = self.handle(headset)?;
        let (events, receiver) = mpsc::unbounded_channel();
        let number = handle.shared.open_link(LinkSender::Gatt(events));
        let gatt = handle.shared.gatt.clone();
        handle
            .shared
            .endpoints
            .lock()
            .expect("endpoints lock")
            .push(TransportEndpoint::BleGatt {
                service_uuid: gatt.service_uuid,
                write_uuid: gatt.write_uuid,
                notify_uuid: gatt.notify_uuid,
            });
        Ok(Box::new(SimulatedGattDriver {
            shared: Arc::clone(&handle.shared),
            number,
            events: tokio::sync::Mutex::new(receiver),
            discoveries: Mutex::new(0),
        }))
    }

    async fn start_scan(&self) -> Result<Box<dyn ScanDriver>, TransportError> {
        self.scan.open()
    }

    async fn open_spp(
        &self,
        headset: &HeadsetId,
        address: &str,
        service_uuid: &str,
    ) -> Result<Box<dyn SppDriver>, TransportError> {
        let handle = self.handle(headset)?;
        if handle.conditions().classic_address.as_deref() != Some(address) {
            return Err(TransportError::InvalidArgument(
                "模拟耳机没有这个经典蓝牙地址".to_string(),
            ));
        }
        let (events, receiver) = mpsc::unbounded_channel();
        let number = handle.shared.open_link(LinkSender::Spp(events));
        handle
            .shared
            .endpoints
            .lock()
            .expect("endpoints lock")
            .push(TransportEndpoint::SppRfcomm {
                address: address.to_string(),
                service_uuid: service_uuid.to_string(),
            });
        let capacity = handle.conditions().spp_capacity;
        Ok(Box::new(SimulatedSppDriver {
            shared: Arc::clone(&handle.shared),
            number,
            capacity,
            incoming: tokio::sync::Mutex::new(receiver),
        }))
    }

    async fn connection_facts(
        &self,
        headset: &HeadsetId,
        classic_address: &str,
    ) -> Result<TransportFacts, TransportError> {
        let handle = self.handle(headset)?;
        handle
            .shared
            .facts_lookups
            .lock()
            .expect("facts lock")
            .push(classic_address.to_string());
        let mut lookup = FactsLookup {
            cancelled: &handle.shared.facts_cancelled,
            finished: false,
        };
        let gate = handle
            .conditions()
            .facts_gate
            .as_ref()
            .map(watch::Sender::subscribe);
        if let Some(mut gate) = gate {
            let _ = gate.wait_for(|open| *open).await;
        }
        lookup.finished = true;
        let conditions = handle.conditions();
        if conditions.facts_failure {
            return Err(TransportError::Terminal(
                "模拟传输无法查询连接事实".to_string(),
            ));
        }
        Ok(conditions.facts)
    }

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver> {
        self.audio.clone()
    }

    fn supports_initialization_recovery(&self) -> bool {
        self.initialization_recovery.load(Ordering::SeqCst)
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
        self.shared.conditions()
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
        let failure = self.conditions().write_failure.clone();
        if let Some(failure) = failure {
            return Err(failure);
        }
        self.shared.accept(value).await;
        Ok(())
    }

    async fn close(&self) -> Result<(), GattError> {
        if self.conditions().refuse_close {
            return Err(GattError::new(GattFailureKind::Unknown, "模拟传输拒绝关闭"));
        }
        self.shared.close_link(self.number);
        Ok(())
    }
}

struct SimulatedSppDriver {
    shared: Arc<Shared>,
    number: usize,
    capacity: usize,
    incoming: tokio::sync::Mutex<mpsc::UnboundedReceiver<Result<Vec<u8>, SppError>>>,
}

#[async_trait]
impl SppDriver for SimulatedSppDriver {
    fn max_write_size(&self) -> usize {
        self.capacity
    }

    async fn connect(&self) -> Result<(), SppError> {
        let (delay, failure) = {
            let conditions = self.shared.conditions();
            (
                conditions.open_delay,
                conditions.spp_connect_failure.clone(),
            )
        };
        if !delay.is_zero() {
            tokio::time::sleep(delay).await;
        }
        match failure {
            Some(failure) => Err(failure),
            None => Ok(()),
        }
    }

    async fn read(&self) -> Result<Vec<u8>, SppError> {
        self.incoming.lock().await.recv().await.unwrap_or_else(|| {
            Err(SppError::new(
                SppFailureKind::Disconnected,
                "模拟 SPP 链路已结束",
            ))
        })
    }

    async fn write(&self, value: &[u8]) -> Result<(), SppError> {
        let failure = self.shared.conditions().spp_write_failure.clone();
        if let Some(failure) = failure {
            return Err(failure);
        }
        self.shared.accept(value).await;
        Ok(())
    }

    async fn close(&self) -> Result<(), SppError> {
        if self.shared.conditions().refuse_close {
            return Err(SppError::new(SppFailureKind::Unknown, "模拟传输拒绝关闭"));
        }
        self.shared.close_link(self.number);
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
