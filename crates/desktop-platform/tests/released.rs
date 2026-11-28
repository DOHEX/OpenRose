mod support;

use openrose_desktop_platform::bluetooth::linux::bus::{
    ADAPTER, AUDIO_SINK_UUID, BluezBus, BluezInterfaces, BluezObjects, BluezSignal, CLASSIC_BEARER,
    DEVICE, LE_BEARER, MEDIA_TRANSPORT,
};
use openrose_desktop_platform::bluetooth::linux::released::ReleasedBluezLinks;
use openrose_desktop_platform::bluetooth::linux::transport::BluezControlTransport;
use std::sync::Arc;
use std::time::Duration;
use support::*;
use tokio::sync::Notify;
use tokio::time::sleep;

const TARGET_ADDRESS: &str = "AA:BB:CC:DD:EE:FF";
const TARGET: &str = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF";
const OTHER: &str = "/org/bluez/hci0/dev_00_11_22_33_44_55";

fn media() -> (String, BluezInterfaces) {
    (
        format!("{TARGET}/sep1/fd0"),
        interfaces(&[(
            MEDIA_TRANSPORT,
            props(&[("Device", text(TARGET)), ("UUID", text(AUDIO_SINK_UUID))]),
        )]),
    )
}

fn with_media(mut snapshot: BluezObjects) -> BluezObjects {
    let (path, interfaces) = media();
    snapshot.insert(path, interfaces);
    snapshot
}

fn links(le: bool, classic: bool) -> BluezObjects {
    objects(&[
        (ADAPTER_PATH, adapter(true)),
        (
            TARGET,
            interfaces(&[
                (DEVICE, props(&[("Paired", flag(true)), ("Address", text(TARGET_ADDRESS))])),
                (CLASSIC_BEARER, props(&[("Connected", flag(classic))])),
                (LE_BEARER, props(&[("Connected", flag(le))])),
            ]),
        ),
        (
            OTHER,
            interfaces(&[
                (DEVICE, props(&[("Paired", flag(true)), ("Address", text("00:11:22:33:44:55"))])),
                (CLASSIC_BEARER, props(&[("Connected", flag(false))])),
                (LE_BEARER, props(&[("Connected", flag(true))])),
            ]),
        ),
    ])
}

fn record_le_disconnects(bus: &Arc<FakeBluezBus>) -> Arc<std::sync::Mutex<Vec<String>>> {
    let dropped = Arc::new(std::sync::Mutex::new(Vec::new()));
    let sink = dropped.clone();
    let resetting = bus.clone();
    bus.with(|inner| {
        inner.on_le_disconnect = Some(hook(move |path: String| {
            let (sink, resetting) = (sink.clone(), resetting.clone());
            async move {
                sink.lock().expect("dropped").push(path);
                resetting.set_snapshot(links(false, false));
                Ok(())
            }
        }));
    });
    dropped
}

fn observe(guard: &Arc<ReleasedBluezLinks>, bus: &Arc<FakeBluezBus>) -> tokio::task::JoinHandle<()> {
    let (guard, bus) = (guard.clone(), bus.clone());
    tokio::spawn(async move { guard.observe(bus as Arc<dyn BluezBus>).await })
}

async fn settle() {
    sleep(Duration::from_millis(10)).await;
}

fn le_connected(path: &str) -> BluezSignal {
    changed(path, LE_BEARER, &[("Connected", flag(true))])
}

fn dropped_paths(dropped: &Arc<std::sync::Mutex<Vec<String>>>) -> Vec<String> {
    dropped.lock().expect("dropped").clone()
}

#[tokio::test(start_paused = true)]
async fn an_le_reconnection_after_the_release_receipt_is_dropped_only_on_the_released_path() {
    let bus = FakeBluezBus::new();
    let transport = BluezControlTransport::with_bus(bus.clone());
    let dropped = record_le_disconnects(&bus);
    bus.set_snapshot(with_media(links(true, true)));
    let releasing = bus.clone();
    bus.with(|inner| {
        inner.on_audio_disconnect = Some(hook(move |_| {
            let releasing = releasing.clone();
            async move {
                releasing.set_snapshot(links(false, false));
                Ok(())
            }
        }));
    });
    transport
        .audio_profiles()
        .release(TARGET_ADDRESS)
        .await
        .expect("release");
    sleep(Duration::from_millis(2_600)).await;
    bus.set_snapshot(links(true, false));
    bus.emit(le_connected(OTHER));
    bus.emit(le_connected(TARGET));
    settle().await;
    assert_eq!(dropped_paths(&dropped), vec![TARGET.to_string()]);
}

