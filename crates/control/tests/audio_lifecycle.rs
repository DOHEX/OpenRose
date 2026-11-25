mod support;

use openrose_control::ControlErrorCode;
use openrose_control::audio::{AudioProfile, RawAudioDevice};
use openrose_control::scan::Advertisement;
use openrose_control::snapshot::{ConnectionState, HostPlatform};
use openrose_control::transport::{AudioProfileEvidence, TransportFacts};
use openrose_headset::{FirmwareVersion, HeadsetCommand, HeadsetId, ModelProfile, NoiseMode};
use std::sync::Arc;
use std::time::Duration;
use support::scripted::{A, B, add_i5, id, simulated};
use support::{Harness, Options, available, elapse, settle};
use tokio::time::Instant;

const BATTERY_QUERY: &str = "08ee00000001010a0002";
const NOISE_QUERY: &str = "08ee00000006020a0008";
const NEXT_INIT_QUERY: &str = "08ee00000002010a0003";
const NORMAL_MODE_WRITE: &str = "08ee00000006820e00000100008d";
const NORMAL_MODE_REPLY: &str = "09ff00000106020e000001000020";
const BATTERY_REPLY: &str = "09ff00000101011100000164620000ffe2";
const NOISE_REPLY: &str = "09ff00000106020e000000010020";

fn normal() -> HeadsetCommand {
    HeadsetCommand::NoiseMode(NoiseMode::Normal)
}

fn is_connected(snapshot: &openrose_control::snapshot::ControlSnapshot) -> bool {
    matches!(snapshot.connection, ConnectionState::Connected { .. })
}

fn audio_i5(platform: HostPlatform) -> Harness {
    let mut options = Options::i5();
    options.platform = available().on(platform);
    Harness::new(options)
}

#[tokio::test(start_paused = true)]
async fn a_manual_retry_while_the_same_i5_has_audio_closes_its_new_link_on_audio_disconnect() {
    for platform in [HostPlatform::Android, HostPlatform::Linux] {
        let harness = audio_i5(platform);
        harness.transport.set_audio_connected(Some(harness.id()));
        settle().await;
        elapse(100).await;
        assert!(is_connected(&harness.snapshot()), "{platform:?}");

        harness.runtime.disconnect().await;
        harness
            .runtime
            .connect(harness.id())
            .await
            .expect("manual retry");
        assert!(harness.headset.link_open());

        harness.transport.set_audio_connected(None);
        settle().await;
        assert_eq!(
            ConnectionState::Disconnected,
            harness.snapshot().connection,
            "{platform:?}"
        );
        assert!(!harness.headset.link_open(), "{platform:?}");
    }
}

#[tokio::test(start_paused = true)]
async fn losing_audio_on_one_i5_keeps_the_other_manually_selected_i5_connected() {
    let (transport, runtime, _) = simulated(available().on(HostPlatform::Android));
    add_i5(&transport, A);
    let silent = add_i5(&transport, B);
    settle().await;
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(100).await;

    runtime.connect(id(B)).await.expect("manual selection");
    transport.set_audio_connected(None);
    settle().await;

    assert_eq!(
        Some(id(B)),
        runtime.snapshot().ready_session().map(|s| s.headset_id)
    );
    assert!(silent.link_open());
}

#[tokio::test(start_paused = true)]
async fn system_audio_connects_the_control_link_and_audio_disconnect_releases_it() {
    let harness = audio_i5(HostPlatform::Unknown);
    assert_eq!(ConnectionState::Disconnected, harness.snapshot().connection);

    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    elapse(100).await;
    let snapshot = harness.snapshot();
    let ConnectionState::Connected {
        headset_id,
        profile,
        ..
    } = snapshot.connection
    else {
        panic!("the system audio should have connected the control link");
    };
    assert_eq!(harness.id(), headset_id);
    assert_eq!(openrose_headset::ModelProfile::I5Zt, profile);
    assert_eq!(Some(harness.id()), snapshot.headset.map(|h| h.headset_id));

    harness.transport.set_audio_connected(None);
    settle().await;
    let snapshot = harness.snapshot();
    assert_eq!(ConnectionState::Disconnected, snapshot.connection);
    assert!(snapshot.headset.is_none());
    assert!(!harness.headset.link_open());
}

#[tokio::test(start_paused = true)]
async fn a_manual_connection_works_without_system_audio() {
    let harness = audio_i5(HostPlatform::Unknown);
    harness.runtime.connect(harness.id()).await.expect("manual");
    assert!(is_connected(&harness.snapshot()));

    harness.transport.set_audio_connected(None);
    settle().await;
    assert!(is_connected(&harness.snapshot()));
    assert!(harness.headset.link_open());

    assert!(harness.runtime.disconnect().await);
    assert_eq!(ConnectionState::Disconnected, harness.snapshot().connection);
    assert!(!harness.headset.link_open());
}

