mod support;

use async_trait::async_trait;
use openrose_circulation::{LocalAudio, YieldReceipt, YieldRefusal};
use openrose_desktop_platform::audio::linux::local_audio::LinuxLocalAudio;
use openrose_desktop_platform::audio::linux::pactl::{
    PactlRunner, headset_in_call, headset_mic_active, matches_pulse_endpoint,
    parse_sink_description,
};
use openrose_desktop_platform::audio::unsupported::UnsupportedLocalAudio;
use openrose_desktop_platform::bluetooth::linux::bus::{
    AUDIO_SINK_UUID, BluezInterfaces, BluezObjects, CLASSIC_BEARER, DEVICE, LE_BEARER,
};
use openrose_desktop_platform::bluetooth::linux::transport::BluezControlTransport;
use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use support::*;

const TARGET_ADDRESS: &str = "00:11:22:33:44:55";
const TARGET_PATH: &str = "/org/bluez/hci0/dev_00_11_22_33_44_55";

fn headset_objects(classic: bool, le: bool, with_media: bool) -> BluezObjects {
    let mut map = objects(&[
        (ADAPTER_PATH, adapter(true)),
        (
            TARGET_PATH,
            interfaces(&[
                (
                    DEVICE,
                    props(&[
                        ("Address", text(TARGET_ADDRESS)),
                        ("Name", text("EarFree i5")),
                        ("Paired", flag(true)),
                    ]),
                ),
                (CLASSIC_BEARER, props(&[("Connected", flag(classic))])),
                (LE_BEARER, props(&[("Connected", flag(le))])),
            ]),
        ),
    ]);
    if with_media {
        let (path, ifaces) = media_transport(TARGET_PATH);
        map.insert(path, ifaces);
    }
    map
}

type PactlReply = (Vec<String>, Result<String, String>);

struct FakePactl {
    calls: Mutex<Vec<Vec<String>>>,
    replies: Mutex<VecDeque<PactlReply>>,
}

impl FakePactl {
    fn new(replies: Vec<(&[&str], Result<&str, &str>)>) -> Arc<Self> {
        let queue = replies
            .into_iter()
            .map(|(args, res)| {
                (
                    args.iter().map(|s| (*s).to_string()).collect(),
                    res.map(str::to_string).map_err(str::to_string),
                )
            })
            .collect();
        Arc::new(Self {
            calls: Mutex::new(Vec::new()),
            replies: Mutex::new(queue),
        })
    }

    fn calls(&self) -> Vec<Vec<String>> {
        self.calls.lock().expect("calls").clone()
    }
}

#[async_trait]
impl PactlRunner for FakePactl {
    async fn run(&self, args: &[&str]) -> Result<String, String> {
        let owned: Vec<String> = args.iter().map(|s| (*s).to_string()).collect();
        self.calls.lock().expect("calls").push(owned.clone());
        let (expected, result) = self
            .replies
            .lock()
            .expect("replies")
            .pop_front()
            .unwrap_or_else(|| panic!("unexpected pactl call: {owned:?}"));
        assert_eq!(owned, expected);
        result
    }
}

#[test]
fn native_pulse_and_pipewire_both_protect_the_specific_headset_microphone() {
    let address = "00:11:22:33:44:55";
    for name in [
        "bluez_source.00_11_22_33_44_55.headset_head_unit",
        "bluez_input.00:11:22:33:44:55.0",
    ] {
        let sources = format!("31\t{name}\tBluetooth\n32\talsa_input.other\tALSA");
        let active = "Source Output #1\n    Source: 31\n    Corked: no\n";
        assert!(headset_mic_active(address, &sources, active));
        assert!(!headset_mic_active(
            address,
            &sources,
            &active.replace("31", "32")
        ));
        assert!(!headset_mic_active(
            address,
            &sources,
            &active.replace("no", "yes")
        ));
    }
}

