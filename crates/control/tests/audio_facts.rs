mod support;

use openrose_control::audio::{
    AudioConnectionState, AudioProfile, AudioProfileEvidence, RawAudioDevice, RawAudioEvent,
    RawAudioSnapshot, RawProfileSnapshot, connection_facts, infer_audio_profile,
    normalize_bluetooth_address, paired_headsets,
};
use openrose_control::simulator::SimulatedTransport;
use openrose_control::snapshot::{PermissionState, PlatformState, TransportAvailability};
use openrose_headset::{HeadsetId, ModelProfile};
use std::collections::BTreeMap;
use std::sync::Arc;
use support::scripted::{ScriptedAudio, ScriptedTransport, runtime_over};
use support::{Recorder, available, settle};

const ADDRESS: &str = "AA:BB:CC:DD:EE:FF";
const OTHER: &str = "11:22:33:44:55:66";

fn i5(address: &str) -> RawAudioDevice {
    RawAudioDevice::new(Some("EarFree i5"), address)
}

fn profile(connected: &[&str]) -> RawProfileSnapshot {
    let states = connected
        .iter()
        .chain(&[ADDRESS])
        .map(|address| {
            let state = if connected.contains(address) {
                AudioConnectionState::Connected
            } else {
                AudioConnectionState::Disconnected
            };
            (address.to_string(), state)
        })
        .collect();
    RawProfileSnapshot {
        connected_addresses: connected.iter().map(|a| a.to_string()).collect(),
        states,
    }
}

fn snapshot(a2dp: &[&str], headset: &[&str], le: &[&str]) -> RawAudioSnapshot {
    RawAudioSnapshot {
        enabled: true,
        spp_available: true,
        le_audio_observable: true,
        bonded_devices: vec![i5(ADDRESS)],
        profiles: BTreeMap::from([
            (AudioProfile::A2dp, Some(profile(a2dp))),
            (AudioProfile::Headset, Some(profile(headset))),
            (AudioProfile::LeAudio, Some(profile(le))),
        ]),
    }
}

fn unreadable() -> RawAudioSnapshot {
    RawAudioSnapshot {
        enabled: false,
        bonded_devices: Vec::new(),
        profiles: BTreeMap::new(),
        ..snapshot(&[], &[], &[])
    }
}

fn changed(profile: AudioProfile, address: &str, connected: bool) -> RawAudioEvent {
    RawAudioEvent::ProfileChanged {
        profile,
        device: i5(&address.to_lowercase()),
        bonded: true,
        connected,
    }
}

struct Rig {
    audio: Arc<ScriptedAudio>,
    runtime: Arc<openrose_control::ControlRuntime>,
    recorder: Arc<Recorder>,
}

impl Rig {
    fn new() -> Self {
        Self::with_platform(available().with_user_unlocked(false))
    }

    fn with_platform(platform: PlatformState) -> Self {
        let audio = ScriptedAudio::new();
        let transport = ScriptedTransport::new(Arc::new(SimulatedTransport::new()), audio.clone());
        let (runtime, recorder) = runtime_over(transport, platform);
        Self {
            audio,
            runtime,
            recorder,
        }
    }

    fn audio_values(&self) -> Vec<Option<HeadsetId>> {
        let mut values: Vec<Option<HeadsetId>> = vec![None];
        for snapshot in self.recorder.all() {
            if *values.last().unwrap() != snapshot.audio_headset_id {
                values.push(snapshot.audio_headset_id.clone());
            }
        }
        values.split_off(1)
    }

    fn audio_headset(&self) -> Option<HeadsetId> {
        self.runtime.snapshot().audio_headset_id
    }
}

fn address() -> Option<HeadsetId> {
    Some(HeadsetId::new(ADDRESS))
}

