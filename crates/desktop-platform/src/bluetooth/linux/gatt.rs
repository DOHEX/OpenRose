use super::bus::{
    ADAPTER, BluezBus, BluezError, BluezObjects, BluezSignal, BluezValue, CHARACTERISTIC,
    CLASSIC_BEARER, DEVICE, LE_BEARER, SERVICE,
};
use async_trait::async_trait;
use openrose_control::gatt::{
    GattCharacteristic, GattDriver, GattError, GattEvent, GattFailureKind, GattService,
    GattSubscription,
};
use std::collections::{BTreeSet, HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{Mutex as AsyncMutex, broadcast};

const FIRST_WRITE_RETRIES: usize = 24;
const BUSY_CONNECT_RETRIES: usize = 8;

struct DriverState {
    characteristics: HashMap<u64, String>,
    path_to_instance: HashMap<String, u64>,
    routed: HashSet<String>,
    subscribed: Vec<String>,
    discovered: bool,
    first_write: bool,
    closed: bool,
    next_instance_id: u64,
}

impl Default for DriverState {
    fn default() -> Self {
        Self {
            characteristics: HashMap::new(),
            path_to_instance: HashMap::new(),
            routed: HashSet::new(),
            subscribed: Vec::new(),
            discovered: false,
            first_write: true,
            closed: false,
            next_instance_id: 1,
        }
    }
}

pub struct BluezGattDriver {
    bus: Arc<dyn BluezBus>,
    device_path: String,
    owner: String,
    state: Mutex<DriverState>,
    events_rx: AsyncMutex<(broadcast::Receiver<BluezSignal>, VecDeque<GattEvent>)>,
}

impl BluezGattDriver {
    pub fn new(bus: Arc<dyn BluezBus>, device_path: impl Into<String>) -> Result<Self, GattError> {
        let owner = bus
            .owner()
            .ok_or_else(|| GattError::new(GattFailureKind::Disconnected, "BlueZ 服务不可用"))?;
        let rx = bus.signals();
        Ok(Self {
            bus,
            device_path: device_path.into(),
            owner,
            state: Mutex::new(DriverState::default()),
            events_rx: AsyncMutex::new((rx, VecDeque::new())),
        })
    }

    fn check_open(&self) -> Result<(), GattError> {
        if self.state.lock().expect("gatt state lock").closed {
            return Err(GattError::new(GattFailureKind::Disconnected, "GATT 已关闭"));
        }
        Ok(())
    }

    fn path_for(&self, characteristic: &GattCharacteristic) -> Result<String, GattError> {
        self.state
            .lock()
            .expect("gatt state lock")
            .characteristics
            .get(&characteristic.instance_id)
            .cloned()
            .ok_or_else(|| GattError::new(GattFailureKind::InvalidValue, "未知 GATT 特征实例"))
    }

    async fn release_subscription(
        &self,
        path: &str,
        recover_not_connected: bool,
    ) -> Result<(), GattError> {
        if self.bus.owner().as_deref() == Some(self.owner.as_str()) {
            self.bus
                .stop_notify(&self.owner, path)
                .await
                .map_err(|err| err.into_gatt_error(recover_not_connected))?;
        }
        let mut state = self.state.lock().expect("gatt state lock");
        state.subscribed.retain(|item| item != path);
        Ok(())
    }

    async fn wait_for_services(&self, timeout: Duration) -> Result<Option<BluezObjects>, GattError> {
        let result = tokio::time::timeout(timeout, async {
            loop {
                let objects = self
                    .bus
                    .objects()
                    .await
                    .map_err(|err| err.into_gatt_error(false))?;
                if gatt_services_ready(&objects, &self.device_path) {
                    return Ok::<_, GattError>(objects);
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        })
        .await;
        match result {
            Ok(Ok(objects)) => Ok(Some(objects)),
            Ok(Err(error)) => Err(error),
            Err(_) => Ok(None),
        }
    }

    fn process_signal(&self, signal: BluezSignal, queue: &mut VecDeque<GattEvent>) {
        if !affects_link(&signal, &self.device_path) {
            return;
        }
        match signal {
            BluezSignal::PrepareForSleep { sleeping } => {
                if sleeping {
                    queue.push_back(disconnected("电脑进入休眠"));
                }
            }
            BluezSignal::Owner { present } => {
                if !present || self.bus.owner().as_deref() != Some(self.owner.as_str()) {
                    queue.push_back(disconnected("BlueZ 服务已停止"));
                }
            }
            BluezSignal::Changed {
                path,
                interface_name,
                values,
            } => {
                let link_lost = match interface_name.as_str() {
                    DEVICE | LE_BEARER => {
                        values.get("Connected") == Some(&Some(BluezValue::Bool(false)))
                    }
                    ADAPTER => values.get("Powered") == Some(&Some(BluezValue::Bool(false))),
                    _ => false,
                };
                if link_lost {
                    queue.push_back(disconnected("蓝牙控制连接已断开"));
                }
                if interface_name == CHARACTERISTIC
                    && let Some(Some(BluezValue::Bytes(bytes))) = values.get("Value")
                {
                    let instance_id = {
                        let state = self.state.lock().expect("gatt state lock");
                        if state.routed.contains(&path) {
                            state.path_to_instance.get(&path).copied()
                        } else {
                            None
                        }
                    };
                    if let Some(instance_id) = instance_id {
                        queue.push_back(GattEvent::Notification {
                            instance_id,
                            value: bytes.clone(),
                        });
                    }
                }
                let services_invalidated = interface_name == DEVICE
                    && values.contains_key("ServicesResolved")
                    && values.get("ServicesResolved") != Some(&Some(BluezValue::Bool(true)));
                if services_invalidated && self.state.lock().expect("gatt state lock").discovered {
                    queue.push_back(GattEvent::ServicesChanged);
                }
            }
            BluezSignal::Removed { path, interfaces } => {
                let device_removed = path == self.device_path && interfaces.contains(DEVICE);
                let adapter_removed = self
                    .device_path
                    .strip_prefix(&path)
                    .is_some_and(|rest| rest.starts_with('/'))
                    && interfaces.contains(ADAPTER);
                if device_removed || adapter_removed {
                    queue.push_back(disconnected("蓝牙设备已移除"));
                } else if self.state.lock().expect("gatt state lock").discovered
                    && is_gatt_attribute(&self.device_path, &path, &interfaces)
                {
                    queue.push_back(GattEvent::ServicesChanged);
                }
            }
            BluezSignal::Added { path, interfaces } => {
                let keys: BTreeSet<String> = interfaces.keys().cloned().collect();
                if self.state.lock().expect("gatt state lock").discovered
                    && is_gatt_attribute(&self.device_path, &path, &keys)
                {
                    queue.push_back(GattEvent::ServicesChanged);
                }
            }
        }
    }
}

#[async_trait]
impl GattDriver for BluezGattDriver {
    async fn next_event(&self) -> Option<GattEvent> {
        let mut guard = self.events_rx.lock().await;
        let (rx, queue) = &mut *guard;
        loop {
            if let Some(event) = queue.pop_front() {
                return Some(event);
            }
            match rx.recv().await {
                Ok(signal) => self.process_signal(signal, queue),
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(broadcast::error::RecvError::Closed) => return None,
            }
        }
    }

    async fn connect(&self) -> Result<(), GattError> {
        self.check_open()?;
        request_gatt_connection(&*self.bus, &self.device_path)
            .await
            .map_err(|err| err.into_gatt_error(false))
    }

    async fn discover_services(&self) -> Result<Vec<GattService>, GattError> {
        self.check_open()?;
        let objects = match self.wait_for_services(Duration::from_millis(2_000)).await? {
            Some(objects) => objects,
            None => {
                request_gatt_connection(&*self.bus, &self.device_path)
                    .await
                    .map_err(|err| err.into_gatt_error(false))?;
                self.wait_for_services(Duration::from_millis(8_000))
                    .await?
                    .ok_or_else(|| {
                        GattError::new(GattFailureKind::Transient, "等待 BlueZ GATT 服务超时")
                    })?
            }
        };

        let device_prefix = format!("{}/", self.device_path);
        let mut services = Vec::new();
        let mut found_chars = HashMap::new();
        let mut path_to_instance = HashMap::new();
        let mut next_id = {
            let state = self.state.lock().expect("gatt state lock");
            state.next_instance_id
        };

        for (path, interfaces) in &objects {
            if !path.starts_with(&device_prefix) {
                continue;
            }
            let Some(service_props) = interfaces.get(SERVICE) else {
                continue;
            };
            let Some(uuid) = service_props.get("UUID").and_then(BluezValue::as_str) else {
                continue;
            };
            let char_prefix = format!("{path}/");
            let mut chars = Vec::new();
            for (char_path, char_interfaces) in &objects {
                if !char_path.starts_with(&char_prefix) {
                    continue;
                }
                let Some(props) = char_interfaces.get(CHARACTERISTIC) else {
                    continue;
                };
                let Some(char_uuid) = props.get("UUID").and_then(BluezValue::as_str) else {
                    continue;
                };
                let flags = props
                    .get("Flags")
                    .and_then(BluezValue::as_strings)
                    .unwrap_or(&[]);
                let has_flag = |target: &str| flags.iter().any(|flag| flag == target);
                let instance_id = next_id;
                next_id += 1;
                found_chars.insert(instance_id, char_path.clone());
                path_to_instance.insert(char_path.clone(), instance_id);
                chars.push(GattCharacteristic {
                    instance_id,
                    uuid: char_uuid.to_lowercase(),
                    can_write: has_flag("write"),
                    can_write_without_response: has_flag("write-without-response"),
                    can_notify: has_flag("notify"),
                    can_indicate: has_flag("indicate"),
                });
            }
            services.push(GattService {
                uuid: uuid.to_lowercase(),
                characteristics: chars,
            });
        }

        let mut state = self.state.lock().expect("gatt state lock");
        state.characteristics = found_chars;
        state.path_to_instance = path_to_instance;
        state.discovered = true;
        state.next_instance_id = next_id;
        Ok(services)
    }

    async fn request_mtu(&self, _preferred: u16) -> Result<Option<u16>, GattError> {
        self.check_open()?;
        let objects = self
            .bus
            .objects()
            .await
            .map_err(|err| err.into_gatt_error(false))?;
        let char_paths: Vec<String> = self
            .state
            .lock()
            .expect("gatt state lock")
            .characteristics
            .values()
            .cloned()
            .collect();
        let min_mtu = char_paths
            .into_iter()
            .filter_map(|path| {
                objects
                    .get(&path)
                    .and_then(|ifaces| ifaces.get(CHARACTERISTIC))
                    .and_then(|props| props.get("MTU"))
                    .and_then(BluezValue::as_u16)
            })
            .min();
        Ok(min_mtu)
    }

    async fn set_notification_routing(
        &self,
        characteristic: &GattCharacteristic,
        enabled: bool,
    ) -> Result<(), GattError> {
        self.check_open()?;
        let path = self.path_for(characteristic)?;
        let mut state = self.state.lock().expect("gatt state lock");
        if enabled {
            state.routed.insert(path);
        } else {
            state.routed.remove(&path);
        }
        Ok(())
    }

    async fn configure_subscription(
        &self,
        characteristic: &GattCharacteristic,
        subscription: GattSubscription,
    ) -> Result<(), GattError> {
        self.check_open()?;
        let path = self.path_for(characteristic)?;
        let is_subscribed = self
            .state
            .lock()
            .expect("gatt state lock")
            .subscribed
            .contains(&path);
        if subscription == GattSubscription::Disabled {
            if is_subscribed {
                self.release_subscription(&path, true).await?;
            }
        } else if !is_subscribed {
            self.bus
                .start_notify(&self.owner, &path)
                .await
                .map_err(|err| err.into_gatt_error(true))?;
            let mut state = self.state.lock().expect("gatt state lock");
            if !state.subscribed.contains(&path) {
                state.subscribed.push(path);
            }
        }
        Ok(())
    }

    async fn write(
        &self,
        characteristic: &GattCharacteristic,
        value: &[u8],
        with_response: bool,
    ) -> Result<(), GattError> {
        self.check_open()?;
        let path = self.path_for(characteristic)?;
        let first_write = self.state.lock().expect("gatt state lock").first_write;
        let mut retries = if first_write { FIRST_WRITE_RETRIES } else { 0 };
        loop {
            match self.bus.write(&path, value, with_response).await {
                Ok(()) => {
                    self.state.lock().expect("gatt state lock").first_write = false;
                    return Ok(());
                }
                Err(error) if error.is_not_connected_failed() && retries > 0 => {
                    retries -= 1;
                    tokio::time::sleep(Duration::from_millis(125)).await;
                    self.check_open()?;
                }
                Err(error) => return Err(error.into_gatt_error(true)),
            }
        }
    }

    async fn close(&self) -> Result<(), GattError> {
        let subscribed = {
            let state = self.state.lock().expect("gatt state lock");
            if state.closed {
                return Ok(());
            }
            state.subscribed.clone()
        };
        for path in subscribed {
            self.release_subscription(&path, false).await?;
        }
        let mut state = self.state.lock().expect("gatt state lock");
        state.routed.clear();
        state.closed = true;
        Ok(())
    }
}

async fn request_gatt_connection(bus: &dyn BluezBus, device_path: &str) -> Result<(), BluezError> {
    let mut busy_retries = BUSY_CONNECT_RETRIES;
    let mut objects = bus.objects().await?;
    let mut interfaces = objects.get(device_path).cloned().unwrap_or_default();
    while interfaces
        .get(LE_BEARER)
        .and_then(|props| props.get("Connected"))
        .and_then(BluezValue::as_bool)
        != Some(true)
    {
        let use_le_bearer = interfaces.contains_key(LE_BEARER);
        let busy = match bus.connect_gatt(device_path, use_le_bearer).await {
            Ok(()) | Err(BluezError::AlreadyConnected(_)) => false,
            Err(BluezError::InProgress(msg)) => {
                let current_objects = bus.objects().await?;
                let current = current_objects.get(device_path).cloned().unwrap_or_default();
                if current
                    .get(DEVICE)
                    .and_then(|props| props.get("Connected"))
                    .and_then(BluezValue::as_bool)
                    != Some(true)
                {
                    return Err(BluezError::InProgress(msg));
                }
                use_le_bearer
                    && current
                        .get(CLASSIC_BEARER)
                        .and_then(|props| props.get("Connected"))
                        .and_then(BluezValue::as_bool)
                        == Some(true)
            }
            Err(error) => return Err(error),
        };
        if !busy || busy_retries == 0 {
            return Ok(());
        }
        busy_retries -= 1;
        tokio::time::sleep(Duration::from_millis(125)).await;
        objects = bus.objects().await?;
        interfaces = objects.get(device_path).cloned().unwrap_or_default();
        if !interfaces.contains_key(LE_BEARER)
            || interfaces
                .get(CLASSIC_BEARER)
                .and_then(|props| props.get("Connected"))
                .and_then(BluezValue::as_bool)
                != Some(true)
        {
            return Ok(());
        }
    }
    Ok(())
}

pub fn gatt_services_ready(objects: &BluezObjects, device_path: &str) -> bool {
    let Some(interfaces) = objects.get(device_path) else {
        return false;
    };
    if let Some(le) = interfaces.get(LE_BEARER)
        && le.get("Connected").and_then(BluezValue::as_bool) != Some(true)
    {
        return false;
    }
    let resolved = interfaces
        .get(DEVICE)
        .and_then(|props| props.get("ServicesResolved"))
        .and_then(BluezValue::as_bool)
        == Some(true);
    let prefix = format!("{device_path}/");
    resolved
        && objects
            .iter()
            .any(|(path, ifaces)| path.starts_with(&prefix) && ifaces.contains_key(SERVICE))
}

fn disconnected(message: &str) -> GattEvent {
    GattEvent::Disconnected(GattError::new(GattFailureKind::Disconnected, message))
}

fn is_gatt_attribute(device_path: &str, path: &str, interfaces: &BTreeSet<String>) -> bool {
    let prefix = format!("{device_path}/");
    path.starts_with(&prefix) && (interfaces.contains(SERVICE) || interfaces.contains(CHARACTERISTIC))
}

fn affects_link(signal: &BluezSignal, device_path: &str) -> bool {
    match signal {
        BluezSignal::Changed {
            path,
            interface_name,
            values,
        } => match interface_name.as_str() {
            ADAPTER => device_path
                .strip_prefix(path)
                .is_some_and(|rest| rest.starts_with('/')),
            DEVICE => {
                path == device_path
                    && (values.contains_key("Connected") || values.contains_key("ServicesResolved"))
            }
            LE_BEARER => path == device_path && values.contains_key("Connected"),
            CHARACTERISTIC => {
                let prefix = format!("{device_path}/");
                path.starts_with(&prefix) && values.contains_key("Value")
            }
            _ => false,
        },
        _ => true,
    }
}