#[tokio::test(start_paused = true)]
async fn an_explicit_local_connection_waits_for_the_in_flight_le_disconnect_and_stops_further_releases() {
    let bus = FakeBluezBus::new();
    let transport = BluezControlTransport::with_bus(bus.clone());
    let disconnecting = Arc::new(Notify::new());
    let closed = Arc::new(Notify::new());
    let dropped = Arc::new(std::sync::Mutex::new(Vec::new()));
    let (sink, started, gate, resetting) =
        (dropped.clone(), disconnecting.clone(), closed.clone(), bus.clone());
    bus.with(|inner| {
        inner.on_le_disconnect = Some(hook(move |path: String| {
            let (sink, started, gate, resetting) =
                (sink.clone(), started.clone(), gate.clone(), resetting.clone());
            async move {
                sink.lock().expect("dropped").push(path);
                started.notify_one();
                gate.notified().await;
                resetting.set_snapshot(links(false, false));
                Ok(())
            }
        }));
    });
    bus.set_snapshot(links(false, true));
    let releasing = bus.clone();
    bus.with(|inner| {
        inner.on_audio_disconnect = Some(hook(move |_| {
            let releasing = releasing.clone();
            async move {
                releasing.set_snapshot(links(false, false));
                Ok(())
            }
        }));
    });
    let profiles = transport.audio_profiles();
    profiles.release(TARGET_ADDRESS).await.expect("release");
    bus.set_snapshot(links(true, false));
    bus.emit(le_connected(TARGET));
    disconnecting.notified().await;

    let connecting = bus.clone();
    let adding = bus.clone();
    bus.with(|inner| {
        inner.on_audio_connect = Some(hook(move |_| {
            let adding = adding.clone();
            async move {
                adding.with(|inner| {
                    let (path, interfaces) = media();
                    inner.snapshot.insert(path, interfaces);
                });
                Ok(())
            }
        }));
        inner.on_connect = Some(hook(move |_| {
            let connecting = connecting.clone();
            async move {
                connecting.set_snapshot(links(true, false));
                Ok(())
            }
        }));
    });
    let acquiring = {
        let profiles = transport.audio_profiles();
        tokio::spawn(async move { profiles.acquire(TARGET_ADDRESS).await })
    };
    settle().await;
    assert!(!acquiring.is_finished());
    closed.notify_one();
    acquiring.await.expect("join").expect("acquire");
    assert_eq!(
        bus.connect_bearers(),
        vec![true],
        "Local acquisition must see the result of the old LE disconnect"
    );
    bus.set_snapshot(links(true, false));
    bus.emit(le_connected(TARGET));
    settle().await;
    assert_eq!(dropped_paths(&dropped), vec![TARGET.to_string()]);
}

#[tokio::test(start_paused = true)]
async fn a_delayed_classic_and_media_snapshot_cannot_disarm_a_release_before_its_receipt() {
    let bus = FakeBluezBus::new();
    let guard = Arc::new(ReleasedBluezLinks::new());
    let dropped = record_le_disconnects(&bus);
    let _observer = observe(&guard, &bus);
    settle().await;
    bus.set_snapshot(with_media(links(true, true)));
    let confirming = bus.clone();
    guard
        .release(&*bus, &[TARGET.to_string()], || async move {
            confirming.set_snapshot(links(false, false));
            Ok(())
        })
        .await
        .expect("release");
    bus.emit(changed(TARGET, CLASSIC_BEARER, &[("Connected", flag(true))]));
    let (path, interfaces) = media();
    bus.emit(added(&path, interfaces));
    settle().await;
    sleep(Duration::from_millis(2_600)).await;
    bus.set_snapshot(links(true, false));
    bus.emit(le_connected(TARGET));
    settle().await;
    assert_eq!(dropped_paths(&dropped), vec![TARGET.to_string()]);
}

#[tokio::test(start_paused = true)]
async fn an_le_connection_between_the_last_confirmation_snapshot_and_enrollment_is_released() {
    let bus = FakeBluezBus::with_snapshot(links(false, false));
    let guard = ReleasedBluezLinks::new();
    let dropped = record_le_disconnects(&bus);
    let confirming = bus.clone();
    guard
        .release(&*bus, &[TARGET.to_string()], || async move {
            confirming.set_snapshot(links(true, false));
            Ok(())
        })
        .await
        .expect("release");
    assert_eq!(dropped_paths(&dropped), vec![TARGET.to_string()]);
}

#[tokio::test(start_paused = true)]
async fn classic_recovery_media_or_loss_of_the_device_adapter_or_owner_ends_the_release() {
    let without_target = {
        let mut snapshot = links(false, false);
        snapshot.remove(TARGET);
        snapshot
    };
    let (media_path, media_interfaces) = media();
    let cases: Vec<(BluezSignal, BluezObjects)> = vec![
        (
            changed(TARGET, CLASSIC_BEARER, &[("Connected", flag(true))]),
            links(true, true),
        ),
        (
            added(&media_path, media_interfaces),
            with_media(links(true, false)),
        ),
        (removed(TARGET, &[DEVICE]), without_target),
        (removed(ADAPTER_PATH, &[ADAPTER]), BluezObjects::new()),
        (
            changed(ADAPTER_PATH, ADAPTER, &[("Powered", flag(false))]),
            BluezObjects::new(),
        ),
        (BluezSignal::Owner { present: false }, BluezObjects::new()),
    ];
    for (signal, snapshot) in cases {
        let bus = FakeBluezBus::new();
        let guard = Arc::new(ReleasedBluezLinks::new());
        let observer = observe(&guard, &bus);
        settle().await;
        bus.set_snapshot(links(false, false));
        guard
            .release(&*bus, &[TARGET.to_string()], || async { Ok(()) })
            .await
            .expect("release");
        let dropped = record_le_disconnects(&bus);
        bus.set_snapshot(snapshot);
        bus.emit(signal.clone());
        settle().await;
        bus.set_snapshot(links(true, false));
        bus.emit(le_connected(TARGET));
        settle().await;
        assert!(
            dropped_paths(&dropped).is_empty(),
            "Unexpected LE disconnect after {signal:?}"
        );
        observer.abort();
    }
}