#[tokio::test(start_paused = true)]
async fn a_non_rose_audio_device_never_triggers_a_control_connection() {
    let harness = audio_i5(HostPlatform::Unknown);
    let car = "11:22:33:44:55:66";
    harness
        .transport
        .audio()
        .pair(RawAudioDevice::new(Some("Car Kit Audio"), car));
    settle().await;
    harness
        .transport
        .set_audio_connected(Some(HeadsetId::new(car)));
    settle().await;
    elapse(100).await;

    let snapshot = harness.snapshot();
    assert_eq!(None, snapshot.audio_headset_id);
    assert_eq!(ConnectionState::Disconnected, snapshot.connection);
    assert_eq!(0, harness.headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn a_manual_disconnect_is_not_undone_while_the_same_audio_remains() {
    let harness = audio_i5(HostPlatform::Unknown);
    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    elapse(100).await;
    assert!(is_connected(&harness.snapshot()));

    harness.runtime.disconnect().await;
    settle().await;
    elapse(100).await;
    assert_eq!(ConnectionState::Disconnected, harness.snapshot().connection);
    assert_eq!(1, harness.headset.connections_opened());
}

#[tokio::test(start_paused = true)]
async fn another_audio_device_connecting_does_not_end_an_active_rose_session() {
    let harness = audio_i5(HostPlatform::Unknown);
    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    elapse(100).await;
    assert!(is_connected(&harness.snapshot()));

    let car = "11:22:33:44:55:66";
    harness
        .transport
        .audio()
        .pair(RawAudioDevice::new(Some("Car Kit Audio"), car));
    harness
        .transport
        .audio()
        .set_profile_connected(AudioProfile::A2dp, car, true);
    settle().await;
    let snapshot = harness.snapshot();
    assert!(is_connected(&snapshot));
    assert_eq!(Some(harness.id()), snapshot.audio_headset_id);
}

#[tokio::test(start_paused = true)]
async fn an_audio_change_to_another_headset_moves_the_audio_bound_session() {
    let (transport, runtime, _) = simulated(available());
    let first = add_i5(&transport, A);
    let second = add_i5(&transport, B);
    settle().await;
    transport.set_audio_connected(Some(id(A)));
    settle().await;
    elapse(100).await;
    assert!(first.link_open());

    transport.set_audio_connected(Some(id(B)));
    settle().await;
    elapse(100).await;
    assert!(!first.link_open());
    assert!(second.link_open());
    assert_eq!(
        Some(id(B)),
        runtime.snapshot().ready_session().map(|s| s.headset_id)
    );
}

#[tokio::test(start_paused = true)]
async fn disconnect_during_early_readback_rejects_the_old_request_after_the_same_i5_reconnects() {
    let harness = Harness::new(Options::i5().paced().desktop());
    harness.headset.ignore_key("0602");
    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    elapse(110).await;
    assert!(
        harness
            .headset
            .written_hex()
            .contains(&NOISE_QUERY.to_string())
    );

    assert!(harness.inject_hex(NOISE_REPLY));
    settle().await;
    let reference = harness
        .snapshot()
        .noise_mode_session()
        .expect("early noise mode admission");
    assert!(harness.snapshot().ready_session().is_none());

    let pending = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        let session = reference.session_id;
        async move { runtime.set(normal(), Some(session)).await }
    });
    settle().await;
    elapse(250).await;
    let writes = harness.headset.written_hex();
    assert!(writes.contains(&NORMAL_MODE_WRITE.to_string()));
    assert_eq!(
        2,
        writes.iter().filter(|write| *write == NOISE_QUERY).count()
    );
    assert!(harness.snapshot().ready_session().is_none());

    harness.transport.set_audio_connected(None);
    settle().await;
    assert_eq!(
        ControlErrorCode::Disconnected,
        pending.await.unwrap().expect_err("old request").code
    );
    assert!(harness.snapshot().noise_mode_session().is_none());

    harness.headset.answer_key("0602");
    let before = harness.headset.written_hex().len();
    harness
        .transport
        .set_audio_connected(Some(reference.headset_id.clone()));
    settle().await;
    elapse(1_500).await;
    let replacement = harness.snapshot().ready_session().expect("fresh session");
    assert!(replacement.session_id > reference.session_id);

    let state = harness.snapshot();
    assert!(
        !harness
            .headset
            .inject_late(1, openrose_headset::decode_hex(NORMAL_MODE_REPLY).unwrap())
    );
    settle().await;
    assert_eq!(state, harness.snapshot());
    assert_eq!(
        ControlErrorCode::Disconnected,
        harness
            .runtime
            .set(normal(), Some(reference.session_id))
            .await
            .expect_err("stale request")
            .code
    );
    assert_eq!(
        support::I5_INIT_WRITES,
        harness.headset.written_hex().len() - before
    );
}

