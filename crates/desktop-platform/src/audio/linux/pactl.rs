use async_trait::async_trait;
use std::collections::BTreeSet;
use std::process::Stdio;
use std::time::Duration;
use tokio::process::Command;

#[async_trait]
pub trait PactlRunner: Send + Sync {
    async fn run(&self, args: &[&str]) -> Result<String, String>;
}

#[derive(Default)]
pub struct SystemPactl;

#[async_trait]
impl PactlRunner for SystemPactl {
    async fn run(&self, args: &[&str]) -> Result<String, String> {
        let mut command = Command::new("pactl");
        command
            .args(args)
            .env("LC_ALL", "C")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        let child = command
            .spawn()
            .map_err(|error| format!("pactl unavailable: {error}"))?;
        let output = tokio::time::timeout(Duration::from_secs(3), child.wait_with_output())
            .await
            .map_err(|_| "pactl timed out".to_string())?
            .map_err(|error| format!("pactl unavailable: {error}"))?;
        let stdout = String::from_utf8_lossy(&output.stdout);
        let stderr = String::from_utf8_lossy(&output.stderr);
        if output.status.success() {
            return Ok(stdout.into_owned());
        }
        let detail = if stderr.trim().is_empty() {
            stdout.trim()
        } else {
            stderr.trim()
        };
        if detail.is_empty() {
            Err("pactl failed without output".to_string())
        } else {
            Err(detail.to_string())
        }
    }
}

pub fn is_call_profile(profile: &str) -> bool {
    let normalized = profile.trim().replace('-', "_").to_ascii_lowercase();
    normalized == "headset_head_unit"
        || normalized.starts_with("headset_head_unit_")
        || normalized == "handsfree_head_unit"
        || normalized.starts_with("handsfree_head_unit_")
}

pub fn headset_in_call(address: &str, cards_output: &str) -> bool {
    cards_output.split("Card #").any(|block| {
        let matches_card = block.lines().any(|line| {
            line.trim()
                .strip_prefix("Name: ")
                .is_some_and(|name| matches_pulse_endpoint(name, address, &["bluez_card"]))
        });
        if !matches_card {
            return false;
        }
        block.lines().any(|line| {
            line.trim()
                .strip_prefix("Active Profile: ")
                .is_some_and(is_call_profile)
        })
    })
}

pub fn matches_pulse_endpoint(name: &str, address: &str, prefixes: &[&str]) -> bool {
    let device = name.trim().replace(':', "_").to_ascii_lowercase();
    let mac = address.trim().replace(':', "_").to_ascii_lowercase();
    if mac.is_empty() {
        return false;
    }
    prefixes.iter().any(|prefix| {
        let prefix = prefix.to_ascii_lowercase();
        if let Some(rest) = device.strip_prefix(&prefix)
            && let Some(after_dot) = rest.strip_prefix('.')
            && let Some(tail) = after_dot.strip_prefix(&mac)
        {
            tail.is_empty() || tail.starts_with('.')
        } else {
            false
        }
    })
}

pub fn headset_mic_active(address: &str, source_list: &str, streams: &str) -> bool {
    let sources: BTreeSet<&str> = source_list
        .lines()
        .filter_map(|line| {
            let mut parts = line.split('\t');
            let id = parts.next()?.trim();
            let name = parts.next()?.trim();
            (!id.is_empty() && matches_pulse_endpoint(name, address, &["bluez_input", "bluez_source"]))
                .then_some(id)
        })
        .collect();
    if sources.is_empty() {
        return false;
    }
    streams.split("Source Output #").any(|stream| {
        let mut uncorked = false;
        let mut matches_source = false;
        for line in stream.lines() {
            let trimmed = line.trim();
            if trimmed == "Corked: no" {
                uncorked = true;
            } else if let Some(source) = trimmed.strip_prefix("Source: ")
                && sources.contains(source.trim())
            {
                matches_source = true;
            }
            if uncorked && matches_source {
                return true;
            }
        }
        false
    })
}

pub fn parse_sink_description(sink_name: &str, sinks_output: &str) -> Option<String> {
    let sink_name = sink_name.trim();
    if sink_name.is_empty() {
        return None;
    }
    sinks_output.split("Sink #").find_map(|block| {
        let matches = block
            .lines()
            .any(|line| line.trim().strip_prefix("Name: ") == Some(sink_name));
        if !matches {
            return None;
        }
        block.lines().find_map(|line| {
            line.trim()
                .strip_prefix("Description: ")
                .map(str::trim)
                .filter(|desc| !desc.is_empty())
                .map(str::to_string)
        })
    })
}
