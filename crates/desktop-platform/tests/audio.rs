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
use std::time::Duration;
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

    assert_eq!(audio.is_connected("00:11:22:33:44:55").await, Ok(true));
    assert!(audio.is_media_connected("00:11:22:33:44:55").await);
    assert_eq!(audio.is_connected("invalid").await, Ok(false));
    assert!(!audio.is_media_connected("invalid").await);

    bus.set_snapshot(headset_objects(false, true, false));
    assert_eq!(audio.is_connected(TARGET_ADDRESS).await, Ok(true));
    assert!(!audio.is_media_connected(TARGET_ADDRESS).await);

    bus.set_snapshot(headset_objects(false, false, false));
    assert_eq!(audio.is_connected(TARGET_ADDRESS).await, Ok(false));
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

struct SinkPactl {
    default: Mutex<Option<String>>,
    broken: bool,
    calls: Mutex<Vec<Vec<String>>>,
}

impl SinkPactl {
    fn new(default: Option<&str>, broken: bool) -> Arc<Self> {
        Arc::new(Self {
            default: Mutex::new(default.map(str::to_string)),
            broken,
            calls: Mutex::new(Vec::new()),
        })
    }

    fn switch_to(&self, sink: &str) {
        *self.default.lock().expect("default") = Some(sink.to_string());
    }

    fn default_sink(&self) -> Option<String> {
        self.default.lock().expect("default").clone()
    }

    fn set_calls(&self) -> Vec<Vec<String>> {
        self.calls
            .lock()
            .expect("calls")
            .iter()
            .filter(|call| call.first().map(String::as_str) == Some("set-default-sink"))
            .cloned()
            .collect()
    }
}

#[async_trait]
impl PactlRunner for SinkPactl {
    async fn run(&self, args: &[&str]) -> Result<String, String> {
        self.calls
            .lock()
            .expect("calls")
            .push(args.iter().map(|arg| (*arg).to_string()).collect());
        if self.broken {
            return Err("pactl unavailable".to_string());
        }
        match args {
            ["get-default-sink"] => self
                .default_sink()
                .map(|sink| format!("{sink}\n"))
                .ok_or_else(|| "no default sink".to_string()),
            ["set-default-sink", sink] => {
                self.switch_to(sink);
                Ok(String::new())
            }
            other => panic!("unexpected pactl call: {other:?}"),
        }
    }
}

const SPEAKER: &str = "alsa_output.speaker";
const HEADSET_SINK: &str = "bluez_output.00_11_22_33_44_55.1";

fn bus_that_brings_up_audio_on_connect(
    on_audio_connected: impl Fn() + Send + Sync + 'static,
) -> (Arc<FakeBluezBus>, Arc<Mutex<Vec<String>>>) {
    let bus = FakeBluezBus::with_snapshot(headset_objects(false, false, false));
    let adding = bus.clone();
    let connected_paths = Arc::new(Mutex::new(Vec::new()));
    let record = connected_paths.clone();
    let on_audio_connected = Arc::new(on_audio_connected);
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
            let on_audio_connected = on_audio_connected.clone();
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
                on_audio_connected();
                Ok(())
            }
        }));
    });
    (bus, connected_paths)
}

#[tokio::test(start_paused = true)]
async fn acquire_puts_the_default_output_back_when_the_headset_sink_takes_over_later() {
    let pactl = SinkPactl::new(Some(SPEAKER), false);
    let late = pactl.clone();
    let (bus, connected_paths) = bus_that_brings_up_audio_on_connect(move || {
        let late = late.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(3)).await;
            late.switch_to(HEADSET_SINK);
        });
    });
    let transport = BluezControlTransport::with_bus(bus);
    transport.await_initialization().await;
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), pactl.clone());

    audio.acquire(TARGET_ADDRESS).await.expect("acquire");
    assert_eq!(
        *connected_paths.lock().expect("record"),
        vec![TARGET_PATH.to_string()]
    );
    assert_eq!(pactl.default_sink().as_deref(), Some(SPEAKER));
    assert!(pactl.set_calls().is_empty());

    tokio::time::sleep(Duration::from_secs(6)).await;
    assert_eq!(pactl.default_sink().as_deref(), Some(SPEAKER));
    assert_eq!(
        pactl.set_calls(),
        vec![vec!["set-default-sink".to_string(), SPEAKER.to_string()]]
    );
}

