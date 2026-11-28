mod support;

use openrose_control::gatt::{GattDriver, GattEvent, GattFailureKind, GattSubscription};
use openrose_desktop_platform::bluetooth::linux::bus::{
    ADAPTER, BluezError, BluezSignal, BluezValue, CHARACTERISTIC, DEVICE, LE_BEARER, SERVICE,
    BluezBus,
};
use openrose_desktop_platform::bluetooth::linux::gatt::{BluezGattDriver, gatt_services_ready};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use support::*;
use tokio::time::{Instant, sleep, timeout};

fn driver(bus: &Arc<FakeBluezBus>) -> BluezGattDriver {
    let bus: Arc<dyn BluezBus> = bus.clone();
    BluezGattDriver::new(bus, DEVICE_PATH).expect("owner present")
}

async fn first_characteristic(driver: &BluezGattDriver) -> openrose_control::gatt::GattCharacteristic {
    driver
        .discover_services()
        .await
        .expect("services")
        .into_iter()
        .next()
        .expect("service")
        .characteristics
        .into_iter()
        .next()
        .expect("characteristic")
}

async fn next_event(driver: &BluezGattDriver) -> GattEvent {
    timeout(Duration::from_secs(1), driver.next_event())
        .await
        .expect("event in time")
        .expect("event")
}

fn disconnect_kind(event: GattEvent) -> GattFailureKind {
    match event {
        GattEvent::Disconnected(error) => error.kind,
        other => panic!("expected disconnect, got {other:?}"),
    }
}

#[tokio::test(start_paused = true)]
async fn native_error_types_remain_distinct() {
    let cases = [
        (BluezError::NotAuthorized("denied".into()), GattFailureKind::Permission),
        (BluezError::NotPermitted("denied".into()), GattFailureKind::Permission),
        (BluezError::AccessDenied("denied".into()), GattFailureKind::Permission),
        (BluezError::NotSupported("unsupported".into()), GattFailureKind::Unsupported),
        (BluezError::InvalidArguments("invalid".into()), GattFailureKind::InvalidValue),
        (BluezError::InProgress("busy".into()), GattFailureKind::Transient),
        (BluezError::NoReply("timeout".into()), GattFailureKind::Transient),
        (BluezError::NotConnected("gone".into()), GattFailureKind::Disconnected),
        (BluezError::Failed("unknown".into()), GattFailureKind::Unknown),
    ];
    for (native, expected) in cases {
        let bus = FakeBluezBus::new();
        bus.with(|inner| inner.connect_failure = Some(native));
        let error = driver(&bus).connect().await.expect_err("connect fails");
        assert_eq!(error.kind, expected);
    }
}

#[tokio::test(start_paused = true)]
async fn adapter_power_off_and_device_removal_end_the_connection() {
    let bus = FakeBluezBus::with_snapshot(gatt_objects());
    let driver = driver(&bus);
    bus.emit(changed(ADAPTER_PATH, ADAPTER, &[("Powered", flag(false))]));
    assert_eq!(disconnect_kind(next_event(&driver).await), GattFailureKind::Disconnected);
    bus.emit(removed(DEVICE_PATH, &[DEVICE]));
    assert_eq!(disconnect_kind(next_event(&driver).await), GattFailureKind::Disconnected);
    bus.emit(BluezSignal::Owner { present: false });
    assert_eq!(disconnect_kind(next_event(&driver).await), GattFailureKind::Disconnected);
}