#[test]
fn paired_headsets_match_models_and_normalize_addresses() {
    let devices = paired_headsets(&[
        RawAudioDevice::new(Some("EarFree i5"), " aa:bb:cc:dd:ee:ff "),
        RawAudioDevice::new(Some("Other"), OTHER),
        RawAudioDevice::new(Some("EarFree i5"), "invalid"),
    ]);
    assert_eq!(1, devices.len());
    assert_eq!(HeadsetId::new(ADDRESS), devices[0].id);
    assert_eq!(Some(ADDRESS.to_string()), devices[0].classic_address);
    assert_eq!(Some(ModelProfile::I5Zt), devices[0].profile);
    assert_eq!(
        Some("AA:BB:CC:DD:EE:FF".to_string()),
        normalize_bluetooth_address("aa:bb:cc:dd:ee:ff")
    );
    assert_eq!(None, normalize_bluetooth_address("AA-BB-CC-DD-EE-FF"));
    assert_eq!(None, normalize_bluetooth_address("AA:BB:CC:DD:EE:GG"));
}

#[test]
fn paired_headsets_match_by_the_original_name_and_show_the_display_alias() {
    let devices = paired_headsets(&[
        RawAudioDevice::new(Some("ROSE EarFree i5"), "aa:bb:cc:dd:ee:ff").aliased("主力耳机"),
        RawAudioDevice::new(Some("ROSE EarFree i5"), OTHER).aliased("   "),
        RawAudioDevice {
            name: None,
            address: "22:33:44:55:66:77".to_string(),
            alias: Some("EarFree i5".to_string()),
        },
    ]);
    let names: Vec<Option<String>> = devices.iter().map(|device| device.name.clone()).collect();
    assert_eq!(
        vec![
            Some("主力耳机".to_string()),
            Some("ROSE EarFree i5".to_string()),
            Some("EarFree i5".to_string()),
        ],
        names
    );
}

#[test]
fn profile_evidence_requires_consistent_complete_snapshots() {
    let targets = vec![ADDRESS.to_string()];
    let classic = snapshot(&[ADDRESS], &[], &[]);
    assert_eq!(
        AudioProfileEvidence::Classic,
        infer_audio_profile(&classic, &targets)
    );
    assert_eq!(
        AudioProfileEvidence::LeAudio,
        infer_audio_profile(&snapshot(&[ADDRESS], &[], &[ADDRESS]), &targets)
    );
    assert_eq!(
        AudioProfileEvidence::Unknown,
        infer_audio_profile(&snapshot(&[], &[], &[]), &targets)
    );
    assert_eq!(
        AudioProfileEvidence::Unknown,
        infer_audio_profile(&snapshot(&[ADDRESS], &[], &[OTHER]), &targets)
    );
    let mut blind = classic.clone();
    blind.le_audio_observable = false;
    assert_eq!(
        AudioProfileEvidence::Unknown,
        infer_audio_profile(&blind, &targets)
    );
    let mut missing = classic.clone();
    missing.profiles.remove(&AudioProfile::Headset);
    assert_eq!(
        AudioProfileEvidence::Unknown,
        infer_audio_profile(&missing, &targets)
    );
    let mut inconsistent = classic.clone();
    inconsistent
        .profiles
        .get_mut(&AudioProfile::A2dp)
        .unwrap()
        .as_mut()
        .unwrap()
        .states = BTreeMap::from([(ADDRESS.to_string(), AudioConnectionState::Disconnected)]);
    assert_eq!(
        AudioProfileEvidence::Unknown,
        infer_audio_profile(&inconsistent, &targets)
    );
    assert_eq!(
        AudioProfileEvidence::Classic,
        infer_audio_profile(&classic, &[" aa:bb:cc:dd:ee:ff ".to_string()])
    );
}

#[tokio::test(start_paused = true)]
async fn the_core_registers_for_events_before_it_queries() {
    let rig = Rig::new();
    settle().await;
    let _query = rig.audio.next_query().await;
    let order = rig.audio.call_order();
    let subscribed = order.iter().position(|call| *call == "subscribe");
    let queried = order.iter().position(|call| *call == "snapshot");
    assert!(subscribed.is_some() && subscribed < queried, "{order:?}");
}