#[test]
fn hfp_and_hsp_active_profiles_are_detected_for_the_target_headset_only() {
    for profile in [
        "headset_head_unit",
        "headset-head-unit",
        "headset-head-unit-msbc",
        "handsfree_head_unit",
        "handsfree-head-unit",
    ] {
        let cards = format!(
            "Card #10\n\tName: bluez_card.00_11_22_33_44_55\n\tActive Profile: {profile}\n"
        );
        assert!(headset_in_call(TARGET_ADDRESS, &cards));
        assert!(!headset_in_call("AA:BB:CC:DD:EE:FF", &cards));
    }
    let a2dp = "Card #10\n\tName: bluez_card.00_11_22_33_44_55\n\tActive Profile: a2dp-sink\n";
    assert!(!headset_in_call(TARGET_ADDRESS, a2dp));
    let off = "Card #10\n\tName: bluez_card.00_11_22_33_44_55\n\tActive Profile: off\n";
    assert!(!headset_in_call(TARGET_ADDRESS, off));
}

#[test]
fn pulse_endpoint_matching_normalizes_colons_and_case_and_requires_segment_boundary() {
    assert!(matches_pulse_endpoint(
        "bluez_output.aa_bb_cc_dd_ee_ff.a2dp-sink",
        "AA:BB:CC:DD:EE:FF",
        &["bluez_output", "bluez_sink"]
    ));
    assert!(matches_pulse_endpoint(
        "bluez_sink.AA_BB_CC_DD_EE_FF",
        "aa:bb:cc:dd:ee:ff",
        &["bluez_output", "bluez_sink"]
    ));
    assert!(!matches_pulse_endpoint(
        "bluez_output.AA_BB_CC_DD_EE_FF_extra",
        "AA:BB:CC:DD:EE:FF",
        &["bluez_output", "bluez_sink"]
    ));
    assert!(!matches_pulse_endpoint(
        "alsa_output.pci-0000_00_1f.3.analog-stereo",
        "AA:BB:CC:DD:EE:FF",
        &["bluez_output", "bluez_sink"]
    ));
}

#[test]
fn sink_description_is_extracted_only_for_the_matching_default_sink() {
    let sinks = "\
Sink #41
    State: SUSPENDED
    Name: alsa_output.pci-0000_00_1f.3.analog-stereo
    Description: Built-in Audio Analog Stereo
Sink #42
    State: RUNNING
    Name: bluez_output.00_11_22_33_44_55.1
    Description: ROSE EarFree i5
";
    assert_eq!(
        parse_sink_description("bluez_output.00_11_22_33_44_55.1", sinks).as_deref(),
        Some("ROSE EarFree i5")
    );
    assert_eq!(
        parse_sink_description("alsa_output.pci-0000_00_1f.3.analog-stereo", sinks).as_deref(),
        Some("Built-in Audio Analog Stereo")
    );
    assert_eq!(parse_sink_description("missing_sink", sinks), None);
}

#[tokio::test]
async fn connection_and_media_facts_follow_bluez_objects() {
    let bus = FakeBluezBus::with_snapshot(headset_objects(true, false, true));
    let transport = BluezControlTransport::with_bus(bus.clone());
    transport.await_initialization().await;
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), FakePactl::new(vec![]));

    assert!(audio.is_connected("00:11:22:33:44:55").await);
    assert!(audio.is_media_connected("00:11:22:33:44:55").await);
    assert!(!audio.is_connected("invalid").await);
    assert!(!audio.is_media_connected("invalid").await);

    bus.set_snapshot(headset_objects(false, true, false));
    assert!(audio.is_connected(TARGET_ADDRESS).await);
    assert!(!audio.is_media_connected(TARGET_ADDRESS).await);

    bus.set_snapshot(headset_objects(false, false, false));
    assert!(!audio.is_connected(TARGET_ADDRESS).await);
}

