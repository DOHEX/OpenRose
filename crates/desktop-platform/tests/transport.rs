mod support;

use openrose_control::audio::{AudioProfile, RawAudioEvent};
use openrose_control::snapshot::{TransportAvailability, TransportUnavailableReason};
use openrose_control::transport::{ControlTransport, TransportError};
use openrose_desktop_platform::bluetooth::linux::bus::{
    ADAPTER, AUDIO_SINK_UUID, BluezError, BluezInterfaces, BluezObjects, BluezSignal, BluezValue,
    CLASSIC_BEARER, DEVICE, LE_BEARER, device_path,
};
use openrose_desktop_platform::bluetooth::linux::transport::{
    BluezControlTransport, connected_paths, has_connected_bearer,
};
use openrose_headset::HeadsetId;
use std::time::Duration;
use support::*;
use tokio::time::timeout;

const HCI0_DEVICE: &str = "/org/bluez/hci0/dev_00_11_22_33_44_55";
const HCI1_DEVICE: &str = "/org/bluez/hci1/dev_00_11_22_33_44_55";

fn device(connected: bool) -> BluezInterfaces {
    interfaces(&[(
        DEVICE,
        props(&[
            ("Address", text(ADDRESS)),
            ("Name", text("EarFree i5")),
            ("Paired", flag(true)),
            ("Connected", flag(connected)),
        ]),
    )])
}

fn with_bearers(mut node: BluezInterfaces, classic: bool, le: bool) -> BluezInterfaces {
    node.insert(CLASSIC_BEARER.to_string(), props(&[("Connected", flag(classic))]));
    node.insert(LE_BEARER.to_string(), props(&[("Connected", flag(le))]));
    node
}

fn two_adapters(hci0: BluezInterfaces, hci1: BluezInterfaces) -> BluezObjects {
    objects(&[
        ("/org/bluez/hci0", adapter(true)),
        ("/org/bluez/hci1", adapter(true)),
        (HCI0_DEVICE, hci0),
        (HCI1_DEVICE, hci1),
    ])
}

fn with_media_on(mut snapshot: BluezObjects, device: &str) -> BluezObjects {
    let (path, interfaces) = media_transport(device);
    snapshot.insert(path, interfaces);
    snapshot
}

#[test]
fn device_selection_with_several_adapters_prefers_audio_then_bearers_then_scan_adapter() {
    let idle = two_adapters(device(false), device(false));
    assert_eq!(device_path(&idle, ADDRESS, false, None).as_deref(), Some(HCI0_DEVICE));
    assert_eq!(
        device_path(&idle, ADDRESS, false, Some("/org/bluez/hci1")).as_deref(),
        Some(HCI1_DEVICE)
    );

    let media = with_media_on(two_adapters(device(false), device(false)), HCI1_DEVICE);
    assert_eq!(device_path(&media, ADDRESS, false, None).as_deref(), Some(HCI1_DEVICE));

    let classic = two_adapters(
        with_bearers(device(false), false, true),
        with_bearers(device(false), true, false),
    );
    assert_eq!(device_path(&classic, ADDRESS, false, None).as_deref(), Some(HCI1_DEVICE));

    let le = two_adapters(
        with_bearers(device(false), false, false),
        with_bearers(device(false), false, true),
    );
    assert_eq!(device_path(&le, ADDRESS, false, None).as_deref(), Some(HCI1_DEVICE));

    let aggregate = two_adapters(device(false), device(true));
    assert_eq!(device_path(&aggregate, ADDRESS, false, None).as_deref(), Some(HCI1_DEVICE));
}

#[test]
fn device_selection_ignores_powered_off_adapters_and_unpaired_copies_when_pairing_is_required() {
    let mut snapshot = two_adapters(device(true), device(false));
    snapshot.insert("/org/bluez/hci0".to_string(), adapter(false));
    assert_eq!(device_path(&snapshot, ADDRESS, false, None).as_deref(), Some(HCI1_DEVICE));

    let mut unpaired = two_adapters(device(false), device(false));
    unpaired
        .get_mut(HCI0_DEVICE)
        .and_then(|node| node.get_mut(DEVICE))
        .expect("device")
        .insert("Paired".to_string(), flag(false));
    assert_eq!(device_path(&unpaired, ADDRESS, true, None).as_deref(), Some(HCI1_DEVICE));
    assert_eq!(device_path(&unpaired, "AA:AA:AA:AA:AA:AA", false, None), None);
}