#[tokio::test(start_paused = true)]
async fn a_changed_paired_list_event_reads_the_paired_headsets_again() {
    let rig = Rig::new();
    rig.audio.set_bonded(vec![i5(ADDRESS)]);
    settle().await;
    let names = |rig: &Rig| -> Vec<Option<String>> {
        rig.runtime
            .snapshot()
            .bonded_headsets
            .iter()
            .map(|headset| headset.name.clone())
            .collect()
    };
    assert_eq!(vec![Some("EarFree i5".to_string())], names(&rig));

    rig.audio.set_bonded(vec![i5(ADDRESS).aliased("主力耳机")]);
    rig.audio.emit(RawAudioEvent::BondedDevicesChanged);
    settle().await;
    assert_eq!(vec![Some("主力耳机".to_string())], names(&rig));
}

#[tokio::test(start_paused = true)]
async fn an_unavailable_le_profile_keeps_a_classic_audio_target_but_leaves_the_evidence_unknown() {
    let rig = Rig::new();
    settle().await;
    let mut incomplete = snapshot(&[ADDRESS], &[], &[]);
    incomplete.profiles.insert(AudioProfile::LeAudio, None);
    rig.audio
        .next_query()
        .await
        .send(incomplete.clone())
        .unwrap();
    settle().await;
    assert_eq!(address(), rig.audio_headset());

    let audio = rig.audio.clone();
    let facts = tokio::spawn(async move {
        connection_facts(&*audio, &HeadsetId::new(ADDRESS), Some(ADDRESS)).await
    });
    rig.audio.next_query().await.send(incomplete).unwrap();
    let facts = facts.await.unwrap();
    assert_eq!(AudioProfileEvidence::Unknown, facts.audio_profile);
    assert!(facts.spp_available);
}

#[tokio::test(start_paused = true)]
async fn events_during_a_query_win_without_a_premature_initial_emission() {
    let rig = Rig::new();
    settle().await;
    let query = rig.audio.next_query().await;
    rig.audio.emit(changed(AudioProfile::A2dp, ADDRESS, true));
    settle().await;
    assert_eq!(None, rig.audio_headset());
    assert!(rig.audio_values().is_empty());
    query.send(snapshot(&[], &[], &[])).unwrap();
    settle().await;
    assert_eq!(vec![address()], rig.audio_values());
    rig.audio.emit(changed(AudioProfile::A2dp, ADDRESS, false));
    settle().await;
    assert_eq!(vec![address(), None], rig.audio_values());
}

#[tokio::test(start_paused = true)]
async fn a_disconnect_during_a_query_does_not_erase_another_profile_fact() {
    let rig = Rig::new();
    settle().await;
    let query = rig.audio.next_query().await;
    rig.audio.emit(changed(AudioProfile::A2dp, ADDRESS, false));
    settle().await;
    query.send(snapshot(&[ADDRESS], &[ADDRESS], &[])).unwrap();
    settle().await;
    assert_eq!(vec![address()], rig.audio_values());
    rig.audio
        .emit(changed(AudioProfile::Headset, ADDRESS, false));
    settle().await;
    assert_eq!(vec![address(), None], rig.audio_values());
}

#[tokio::test(start_paused = true)]
async fn a_disconnect_during_a_query_removes_the_stale_snapshot_connection() {
    let rig = Rig::new();
    settle().await;
    let query = rig.audio.next_query().await;
    rig.audio.emit(changed(AudioProfile::A2dp, ADDRESS, false));
    settle().await;
    query.send(snapshot(&[ADDRESS], &[], &[])).unwrap();
    settle().await;
    assert_eq!(None, rig.audio_headset());
    assert!(rig.audio_values().is_empty());
}

