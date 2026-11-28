#![allow(dead_code)]

use async_trait::async_trait;
use openrose_desktop_platform::bluetooth::linux::bus::{
    ADAPTER, AUDIO_SINK_UUID, BluezAudioBus, BluezBus, BluezError, BluezInterfaces, BluezObjects,
    BluezProperties, BluezSignal, BluezValue, CHARACTERISTIC, CLASSIC_BEARER, DEVICE, LE_BEARER,
    MEDIA_TRANSPORT, SERVICE,
};
use std::pin::Pin;
use std::sync::{Arc, Mutex};
use tokio::sync::broadcast;

pub const ADAPTER_PATH: &str = "/org/bluez/hci0";
pub const DEVICE_PATH: &str = "/org/bluez/hci0/dev_00_11_22_33_44_55";
pub const SERVICE_PATH: &str = "/org/bluez/hci0/dev_00_11_22_33_44_55/service0001";
pub const CHAR_PATH: &str = "/org/bluez/hci0/dev_00_11_22_33_44_55/service0001/char0002";
pub const ADDRESS: &str = "00:11:22:33:44:55";

pub type HookFuture = Pin<Box<dyn Future<Output = Result<(), BluezError>> + Send>>;
pub type Hook<A> = Arc<dyn Fn(A) -> HookFuture + Send + Sync>;

pub fn hook<A, F, Fut>(action: F) -> Hook<A>
where
    F: Fn(A) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<(), BluezError>> + Send + 'static,
{
    Arc::new(move |argument| Box::pin(action(argument)))
}

#[derive(Default)]
pub struct Inner {
    pub owner: Option<String>,
    pub snapshot: BluezObjects,
    pub connect_failure: Option<BluezError>,
    pub discovery_failure: Option<BluezError>,
    pub stop_discovery_failure: Option<BluezError>,
    pub objects_failure: Option<BluezError>,
    pub on_connect: Option<Hook<usize>>,
    pub on_write: Option<Hook<Vec<u8>>>,
    pub on_start_notify: Option<Hook<()>>,
    pub on_stop_notify: Option<Hook<String>>,
    pub on_start_discovery: Option<Hook<()>>,
    pub on_audio_connect: Option<Hook<String>>,
    pub on_audio_disconnect: Option<Hook<String>>,
    pub on_le_disconnect: Option<Hook<String>>,
    pub connect_bearers: Vec<bool>,
    pub connect_paths: Vec<String>,
    pub connects: usize,
    pub notifications: Vec<String>,
    pub disconnects: Vec<String>,
    pub writes: Vec<(String, Vec<u8>, bool)>,
    pub scans: usize,
    pub scan_stops: usize,
}

pub struct FakeBluezBus {
    inner: Mutex<Inner>,
    events: broadcast::Sender<BluezSignal>,
}

impl FakeBluezBus {
    pub fn new() -> Arc<Self> {
        let (events, _) = broadcast::channel(64);
        Arc::new(Self {
            inner: Mutex::new(Inner {
                owner: Some(":1.1".to_string()),
                ..Inner::default()
            }),
            events,
        })
    }

    pub fn with_snapshot(snapshot: BluezObjects) -> Arc<Self> {
        let bus = Self::new();
        bus.set_snapshot(snapshot);
        bus
    }

    pub fn with<R>(&self, action: impl FnOnce(&mut Inner) -> R) -> R {
        action(&mut self.inner.lock().expect("fake bus lock"))
    }

    pub fn set_snapshot(&self, snapshot: BluezObjects) {
        self.with(|inner| inner.snapshot = snapshot);
    }

    pub fn set_owner(&self, owner: Option<&str>) {
        self.with(|inner| inner.owner = owner.map(str::to_string));
    }

    pub fn emit(&self, signal: BluezSignal) {
        let _ = self.events.send(signal);
    }

    pub fn notifications(&self) -> Vec<String> {
        self.with(|inner| inner.notifications.clone())
    }

