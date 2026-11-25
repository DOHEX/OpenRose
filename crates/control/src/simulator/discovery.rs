use crate::audio::{
    AudioConnectionState, AudioEvents, AudioFactsDriver, AudioProfile, RawAudioDevice,
    RawAudioEvent, RawAudioSnapshot, RawProfileSnapshot, normalize_bluetooth_address,
};
use crate::scan::{Advertisement, ScanDriver};
use crate::transport::TransportError;
use async_trait::async_trait;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex, MutexGuard};
use tokio::sync::mpsc;

struct AudioWorld {
    enabled: bool,
    bonded: Vec<RawAudioDevice>,
    connected: BTreeMap<AudioProfile, BTreeSet<String>>,
    subscribers: Vec<mpsc::UnboundedSender<RawAudioEvent>>,
}

pub struct SimulatedAudio {
    world: Mutex<AudioWorld>,
}

impl Default for SimulatedAudio {
    fn default() -> Self {
        Self::new()
    }
}

fn same_address(left: &str, right: &str) -> bool {
    normalize_bluetooth_address(left)
        .is_some_and(|left| normalize_bluetooth_address(right).is_some_and(|right| left == right))
}

impl SimulatedAudio {
    pub fn new() -> Self {
        Self {
            world: Mutex::new(AudioWorld {
                enabled: true,
                bonded: Vec::new(),
                connected: BTreeMap::new(),
                subscribers: Vec::new(),
            }),
        }
    }

    fn world(&self) -> MutexGuard<'_, AudioWorld> {
        self.world.lock().expect("audio world lock")
    }

    fn emit(world: &mut AudioWorld, event: RawAudioEvent) {
        world
            .subscribers
            .retain(|subscriber| subscriber.send(event.clone()).is_ok());
    }

    pub fn emit_event(&self, event: RawAudioEvent) {
        Self::emit(&mut self.world(), event);
    }

    pub fn pair(&self, device: RawAudioDevice) {
        let mut world = self.world();
        world
            .bonded
            .retain(|bonded| !same_address(&bonded.address, &device.address));
        world.bonded.push(device);
        Self::emit(&mut world, RawAudioEvent::BondedDevicesChanged);
    }

    pub fn unpair(&self, address: &str) {
        let mut world = self.world();
        world
            .bonded
            .retain(|bonded| !same_address(&bonded.address, address));
        Self::emit(&mut world, RawAudioEvent::BondedDevicesChanged);
    }

    pub fn set_profile_connected(&self, profile: AudioProfile, address: &str, connected: bool) {
        let mut world = self.world();
        let normalized =
            normalize_bluetooth_address(address).unwrap_or_else(|| address.to_string());
        let set = world.connected.entry(profile).or_default();
        if connected {
            set.insert(normalized);
        } else {
            set.remove(&normalized);
        }
        let known = world
            .bonded
            .iter()
            .find(|bonded| same_address(&bonded.address, address))
            .cloned();
        let event = RawAudioEvent::ProfileChanged {
            profile,
            bonded: known.is_some(),
            device: known.unwrap_or_else(|| RawAudioDevice::new(None, address)),
            connected,
        };
        Self::emit(&mut world, event);
    }

    pub fn set_audio_connected(&self, address: Option<&str>) {
        let previous: Vec<String> = self
            .world()
            .connected
            .get(&AudioProfile::A2dp)
            .map(|set| set.iter().cloned().collect())
            .unwrap_or_default();
        for old in previous {
            if address.is_none_or(|address| !same_address(&old, address)) {
                self.set_profile_connected(AudioProfile::A2dp, &old, false);
            }
        }
        if let Some(address) = address {
            self.set_profile_connected(AudioProfile::A2dp, address, true);
        }
    }

    pub fn set_adapter_enabled(&self, enabled: bool) {
        let mut world = self.world();
        world.enabled = enabled;
        if !enabled {
            world.connected.clear();
        }
        let event = if enabled {
            RawAudioEvent::AdapterOn
        } else {
            RawAudioEvent::AdapterOff
        };
        Self::emit(&mut world, event);
    }
}

struct SimulatedAudioEvents {
    receiver: tokio::sync::Mutex<mpsc::UnboundedReceiver<RawAudioEvent>>,
}