#[tokio::test(start_paused = true)]
async fn losing_only_the_le_bearer_ends_the_control_link_with_classic_audio_still_connected() {
    let bus = FakeBluezBus::with_snapshot(dual_mode_gatt_objects(true));
    let driver = driver(&bus);
    let characteristic = first_characteristic(&driver).await;
    driver
        .configure_subscription(&characteristic, GattSubscription::Notify)
        .await
        .expect("subscribe");
    bus.emit(changed(
        "/org/bluez/hci1/dev_00_11_22_33_44_55",
        LE_BEARER,
        &[("Connected", flag(false))],
    ));
    assert!(timeout(Duration::from_millis(200), driver.next_event()).await.is_err());
    bus.set_snapshot(dual_mode_gatt_objects(false));
    bus.emit(changed(DEVICE_PATH, LE_BEARER, &[("Connected", flag(false))]));
    assert_eq!(disconnect_kind(next_event(&driver).await), GattFailureKind::Disconnected);
    driver.close().await.expect("close");
    assert_eq!(bus.notifications(), vec![format!("start:{CHAR_PATH}"), format!("stop:{CHAR_PATH}")]);
}

#[tokio::test(start_paused = true)]
async fn busy_audio_connect_still_allows_the_resolved_gatt_service_to_be_used() {
    let bus = FakeBluezBus::with_snapshot(gatt_objects());
    bus.with(|inner| inner.connect_failure = Some(BluezError::InProgress("In Progress".into())));
    let driver = driver(&bus);
    driver.connect().await.expect("connect");
    let characteristic = first_characteristic(&driver).await;
    driver
        .configure_subscription(&characteristic, GattSubscription::Notify)
        .await
        .expect("subscribe");
    assert_eq!(bus.notifications(), vec![format!("start:{CHAR_PATH}")]);
    driver.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn services_changed_invalidates_the_discovered_table() {
    let bus = FakeBluezBus::with_snapshot(gatt_objects());
    let driver = driver(&bus);
    driver.discover_services().await.expect("services");
    bus.emit(removed(SERVICE_PATH, &[SERVICE]));
    assert_eq!(next_event(&driver).await, GattEvent::ServicesChanged);
}

#[tokio::test(start_paused = true)]
async fn reconnect_with_classic_audio_waits_for_the_le_bearer_before_subscribing_to_cached_services() {
    let bus = FakeBluezBus::with_snapshot(dual_mode_gatt_objects(false));
    let watched = bus.clone();
    bus.with(|inner| {
        inner.on_start_notify = Some(hook(move |()| {
            let watched = watched.clone();
            async move {
                let le_connected = watched.with(|inner| {
                    inner.snapshot[DEVICE_PATH][LE_BEARER]["Connected"] == BluezValue::Bool(true)
                });
                if le_connected {
                    Ok(())
                } else {
                    Err(BluezError::NotConnected("Not connected".into()))
                }
            }
        }));
    });
    let connecting = bus.clone();
    bus.with(|inner| {
        inner.on_connect = Some(hook(move |_| {
            let connecting = connecting.clone();
            async move {
                if connecting.connect_bearers().last() == Some(&true) {
                    let later = connecting.clone();
                    tokio::spawn(async move {
                        sleep(Duration::from_millis(400)).await;
                        later.set_snapshot(dual_mode_gatt_objects(true));
                    });
                }
                Ok(())
            }
        }));
    });
    let driver = driver(&bus);
    driver.connect().await.expect("connect");
    let characteristic = first_characteristic(&driver).await;
    driver
        .configure_subscription(&characteristic, GattSubscription::Notify)
        .await
        .expect("subscribe");
    assert_eq!(bus.notifications(), vec![format!("start:{CHAR_PATH}")]);
    driver.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn le_connection_rejected_by_the_audio_profile_retries_when_bluez_can_accept_it() {
    let bus = FakeBluezBus::with_snapshot(dual_mode_gatt_objects(false));
    let started = Instant::now();
    let connecting = bus.clone();
    bus.with(|inner| {
        inner.on_connect = Some(hook(move |_| {
            let connecting = connecting.clone();
            async move {
                if started.elapsed() < Duration::from_millis(200) {
                    return Err(BluezError::InProgress("In Progress".into()));
                }
                connecting.set_snapshot(dual_mode_gatt_objects(true));
                Ok(())
            }
        }));
    });
    let driver = driver(&bus);
    driver.connect().await.expect("connect");
    driver.discover_services().await.expect("services");
    assert!(started.elapsed() < Duration::from_secs(1));
    assert!(bus.connect_bearers().iter().all(|le| *le));
    driver.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn busy_le_request_does_not_connect_again_when_the_system_completes_it() {
    let bus = FakeBluezBus::with_snapshot(dual_mode_gatt_objects(false));
    let connecting = bus.clone();
    bus.with(|inner| {
        inner.on_connect = Some(hook(move |_| {
            let connecting = connecting.clone();
            async move {
                let later = connecting.clone();
                tokio::spawn(async move {
                    sleep(Duration::from_millis(50)).await;
                    later.set_snapshot(dual_mode_gatt_objects(true));
                });
                Err(BluezError::InProgress("In Progress".into()))
            }
        }));
    });
    let driver = driver(&bus);
    driver.connect().await.expect("connect");
    driver.discover_services().await.expect("services");
    assert_eq!(bus.connect_bearers(), vec![true]);
    driver.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn classic_connection_retries_for_gatt_and_close_only_releases_the_notification_lease() {
    let bus = FakeBluezBus::with_snapshot(objects(&[(
        DEVICE_PATH,
        interfaces(&[(
            DEVICE,
            props(&[("Connected", flag(true)), ("ServicesResolved", flag(false))]),
        )]),
    )]));
    let connecting = bus.clone();
    bus.with(|inner| {
        inner.on_connect = Some(hook(move |attempt| {
            let connecting = connecting.clone();
            async move {
                if attempt == 2 {
                    connecting.set_snapshot(gatt_objects());
                }
                Ok(())
            }
        }));
    });
    let driver = driver(&bus);
    driver.connect().await.expect("connect");
    let characteristic = first_characteristic(&driver).await;
    assert_eq!(bus.connects(), 2);
    driver
        .set_notification_routing(&characteristic, true)
        .await
        .expect("routing");
    driver
        .configure_subscription(&characteristic, GattSubscription::Notify)
        .await
        .expect("subscribe");
    driver.close().await.expect("close");
    assert_eq!(bus.notifications(), vec![format!("start:{CHAR_PATH}"), format!("stop:{CHAR_PATH}")]);
    assert_eq!(bus.connects(), 2);
}

#[tokio::test(start_paused = true)]
async fn first_write_waits_for_the_gatt_client_after_bluez_accepts_a_notification_lease() {
    let bus = FakeBluezBus::with_snapshot(gatt_objects());
    let driver = driver(&bus);
    let characteristic = first_characteristic(&driver).await;
    driver
        .configure_subscription(&characteristic, GattSubscription::Notify)
        .await
        .expect("subscribe");
    let attached = Arc::new(AtomicBool::new(false));
    let flag_attached = attached.clone();
    tokio::spawn(async move {
        sleep(Duration::from_millis(400)).await;
        flag_attached.store(true, Ordering::SeqCst);
    });
    let delivered = Arc::new(std::sync::Mutex::new(Vec::new()));
    let (check, record) = (attached.clone(), delivered.clone());
    bus.with(|inner| {
        inner.on_write = Some(hook(move |value: Vec<u8>| {
            let (check, record) = (check.clone(), record.clone());
            async move {
                if !check.load(Ordering::SeqCst) {
                    return Err(BluezError::Failed("Not connected".into()));
                }
                record.lock().expect("record").push(value[0]);
                Ok(())
            }
        }));
    });
    driver.write(&characteristic, &[1], true).await.expect("first write");
    assert_eq!(*delivered.lock().expect("record"), vec![1]);
    attached.store(false, Ordering::SeqCst);
    assert!(driver.write(&characteristic, &[2], true).await.is_err());
    assert_eq!(*delivered.lock().expect("record"), vec![1]);
    driver.close().await.expect("close");
}

#[tokio::test(start_paused = true)]
async fn release_retries_while_the_owner_lives_and_completes_after_bluez_restarts() {
    let bus = FakeBluezBus::with_snapshot(gatt_objects());
    let driver = driver(&bus);
    let characteristic = first_characteristic(&driver).await;
    driver
        .configure_subscription(&characteristic, GattSubscription::Notify)
        .await
        .expect("subscribe");
    bus.with(|inner| {
        inner.on_stop_notify = Some(hook(|_| async { Err(BluezError::NoReply("timeout".into())) }));
    });
    assert!(driver.close().await.is_err());
    bus.set_owner(Some(":1.2"));
    driver.close().await.expect("close after restart");
    assert_eq!(bus.notifications(), vec![format!("start:{CHAR_PATH}"), format!("stop:{CHAR_PATH}")]);
}

#[tokio::test(start_paused = true)]
async fn close_after_owner_change_does_not_stop_notify_on_the_new_daemon() {
    let bus = FakeBluezBus::with_snapshot(gatt_objects());
    let driver = driver(&bus);
    let characteristic = first_characteristic(&driver).await;
    driver
        .configure_subscription(&characteristic, GattSubscription::Notify)
        .await
        .expect("subscribe");
    bus.set_owner(Some(":1.2"));
    driver.close().await.expect("close");
    assert_eq!(bus.notifications(), vec![format!("start:{CHAR_PATH}")]);
}

#[tokio::test(start_paused = true)]
async fn close_never_disconnects_the_device() {
    let bus = FakeBluezBus::with_snapshot(dual_mode_gatt_objects(true));
    let driver = driver(&bus);
    let characteristic = first_characteristic(&driver).await;
    driver
        .configure_subscription(&characteristic, GattSubscription::Notify)
        .await
        .expect("subscribe");
    driver.close().await.expect("close");
    assert!(bus.disconnects().is_empty());
    assert_eq!(bus.notifications(), vec![format!("start:{CHAR_PATH}"), format!("stop:{CHAR_PATH}")]);
}

#[tokio::test(start_paused = true)]
async fn notification_and_writes_keep_the_discovered_characteristic_identity() {
    let bus = FakeBluezBus::with_snapshot(gatt_objects());
    let driver = driver(&bus);
    driver.connect().await.expect("connect");
    let characteristic = first_characteristic(&driver).await;
    assert_eq!(driver.request_mtu(512).await.expect("mtu"), Some(100));
    driver
        .set_notification_routing(&characteristic, true)
        .await
        .expect("routing");
    bus.emit(changed(CHAR_PATH, CHARACTERISTIC, &[("Value", BluezValue::Bytes(vec![1, 2]))]));
    assert_eq!(
        next_event(&driver).await,
        GattEvent::Notification {
            instance_id: characteristic.instance_id,
            value: vec![1, 2],
        }
    );
    driver.write(&characteristic, &[3], true).await.expect("write");
    let writes = bus.with(|inner| inner.writes.clone());
    assert_eq!(writes, vec![(CHAR_PATH.to_string(), vec![3], true)]);
    driver.close().await.expect("close");
}

#[test]
fn services_are_ready_only_when_a_present_le_bearer_is_connected() {
    assert!(gatt_services_ready(&gatt_objects(), DEVICE_PATH));
    assert!(gatt_services_ready(&dual_mode_gatt_objects(true), DEVICE_PATH));
    assert!(!gatt_services_ready(&dual_mode_gatt_objects(false), DEVICE_PATH));
    let mut unresolved = gatt_objects();
    unresolved
        .get_mut(DEVICE_PATH)
        .and_then(|interfaces| interfaces.get_mut(DEVICE))
        .expect("device")
        .insert("ServicesResolved".to_string(), flag(false));
    assert!(!gatt_services_ready(&unresolved, DEVICE_PATH));
}