    pub fn disconnects(&self) -> Vec<String> {
        self.with(|inner| inner.disconnects.clone())
    }

    pub fn connects(&self) -> usize {
        self.with(|inner| inner.connects)
    }

    pub fn connect_bearers(&self) -> Vec<bool> {
        self.with(|inner| inner.connect_bearers.clone())
    }

    fn hook_of<A>(&self, pick: impl FnOnce(&Inner) -> Option<Hook<A>>) -> Option<Hook<A>> {
        self.with(|inner| pick(inner))
    }
}

async fn run<A>(hook: Option<Hook<A>>, argument: A) -> Result<(), BluezError> {
    match hook {
        Some(hook) => hook(argument).await,
        None => Ok(()),
    }
}

#[async_trait]
impl BluezAudioBus for FakeBluezBus {
    async fn connect_audio(&self, path: &str) -> Result<(), BluezError> {
        let hook = self.hook_of(|inner| inner.on_audio_connect.clone());
        run(hook, path.to_string()).await
    }

    async fn disconnect_audio(
        &self,
        path: &str,
        owner: &str,
        _interfaces: &BluezInterfaces,
    ) -> Result<(), BluezError> {
        assert!(self.owner().as_deref() == Some(owner) || owner == "org.bluez");
        self.with(|inner| inner.disconnects.push(format!("audio:{path}")));
        let hook = self.hook_of(|inner| inner.on_audio_disconnect.clone());
        run(hook, path.to_string()).await
    }

    async fn disconnect_le(&self, path: &str, owner: &str) -> Result<(), BluezError> {
        assert!(self.owner().as_deref() == Some(owner) || owner == "org.bluez");
        self.with(|inner| inner.disconnects.push(format!("le:{path}")));
        let hook = self.hook_of(|inner| inner.on_le_disconnect.clone());
        run(hook, path.to_string()).await
    }
}

#[async_trait]
impl BluezBus for FakeBluezBus {
    fn owner(&self) -> Option<String> {
        self.with(|inner| inner.owner.clone())
    }

    fn signals(&self) -> broadcast::Receiver<BluezSignal> {
        self.events.subscribe()
    }

    async fn objects(&self) -> Result<BluezObjects, BluezError> {
        self.with(|inner| match &inner.objects_failure {
            Some(error) => Err(error.clone()),
            None => Ok(inner.snapshot.clone()),
        })
    }

    async fn connect_gatt(&self, path: &str, use_le_bearer: bool) -> Result<(), BluezError> {
        let (attempt, failure, hook) = self.with(|inner| {
            inner.connect_bearers.push(use_le_bearer);
            inner.connect_paths.push(path.to_string());
            inner.connects += 1;
            (
                inner.connects,
                inner.connect_failure.clone(),
                inner.on_connect.clone(),
            )
        });
        if let Some(error) = failure {
            return Err(error);
        }
        run(hook, attempt).await
    }

    async fn start_notify(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        assert_eq!(self.owner().as_deref(), Some(owner));
        let hook = self.hook_of(|inner| inner.on_start_notify.clone());
        run(hook, ()).await?;
        self.with(|inner| inner.notifications.push(format!("start:{path}")));
        Ok(())
    }

    async fn stop_notify(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        assert_eq!(self.owner().as_deref(), Some(owner));
        self.with(|inner| inner.notifications.push(format!("stop:{path}")));
        let hook = self.hook_of(|inner| inner.on_stop_notify.clone());
        run(hook, path.to_string()).await
    }

    async fn write(&self, path: &str, value: &[u8], with_response: bool) -> Result<(), BluezError> {
        self.with(|inner| {
            inner
                .writes
                .push((path.to_string(), value.to_vec(), with_response))
        });
        let hook = self.hook_of(|inner| inner.on_write.clone());
        run(hook, value.to_vec()).await
    }

