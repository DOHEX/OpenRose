use async_trait::async_trait;
use openrose_circulation::{LocalAudio, YieldReceipt, YieldRefusal};

pub struct UnsupportedLocalAudio;

#[async_trait]
impl LocalAudio for UnsupportedLocalAudio {
    async fn is_connected(&self, _address: &str) -> bool {
        false
    }

    async fn is_media_connected(&self, _address: &str) -> bool {
        false
    }

    async fn yield_audio(&self, _address: &str) -> Result<YieldReceipt, YieldRefusal> {
        Err(YieldRefusal::Unavailable(
            "此桌面平台的音频让出尚未实现".to_string(),
        ))
    }

    async fn acquire(&self, _address: &str) -> Result<(), YieldRefusal> {
        Err(YieldRefusal::Unavailable(
            "此桌面平台的音频接入尚未实现".to_string(),
        ))
    }
}

pub async fn current_output() -> Result<String, String> {
    Err("此桌面平台不支持读取当前系统输出".to_string())
}