#[async_trait]
impl AudioEvents for SimulatedAudioEvents {
    async fn next_event(&self) -> Option<RawAudioEvent> {
        self.receiver.lock().await.recv().await
    }
}

#[async_trait]
impl AudioFactsDriver for SimulatedAudio {
    async fn subscribe(&self) -> Box<dyn AudioEvents> {
        let (sender, receiver) = mpsc::unbounded_channel();
        self.world().subscribers.push(sender);
        Box::new(SimulatedAudioEvents {
            receiver: tokio::sync::Mutex::new(receiver),
        })
    }

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportError> {
        Ok(self.world().bonded.clone())
    }

    async fn snapshot(&self, target_addresses: &[String]) -> RawAudioSnapshot {
        let world = self.world();
        let addresses: BTreeSet<String> = world
            .bonded
            .iter()
            .map(|device| device.address.as_str())
            .chain(target_addresses.iter().map(String::as_str))
            .filter_map(normalize_bluetooth_address)
            .collect();
        let profiles = [
            AudioProfile::A2dp,
            AudioProfile::Headset,
            AudioProfile::LeAudio,
        ]
        .into_iter()
        .map(|profile| {
            let connected = world.connected.get(&profile).cloned().unwrap_or_default();
            let states = addresses
                .iter()
                .chain(&connected)
                .map(|address| {
                    let state = if connected.contains(address) {
                        AudioConnectionState::Connected
                    } else {
                        AudioConnectionState::Disconnected
                    };
                    (address.clone(), state)
                })
                .collect();
            (
                profile,
                Some(RawProfileSnapshot {
                    connected_addresses: connected.into_iter().collect(),
                    states,
                }),
            )
        })
        .collect();
        RawAudioSnapshot {
            enabled: world.enabled,
            spp_available: true,
            le_audio_observable: true,
            bonded_devices: world.bonded.clone(),
            profiles,
        }
    }
}

type Delivery = Result<Advertisement, TransportError>;

struct Scanner {
    sender: mpsc::UnboundedSender<Delivery>,
    live: bool,
}

#[derive(Default)]
struct ScanWorld {
    scanners: Vec<Scanner>,
    started: usize,
    start_failure: Option<TransportError>,
}

#[derive(Default)]
pub struct SimulatedScan {
    world: Mutex<ScanWorld>,
}

impl SimulatedScan {
    fn world(&self) -> MutexGuard<'_, ScanWorld> {
        self.world.lock().expect("scan world lock")
    }

    pub fn advertise(&self, advertisement: Advertisement) {
        for scanner in self.world().scanners.iter().filter(|scanner| scanner.live) {
            let _ = scanner.sender.send(Ok(advertisement.clone()));
        }
    }

    pub fn fail_scans(&self, error: TransportError) {
        for scanner in self.world().scanners.iter().filter(|scanner| scanner.live) {
            let _ = scanner.sender.send(Err(error.clone()));
        }
    }

    pub fn fail_start(&self, error: Option<TransportError>) {
        self.world().start_failure = error;
    }

    pub fn scans_started(&self) -> usize {
        self.world().started
    }

    pub fn active_scans(&self) -> usize {
        self.world()
            .scanners
            .iter()
            .filter(|scanner| scanner.live)
            .count()
    }

    pub(super) fn open(self: &Arc<Self>) -> Result<Box<dyn ScanDriver>, TransportError> {
        let mut world = self.world();
        if let Some(error) = world.start_failure.clone() {
            return Err(error);
        }
        let (sender, receiver) = mpsc::unbounded_channel();
        world.started += 1;
        let index = world.scanners.len();
        world.scanners.push(Scanner { sender, live: true });
        Ok(Box::new(SimulatedScanDriver {
            scan: Arc::clone(self),
            index,
            receiver: tokio::sync::Mutex::new(receiver),
        }))
    }
}

struct SimulatedScanDriver {
    scan: Arc<SimulatedScan>,
    index: usize,
    receiver: tokio::sync::Mutex<mpsc::UnboundedReceiver<Delivery>>,
}

#[async_trait]
impl ScanDriver for SimulatedScanDriver {
    async fn next_advertisement(&self) -> Result<Option<Advertisement>, TransportError> {
        match self.receiver.lock().await.recv().await {
            Some(delivery) => delivery.map(Some),
            None => Ok(None),
        }
    }

    async fn stop(&self) {
        self.scan.world().scanners[self.index].live = false;
    }
}