#[tokio::test]
async fn yield_audio_refuses_in_call_and_mic_in_use_without_disconnecting_bluez() {
    let bus = FakeBluezBus::with_snapshot(headset_objects(true, true, true));
    let disconnected = Arc::new(Mutex::new(Vec::new()));
    let sink = disconnected.clone();
    bus.with(|inner| {
        inner.on_audio_disconnect = Some(hook(move |path| {
            let sink = sink.clone();
            async move {
                sink.lock().expect("sink").push(path);
                Ok(())
            }
        }));
    });
    let transport = BluezControlTransport::with_bus(bus);
    transport.await_initialization().await;

    let in_call = LinuxLocalAudio::with_pactl(
        transport.audio_profiles(),
        FakePactl::new(vec![(
            &["list", "cards"],
            Ok("Card #1\n\tName: bluez_card.00_11_22_33_44_55\n\tActive Profile: headset-head-unit\n"),
        )]),
    );
    assert_eq!(
        in_call.yield_audio("00:11:22:33:44:55").await,
        Err(YieldRefusal::InCall)
    );
    assert!(disconnected.lock().expect("sink").is_empty());

    let mic_in_use = LinuxLocalAudio::with_pactl(
        transport.audio_profiles(),
        FakePactl::new(vec![
            (
                &["list", "cards"],
                Ok("Card #1\n\tName: bluez_card.00_11_22_33_44_55\n\tActive Profile: a2dp-sink\n"),
            ),
            (
                &["list", "short", "sources"],
                Ok("31\tbluez_input.00_11_22_33_44_55.0\tPipeWire\n"),
            ),
            (
                &["list", "source-outputs"],
                Ok("Source Output #9\n\tSource: 31\n\tCorked: no\n"),
            ),
        ]),
    );
    assert_eq!(
        mic_in_use.yield_audio("00:11:22:33:44:55").await,
        Err(YieldRefusal::MicInUse)
    );
    assert!(disconnected.lock().expect("sink").is_empty());
}

#[tokio::test(start_paused = true)]
async fn yield_audio_disconnects_bluez_links_and_returns_normalized_receipt() {
    let bus = FakeBluezBus::with_snapshot(headset_objects(true, true, true));
    let disconnected = Arc::new(Mutex::new(Vec::new()));
    let sink = disconnected.clone();
    let resetting = bus.clone();
    bus.with(|inner| {
        inner.on_audio_disconnect = Some(hook(move |path| {
            let sink = sink.clone();
            let resetting = resetting.clone();
            async move {
                sink.lock().expect("sink").push(path);
                resetting.set_snapshot(headset_objects(false, false, false));
                Ok(())
            }
        }));
    });
    let transport = BluezControlTransport::with_bus(bus);
    transport.await_initialization().await;
    let pactl = FakePactl::new(vec![
        (
            &["list", "cards"],
            Ok("Card #1\n\tName: bluez_card.00_11_22_33_44_55\n\tActive Profile: a2dp-sink\n"),
        ),
        (
            &["list", "short", "sources"],
            Ok("31\talsa_input.pci.analog\tALSA\n"),
        ),
        (&["list", "source-outputs"], Ok("")),
    ]);
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), pactl);

    let receipt = audio
        .yield_audio("00:11:22:33:44:55")
        .await
        .expect("yield receipt");
    assert_eq!(
        receipt,
        YieldReceipt {
            address: TARGET_ADDRESS.to_string()
        }
    );
    assert_eq!(
        *disconnected.lock().expect("sink"),
        vec![TARGET_PATH.to_string()]
    );
}

