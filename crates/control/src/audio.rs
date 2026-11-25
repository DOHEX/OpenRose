use crate::snapshot::HeadsetCandidate;
use crate::transport::TransportError;
use async_trait::async_trait;
use openrose_headset::{HeadsetId, ModelProfile};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawAudioDevice {
    pub name: Option<String>,
    pub address: String,
    pub alias: Option<String>,
}

impl RawAudioDevice {
    pub fn new(name: Option<&str>, address: &str) -> Self {
        Self {
            name: name.map(str::to_string),
            address: address.to_string(),
            alias: None,
        }
    }

    pub fn aliased(mut self, alias: &str) -> Self {
        self.alias = Some(alias.to_string());
        self
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum AudioProfile {
    A2dp,
    Headset,
    LeAudio,
}

impl AudioProfile {
    const ALL: [Self; 3] = [Self::A2dp, Self::Headset, Self::LeAudio];
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioConnectionState {
    Connected,
    Disconnected,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawProfileSnapshot {
    pub connected_addresses: Vec<String>,
    pub states: BTreeMap<String, AudioConnectionState>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawAudioSnapshot {
    pub enabled: bool,
    pub spp_available: bool,
    pub le_audio_observable: bool,
    pub bonded_devices: Vec<RawAudioDevice>,
    pub profiles: BTreeMap<AudioProfile, Option<RawProfileSnapshot>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RawAudioEvent {
    AdapterOff,
    AdapterOn,
    ProfileChanged {
        profile: AudioProfile,
        device: RawAudioDevice,
        bonded: bool,
        connected: bool,
    },
    BondedDevicesChanged,
}

#[async_trait]
pub trait AudioEvents: Send + Sync {
    async fn next_event(&self) -> Option<RawAudioEvent>;
}

#[async_trait]
pub trait AudioFactsDriver: Send + Sync {
    async fn subscribe(&self) -> Box<dyn AudioEvents>;

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError>;

    async fn snapshot(&self, target_addresses: &[String]) -> RawAudioSnapshot;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioProfileEvidence {
    Unknown,
    LeAudio,
    Classic,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TransportFacts {
    pub spp_available: bool,
    pub audio_profile: AudioProfileEvidence,
}

pub fn normalize_bluetooth_address(value: &str) -> Option<String> {
    let address = value.trim().to_uppercase();
    let valid = address.len() == 17
        && address.bytes().enumerate().all(|(index, byte)| {
            if index % 3 == 2 {
                byte == b':'
            } else {
                byte.is_ascii_digit() || (b'A'..=b'F').contains(&byte)
            }
        });
    valid.then_some(address)
}

pub(crate) fn paired_headset(device: &RawAudioDevice) -> Option<HeadsetCandidate> {
    let profile = ModelProfile::match_name(device.name.as_deref().or(device.alias.as_deref()))?;
    let address = normalize_bluetooth_address(&device.address)?;
    let readable = |text: &Option<String>| text.clone().filter(|text| !text.trim().is_empty());
    Some(HeadsetCandidate {
        id: HeadsetId::new(address.clone()),
        name: readable(&device.alias).or_else(|| readable(&device.name)),
        rssi: None,
        profile: Some(profile),
        manufacturer_data_hex: String::new(),
        classic_address: Some(address),
    })
}

pub fn paired_headsets(devices: &[RawAudioDevice]) -> Vec<HeadsetCandidate> {
    devices.iter().filter_map(paired_headset).collect()
}

pub fn infer_audio_profile(
    snapshot: &RawAudioSnapshot,
    target_addresses: &[String],
) -> AudioProfileEvidence {
    use AudioProfileEvidence::{Classic, LeAudio, Unknown};
    if !snapshot.enabled || !snapshot.le_audio_observable {
        return Unknown;
    }
    let targets: BTreeSet<String> = target_addresses
        .iter()
        .filter_map(|address| normalize_bluetooth_address(address))
        .collect();
    if targets.is_empty() {
        return Unknown;
    }
    let mut connected_by_profile = BTreeMap::new();
    for profile in AudioProfile::ALL {
        let Some(Some(raw)) = snapshot.profiles.get(&profile) else {
            return Unknown;
        };
        let Some(connected) = raw
            .connected_addresses
            .iter()
            .map(|address| normalize_bluetooth_address(address))
            .collect::<Option<BTreeSet<String>>>()
        else {
            return Unknown;
        };
        let Some(states) = raw
            .states
            .iter()
            .map(|(address, state)| normalize_bluetooth_address(address).map(|a| (a, *state)))
            .collect::<Option<BTreeMap<String, AudioConnectionState>>>()
        else {
            return Unknown;
        };
        let connected_by_state: BTreeSet<String> = states
            .iter()
            .filter(|(_, state)| **state == AudioConnectionState::Connected)
            .map(|(address, _)| address.clone())
            .collect();
        if !targets.iter().all(|target| states.contains_key(target))
            || connected_by_state != connected
        {
            return Unknown;
        }
        connected_by_profile.insert(profile, connected);
    }
    let le_audio = &connected_by_profile[&AudioProfile::LeAudio];
    if le_audio.iter().any(|address| targets.contains(address)) {
        return LeAudio;
    }
    if !le_audio.is_empty() {
        return Unknown;
    }
    let classic = [AudioProfile::A2dp, AudioProfile::Headset]
        .iter()
        .any(|profile| {
            connected_by_profile[profile]
                .iter()
                .any(|address| targets.contains(address))
        });
    if classic { Classic } else { Unknown }
}

pub async fn connection_facts(
    driver: &dyn AudioFactsDriver,
    headset: &HeadsetId,
    classic_address: Option<&str>,
) -> TransportFacts {
    let classic = classic_address.and_then(normalize_bluetooth_address);
    let targets: Vec<String> = [
        normalize_bluetooth_address(headset.as_str()),
        classic.clone(),
    ]
    .into_iter()
    .flatten()
    .collect::<BTreeSet<_>>()
    .into_iter()
    .collect();
    let snapshot = driver.snapshot(&targets).await;
    TransportFacts {
        spp_available: snapshot.enabled && snapshot.spp_available && classic.is_some(),
        audio_profile: infer_audio_profile(&snapshot, &targets),
    }
}