#[test]
fn audio_connection_paths_are_collected_from_every_powered_adapter() {
    let snapshot = with_media_on(
        two_adapters(with_bearers(device(true), false, true), device(false)),
        HCI1_DEVICE,
    );
    assert_eq!(
        connected_paths(&snapshot, ADDRESS).expect("paths"),
        vec![HCI0_DEVICE.to_string(), HCI1_DEVICE.to_string()]
    );

    let mut one_off = snapshot.clone();
    one_off.insert("/org/bluez/hci1".to_string(), adapter(false));
    assert_eq!(
        connected_paths(&one_off, ADDRESS).expect("paths"),
        vec![HCI0_DEVICE.to_string()]
    );
}

#[test]
fn older_bluez_without_bearer_interfaces_needs_an_aggregate_reading_to_prove_disconnection() {
    let legacy_connected = objects(&[("/org/bluez/hci0", adapter(true)), (HCI0_DEVICE, device(true))]);
    assert_eq!(
        connected_paths(&legacy_connected, ADDRESS).expect("paths"),
        vec![HCI0_DEVICE.to_string()]
    );
    let legacy_idle = objects(&[("/org/bluez/hci0", adapter(true)), (HCI0_DEVICE, device(false))]);
    assert!(connected_paths(&legacy_idle, ADDRESS).expect("paths").is_empty());

    let mut unreadable = legacy_idle.clone();
    unreadable
        .get_mut(HCI0_DEVICE)
        .and_then(|node| node.get_mut(DEVICE))
        .expect("device")
        .remove("Connected");
    assert!(connected_paths(&unreadable, ADDRESS).is_err());

    let half = with_bearers(device(false), true, false);
    let mut only_classic = half.clone();
    only_classic.remove(LE_BEARER);
    let device_props = only_classic.get(DEVICE).expect("device").clone();
    assert_eq!(has_connected_bearer(&only_classic, &device_props), Ok(true));
    let mut missing_le_idle = device(false);
    missing_le_idle.insert(CLASSIC_BEARER.to_string(), props(&[("Connected", flag(false))]));
    let device_props = missing_le_idle.get(DEVICE).expect("device").clone();
    assert_eq!(has_connected_bearer(&missing_le_idle, &device_props), Ok(false));
}

#[tokio::test(start_paused = true)]
async fn is_connected_reports_an_error_instead_of_disconnected_when_bearers_are_unreadable() {
    let mut snapshot = objects(&[("/org/bluez/hci0", adapter(true)), (HCI0_DEVICE, device(false))]);
    snapshot
        .get_mut(HCI0_DEVICE)
        .and_then(|node| node.get_mut(DEVICE))
        .expect("device")
        .remove("Connected");
    let transport = BluezControlTransport::with_bus(FakeBluezBus::with_snapshot(snapshot));
    transport.await_initialization().await;
    assert!(transport.audio_profiles().is_connected(ADDRESS).await.is_err());
}

async fn availability_after_open(snapshot: impl FnOnce(&FakeBluezBus)) -> TransportAvailability {
    let bus = FakeBluezBus::new();
    snapshot(&bus);
    let transport = BluezControlTransport::with_bus(bus);
    transport.await_initialization().await;
    let availability = transport.availability().borrow().clone();
    availability
}

fn unavailable(reason: TransportUnavailableReason) -> TransportAvailability {
    TransportAvailability::Unavailable { reason }
}

#[tokio::test(start_paused = true)]
async fn adapter_state_is_reported_as_transport_availability() {
    assert_eq!(
        availability_after_open(|bus| bus.set_snapshot(objects(&[("/org/bluez/hci0", adapter(true))]))).await,
        TransportAvailability::Available
    );
    assert_eq!(
        availability_after_open(|bus| bus.set_snapshot(objects(&[("/org/bluez/hci0", adapter(false))]))).await,
        unavailable(TransportUnavailableReason::AdapterPoweredOff)
    );
    assert_eq!(
        availability_after_open(|bus| bus.set_snapshot(BluezObjects::new())).await,
        unavailable(TransportUnavailableReason::NoAdapter)
    );
}

