use crate::diff::ProbeEvent;
use crate::model::{AudioLayer, BluetoothTransport, EvidenceStrength, ProbeSnapshot};
use serde::Serialize;
use std::fmt::Write;
use std::time::{SystemTime, UNIX_EPOCH};

pub fn now_timestamp() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let tod = secs % 86_400;
    let hours = tod / 3600;
    let minutes = (tod % 3600) / 60;
    let seconds = tod % 60;
    format!("{hours:02}:{minutes:02}:{seconds:02}Z")
}

fn format_layer_tag(layer: AudioLayer, strength: EvidenceStrength, direct: AudioLayer) -> String {
    if strength == EvidenceStrength::Inferred && direct != layer {
        format!(
            "{} ({}; direct={})",
            layer.label(),
            strength.label(),
            direct.label()
        )
    } else {
        format!("{} ({})", layer.label(), strength.label())
    }
}

fn format_transports(transports: &[BluetoothTransport]) -> String {
    if transports.is_empty() {
        return "-".to_owned();
    }
    transports
        .iter()
        .map(|t| match t {
            BluetoothTransport::Classic => "classic",
            BluetoothTransport::Le => "le",
        })
        .collect::<Vec<_>>()
        .join("+")
}

pub fn format_snapshot_text(timestamp: &str, snapshot: &ProbeSnapshot) -> String {
    let mut out = String::new();
    let bt_detail = snapshot
        .bluetooth
        .detail
        .as_deref()
        .map(|d| format!(" ({d})"))
        .unwrap_or_default();
    let _ = writeln!(
        out,
        "[{timestamp}] SNAPSHOT platform={} bluetooth={}{bt_detail} devices={}",
        snapshot.platform,
        snapshot.bluetooth.state,
        snapshot.devices.len()
    );

    if snapshot.devices.is_empty() {
        let _ = writeln!(out, "  (no matching Bluetooth devices)");
        return out;
    }

    for dev in &snapshot.devices {
        let addr = dev.address.as_deref().unwrap_or("-");
        let link = if dev.device_connected {
            "connected"
        } else {
            "disconnected"
        };
        let layer = format_layer_tag(
            dev.assessment.layer,
            dev.assessment.strength,
            dev.assessment.direct_layer,
        );
        let _ = writeln!(
            out,
            "  - {} [{}] transports={} link={} layer={}",
            dev.name,
            addr,
            format_transports(&dev.transports),
            link,
            layer
        );
        if let Some(container) = &dev.container_id {
            let _ = writeln!(out, "      container={container}");
        }
        if !dev.advertised_profiles.is_empty() {
            let _ = writeln!(
                out,
                "      profiles=[{}]",
                dev.advertised_profiles.join(", ")
            );
        }
        if !dev.assessment.reasons.is_empty() {
            let _ = writeln!(out, "      evidence=[{}]", dev.assessment.reasons.join(", "));
        }
        for ep in &dev.endpoints {
            let _ = writeln!(out, "      endpoint: {ep}");
        }
    }
    out
}

pub fn format_event_text(timestamp: &str, event: &ProbeEvent) -> String {
    match event {
        ProbeEvent::BluetoothStatusChanged { from, to } => format!(
            "[{timestamp}] BLUETOOTH_STATUS {} -> {}",
            from.state, to.state
        ),
        ProbeEvent::PairedAdded { device } => format!(
            "[{timestamp}] PAIRED_ADDED {} [{}] layer={}",
            device.name,
            device.address.as_deref().unwrap_or(&device.id),
            format_layer_tag(
                device.assessment.layer,
                device.assessment.strength,
                device.assessment.direct_layer,
            )
        ),
        ProbeEvent::PairedRemoved { id, name, address } => format!(
            "[{timestamp}] PAIRED_REMOVED {} [{}]",
            name,
            address.as_deref().unwrap_or(id)
        ),
        ProbeEvent::DeviceLinkChanged {
            id,
            name,
            address,
            connected,
        } => format!(
            "[{timestamp}] DEVICE_LINK {} [{}] {}",
            name,
            address.as_deref().unwrap_or(id),
            if *connected {
                "connected (device-level)"
            } else {
                "disconnected (device-level)"
            }
        ),
        ProbeEvent::AudioConnected {
            id,
            name,
            address,
            layer,
            strength,
            direct_layer,
            reasons,
        } => format!(
            "[{timestamp}] AUDIO_CONNECTED {} [{}] layer={} evidence=[{}]",
            name,
            address.as_deref().unwrap_or(id),
            format_layer_tag(*layer, *strength, *direct_layer),
            reasons.join(", ")
        ),
        ProbeEvent::AudioDisconnected {
            id,
            name,
            address,
            previous_layer,
            device_still_connected,
        } => format!(
            "[{timestamp}] AUDIO_DISCONNECTED {} [{}] from={} device_link={}",
            name,
            address.as_deref().unwrap_or(id),
            previous_layer.label(),
            if *device_still_connected {
                "still-connected"
            } else {
                "disconnected"
            }
        ),
        ProbeEvent::AudioLayerChanged {
            id,
            name,
            address,
            from_layer,
            to_layer,
            strength,
            direct_layer,
            reasons,
        } => format!(
            "[{timestamp}] AUDIO_LAYER_CHANGED {} [{}] {} -> {} evidence=[{}]",
            name,
            address.as_deref().unwrap_or(id),
            from_layer.label(),
            format_layer_tag(*to_layer, *strength, *direct_layer),
            reasons.join(", ")
        ),
    }
}

#[derive(Serialize)]
struct SnapshotRecord<'a> {
    #[serde(rename = "type")]
    record_type: &'static str,
    timestamp: &'a str,
    snapshot: &'a ProbeSnapshot,
}

#[derive(Serialize)]
struct EventRecord<'a> {
    #[serde(rename = "type")]
    record_type: &'static str,
    timestamp: &'a str,
    event: &'a ProbeEvent,
}

pub fn format_snapshot_json(timestamp: &str, snapshot: &ProbeSnapshot) -> String {
    serde_json::to_string(&SnapshotRecord {
        record_type: "snapshot",
        timestamp,
        snapshot,
    })
    .unwrap_or_else(|_| "{}".to_owned())
}

pub fn format_event_json(timestamp: &str, event: &ProbeEvent) -> String {
    serde_json::to_string(&EventRecord {
        record_type: "event",
        timestamp,
        event,
    })
    .unwrap_or_else(|_| "{}".to_owned())
}