#[tokio::test(start_paused = true)]
async fn acquire_leaves_the_default_output_alone_when_the_headset_sink_never_takes_over() {
    let pactl = SinkPactl::new(Some(SPEAKER), false);
    let (bus, _) = bus_that_brings_up_audio_on_connect(|| {});
    let transport = BluezControlTransport::with_bus(bus);
    transport.await_initialization().await;
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), pactl.clone());

    audio.acquire(TARGET_ADDRESS).await.expect("acquire");
    tokio::time::sleep(Duration::from_secs(30)).await;

    assert_eq!(pactl.default_sink().as_deref(), Some(SPEAKER));
    assert!(pactl.set_calls().is_empty());
}

#[tokio::test(start_paused = true)]
async fn acquire_connects_even_when_pactl_or_the_default_sink_is_unavailable() {
    for pactl in [SinkPactl::new(Some(SPEAKER), true), SinkPactl::new(None, false)] {
        let (bus, connected_paths) = bus_that_brings_up_audio_on_connect(|| {});
        let transport = BluezControlTransport::with_bus(bus);
        transport.await_initialization().await;
        let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), pactl.clone());

        audio.acquire(TARGET_ADDRESS).await.expect("acquire");
        assert_eq!(
            *connected_paths.lock().expect("record"),
            vec![TARGET_PATH.to_string()]
        );
        tokio::time::sleep(Duration::from_secs(30)).await;
        assert!(pactl.set_calls().is_empty());
    }
}

#[tokio::test(start_paused = true)]
async fn a_failed_bluez_acquire_is_a_platform_refusal_and_still_restores_the_default() {
    let bus = FakeBluezBus::with_snapshot(objects(&[(ADAPTER_PATH, adapter(true))]));
    let transport = BluezControlTransport::with_bus(bus);
    transport.await_initialization().await;
    let pactl = SinkPactl::new(Some(SPEAKER), false);
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), pactl.clone());

    let err = audio
        .acquire(TARGET_ADDRESS)
        .await
        .expect_err("unpaired headset fails");
    assert!(matches!(err, YieldRefusal::PlatformUnavailable { .. }));

    pactl.switch_to(HEADSET_SINK);
    tokio::time::sleep(Duration::from_secs(1)).await;
    assert_eq!(pactl.default_sink().as_deref(), Some(SPEAKER));
}

#[tokio::test(start_paused = true)]
async fn a_holder_that_cannot_read_its_bluez_state_reports_unreadable_instead_of_disconnected() {
    let mut snapshot = headset_objects(true, true, false);
    snapshot
        .get_mut(TARGET_PATH)
        .and_then(|node| node.remove(CLASSIC_BEARER));
    snapshot
        .get_mut(TARGET_PATH)
        .and_then(|node| node.remove(LE_BEARER));
    let transport = BluezControlTransport::with_bus(FakeBluezBus::with_snapshot(snapshot));
    transport.await_initialization().await;
    let audio = LinuxLocalAudio::with_pactl(transport.audio_profiles(), FakePactl::new(vec![]));

    assert!(matches!(
        audio.is_connected(TARGET_ADDRESS).await,
        Err(YieldRefusal::AudioStateUnreadable { .. })
    ));
}

#[tokio::test]
async fn an_unreadable_pactl_refuses_the_yield_with_a_state_code_not_a_sentence() {
    let transport = BluezControlTransport::with_bus(FakeBluezBus::with_snapshot(headset_objects(
        true, false, true,
    )));
    transport.await_initialization().await;
    let audio = LinuxLocalAudio::with_pactl(
        transport.audio_profiles(),
        FakePactl::new(vec![(&["list", "cards"], Err("pactl unavailable"))]),
    );

    assert_eq!(
        audio.yield_audio(TARGET_ADDRESS).await,
        Err(YieldRefusal::AudioStateUnreadable {
            detail: "pactl unavailable".to_string()
        })
    );
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
    assert_eq!(audio.is_connected(TARGET_ADDRESS).await, Ok(false));
    assert!(!audio.is_media_connected(TARGET_ADDRESS).await);
    assert!(matches!(
        audio.yield_audio(TARGET_ADDRESS).await,
        Err(YieldRefusal::PlatformUnavailable { .. })
    ));
    assert!(matches!(
        audio.acquire(TARGET_ADDRESS).await,
        Err(YieldRefusal::PlatformUnavailable { .. })
    ));
}