#[tokio::test(start_paused = true)]
async fn desktop_i5_gives_the_next_write_slot_to_a_noise_command_queued_during_the_gap() {
    let harness = Harness::new(Options::i5().paced().desktop());
    harness.headset.write_duration(Duration::from_millis(20));
    harness.headset.ignore_key("0101");
    harness.headset.ignore_key("0602");
    let start = Instant::now();
    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    let at = |index: usize| harness.headset.writes()[index].at - start;
    assert_eq!(vec![BATTERY_QUERY], harness.headset.written_hex());

    elapse(70).await;
    assert!(harness.inject_hex(BATTERY_REPLY));
    elapse(50).await;
    assert_eq!(
        vec![BATTERY_QUERY, NOISE_QUERY],
        harness.headset.written_hex()
    );
    assert_eq!(
        Duration::from_millis(120),
        at(1),
        "battery write completed at 20 ms"
    );

    elapse(50).await;
    assert!(harness.inject_hex(NOISE_REPLY));
    settle().await;
    let reference = harness
        .snapshot()
        .noise_mode_session()
        .expect("the confirmed mode admits the early change");
    elapse(20).await;
    let change = tokio::spawn({
        let runtime = Arc::clone(&harness.runtime);
        async move { runtime.set(normal(), Some(reference.session_id)).await }
    });
    elapse(50).await;
    assert_eq!(
        vec![BATTERY_QUERY, NOISE_QUERY, NORMAL_MODE_WRITE],
        harness.headset.written_hex()
    );
    assert_eq!(
        Duration::from_millis(240),
        at(2),
        "the mode change takes the slot reserved by the 140 ms write completion"
    );

    assert!(harness.inject_hex(NORMAL_MODE_REPLY));
    elapse(120).await;
    assert_eq!(NOISE_QUERY, harness.headset.written_hex()[3]);
    assert_eq!(
        Duration::from_millis(360),
        at(3),
        "the readback also respects the setting write's completion"
    );

    elapse(150).await;
    assert!(
        !change.is_finished(),
        "an unsolicited report cannot confirm the readback"
    );
    assert!(harness.inject_hex(NORMAL_MODE_REPLY));
    settle().await;
    change.await.unwrap().expect("confirmed by the readback");
    assert_eq!(NEXT_INIT_QUERY, harness.headset.written_hex()[4]);
    assert_eq!(
        Duration::from_millis(510),
        at(4),
        "the full gap elapsed while awaiting the readback"
    );
}

#[tokio::test(start_paused = true)]
async fn android_i5_keeps_its_existing_pause_after_the_battery_reply() {
    let mut options = Options::i5().paced();
    options.platform = available().on(HostPlatform::Android);
    let harness = Harness::new(options);
    harness.headset.write_duration(Duration::from_millis(20));
    harness.headset.ignore_key("0101");
    let start = Instant::now();
    harness.transport.set_audio_connected(Some(harness.id()));
    settle().await;
    assert_eq!(vec![BATTERY_QUERY], harness.headset.written_hex());

    elapse(70).await;
    assert!(harness.inject_hex(BATTERY_REPLY));
    settle().await;
    elapse(100).await;
    assert_eq!(NOISE_QUERY, harness.headset.written_hex()[1]);
    assert_eq!(
        Duration::from_millis(170),
        harness.headset.writes()[1].at - start
    );
}

#[tokio::test(start_paused = true)]
async fn losing_classic_audio_closes_the_i7_control_connection_opened_by_its_ble_address() {
    const BLE: &str = "11:22:33:44:55:66";
    const CLASSIC: &str = "A1:B2:C3:D4:E5:F6";
    let advertised = || {
        let mut payload = vec![0u8; 15];
        payload[0] = 0x29;
        payload[1] = 0x0f;
        payload[5] = 1;
        payload[9..15].copy_from_slice(&[0xa1, 0xb2, 0xc3, 0xd4, 0xe5, 0xf6]);
        Advertisement {
            manufacturer_data: [(0xe5b8, payload)].into(),
            ..Advertisement::new(id(BLE))
        }
    };
    for audio_first in [true, false] {
        let (transport, runtime, _) = simulated(available());
        let headset = transport
            .add_headset(
                id(BLE),
                ModelProfile::I7RsV2,
                FirmwareVersion::new(vec![1, 2, 5], "125"),
            )
            .expect("simulated i7");
        headset.set_connection_facts(TransportFacts {
            spp_available: false,
            audio_profile: AudioProfileEvidence::Classic,
        });
        transport.audio().unpair(BLE);
        transport
            .audio()
            .pair(RawAudioDevice::new(Some("EarFree i7"), CLASSIC));
        settle().await;
        runtime.start_scan().await.expect("scan");
        transport.scan().advertise(advertised());
        settle().await;

        if !audio_first {
            runtime.connect(id(BLE)).await.expect("connected");
        }
        transport.set_audio_connected(Some(id(CLASSIC)));
        settle().await;
        if audio_first {
            runtime.connect(id(BLE)).await.expect("connected");
        }
        assert!(runtime.snapshot().headset_has_system_audio());
        assert!(headset.link_open());

        transport.set_audio_connected(None);
        settle().await;
        assert_eq!(
            ConnectionState::Disconnected,
            runtime.snapshot().connection,
            "audio first: {audio_first}"
        );
        assert!(!headset.link_open(), "audio first: {audio_first}");
    }
}
