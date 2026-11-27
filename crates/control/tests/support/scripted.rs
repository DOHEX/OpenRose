use super::Recorder;
use async_trait::async_trait;
use openrose_control::audio::{
    AudioEvents, AudioFactsDriver, RawAudioDevice, RawAudioEvent, RawAudioSnapshot,
};
use openrose_control::gatt::GattDriver;
use openrose_control::scan::ScanDriver;
use openrose_control::simulator::{SimulatedClock, SimulatedHeadsetHandle, SimulatedTransport};
use openrose_control::snapshot::PlatformState;
use openrose_control::transport::{ControlTransport, TransportError};
use openrose_control::{ControlConfig, ControlRuntime, SessionTiming};
use openrose_headset::{FirmwareVersion, HeadsetId, LocalDateTime, ModelProfile};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, Weak};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot, watch};

pub struct ScriptedAudio {
    subscribers: Mutex<Vec<Weak<EventQueue>>>,
    queries: tokio::sync::Mutex<mpsc::UnboundedReceiver<oneshot::Sender<RawAudioSnapshot>>>,
    query_sender: mpsc::UnboundedSender<oneshot::Sender<RawAudioSnapshot>>,
    bonded: Mutex<Vec<RawAudioDevice>>,
    order: Mutex<Vec<&'static str>>,
}

impl ScriptedAudio {
    pub fn new() -> Arc<Self> {
        let (query_sender, queries) = mpsc::unbounded_channel();
        Arc::new(Self {
            subscribers: Mutex::new(Vec::new()),
            queries: tokio::sync::Mutex::new(queries),
            query_sender,
            bonded: Mutex::new(Vec::new()),
            order: Mutex::new(Vec::new()),
        })
    }

    pub fn emit(&self, event: RawAudioEvent) {
        self.subscribers.lock().unwrap().retain(|subscriber| match subscriber.upgrade() {
            Some(queue) => {
                queue.deliver(event.clone());
                true
            }
            None => false,
        });
    }

    pub async fn next_query(&self) -> oneshot::Sender<RawAudioSnapshot> {
        self.queries
            .lock()
            .await
            .recv()
            .await
            .expect("the core asked for an audio snapshot")
    }

    pub fn set_bonded(&self, devices: Vec<RawAudioDevice>) {
        *self.bonded.lock().unwrap() = devices;
    }

    pub fn call_order(&self) -> Vec<&'static str> {
        self.order.lock().unwrap().clone()
    }
}

#[derive(Default)]
struct EventQueue {
    waiting: Mutex<VecDeque<oneshot::Sender<RawAudioEvent>>>,
    buffered: Mutex<VecDeque<RawAudioEvent>>,
}

impl EventQueue {
    fn deliver(&self, event: RawAudioEvent) {
        let mut waiting = self.waiting.lock().unwrap();
        match waiting.pop_front() {
            Some(waiter) => {
                let _ = waiter.send(event);
            }
            None => self.buffered.lock().unwrap().push_back(event),
        }
    }
}

struct ScriptedEvents(Arc<EventQueue>);

#[async_trait]
impl AudioEvents for ScriptedEvents {
    async fn next_event(&self) -> Option<RawAudioEvent> {
        let waiter = {
            let mut waiting = self.0.waiting.lock().unwrap();
            if let Some(event) = self.0.buffered.lock().unwrap().pop_front() {
                return Some(event);
            }
            let (sender, receiver) = oneshot::channel();
            waiting.push_back(sender);
            receiver
        };
        waiter.await.ok()
    }
}

#[async_trait]
impl AudioFactsDriver for ScriptedAudio {
    async fn subscribe(&self) -> Box<dyn AudioEvents> {
        let queue = Arc::new(EventQueue::default());
        self.subscribers.lock().unwrap().push(Arc::downgrade(&queue));
        self.order.lock().unwrap().push("subscribe");
        Box::new(ScriptedEvents(queue))
    }

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError> {
        self.order.lock().unwrap().push("bonded");
        Ok(self.bonded.lock().unwrap().clone())
    }

