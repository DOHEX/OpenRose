use crate::model::{
    AudioLayer, BluetoothStatus, BluetoothTransport, DeviceSnapshot, EvidenceStrength,
    LayerAssessment, MacAudioTransport, MacBluetoothObservation, MacCoreAudioObservation,
    ProbeSnapshot, WindowsAepServiceObservation, WindowsAudioEndpointObservation,
    WindowsDeviceObservation, WindowsEndpointFlow, WindowsEndpointState, WindowsTopologyFilter,
};
use std::collections::{BTreeMap, BTreeSet};

pub fn normalize_address(raw: &str) -> Option<String> {
    let hex: String = raw
        .chars()
        .filter(|c| !matches!(c, ':' | '-' | ' '))
        .collect();
    if hex.len() != 12 || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let upper = hex.to_ascii_uppercase();
    let mut formatted = String::with_capacity(17);
    for (index, chunk) in upper.as_bytes().chunks(2).enumerate() {
        if index > 0 {
            formatted.push(':');
        }
        formatted.push(chunk[0] as char);
        formatted.push(chunk[1] as char);
    }
    Some(formatted)
}

fn normalize_container(raw: &str) -> Option<String> {
    let trimmed = raw.trim().trim_matches(|c| c == '{' || c == '}');
    if trimmed.is_empty() {
        return None;
    }
    Some(format!("{{{}}}", trimmed.to_ascii_lowercase()))
}

fn service_profile_name(service_class_id: &str) -> String {
    let normalized = service_class_id
        .trim()
        .trim_matches(|c| c == '{' || c == '}')
        .to_ascii_lowercase();
    match normalized.as_str() {
        "0000110b-0000-1000-8000-00805f9b34fb" | "110b" => "a2dp-sink".to_owned(),
        "0000110a-0000-1000-8000-00805f9b34fb" | "110a" => "a2dp-source".to_owned(),
        "0000111e-0000-1000-8000-00805f9b34fb" | "111e" => "hfp".to_owned(),
        "0000111f-0000-1000-8000-00805f9b34fb" | "111f" => "hfp-ag".to_owned(),
        "00001108-0000-1000-8000-00805f9b34fb" | "1108" => "hsp".to_owned(),
        "0000110e-0000-1000-8000-00805f9b34fb" | "110e" => "avrcp".to_owned(),
        "0000184e-0000-1000-8000-00805f9b34fb" | "184e" => "le-audio-ascs".to_owned(),
        "00001850-0000-1000-8000-00805f9b34fb" | "1850" => "le-audio-pacs".to_owned(),
        "00001855-0000-1000-8000-00805f9b34fb" | "1855" => "le-audio-tmap".to_owned(),
        "00001846-0000-1000-8000-00805f9b34fb" | "1846" => "le-audio-csis".to_owned(),
        _ => normalized,
    }
}

#[derive(Default)]
struct WindowsFilterSignals {
    has_le_audio: bool,
    has_a2dp: bool,
    has_hfp: bool,
}

fn inspect_filter(filter: &WindowsTopologyFilter, signals: &mut WindowsFilterSignals) {
    let enumerator = filter
        .enumerator
        .as_deref()
        .unwrap_or_default()
        .to_ascii_uppercase();
    let instance = filter
        .instance_id
        .as_deref()
        .unwrap_or_default()
        .to_ascii_uppercase();

    if enumerator == "BTHLE"
        || enumerator == "BTHLEDEVICE"
        || enumerator == "BTHLEAUDIO"
        || instance.contains("BTHLEAUDIO")
        || instance.contains("BTHLEDEVICE")
        || instance.starts_with("BTHLE\\")
    {
        signals.has_le_audio = true;
    }

    if enumerator == "BTHHFENUM"
        || instance.contains("BTHHFENUM")
        || instance.contains("BTHHFP")
        || instance.contains("0000111E")
    {
        signals.has_hfp = true;
    }

    if instance.contains("0000110B")
        || instance.contains("BTHA2DP")
        || (enumerator == "BTHENUM" && !instance.contains("BTHHFP") && !instance.contains("0000111E"))
    {
        signals.has_a2dp = true;
    }
}

