use crate::audio::{
    AudioProfile, RawAudioDevice, RawAudioEvent, RawAudioSnapshot, normalize_bluetooth_address,
    paired_headset,
};
use crate::core::{Core, detached};
use openrose_headset::HeadsetId;
use std::collections::{BTreeMap, BTreeSet};
use std::pin::Pin;
use std::sync::Arc;
use tokio::sync::watch;

type Published = Option<Option<HeadsetId>>;
type Slot<'a, T> = Option<Pin<Box<dyn Future<Output = T> + Send + 'a>>>;

struct Tracker {
    connected: BTreeMap<AudioProfile, BTreeSet<String>>,
    changes: BTreeMap<(AudioProfile, String), bool>,
    event_devices: BTreeMap<String, Option<RawAudioDevice>>,
    bonded: BTreeSet<String>,
    querying: bool,
    enabled: bool,
    emitted: bool,
    current: Option<HeadsetId>,
}

fn is_paired(device: Option<&RawAudioDevice>) -> bool {
    device.and_then(paired_headset).is_some()
}

impl Tracker {
    fn new() -> Self {
        Self {
            connected: BTreeMap::new(),
            changes: BTreeMap::new(),
            event_devices: BTreeMap::new(),
            bonded: BTreeSet::new(),
            querying: true,
            enabled: true,
            emitted: false,
            current: None,
        }
    }

    fn publish(&mut self) -> Published {
        let next = self
            .connected
            .values()
            .flatten()
            .find(|address| self.bonded.contains(*address))
            .map(|address| HeadsetId::new(address.clone()));
        if self.emitted && next == self.current {
            return None;
        }
        self.emitted = true;
        self.current = next.clone();
        Some(next)
    }

    fn mark(&mut self, profile: AudioProfile, address: &str, connected: bool) {
        let set = self.connected.entry(profile).or_default();
        if connected {
            set.insert(address.to_string());
        } else {
            set.remove(address);
        }
    }

    fn profile_changed(
        &mut self,
        profile: AudioProfile,
        device: &RawAudioDevice,
        bonded: bool,
        connected: bool,
    ) -> Published {
        if !self.enabled || profile == AudioProfile::LeAudio {
            return None;
        }
        let address = normalize_bluetooth_address(&device.address)?;
        let device = bonded.then_some(device);
        let has_identity = !bonded || device.is_some_and(|device| device.name.is_some());
        if self.querying {
            self.changes.insert((profile, address.clone()), connected);
            if has_identity {
                self.event_devices.insert(address.clone(), device.cloned());
            }
        }
        if has_identity {
            if is_paired(device) {
                self.bonded.insert(address.clone());
            } else {
                self.bonded.remove(&address);
            }
        }
        self.mark(profile, &address, connected);
        if self.querying { None } else { self.publish() }
    }

    fn snapshot_arrived(&mut self, snapshot: &RawAudioSnapshot) -> Published {
        self.connected.clear();
        self.bonded.clear();
        if snapshot.enabled {
            for profile in [AudioProfile::A2dp, AudioProfile::Headset] {
                let addresses: Vec<String> = snapshot
                    .profiles
                    .get(&profile)
                    .and_then(Option::as_ref)
                    .map(|raw| {
                        raw.connected_addresses
                            .iter()
                            .filter_map(|address| normalize_bluetooth_address(address))
                            .collect()
                    })
                    .unwrap_or_default();
                self.connected.entry(profile).or_default().extend(addresses);
            }
            for device in &snapshot.bonded_devices {
                if let Some(paired) = paired_headset(device) {
                    self.bonded.insert(paired.id.0);
                }
            }
        }
        for (address, device) in &self.event_devices {
            if is_paired(device.as_ref()) {
                self.bonded.insert(address.clone());
            } else {
                self.bonded.remove(address);
            }
        }
        let changes: Vec<_> = self.changes.clone().into_iter().collect();
        for ((profile, address), connected) in changes {
            self.mark(profile, &address, connected);
        }
        self.querying = false;
        self.publish()
    }

    fn adapter_off(&mut self) -> Published {
        self.enabled = false;
        self.querying = false;
        self.clear(true);
        self.publish()
    }

    fn adapter_on(&mut self) {
        self.enabled = true;
        self.querying = true;
        self.clear(true);
    }

    fn clear(&mut self, with_changes: bool) {
        self.connected.clear();
        self.bonded.clear();
        if with_changes {
            self.changes.clear();
            self.event_devices.clear();
        }
    }
}

async fn next<T>(slot: &mut Slot<'_, T>) -> T {
    match slot.as_mut() {
        Some(future) => {
            let value = future.await;
            *slot = None;
            value
        }
        None => std::future::pending().await,
    }
}

async fn until(readable: &mut watch::Receiver<bool>, value: bool) -> bool {
    readable.wait_for(|current| *current == value).await.is_ok()
}

impl Core {
    pub(crate) async fn observe_audio_facts(self: Arc<Self>) {
        let mut readable = self.audio_readable.subscribe();
        loop {
            tokio::select! {
                _ = self.shutdown.cancelled() => return,
                alive = until(&mut readable, true) => if !alive { return },
            }
            tokio::select! {
                _ = self.shutdown.cancelled() => return,
                alive = until(&mut readable, false) => if !alive { return },
                _ = self.track_audio_facts() => {
                    tokio::select! {
                        _ = self.shutdown.cancelled() => return,
                        alive = until(&mut readable, false) => if !alive { return },
                    }
                }
            }
            self.state
                .update(|snapshot| snapshot.audio_headset_id = None);
        }
    }

    async fn track_audio_facts(self: &Arc<Self>) {
        let events = self.audio.subscribe().await;
        let mut tracker = Tracker::new();
        let mut query: Slot<'_, RawAudioSnapshot> = Some(self.audio.snapshot(&[]));
        let mut refresh: Slot<'_, ()> = Some(Box::pin(self.refresh_bonded_headsets()));
        loop {
            let published = tokio::select! {
                event = events.next_event() => match event {
                    None => return,
                    Some(RawAudioEvent::ProfileChanged { profile, device, bonded, connected }) => {
                        tracker.profile_changed(profile, &device, bonded, connected)
                    }
                    Some(RawAudioEvent::AdapterOff) => {
                        query = None;
                        tracker.adapter_off()
                    }
                    Some(RawAudioEvent::AdapterOn) => {
                        tracker.adapter_on();
                        query = Some(self.audio.snapshot(&[]));
                        None
                    }
                    Some(RawAudioEvent::BondedDevicesChanged) => {
                        refresh = Some(Box::pin(self.refresh_bonded_headsets()));
                        None
                    }
                },
                snapshot = next(&mut query) => tracker.snapshot_arrived(&snapshot),
                () = next(&mut refresh) => None,
            };
            if let Some(headset) = published {
                self.state
                    .update(|snapshot| snapshot.audio_headset_id = headset.clone());
                let core = Arc::clone(self);
                detached(async move { core.audio_changed(headset).await }).await;
            }
        }
    }
}