#[tokio::test(start_paused = true)]
async fn acquire_connects_audio_and_restores_previous_default_sink_only_if_switched() {
    let bus = FakeBluezBus::with_snapshot(headset_objects(false, false, false));
    let adding = bus.clone();
    let connected_paths = Arc::new(Mutex::new(Vec::new()));
    let record = connected_paths.clone();
    bus.with(|inner| {
        inner.on_connect = Some(hook(move |_| {
            let adding = adding.clone();
            async move {
                adding.set_snapshot(headset_objects(false, true, false));
                Ok(())
            }
        }));
        let adding = bus.clone();
        inner.on_audio_connect = Some(hook(move |path| {
            let record = record.clone();
            let adding = adding.clone();
            async move {
                record.lock().expect("record").push(path);
                adding.with(|inner| {
                    let (media_path, media_ifaces): (String, BluezInterfaces) =
                        media_transport(TARGET_PATH);
                    inner.snapshot.insert(media_path, media_ifaces);
                    inner
                        .snapshot
                        .get_mut(TARGET_PATH)
                        .and_then(|ifaces| ifaces.get_mut(DEVICE))
                        .expect("device")
                        .insert("UUIDs".to_string(), strings(&[AUDIO_SINK_UUID]));
                });
                Ok(())
            }
        }));
    });
    let transport = BluezControlTransport::with_bus(bus);
    transport.await_initialization().await;

    let pactl = FakePactl::new(vec![
        (&["get-default-sink"], Ok("alsa_output.speaker\n")),
        (&["set-default-sink", "alsa_output.speaker"], Ok("")),
        (
            &["get-default-sink"],
            Ok("bluez_output.00_11_22_33_44_55.1\n"),
        ),
        (&["set-default-sink", "alsa_output.speaker"], Ok("")),
    ]);
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), pactl.clone());
    audio.acquire("00:11:22:33:44:55").await.expect("acquire");
    assert_eq!(
        *connected_paths.lock().expect("record"),
        vec![TARGET_PATH.to_string()]
    );
    assert_eq!(
        pactl.calls(),
        vec![
            vec!["get-default-sink"],
            vec!["set-default-sink", "alsa_output.speaker"],
            vec!["get-default-sink"],
            vec!["set-default-sink", "alsa_output.speaker"],
        ]
    );

    let unchanged_pactl = FakePactl::new(vec![
        (&["get-default-sink"], Ok("alsa_output.speaker\n")),
        (&["set-default-sink", "alsa_output.speaker"], Ok("")),
        (&["get-default-sink"], Ok("alsa_output.speaker\n")),
    ]);
    let audio_unchanged =
        LinuxLocalAudio::with_pactl(transport.audio_profiles(), unchanged_pactl.clone());
    audio_unchanged
        .acquire("00:11:22:33:44:55")
        .await
        .expect("acquire when already active");
    assert_eq!(
        unchanged_pactl.calls(),
        vec![
            vec!["get-default-sink"],
            vec!["set-default-sink", "alsa_output.speaker"],
            vec!["get-default-sink"],
        ]
    );
}

#[tokio::test]
async fn acquire_still_restores_default_sink_when_bluez_acquire_fails() {
    let bus = FakeBluezBus::with_snapshot(objects(&[(ADAPTER_PATH, adapter(true))]));
    let transport = BluezControlTransport::with_bus(bus);
    transport.await_initialization().await;
    let pactl = FakePactl::new(vec![
        (&["get-default-sink"], Ok("alsa_output.speaker\n")),
        (&["set-default-sink", "alsa_output.speaker"], Ok("")),
        (
            &["get-default-sink"],
            Ok("bluez_sink.00_11_22_33_44_55.a2dp_sink\n"),
        ),
        (&["set-default-sink", "alsa_output.speaker"], Ok("")),
    ]);
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), pactl.clone());
    let err = audio
        .acquire(TARGET_ADDRESS)
        .await
        .expect_err("unpaired headset fails");
    assert!(matches!(err, YieldRefusal::Unavailable(_)));
    assert_eq!(pactl.calls().len(), 4);
}

#[tokio::test]
async fn current_output_uses_description_and_falls_back_to_sink_name() {
    let bus = FakeBluezBus::new();
    let transport = BluezControlTransport::with_bus(bus);
    let pactl = FakePactl::new(vec![
        (&["get-default-sink"], Ok("bluez_output.00_11_22_33_44_55.1\n")),
        (
            &["list", "sinks"],
            Ok("Sink #1\n\tName: bluez_output.00_11_22_33_44_55.1\n\tDescription: ROSE EarFree i5\n"),
        ),
        (&["get-default-sink"], Ok("alsa_output.unknown\n")),
        (&["list", "sinks"], Ok("Sink #1\n\tName: other\n\tDescription: Other\n")),
    ]);
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), pactl);
    assert_eq!(audio.current_output().await.expect("desc"), "ROSE EarFree i5");
    assert_eq!(
        audio.current_output().await.expect("fallback"),
        "alsa_output.unknown"
    );
}

#[tokio::test]
async fn unsupported_local_audio_reports_unavailable_and_disconnected() {
    let audio = UnsupportedLocalAudio;
    assert!(!audio.is_connected(TARGET_ADDRESS).await);
    assert!(!audio.is_media_connected(TARGET_ADDRESS).await);
    assert!(matches!(
        audio.yield_audio(TARGET_ADDRESS).await,
        Err(YieldRefusal::Unavailable(_))
    ));
    assert!(matches!(
        audio.acquire(TARGET_ADDRESS).await,
        Err(YieldRefusal::Unavailable(_))
    ));
}
