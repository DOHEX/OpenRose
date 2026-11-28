use async_trait::async_trait;
use openrose_circulation::{LocalAudio, YieldReceipt, YieldRefusal};

pub struct UnsupportedLocalAudio;

fn unsupported() -> YieldRefusal {
    YieldRefusal::PlatformUnavailable {
        detail: "audio handover is not implemented on this platform".to_string(),
    }
}

#[async_trait]
impl LocalAudio for UnsupportedLocalAudio {
    async fn is_connected(&self, _address: &str) -> Result<bool, YieldRefusal> {
        Ok(false)
    }

    async fn is_media_connected(&self, _address: &str) -> bool {
        false
    }

    async fn yield_audio(&self, _address: &str) -> Result<YieldReceipt, YieldRefusal> {
        Err(unsupported())
    }

    async fn acquire(&self, _address: &str) -> Result<(), YieldRefusal> {
        Err(unsupported())
    }
}

pub async fn current_output() -> Result<String, String> {
    Err("reading the system output is not supported on this platform".to_string())
}