#[tokio::test(start_paused = true)]
async fn events_only_select_paired_supported_devices_with_valid_addresses() {
    let rig = Rig::new();
    settle().await;
    rig.audio
        .next_query()
        .await
        .send(snapshot(&[], &[], &[]))
        .unwrap();
    settle().await;
    let event = |name: &str, address: &str, bonded: bool| RawAudioEvent::ProfileChanged {
        profile: AudioProfile::A2dp,
        device: RawAudioDevice::new(Some(name), address),
        bonded,
        connected: true,
    };
    rig.audio.emit(event("Other", OTHER, true));
    rig.audio.emit(event("EarFree i5", "invalid", true));
    rig.audio.emit(event("EarFree i5", ADDRESS, false));
    settle().await;
    assert_eq!(None, rig.audio_headset());
    rig.audio.emit(changed(AudioProfile::A2dp, ADDRESS, true));
    settle().await;
    assert_eq!(address(), rig.audio_headset());
}

#[tokio::test(start_paused = true)]
async fn a_restart_discards_the_old_query_and_queries_again() {
    let rig = Rig::new();
    settle().await;
    let old = rig.audio.next_query().await;
    rig.audio.emit(RawAudioEvent::AdapterOff);
    rig.audio.emit(RawAudioEvent::AdapterOn);
    settle().await;
    let fresh = rig.audio.next_query().await;
    let _ = old.send(snapshot(&[ADDRESS], &[], &[]));
    settle().await;
    assert_eq!(None, rig.audio_headset());
    fresh.send(snapshot(&[], &[ADDRESS], &[])).unwrap();
    settle().await;
    assert_eq!(address(), rig.audio_headset());
}

#[tokio::test(start_paused = true)]
async fn an_unreadable_initial_probe_does_not_suppress_later_readable_profile_events() {
    let rig = Rig::new();
    settle().await;
    rig.audio.next_query().await.send(unreadable()).unwrap();
    settle().await;
    assert_eq!(None, rig.audio_headset());
    rig.audio.emit(changed(AudioProfile::A2dp, ADDRESS, true));
    settle().await;
    assert_eq!(address(), rig.audio_headset());
}

#[tokio::test(start_paused = true)]
async fn a_readable_event_during_an_unreadable_probe_wins_over_that_probe() {
    let rig = Rig::new();
    settle().await;
    let query = rig.audio.next_query().await;
    rig.audio.emit(changed(AudioProfile::A2dp, ADDRESS, true));
    settle().await;
    query.send(unreadable()).unwrap();
    settle().await;
    assert_eq!(address(), rig.audio_headset());
}

#[tokio::test(start_paused = true)]
async fn the_adapter_going_off_clears_the_audio_fact() {
    let rig = Rig::new();
    settle().await;
    rig.audio
        .next_query()
        .await
        .send(snapshot(&[ADDRESS], &[], &[]))
        .unwrap();
    settle().await;
    assert_eq!(address(), rig.audio_headset());
    rig.audio.emit(RawAudioEvent::AdapterOff);
    settle().await;
    assert_eq!(None, rig.audio_headset());
}

#[tokio::test(start_paused = true)]
async fn losing_bluetooth_permission_clears_the_audio_fact_and_regaining_it_reads_it_again() {
    let granted = available().with_user_unlocked(false);
    let rig = Rig::with_platform(granted.clone());
    settle().await;
    rig.audio
        .next_query()
        .await
        .send(snapshot(&[ADDRESS], &[], &[]))
        .unwrap();
    settle().await;
    assert_eq!(address(), rig.audio_headset());

    let denied = PlatformState::new(PermissionState::Denied, TransportAvailability::Available)
        .with_user_unlocked(false);
    rig.runtime.update_platform_state(denied);
    settle().await;
    assert_eq!(None, rig.audio_headset());

    rig.runtime.update_platform_state(granted);
    settle().await;
    rig.audio
        .next_query()
        .await
        .send(snapshot(&[ADDRESS], &[], &[]))
        .unwrap();
    settle().await;
    assert_eq!(address(), rig.audio_headset());
}
