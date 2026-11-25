#![allow(dead_code)]

use openrose_control::simulator::{SimulatedClock, SimulatedHeadsetHandle, SimulatedTransport};
use openrose_control::snapshot::{
    ConnectionState, ControlSnapshot, HostPlatform, PermissionState, PlatformState, SettingStage,
    TransportAvailability,
};
use openrose_control::{ControlConfig, ControlObserver, ControlRuntime, SessionTiming};
use openrose_headset::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetId, LocalDateTime, ModelProfile,
};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub async fn settle() {
    for _ in 0..64 {
        tokio::task::yield_now().await;
    }
}

pub async fn elapse(millis: u64) {
    for _ in 0..millis {
        tokio::time::advance(Duration::from_millis(1)).await;
        for _ in 0..8 {
            tokio::task::yield_now().await;
        }
    }
}

#[derive(Default)]
pub struct Recorder {
    snapshots: Mutex<Vec<Arc<ControlSnapshot>>>,
}

impl Recorder {
    pub fn all(&self) -> Vec<Arc<ControlSnapshot>> {
        self.snapshots.lock().unwrap().clone()
    }
}

impl ControlObserver for Recorder {
    fn state_changed(&self, snapshot: &ControlSnapshot) {
        self.snapshots
            .lock()
            .unwrap()
            .push(Arc::new(snapshot.clone()));
    }
}

pub struct Harness {
    pub runtime: Arc<ControlRuntime>,
    pub transport: Arc<SimulatedTransport>,
    pub headset: SimulatedHeadsetHandle,
    pub recorder: Arc<Recorder>,
    pub clock: Arc<SimulatedClock>,
}

pub struct Options {
    pub profile: ModelProfile,
    pub id: &'static str,
    pub platform: PlatformState,
    pub timing: SessionTiming,
    pub session_id_base: u64,
}

impl Options {
    pub fn i5() -> Self {
        Self {
            profile: ModelProfile::I5Zt,
            id: "i5",
            platform: available(),
            timing: SessionTiming {
                write_interval: Some(Duration::ZERO),
                query_timeout: Duration::from_millis(50),
                write_timeout: Duration::from_millis(50),
                ..SessionTiming::default()
            },
            session_id_base: 0,
        }
    }

    pub fn i7() -> Self {
        Self {
            profile: ModelProfile::I7RsV2,
            id: "i7",
            ..Self::i5()
        }
    }

    pub fn paced(mut self) -> Self {
        self.timing = SessionTiming::default();
        self
    }

    pub fn desktop(mut self) -> Self {
        self.platform = available().on(HostPlatform::Linux);
        self
    }
}

pub fn available() -> PlatformState {
    PlatformState::new(PermissionState::Granted, TransportAvailability::Available)
}

pub fn connect_time() -> LocalDateTime {
    LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time")
}

impl Harness {
    pub fn new(options: Options) -> Self {
        let transport = Arc::new(SimulatedTransport::new());
        let headset = transport
            .add_headset(
                HeadsetId::new(options.id),
                options.profile,
                FirmwareVersion::new(vec![1, 2, 5], "125"),
            )
            .expect("simulated headset");
        let recorder = Arc::new(Recorder::default());
        let clock = Arc::new(SimulatedClock::new(connect_time()));
        let mut config = ControlConfig::new(options.platform, clock.clone());
        config.timing = options.timing;
        config.session_id_base = options.session_id_base;
        let runtime = Arc::new(ControlRuntime::new(
            transport.clone(),
            recorder.clone(),
            config,
        ));
        Self {
            runtime,
            transport,
            headset,
            recorder,
            clock,
        }
    }

    pub fn i5() -> Self {
        Self::new(Options::i5())
    }

    pub fn id(&self) -> HeadsetId {
        self.headset.id()
    }

    pub fn snapshot(&self) -> ControlSnapshot {
        self.runtime.snapshot()
    }

    pub fn reading(&self, field: HeadsetField) -> Option<FieldReading> {
        self.snapshot()
            .headset
            .and_then(|headset| headset.readings.fields.get(&field).cloned())
    }

    pub fn value(&self, field: HeadsetField) -> Option<FieldValue> {
        match self.reading(field)? {
            FieldReading::Known { value, .. } => Some(value),
            _ => None,
        }
    }

    pub fn stages(&self) -> Vec<SettingStage> {
        self.snapshot()
            .setting_operations
            .iter()
            .map(|operation| operation.stage)
            .collect()
    }

    pub fn connection(&self) -> ConnectionState {
        self.snapshot().connection
    }
}

pub const I5_INIT_WRITES: usize = 13;

impl Harness {
    pub async fn ready(options: Options) -> Self {
        let harness = Self::new(options);
        harness
            .runtime
            .connect(harness.id())
            .await
            .expect("ready session");
        harness
    }

    pub async fn ready_i5() -> Self {
        let harness = Self::ready(Options::i5()).await;
        harness.headset.mute(true);
        harness
    }

    pub fn inject_hex(&self, hex: &str) -> bool {
        self.headset
            .inject(openrose_headset::decode_hex(hex).expect("reply hex"))
    }

    pub fn writes_after_init(&self) -> Vec<String> {
        self.headset.written_hex().split_off(I5_INIT_WRITES)
    }

    pub fn session_id(&self) -> u64 {
        match self.connection() {
            ConnectionState::Connected { session_id, .. }
            | ConnectionState::Connecting { session_id, .. } => session_id,
            ConnectionState::Disconnected => panic!("no control session"),
        }
    }
}
