use async_trait::async_trait;
use openrose_control::audio::{
    AudioEvents, AudioFactsDriver, RawAudioDevice, RawAudioEvent, RawAudioSnapshot,
};
use openrose_control::gatt::GattDriver;
use openrose_control::scan::ScanDriver;
use openrose_control::transport::{ControlTransport, TransportError};
use openrose_headset::HeadsetId;
use std::sync::Arc;

pub struct UnsupportedTransport;

struct SilentAudioEvents;

#[async_trait]
impl AudioEvents for SilentAudioEvents {
    async fn next_event(&self) -> Option<RawAudioEvent> {
        std::future::pending().await
    }
}

struct NoAudioFacts;

#[async_trait]
impl AudioFactsDriver for NoAudioFacts {
    async fn subscribe(&self) -> Box<dyn AudioEvents> {
        Box::new(SilentAudioEvents)
    }

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError> {
        Ok(Vec::new())
    }

    async fn snapshot(&self, _target_addresses: &[String]) -> RawAudioSnapshot {
        RawAudioSnapshot::unavailable()
    }
}

fn unsupported() -> TransportError {
    TransportError::Unsupported("此桌面平台的蓝牙控制尚未实现".to_string())
}

#[async_trait]
impl ControlTransport for UnsupportedTransport {
    async fn open_gatt(&self, _headset: &HeadsetId) -> Result<Box<dyn GattDriver>, TransportError> {
        Err(unsupported())
    }

    async fn start_scan(&self) -> Result<Box<dyn ScanDriver>, TransportError> {
        Err(unsupported())
    }

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver> {
        Arc::new(NoAudioFacts)
    }
}