struct WindowsGroup {
    id: String,
    name: String,
    address: Option<String>,
    container_id: Option<String>,
    transports: BTreeSet<BluetoothTransport>,
    device_connected: bool,
    profiles: BTreeSet<String>,
    endpoints: Vec<WindowsAudioEndpointObservation>,
}

fn format_windows_endpoint(ep: &WindowsAudioEndpointObservation) -> String {
    let flow = match ep.flow {
        WindowsEndpointFlow::Render => "render",
        WindowsEndpointFlow::Capture => "capture",
    };
    let state = match ep.state {
        WindowsEndpointState::Active => "active",
        WindowsEndpointState::Unplugged => "unplugged",
        WindowsEndpointState::Other => "other",
    };
    let filters: Vec<String> = ep
        .topology_filters
        .iter()
        .map(|f| {
            f.enumerator
                .clone()
                .or_else(|| f.instance_id.clone())
                .unwrap_or_else(|| f.id.clone())
        })
        .collect();
    let fmt = match (ep.sample_rate_hz, ep.channels) {
        (Some(rate), Some(ch)) => format!(" {rate}Hz/{ch}ch"),
        (Some(rate), None) => format!(" {rate}Hz"),
        _ => String::new(),
    };
    let cap = if ep.capture_session_active {
        " capture-active"
    } else {
        ""
    };
    let flt = if filters.is_empty() {
        String::new()
    } else {
        format!(" filters=[{}]", filters.join(","))
    };
    format!("{flow}:{state} \"{}\"{fmt}{cap}{flt}", ep.friendly_name)
}

