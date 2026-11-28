use super::pactl::{
    PactlRunner, SystemPactl, headset_in_call, headset_mic_active, matches_pulse_endpoint,
    parse_sink_description,
};
use crate::bluetooth::linux::transport::BluezAudioProfiles;
use async_trait::async_trait;
use openrose_circulation::{LocalAudio, YieldReceipt, YieldRefusal};
use openrose_control::audio::normalize_bluetooth_address;
use std::sync::Arc;
use std::time::Duration;

const DEFAULT_SINK_RESTORE_WINDOW: Duration = Duration::from_secs(10);
const DEFAULT_SINK_RESTORE_INTERVAL: Duration = Duration::from_millis(500);

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

    async fn default_sink(&self) -> Option<String> {
        let name = self.pactl.run(&["get-default-sink"]).await.ok()?;
        let name = name.trim();
        (!name.is_empty()).then(|| name.to_string())
    }
}

fn pactl_unreadable(detail: String) -> YieldRefusal {
    YieldRefusal::AudioStateUnreadable { detail }
}

fn platform_unavailable(detail: String) -> YieldRefusal {
    YieldRefusal::PlatformUnavailable { detail }
}

fn invalid_address() -> YieldRefusal {
    platform_unavailable("invalid bluetooth address".to_string())
}

async fn restore_default_sink(pactl: Arc<dyn PactlRunner>, address: String, previous: String) {
    let deadline = tokio::time::Instant::now() + DEFAULT_SINK_RESTORE_WINDOW;
    loop {
        if let Ok(current) = pactl.run(&["get-default-sink"]).await
            && matches_pulse_endpoint(current.trim(), &address, &["bluez_output", "bluez_sink"])
        {
            let _ = pactl.run(&["set-default-sink", &previous]).await;
            return;
        }
        if tokio::time::Instant::now() >= deadline {
            return;
        }
        tokio::time::sleep(DEFAULT_SINK_RESTORE_INTERVAL).await;
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
        return Err("pactl reported no default sink".to_string());
    }
    let sinks = pactl.run(&["list", "sinks"]).await?;
    Ok(parse_sink_description(&name, &sinks).unwrap_or(name))
}

#[async_trait]
impl LocalAudio for LinuxLocalAudio {
    async fn is_connected(&self, address: &str) -> Result<bool, YieldRefusal> {
        let Some(normalized) = normalize_bluetooth_address(address) else {
            return Ok(false);
        };
        self.profiles
            .is_connected(&normalized)
            .await
            .map_err(pactl_unreadable)
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
        let normalized = normalize_bluetooth_address(address).ok_or_else(invalid_address)?;
        let cards = self
            .pactl
            .run(&["list", "cards"])
            .await
            .map_err(pactl_unreadable)?;
        if headset_in_call(&normalized, &cards) {
            return Err(YieldRefusal::InCall);
        }
        let sources = self
            .pactl
            .run(&["list", "short", "sources"])
            .await
            .map_err(pactl_unreadable)?;
        let streams = self
            .pactl
            .run(&["list", "source-outputs"])
            .await
            .map_err(pactl_unreadable)?;
        if headset_mic_active(&normalized, &sources, &streams) {
            return Err(YieldRefusal::MicInUse);
        }
        self.profiles
            .release(&normalized)
            .await
            .map_err(platform_unavailable)?;
        Ok(YieldReceipt {
            address: normalized,
        })
    }

    async fn acquire(&self, address: &str) -> Result<(), YieldRefusal> {
        let normalized = normalize_bluetooth_address(address).ok_or_else(invalid_address)?;
        let previous = self.default_sink().await;
        let outcome = self.profiles.acquire(&normalized).await;
        if let Some(previous) = previous {
            tokio::spawn(restore_default_sink(
                Arc::clone(&self.pactl),
                normalized,
                previous,
            ));
        }
        outcome.map_err(platform_unavailable)
    }
}