    async fn start_discovery(&self, _path: &str) -> Result<(), BluezError> {
        let (failure, hook) = self.with(|inner| {
            inner.scans += 1;
            (inner.discovery_failure.clone(), inner.on_start_discovery.clone())
        });
        if let Some(error) = failure {
            return Err(error);
        }
        run(hook, ()).await
    }

    async fn stop_discovery(&self, _path: &str) -> Result<(), BluezError> {
        self.with(|inner| {
            inner.scan_stops += 1;
            match &inner.stop_discovery_failure {
                Some(error) => Err(error.clone()),
                None => Ok(()),
            }
        })
    }

    async fn close(&self) {}
}

pub fn text(value: &str) -> BluezValue {
    BluezValue::String(value.to_string())
}

pub fn flag(value: bool) -> BluezValue {
    BluezValue::Bool(value)
}

pub fn strings(values: &[&str]) -> BluezValue {
    BluezValue::Strings(values.iter().map(|value| value.to_string()).collect())
}

pub fn props(items: &[(&str, BluezValue)]) -> BluezProperties {
    items
        .iter()
        .map(|(key, value)| (key.to_string(), value.clone()))
        .collect()
}

pub fn interfaces(items: &[(&str, BluezProperties)]) -> BluezInterfaces {
    items
        .iter()
        .map(|(name, properties)| (name.to_string(), properties.clone()))
        .collect()
}

pub fn objects(items: &[(&str, BluezInterfaces)]) -> BluezObjects {
    items
        .iter()
        .map(|(path, interfaces)| (path.to_string(), interfaces.clone()))
        .collect()
}

pub fn adapter(powered: bool) -> BluezInterfaces {
    interfaces(&[(ADAPTER, props(&[("Powered", flag(powered))]))])
}

pub fn changed(path: &str, interface: &str, values: &[(&str, BluezValue)]) -> BluezSignal {
    BluezSignal::Changed {
        path: path.to_string(),
        interface_name: interface.to_string(),
        values: values
            .iter()
            .map(|(key, value)| (key.to_string(), Some(value.clone())))
            .collect(),
    }
}

pub fn removed(path: &str, interfaces: &[&str]) -> BluezSignal {
    BluezSignal::Removed {
        path: path.to_string(),
        interfaces: interfaces.iter().map(|name| name.to_string()).collect(),
    }
}

pub fn added(path: &str, interfaces: BluezInterfaces) -> BluezSignal {
    BluezSignal::Added {
        path: path.to_string(),
        interfaces,
    }
}

pub fn gatt_objects() -> BluezObjects {
    objects(&[
        (
            DEVICE_PATH,
            interfaces(&[(
                DEVICE,
                props(&[("Connected", flag(true)), ("ServicesResolved", flag(true))]),
            )]),
        ),
        (
            SERVICE_PATH,
            interfaces(&[(
                SERVICE,
                props(&[("UUID", text("00001800-0000-1000-8000-00805f9b34fb"))]),
            )]),
        ),
        (
            CHAR_PATH,
            interfaces(&[(
                CHARACTERISTIC,
                props(&[
                    ("UUID", text("00002a00-0000-1000-8000-00805f9b34fb")),
                    ("Flags", strings(&["write", "notify"])),
                    ("MTU", BluezValue::U16(100)),
                ]),
            )]),
        ),
    ])
}

pub fn dual_mode_gatt_objects(le_connected: bool) -> BluezObjects {
    let mut snapshot = gatt_objects();
    let device = snapshot.get_mut(DEVICE_PATH).expect("device node");
    device.insert(
        CLASSIC_BEARER.to_string(),
        props(&[("Connected", flag(true))]),
    );
    device.insert(
        LE_BEARER.to_string(),
        props(&[("Connected", flag(le_connected))]),
    );
    snapshot
}

pub fn media_transport(device: &str) -> (String, BluezInterfaces) {
    (
        format!("{device}/sep1/fd0"),
        interfaces(&[(
            MEDIA_TRANSPORT,
            props(&[("Device", text(device)), ("UUID", text(AUDIO_SINK_UUID))]),
        )]),
    )
}