#[tokio::test(start_paused = true)]
async fn missing_bluez_is_reported_as_service_unavailable() {
    let missing = availability_after_open(|bus| {
        bus.with(|inner| {
            inner.objects_failure = Some(BluezError::from_dbus_name(
                "org.freedesktop.DBus.Error.ServiceUnknown",
                "The name org.bluez was not provided by any .service files",
            ));
        });
    })
    .await;
    assert_eq!(missing, unavailable(TransportUnavailableReason::ServiceUnavailable));

    let transport = BluezControlTransport::with_opener(|| async {
        Err(BluezError::from_dbus_name(
            "org.freedesktop.DBus.Error.NameHasNoOwner",
            "no owner",
        ))
    });
    transport.await_initialization().await;
    assert_eq!(
        *transport.availability().borrow(),
        unavailable(TransportUnavailableReason::ServiceUnavailable)
    );
}

#[tokio::test(start_paused = true)]
async fn all_three_access_denied_errors_are_reported_as_access_denied() {
    for name in [
        "org.bluez.Error.NotAuthorized",
        "org.bluez.Error.NotPermitted",
        "org.freedesktop.DBus.Error.AccessDenied",
    ] {
        let denied = BluezError::from_dbus_name(name, "denied");
        assert!(denied.is_access_denied(), "{name}");
        let at_listing = availability_after_open(|bus| {
            bus.with(|inner| inner.objects_failure = Some(denied.clone()));
        })
        .await;
        assert_eq!(at_listing, unavailable(TransportUnavailableReason::AccessDenied), "{name}");

        let opener_error = denied.clone();
        let transport = BluezControlTransport::with_opener(move || async move { Err(opener_error) });
        transport.await_initialization().await;
        assert_eq!(
            *transport.availability().borrow(),
            unavailable(TransportUnavailableReason::AccessDenied),
            "{name}"
        );
    }
}

#[tokio::test(start_paused = true)]
async fn denied_discovery_marks_the_transport_unavailable_without_stopping_discovery() {
    for name in [
        "org.bluez.Error.NotAuthorized",
        "org.bluez.Error.NotPermitted",
        "org.freedesktop.DBus.Error.AccessDenied",
    ] {
        let bus = FakeBluezBus::with_snapshot(objects(&[("/org/bluez/hci0", adapter(true))]));
        bus.with(|inner| inner.discovery_failure = Some(BluezError::from_dbus_name(name, "denied")));
        let transport = BluezControlTransport::with_bus(bus.clone());
        transport.await_initialization().await;
        let error = match transport.start_scan().await {
            Ok(_) => panic!("scan must fail"),
            Err(error) => error,
        };
        assert!(matches!(error, TransportError::PermissionDenied(_)), "{name}");
        assert_eq!(
            *transport.availability().borrow(),
            unavailable(TransportUnavailableReason::AccessDenied),
            "{name}"
        );
        assert_eq!(bus.with(|inner| inner.scan_stops), 0);
    }
}

#[tokio::test(start_paused = true)]
async fn a_closed_transport_refuses_to_open_gatt() {
    let transport = BluezControlTransport::with_bus(FakeBluezBus::with_snapshot(two_adapters(
        device(true),
        device(false),
    )));
    transport.await_initialization().await;
    transport.close().await;
    assert!(transport.open_gatt(&HeadsetId::new(ADDRESS.to_string())).await.is_err());
}

#[tokio::test(start_paused = true)]
async fn control_connects_on_the_adapter_that_holds_the_headset() {
    let snapshot = two_adapters(device(false), device(true));
    let bus = FakeBluezBus::with_snapshot(snapshot);
    let transport = BluezControlTransport::with_bus(bus.clone());
    transport.await_initialization().await;
    let driver = transport
        .open_gatt(&HeadsetId::new(ADDRESS.to_string()))
        .await
        .expect("driver");
    driver.connect().await.expect("connect");
    assert_eq!(bus.with(|inner| inner.connect_paths.clone()), vec![HCI1_DEVICE.to_string()]);
}

#[tokio::test(start_paused = true)]
async fn scan_converts_manufacturer_data_and_releases_discovery() {
    let mut snapshot = objects(&[("/org/bluez/hci0", adapter(true)), (HCI0_DEVICE, device(false))]);
    snapshot
        .get_mut(HCI0_DEVICE)
        .and_then(|node| node.get_mut(DEVICE))
        .expect("device")
        .insert(
            "ManufacturerData".to_string(),
            BluezValue::ManufacturerData([(0x1234u16, vec![1, 2, 3])].into_iter().collect()),
        );
    let bus = FakeBluezBus::with_snapshot(snapshot);
    let transport = BluezControlTransport::with_bus(bus.clone());
    transport.await_initialization().await;
    let scan = transport.start_scan().await.expect("scan");
    let found = timeout(Duration::from_secs(1), scan.next_advertisement())
        .await
        .expect("in time")
        .expect("ok")
        .expect("advertisement");
    assert_eq!(found.headset_id, HeadsetId::new(ADDRESS.to_string()));
    assert_eq!(found.manufacturer_data.get(&0x1234), Some(&vec![1, 2, 3]));
    scan.stop().await;
    assert_eq!((bus.with(|inner| inner.scans), bus.with(|inner| inner.scan_stops)), (1, 1));
}