    async fn snapshot(&self, _target_addresses: &[String]) -> RawAudioSnapshot {
        self.order.lock().unwrap().push("snapshot");
        let (sender, receiver) = oneshot::channel();
        self.query_sender.send(sender).expect("query receiver");
        match receiver.await {
            Ok(snapshot) => snapshot,
            Err(_) => std::future::pending().await,
        }
    }
}

pub struct GatedAudio {
    inner: Arc<dyn AudioFactsDriver>,
    gate: Mutex<Option<watch::Receiver<bool>>>,
}

impl GatedAudio {
    pub fn over(inner: Arc<dyn AudioFactsDriver>) -> Arc<Self> {
        Arc::new(Self {
            inner,
            gate: Mutex::new(None),
        })
    }

    pub fn hold_next_bonded_lookup(&self) -> watch::Sender<bool> {
        let (sender, receiver) = watch::channel(false);
        *self.gate.lock().unwrap() = Some(receiver);
        sender
    }
}

#[async_trait]
impl AudioFactsDriver for GatedAudio {
    async fn subscribe(&self) -> Box<dyn AudioEvents> {
        self.inner.subscribe().await
    }

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError> {
        let gate = self.gate.lock().unwrap().take();
        let devices = self.inner.bonded_devices().await;
        if let Some(mut gate) = gate {
            let _ = gate.wait_for(|open| *open).await;
        }
        devices
    }

    async fn snapshot(&self, target_addresses: &[String]) -> RawAudioSnapshot {
        self.inner.snapshot(target_addresses).await
    }
}

pub struct ScriptedTransport {
    pub sim: Arc<SimulatedTransport>,
    audio: Arc<dyn AudioFactsDriver>,
}

impl ScriptedTransport {
    pub fn new(sim: Arc<SimulatedTransport>, audio: Arc<dyn AudioFactsDriver>) -> Arc<Self> {
        Arc::new(Self { sim, audio })
    }
}

#[async_trait]
impl ControlTransport for ScriptedTransport {
    async fn open_gatt(&self, headset: &HeadsetId) -> Result<Box<dyn GattDriver>, TransportError> {
        self.sim.open_gatt(headset).await
    }

    async fn start_scan(&self) -> Result<Box<dyn ScanDriver>, TransportError> {
        self.sim.start_scan().await
    }

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver> {
        Arc::clone(&self.audio)
    }

    fn supports_initialization_recovery(&self) -> bool {
        self.sim.supports_initialization_recovery()
    }
}

pub fn fast_timing() -> SessionTiming {
    SessionTiming {
        write_interval: Some(Duration::ZERO),
        query_timeout: Duration::from_millis(50),
        write_timeout: Duration::from_millis(50),
        ..SessionTiming::default()
    }
}

pub fn runtime_over(
    transport: Arc<dyn ControlTransport>,
    platform: PlatformState,
) -> (Arc<ControlRuntime>, Arc<Recorder>) {
    runtime_timed(transport, platform, fast_timing())
}

pub fn runtime_timed(
    transport: Arc<dyn ControlTransport>,
    platform: PlatformState,
    timing: SessionTiming,
) -> (Arc<ControlRuntime>, Arc<Recorder>) {
    let recorder = Arc::new(Recorder::default());
    let clock = Arc::new(SimulatedClock::new(
        LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time"),
    ));
    let mut config = ControlConfig::new(platform, clock);
    config.timing = timing;
    let runtime = Arc::new(ControlRuntime::new(transport, recorder.clone(), config));
    (runtime, recorder)
}

pub fn simulated(
    platform: PlatformState,
) -> (Arc<SimulatedTransport>, Arc<ControlRuntime>, Arc<Recorder>) {
    let transport = Arc::new(SimulatedTransport::new());
    let (runtime, recorder) = runtime_over(transport.clone(), platform);
    (transport, runtime, recorder)
}

pub const A: &str = "AA:BB:CC:00:00:0A";
pub const B: &str = "AA:BB:CC:00:00:0B";

pub fn id(address: &str) -> HeadsetId {
    HeadsetId::new(address)
}

pub fn add_i5(transport: &SimulatedTransport, address: &str) -> SimulatedHeadsetHandle {
    transport
        .add_headset(
            HeadsetId::new(address),
            ModelProfile::I5Zt,
            FirmwareVersion::new(vec![1, 2, 5], "125"),
        )
        .expect("simulated i5")
}
