use crate::model::{AudioLayer, BluetoothStatus, DeviceSnapshot, EvidenceStrength, ProbeSnapshot};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum ProbeEvent {
    BluetoothStatusChanged {
        from: BluetoothStatus,
        to: BluetoothStatus,
    },
    PairedAdded {
        device: DeviceSnapshot,
    },
    PairedRemoved {
        id: String,
        name: String,
        address: Option<String>,
    },
    DeviceLinkChanged {
        id: String,
        name: String,
        address: Option<String>,
        connected: bool,
    },
    AudioConnected {
        id: String,
        name: String,
        address: Option<String>,
        layer: AudioLayer,
        strength: EvidenceStrength,
        direct_layer: AudioLayer,
        reasons: Vec<String>,
    },
    AudioDisconnected {
        id: String,
        name: String,
        address: Option<String>,
        previous_layer: AudioLayer,
        device_still_connected: bool,
    },
    AudioLayerChanged {
        id: String,
        name: String,
        address: Option<String>,
        from_layer: AudioLayer,
        to_layer: AudioLayer,
        strength: EvidenceStrength,
        direct_layer: AudioLayer,
        reasons: Vec<String>,
    },
}

pub fn filter_snapshot(snapshot: &ProbeSnapshot, query: Option<&str>) -> ProbeSnapshot {
    let Some(needle) = query.map(str::trim).filter(|q| !q.is_empty()) else {
        return snapshot.clone();
    };
    let needle = needle.to_ascii_lowercase();
    let devices = snapshot
        .devices
        .iter()
        .filter(|d| {
            d.name.to_ascii_lowercase().contains(&needle)
                || d.id.to_ascii_lowercase().contains(&needle)
                || d.address
                    .as_deref()
                    .is_some_and(|a| a.to_ascii_lowercase().contains(&needle))
                || d.container_id
                    .as_deref()
                    .is_some_and(|c| c.to_ascii_lowercase().contains(&needle))
        })
        .cloned()
        .collect();
    ProbeSnapshot {
        platform: snapshot.platform.clone(),
        bluetooth: snapshot.bluetooth.clone(),
        devices,
    }
}

pub fn diff_snapshots(previous: &ProbeSnapshot, next: &ProbeSnapshot) -> Vec<ProbeEvent> {
    let mut events = Vec::new();
    if previous.bluetooth != next.bluetooth {
        events.push(ProbeEvent::BluetoothStatusChanged {
            from: previous.bluetooth.clone(),
            to: next.bluetooth.clone(),
        });
    }

    let prev_map: BTreeMap<&str, &DeviceSnapshot> = previous
        .devices
        .iter()
        .map(|d| (d.id.as_str(), d))
        .collect();
    let next_map: BTreeMap<&str, &DeviceSnapshot> = next
        .devices
        .iter()
        .map(|d| (d.id.as_str(), d))
        .collect();

    for (id, prev) in &prev_map {
        if !next_map.contains_key(id) {
            if prev.assessment.layer.is_audio_connected() {
                events.push(ProbeEvent::AudioDisconnected {
                    id: prev.id.clone(),
                    name: prev.name.clone(),
                    address: prev.address.clone(),
                    previous_layer: prev.assessment.layer,
                    device_still_connected: false,
                });
            }
            events.push(ProbeEvent::PairedRemoved {
                id: prev.id.clone(),
                name: prev.name.clone(),
                address: prev.address.clone(),
            });
        }
    }

    for (id, curr) in &next_map {
        match prev_map.get(id) {
            None => {
                events.push(ProbeEvent::PairedAdded {
                    device: (*curr).clone(),
                });
                if curr.assessment.layer.is_audio_connected() {
                    events.push(ProbeEvent::AudioConnected {
                        id: curr.id.clone(),
                        name: curr.name.clone(),
                        address: curr.address.clone(),
                        layer: curr.assessment.layer,
                        strength: curr.assessment.strength,
                        direct_layer: curr.assessment.direct_layer,
                        reasons: curr.assessment.reasons.clone(),
                    });
                }
            }
            Some(prev) => {
                if prev.device_connected != curr.device_connected {
                    events.push(ProbeEvent::DeviceLinkChanged {
                        id: curr.id.clone(),
                        name: curr.name.clone(),
                        address: curr.address.clone(),
                        connected: curr.device_connected,
                    });
                }
                let prev_layer = prev.assessment.layer;
                let curr_layer = curr.assessment.layer;
                match (prev_layer.is_audio_connected(), curr_layer.is_audio_connected()) {
                    (false, true) => {
                        events.push(ProbeEvent::AudioConnected {
                            id: curr.id.clone(),
                            name: curr.name.clone(),
                            address: curr.address.clone(),
                            layer: curr_layer,
                            strength: curr.assessment.strength,
                            direct_layer: curr.assessment.direct_layer,
                            reasons: curr.assessment.reasons.clone(),
                        });
                    }
                    (true, false) => {
                        events.push(ProbeEvent::AudioDisconnected {
                            id: curr.id.clone(),
                            name: curr.name.clone(),
                            address: curr.address.clone(),
                            previous_layer: prev_layer,
                            device_still_connected: curr.device_connected,
                        });
                    }
                    (true, true) if prev_layer != curr_layer => {
                        events.push(ProbeEvent::AudioLayerChanged {
                            id: curr.id.clone(),
                            name: curr.name.clone(),
                            address: curr.address.clone(),
                            from_layer: prev_layer,
                            to_layer: curr_layer,
                            strength: curr.assessment.strength,
                            direct_layer: curr.assessment.direct_layer,
                            reasons: curr.assessment.reasons.clone(),
                        });
                    }
                    _ => {}
                }
            }
        }
    }

    events
}
