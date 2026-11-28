#![allow(dead_code)]

mod foreign;

pub use foreign::{FakeHeadset, FixedClock, Recorder};

use openrose_control::SessionTiming;
use openrose_control::snapshot::{
    ConnectionState, ControlSnapshot, PermissionState, PlatformState, TransportAvailability,
};
use openrose_ffi::{ControlRuntime, ControlSettings};
use openrose_headset::{FieldReading, FieldValue, HeadsetField, HeadsetId, ModelProfile};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub const I5_ADDRESS: &str = "AA:BB:CC:00:00:05";
pub const NOISE_READBACK_QUERY: &str = "08ee00000006020a0008";
pub const NOISE_REPLY_ANC: &str = "09ff00000106020e000100000020";

pub struct World {
    pub runtime: Arc<ControlRuntime>,
    pub headset: Arc<FakeHeadset>,
    pub recorder: Arc<Recorder>,
}

impl World {
    pub fn i5() -> Self {
        let headset = FakeHeadset::new(ModelProfile::I5Zt, I5_ADDRESS);
        let recorder = Arc::new(Recorder::default());
        let runtime = ControlRuntime::new(
            headset.transport(),
            recorder.clone(),
            Arc::new(FixedClock::new(2026, 11, 20, 17, 38, 9)),
            PlatformState::new(PermissionState::Granted, TransportAvailability::Available),
            ControlSettings::default(),
            SessionTiming {
                write_interval: Some(Duration::ZERO),
                query_timeout: Duration::from_millis(500),
                write_timeout: Duration::from_millis(500),
                ..SessionTiming::default()
            },
            0,
        );
        Self {
            runtime,
            headset,
            recorder,
        }
    }

    pub fn id(&self) -> HeadsetId {
        HeadsetId::new(I5_ADDRESS)
    }

    pub fn snapshot(&self) -> ControlSnapshot {
        self.runtime.snapshot()
    }

    pub fn session(&self) -> u64 {
        match self.snapshot().connection {
            ConnectionState::Connected { session_id, .. } => session_id,
            other => panic!("expected a connected session, found {other:?}"),
        }
    }

    pub fn value(&self, field: HeadsetField) -> Option<FieldValue> {
        let headset = self.snapshot().headset?;
        match headset.readings.fields.get(&field)? {
            FieldReading::Known { value, .. } => Some(value.clone()),
            _ => None,
        }
    }
}

pub async fn eventually(what: &str, condition: impl Fn() -> bool) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while !condition() {
        assert!(Instant::now() < deadline, "timed out waiting for {what}");
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
}