#[tokio::test(start_paused = true)]
async fn audio_facts_follow_media_transports_on_powered_adapters_only() {
    let mut snapshot = with_media_on(two_adapters(device(true), device(true)), HCI1_DEVICE);
    snapshot
        .get_mut(HCI1_DEVICE)
        .and_then(|node| node.get_mut(DEVICE))
        .expect("device")
        .insert("UUIDs".to_string(), strings(&[AUDIO_SINK_UUID]));
    let bus = FakeBluezBus::with_snapshot(snapshot.clone());
    let transport = BluezControlTransport::with_bus(bus.clone());
    transport.await_initialization().await;
    let facts = transport.audio_facts();

    let bonded = facts.bonded_devices().await.expect("bonded");
    assert_eq!(bonded.len(), 1);
    assert_eq!(bonded[0].address, ADDRESS);

    let target = vec![ADDRESS.to_string()];
    let live = facts.snapshot(&target).await;
    assert!(live.enabled);
    let a2dp = live.profiles[&AudioProfile::A2dp].as_ref().expect("a2dp");
    assert_eq!(a2dp.connected_addresses, vec![ADDRESS.to_string()]);

    snapshot.insert("/org/bluez/hci1".to_string(), adapter(false));
    bus.set_snapshot(snapshot);
    let off = facts.snapshot(&target).await;
    let a2dp = off.profiles[&AudioProfile::A2dp].as_ref().expect("a2dp");
    assert!(a2dp.connected_addresses.is_empty());
}

#[tokio::test(start_paused = true)]
async fn audio_events_report_adapter_power_and_media_connection_changes() {
    let idle = two_adapters(device(false), device(false));
    let bus = FakeBluezBus::with_snapshot(idle.clone());
    let transport = BluezControlTransport::with_bus(bus.clone());
    transport.await_initialization().await;
    let events = transport.audio_facts().subscribe().await;

    let connected = with_media_on(idle.clone(), HCI0_DEVICE);
    bus.set_snapshot(connected.clone());
    let (path, interfaces) = media_transport(HCI0_DEVICE);
    bus.emit(added(&path, interfaces));
    let mut saw_connected = false;
    for _ in 0..8 {
        let event = timeout(Duration::from_secs(1), events.next_event())
            .await
            .expect("in time")
            .expect("event");
        if let RawAudioEvent::ProfileChanged {
            profile: AudioProfile::A2dp,
            connected: true,
            ..
        } = event
        {
            saw_connected = true;
            break;
        }
    }
    assert!(saw_connected);

    let mut off = objects(&[("/org/bluez/hci0", adapter(false)), ("/org/bluez/hci1", adapter(false))]);
    off.extend(connected.into_iter().filter(|(path, _)| path.contains("dev_")));
    bus.set_snapshot(off);
    bus.emit(changed("/org/bluez/hci0", ADAPTER, &[("Powered", flag(false))]));
    let mut saw_off = false;
    for _ in 0..8 {
        let event = timeout(Duration::from_secs(1), events.next_event())
            .await
            .expect("in time")
            .expect("event");
        if event == RawAudioEvent::AdapterOff {
            saw_off = true;
            break;
        }
    }
    assert!(saw_off);
}

#[tokio::test(start_paused = true)]
async fn losing_the_bluez_owner_reports_the_adapter_off_to_audio_observers() {
    let bus = FakeBluezBus::with_snapshot(two_adapters(device(true), device(false)));
    let transport = BluezControlTransport::with_bus(bus.clone());
    transport.await_initialization().await;
    let events = transport.audio_facts().subscribe().await;
    bus.emit(BluezSignal::Owner { present: false });
    let event = timeout(Duration::from_secs(1), events.next_event())
        .await
        .expect("in time")
        .expect("event");
    assert_eq!(event, RawAudioEvent::AdapterOff);
}