fn assess_windows_group(group: &WindowsGroup) -> LayerAssessment {
    let active: Vec<&WindowsAudioEndpointObservation> = group
        .endpoints
        .iter()
        .filter(|ep| ep.state == WindowsEndpointState::Active)
        .collect();

    if active.is_empty() {
        if group.device_connected {
            return LayerAssessment {
                layer: AudioLayer::DeviceLevelOnly,
                strength: EvidenceStrength::Direct,
                direct_layer: AudioLayer::DeviceLevelOnly,
                layers_present: vec![AudioLayer::DeviceLevelOnly],
                reasons: vec!["winrt-aep-connected-without-active-audio-endpoint".to_owned()],
            };
        }
        return LayerAssessment {
            layer: AudioLayer::None,
            strength: EvidenceStrength::Direct,
            direct_layer: AudioLayer::None,
            layers_present: Vec::new(),
            reasons: Vec::new(),
        };
    }

    let mut signals = WindowsFilterSignals::default();
    let mut has_active_render = false;
    let mut has_active_capture = false;
    let mut capture_session_active = false;
    let mut render_mono_narrow = false;

    for ep in &active {
        match ep.flow {
            WindowsEndpointFlow::Render => {
                has_active_render = true;
                if ep.channels == Some(1) && ep.sample_rate_hz.is_some_and(|hz| hz <= 16000) {
                    render_mono_narrow = true;
                }
            }
            WindowsEndpointFlow::Capture => {
                has_active_capture = true;
                if ep.capture_session_active {
                    capture_session_active = true;
                }
            }
        }
        for filter in &ep.topology_filters {
            inspect_filter(filter, &mut signals);
        }
    }

    let mut layers_present = BTreeSet::new();
    let mut reasons = Vec::new();
    if group.device_connected {
        layers_present.insert(AudioLayer::DeviceLevelOnly);
    }

    if signals.has_le_audio {
        layers_present.insert(AudioLayer::LeAudio);
        reasons.push("mmdevice-active-endpoint-with-bthle-topology-filter".to_owned());
        return LayerAssessment {
            layer: AudioLayer::LeAudio,
            strength: EvidenceStrength::Direct,
            direct_layer: AudioLayer::LeAudio,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    if signals.has_a2dp && !signals.has_hfp && !has_active_capture {
        layers_present.insert(AudioLayer::ClassicAudio);
        layers_present.insert(AudioLayer::A2dp);
        reasons.push("mmdevice-active-render-with-a2dp-only-filter".to_owned());
        return LayerAssessment {
            layer: AudioLayer::A2dp,
            strength: EvidenceStrength::Direct,
            direct_layer: AudioLayer::A2dp,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    if signals.has_hfp && !signals.has_a2dp {
        layers_present.insert(AudioLayer::ClassicAudio);
        layers_present.insert(AudioLayer::Hfp);
        reasons.push("mmdevice-active-endpoint-with-hfp-only-filter".to_owned());
        return LayerAssessment {
            layer: AudioLayer::Hfp,
            strength: EvidenceStrength::Direct,
            direct_layer: AudioLayer::Hfp,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    if signals.has_a2dp || signals.has_hfp {
        layers_present.insert(AudioLayer::ClassicAudio);
        if signals.has_a2dp {
            layers_present.insert(AudioLayer::A2dp);
        }
        if signals.has_hfp {
            layers_present.insert(AudioLayer::Hfp);
        }
        if capture_session_active || render_mono_narrow {
            reasons.push("win11-unified-classic-endpoint-capture-or-narrowband-active".to_owned());
            return LayerAssessment {
                layer: AudioLayer::Hfp,
                strength: EvidenceStrength::Inferred,
                direct_layer: AudioLayer::ClassicAudio,
                layers_present: layers_present.into_iter().collect(),
                reasons,
            };
        }
        if has_active_render {
            reasons.push("win11-unified-classic-endpoint-render-active-capture-idle".to_owned());
            return LayerAssessment {
                layer: AudioLayer::A2dp,
                strength: EvidenceStrength::Inferred,
                direct_layer: AudioLayer::ClassicAudio,
                layers_present: layers_present.into_iter().collect(),
                reasons,
            };
        }
        reasons.push("mmdevice-classic-bluetooth-endpoint-active".to_owned());
        return LayerAssessment {
            layer: AudioLayer::ClassicAudio,
            strength: EvidenceStrength::Direct,
            direct_layer: AudioLayer::ClassicAudio,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    let has_le_audio_service = group
        .profiles
        .iter()
        .any(|p| p.starts_with("le-audio-"));
    let only_le_transport = group.transports.len() == 1
        && group.transports.contains(&BluetoothTransport::Le);

    if only_le_transport || has_le_audio_service {
        layers_present.insert(AudioLayer::LeAudio);
        reasons.push("active-audio-endpoint-in-le-only-or-lea-service-container".to_owned());
        return LayerAssessment {
            layer: AudioLayer::LeAudio,
            strength: EvidenceStrength::Inferred,
            direct_layer: AudioLayer::AudioUnknown,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    if group.transports.len() == 1 && group.transports.contains(&BluetoothTransport::Classic) {
        layers_present.insert(AudioLayer::ClassicAudio);
        reasons.push("active-audio-endpoint-in-classic-only-container".to_owned());
        return LayerAssessment {
            layer: AudioLayer::ClassicAudio,
            strength: EvidenceStrength::Direct,
            direct_layer: AudioLayer::ClassicAudio,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    layers_present.insert(AudioLayer::AudioUnknown);
    reasons.push("active-audio-endpoint-matched-container-without-profile-filter".to_owned());
    LayerAssessment {
        layer: AudioLayer::AudioUnknown,
        strength: EvidenceStrength::Direct,
        direct_layer: AudioLayer::AudioUnknown,
        layers_present: layers_present.into_iter().collect(),
        reasons,
    }
}

pub fn build_windows_snapshot(
    bluetooth: BluetoothStatus,
    devices: &[WindowsDeviceObservation],
    services: &[WindowsAepServiceObservation],
    endpoints: &[WindowsAudioEndpointObservation],
) -> ProbeSnapshot {
    let mut groups: BTreeMap<String, WindowsGroup> = BTreeMap::new();
    let mut container_to_key: BTreeMap<String, String> = BTreeMap::new();
    let mut address_to_key: BTreeMap<String, String> = BTreeMap::new();

    for obs in devices {
        let norm_container = obs.container_id.as_deref().and_then(normalize_container);
        let norm_addr = obs.address.as_deref().and_then(normalize_address);
        let key = norm_container
            .as_ref()
            .and_then(|c| container_to_key.get(c).cloned())
            .or_else(|| norm_addr.as_ref().and_then(|a| address_to_key.get(a).cloned()))
            .or_else(|| norm_container.clone())
            .or_else(|| norm_addr.clone())
            .unwrap_or_else(|| obs.id.clone());

        if let Some(c) = &norm_container {
            container_to_key.insert(c.clone(), key.clone());
        }
        if let Some(a) = &norm_addr {
            address_to_key.insert(a.clone(), key.clone());
        }

        let entry = groups.entry(key.clone()).or_insert_with(|| WindowsGroup {
            id: norm_addr.clone().unwrap_or_else(|| key.clone()),
            name: obs.name.clone(),
            address: norm_addr.clone(),
            container_id: norm_container.clone(),
            transports: BTreeSet::new(),
            device_connected: false,
            profiles: BTreeSet::new(),
            endpoints: Vec::new(),
        });

        if entry.name.is_empty() && !obs.name.is_empty() {
            entry.name = obs.name.clone();
        }
        if entry.address.is_none() {
            entry.address = norm_addr;
        }
        if entry.container_id.is_none() {
            entry.container_id = norm_container;
        }
        entry.transports.insert(obs.transport);
        entry.device_connected |= obs.is_connected;
    }

    for svc in services {
        let norm_container = svc.container_id.as_deref().and_then(normalize_container);
        let norm_addr = svc.address.as_deref().and_then(normalize_address);
        let key = norm_container
            .as_ref()
            .and_then(|c| container_to_key.get(c))
            .or_else(|| norm_addr.as_ref().and_then(|a| address_to_key.get(a)))
            .cloned();
        if let Some(key) = key {
            if let Some(group) = groups.get_mut(&key) {
                let mut label = service_profile_name(&svc.service_class_id);
                if svc.is_connected {
                    group.profiles.insert(label.clone());
                } else {
                    label.push_str("(disconnected)");
                    group.profiles.insert(label);
                }
            }
        }
    }

    for ep in endpoints {
        let norm_container = ep.container_id.as_deref().and_then(normalize_container);
        if let Some(key) = norm_container
            .as_ref()
            .and_then(|c| container_to_key.get(c))
            .cloned()
        {
            if let Some(group) = groups.get_mut(&key) {
                group.endpoints.push(ep.clone());
            }
        }
    }

    let mut result: Vec<DeviceSnapshot> = groups
        .into_values()
        .map(|group| {
            let assessment = assess_windows_group(&group);
            let endpoints = group.endpoints.iter().map(format_windows_endpoint).collect();
            DeviceSnapshot {
                id: group.id,
                name: group.name,
                address: group.address,
                container_id: group.container_id,
                transports: group.transports.into_iter().collect(),
                device_connected: group.device_connected,
                assessment,
                endpoints,
                advertised_profiles: group.profiles.into_iter().collect(),
            }
        })
        .collect();

    result.sort_by(|a, b| a.name.cmp(&b.name).then_with(|| a.id.cmp(&b.id)));

    ProbeSnapshot {
        platform: "windows".to_owned(),
        bluetooth,
        devices: result,
    }
}

pub fn extract_address_from_uid(uid: &str) -> Option<String> {
    let bytes = uid.as_bytes();
    if bytes.len() < 17 {
        return normalize_address(uid);
    }
    for start in 0..=bytes.len() - 17 {
        let candidate = &uid[start..start + 17];
        let sep = candidate.as_bytes()[2];
        if !matches!(sep, b'-' | b':') {
            continue;
        }
        let valid_separators = [2, 5, 8, 11, 14]
            .iter()
            .all(|&idx| candidate.as_bytes()[idx] == sep);
        if valid_separators {
            if let Some(normalized) = normalize_address(candidate) {
                return Some(normalized);
            }
        }
    }
    normalize_address(uid)
}

struct MacGroup {
    id: String,
    name: String,
    address: Option<String>,
    transports: BTreeSet<BluetoothTransport>,
    device_connected: bool,
    profiles: BTreeSet<String>,
    audio_devices: Vec<MacCoreAudioObservation>,
}

fn format_macos_endpoint(ep: &MacCoreAudioObservation) -> String {
    let transport = match ep.transport {
        MacAudioTransport::BluetoothClassic => "bt-classic",
        MacAudioTransport::BluetoothLe => "bt-le",
    };
    let rate = ep
        .nominal_sample_rate_hz
        .map(|hz| format!(" {:.0}Hz", hz))
        .unwrap_or_default();
    let running = match (ep.output_running, ep.input_running) {
        (true, true) => " run=out+in",
        (true, false) => " run=out",
        (false, true) => " run=in",
        (false, false) => "",
    };
    format!(
        "id={} {transport} uid={} out={}ch in={}ch{rate}{running}",
        ep.device_id, ep.uid, ep.output_channels, ep.input_channels
    )
}

fn assess_macos_group(group: &MacGroup) -> LayerAssessment {
    let alive: Vec<&MacCoreAudioObservation> =
        group.audio_devices.iter().filter(|d| d.alive).collect();

    if alive.is_empty() {
        if group.device_connected {
            return LayerAssessment {
                layer: AudioLayer::DeviceLevelOnly,
                strength: EvidenceStrength::Direct,
                direct_layer: AudioLayer::DeviceLevelOnly,
                layers_present: vec![AudioLayer::DeviceLevelOnly],
                reasons: vec!["iobluetooth-acl-connected-without-coreaudio-device".to_owned()],
            };
        }
        return LayerAssessment {
            layer: AudioLayer::None,
            strength: EvidenceStrength::Direct,
            direct_layer: AudioLayer::None,
            layers_present: Vec::new(),
            reasons: Vec::new(),
        };
    }

    let mut layers_present = BTreeSet::new();
    let mut reasons = Vec::new();
    if group.device_connected {
        layers_present.insert(AudioLayer::DeviceLevelOnly);
    }

    let has_le_audio = alive
        .iter()
        .any(|d| d.transport == MacAudioTransport::BluetoothLe);
    if has_le_audio {
        layers_present.insert(AudioLayer::LeAudio);
        reasons.push("coreaudio-transport-type-bluetooth-le".to_owned());
        return LayerAssessment {
            layer: AudioLayer::LeAudio,
            strength: EvidenceStrength::Direct,
            direct_layer: AudioLayer::LeAudio,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    layers_present.insert(AudioLayer::ClassicAudio);
    let has_output = alive.iter().any(|d| d.output_channels > 0);
    let has_input = alive.iter().any(|d| d.input_channels > 0);
    let input_running = alive
        .iter()
        .any(|d| d.input_channels > 0 && d.input_running);
    let output_narrowband = alive.iter().any(|d| {
        d.output_channels > 0 && d.nominal_sample_rate_hz.is_some_and(|hz| hz <= 16000.0)
    });

    if has_output {
        layers_present.insert(AudioLayer::A2dp);
    }
    if has_input {
        layers_present.insert(AudioLayer::Hfp);
    }

    if input_running || output_narrowband || (!has_output && has_input) {
        reasons.push("coreaudio-classic-bt-input-running-or-narrowband-sco".to_owned());
        return LayerAssessment {
            layer: AudioLayer::Hfp,
            strength: EvidenceStrength::Inferred,
            direct_layer: AudioLayer::ClassicAudio,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    if has_output {
        reasons.push("coreaudio-classic-bt-output-present-input-idle".to_owned());
        return LayerAssessment {
            layer: AudioLayer::A2dp,
            strength: EvidenceStrength::Inferred,
            direct_layer: AudioLayer::ClassicAudio,
            layers_present: layers_present.into_iter().collect(),
            reasons,
        };
    }

    reasons.push("coreaudio-classic-bt-device-present".to_owned());
    LayerAssessment {
        layer: AudioLayer::ClassicAudio,
        strength: EvidenceStrength::Direct,
        direct_layer: AudioLayer::ClassicAudio,
        layers_present: layers_present.into_iter().collect(),
        reasons,
    }
}

pub fn build_macos_snapshot(
    bluetooth: BluetoothStatus,
    bt_devices: &[MacBluetoothObservation],
    audio_devices: &[MacCoreAudioObservation],
) -> ProbeSnapshot {
    let mut groups: BTreeMap<String, MacGroup> = BTreeMap::new();
    let mut name_to_key: BTreeMap<String, Vec<String>> = BTreeMap::new();

    for obs in bt_devices {
        let norm_addr = normalize_address(&obs.address);
        let key = norm_addr
            .clone()
            .unwrap_or_else(|| format!("bt:{}", obs.address));
        let entry = groups.entry(key.clone()).or_insert_with(|| MacGroup {
            id: key.clone(),
            name: obs.name.clone(),
            address: norm_addr.clone(),
            transports: BTreeSet::new(),
            device_connected: false,
            profiles: BTreeSet::new(),
            audio_devices: Vec::new(),
        });
        if entry.name.is_empty() && !obs.name.is_empty() {
            entry.name = obs.name.clone();
        }
        entry.transports.insert(BluetoothTransport::Classic);
        entry.device_connected |= obs.connected;
        if obs.hands_free_device {
            entry.profiles.insert("hfp-device".to_owned());
        }
        if obs.hands_free_gateway {
            entry.profiles.insert("hfp-ag".to_owned());
        }
        for profile in &obs.sdp_profiles {
            entry.profiles.insert(profile.clone());
        }
        let lower_name = obs.name.trim().to_ascii_lowercase();
        if !lower_name.is_empty() {
            let bucket = name_to_key.entry(lower_name).or_default();
            if !bucket.contains(&key) {
                bucket.push(key);
            }
        }
    }

    for ep in audio_devices {
        let uid_addr = extract_address_from_uid(&ep.uid);
        let matched_key = uid_addr
            .as_ref()
            .filter(|addr| groups.contains_key(*addr))
            .cloned()
            .or_else(|| {
                let lower_name = ep.name.trim().to_ascii_lowercase();
                name_to_key
                    .get(&lower_name)
                    .filter(|keys| keys.len() == 1)
                    .map(|keys| keys[0].clone())
            });

        let key = matched_key.unwrap_or_else(|| {
            uid_addr
                .clone()
                .unwrap_or_else(|| format!("coreaudio:{}", ep.uid))
        });

        let entry = groups.entry(key.clone()).or_insert_with(|| MacGroup {
            id: key.clone(),
            name: ep.name.clone(),
            address: uid_addr.clone(),
            transports: BTreeSet::new(),
            device_connected: ep.alive,
            profiles: BTreeSet::new(),
            audio_devices: Vec::new(),
        });
        if entry.name.is_empty() && !ep.name.is_empty() {
            entry.name = ep.name.clone();
        }
        match ep.transport {
            MacAudioTransport::BluetoothClassic => {
                entry.transports.insert(BluetoothTransport::Classic);
            }
            MacAudioTransport::BluetoothLe => {
                entry.transports.insert(BluetoothTransport::Le);
            }
        }
        entry.audio_devices.push(ep.clone());
    }

    let mut result: Vec<DeviceSnapshot> = groups
        .into_values()
        .map(|group| {
            let assessment = assess_macos_group(&group);
            let endpoints = group
                .audio_devices
                .iter()
                .map(format_macos_endpoint)
                .collect();
            DeviceSnapshot {
                id: group.id,
                name: group.name,
                address: group.address,
                container_id: None,
                transports: group.transports.into_iter().collect(),
                device_connected: group.device_connected,
                assessment,
                endpoints,
                advertised_profiles: group.profiles.into_iter().collect(),
            }
        })
        .collect();

    result.sort_by(|a, b| a.name.cmp(&b.name).then_with(|| a.id.cmp(&b.id)));

    ProbeSnapshot {
        platform: "macos".to_owned(),
        bluetooth,
        devices: result,
    }
}
