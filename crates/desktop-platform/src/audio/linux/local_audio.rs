use super::pactl::{
    PactlRunner, SystemPactl, headset_in_call, headset_mic_active, matches_pulse_endpoint,
    parse_sink_description,
};
use crate::bluetooth::linux::transport::BluezAudioProfiles;
use async_trait::async_trait;
use openrose_circulation::{LocalAudio, YieldReceipt, YieldRefusal};
use openrose_control::audio::normalize_bluetooth_address;
use std::sync::Arc;

pub struct LinuxLocalAudio {
    profiles: BluezAudioProfiles,
    pactl: Arc<dyn PactlRunner>,
}

impl LinuxLocalAudio {
    pub fn new(profiles: BluezAudioProfiles) -> Self {
        Self::with_pactl(profiles, Arc::new(SystemPactl))
    }

    pub fn with_pactl(profiles: BluezAudioProfiles, pactl: Arc<dyn PactlRunner>) -> Self {
        Self { profiles, pactl }
    }

    pub async fn current_output(&self) -> Result<String, String> {
        current_output_with(&*self.pactl).await
    }
}

pub async fn current_output() -> Result<String, String> {
    current_output_with(&SystemPactl).await
}

pub async fn current_output_with(pactl: &dyn PactlRunner) -> Result<String, String> {
    let name = pactl
        .run(&["get-default-sink"])
        .await?
        .trim()
        .to_string();
    if name.is_empty() {
        return Err("无法读取当前系统输出".to_string());
    }
    let sinks = pactl.run(&["list", "sinks"]).await?;
    Ok(parse_sink_description(&name, &sinks).unwrap_or(name))
}

#[async_trait]
impl LocalAudio for LinuxLocalAudio {
    async fn is_connected(&self, address: &str) -> bool {
        let Some(normalized) = normalize_bluetooth_address(address) else {
            return false;
        };
        self.profiles
            .is_connected(&normalized)
            .await
            .unwrap_or(false)
    }

    async fn is_media_connected(&self, address: &str) -> bool {
        let Some(normalized) = normalize_bluetooth_address(address) else {
            return false;
        };
        self.profiles
            .is_media_connected(&normalized)
            .await
            .unwrap_or(false)
    }

    async fn yield_audio(&self, address: &str) -> Result<YieldReceipt, YieldRefusal> {
        let normalized = normalize_bluetooth_address(address)
            .ok_or_else(|| YieldRefusal::Unavailable("蓝牙地址无效".to_string()))?;
        let cards = self
            .pactl
            .run(&["list", "cards"])
            .await
            .map_err(YieldRefusal::Unavailable)?;
        if headset_in_call(&normalized, &cards) {
            return Err(YieldRefusal::InCall);
        }
        let sources = self
            .pactl
            .run(&["list", "short", "sources"])
            .await
            .map_err(YieldRefusal::Unavailable)?;
        let streams = self
            .pactl
            .run(&["list", "source-outputs"])
            .await
            .map_err(YieldRefusal::Unavailable)?;
        if headset_mic_active(&normalized, &sources, &streams) {
            return Err(YieldRefusal::MicInUse);
        }
        self.profiles
            .release(&normalized)
            .await
            .map_err(YieldRefusal::Unavailable)?;
        Ok(YieldReceipt {
            address: normalized,
        })
    }

    async fn acquire(&self, address: &str) -> Result<(), YieldRefusal> {
        let normalized = normalize_bluetooth_address(address)
            .ok_or_else(|| YieldRefusal::Unavailable("蓝牙地址无效".to_string()))?;
        let previous = self
            .pactl
            .run(&["get-default-sink"])
            .await
            .map_err(YieldRefusal::Unavailable)?
            .trim()
            .to_string();
        if previous.is_empty() {
            return Err(YieldRefusal::Unavailable(
                "无法读取当前系统输出".to_string(),
            ));
        }
        self.pactl
            .run(&["set-default-sink", &previous])
            .await
            .map_err(YieldRefusal::Unavailable)?;
        let outcome = self.profiles.acquire(&normalized).await;
        if let Ok(current) = self.pactl.run(&["get-default-sink"]).await {
            let current = current.trim();
            if matches_pulse_endpoint(current, &normalized, &["bluez_output", "bluez_sink"]) {
                let _ = self.pactl.run(&["set-default-sink", &previous]).await;
            }
        }
        outcome.map_err(YieldRefusal::Unavailable)
    }
}
