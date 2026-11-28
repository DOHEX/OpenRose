use super::bus::{
    AudioEndpointOps, BluezAudioBus, BluezBus, BluezError, BluezInterfaces, BluezObjects,
    BluezProperties, BluezSignal, BluezValue, CHARACTERISTIC, CLASSIC_BEARER, LE_BEARER,
    ProxyBluezAudioBus,
};
use async_trait::async_trait;
use bluer::Address;
use bluer::gatt::WriteOp;
use bluer::gatt::remote::CharacteristicWriteRequest;
use futures::StreamExt;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::{Arc, RwLock};
use std::time::Duration;
use tokio::sync::broadcast;
use tokio::task::JoinHandle;
use zbus::fdo::{DBusProxy, ObjectManagerProxy};
use zbus::names::BusName;
use zbus::{Connection, MatchRule, MessageStream, OwnedObjectPath, OwnedVariant, Proxy, Variant};

const DBUS_CALL_TIMEOUT: Duration = Duration::from_secs(10);
const BLUEZ_SERVICE: &str = "org.bluez";
const LOGIN1_SERVICE: &str = "org.freedesktop.login1";
const LOGIN1_PATH: &str = "/org/freedesktop/login1";

#[derive(Clone)]
struct ZbusAudioOps {
    connection: Connection,
    session: Option<bluer::Session>,
}

#[async_trait]
impl AudioEndpointOps for ZbusAudioOps {
    async fn connect_profile(&self, owner: &str, path: &str, uuid: &str) -> Result<(), BluezError> {
        if let Some(session) = &self.session
            && let Some((adapter_name, addr)) = parse_device_path(path)
            && let Ok(parsed_uuid) = uuid.parse::<bluer::Uuid>()
        {
            let adapter = session.adapter(adapter_name).map_err(map_bluer_error)?;
            let device = adapter.device(addr).map_err(map_bluer_error)?;
            return timed(device.connect_profile(&parsed_uuid), map_bluer_error).await;
        }
        call_method_no_reply(&self.connection, owner, path, "org.bluez.Device1", "ConnectProfile", &(uuid,))
            .await
    }

    async fn disconnect_device(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        call_method_no_reply(&self.connection, owner, path, "org.bluez.Device1", "Disconnect", &())
            .await
    }

    async fn disconnect_classic_bearer(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        call_method_no_reply(&self.connection, owner, path, CLASSIC_BEARER, "Disconnect", &())
            .await
    }

    async fn disconnect_le_bearer(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        call_method_no_reply(&self.connection, owner, path, LE_BEARER, "Disconnect", &())
            .await
    }
}

pub struct NativeBluezBus {
    connection: Connection,
    session: Option<bluer::Session>,
    audio: ProxyBluezAudioBus<ZbusAudioOps>,
    owner: Arc<RwLock<Option<String>>>,
    signals_tx: broadcast::Sender<BluezSignal>,
    signal_task: JoinHandle<()>,
}

impl NativeBluezBus {
    pub async fn open() -> Result<Arc<dyn BluezBus>, BluezError> {
        let connection = timed(Connection::system(), map_zbus_error).await?;
        let session = timed(bluer::Session::new(), map_bluer_error).await.ok();

        let dbus = DBusProxy::new(&connection).await.map_err(map_zbus_error)?;
        let initial_bluez_owner = get_name_owner(&dbus, BLUEZ_SERVICE).await;
        let initial_login_owner = get_name_owner(&dbus, LOGIN1_SERVICE).await;

        let owner = Arc::new(RwLock::new(initial_bluez_owner));
        let login_owner = Arc::new(RwLock::new(initial_login_owner));

        add_signal_matches(&dbus).await?;

        let (signals_tx, _) = broadcast::channel(512);
        let mut stream = MessageStream::from(&connection);
        let task_owner = Arc::clone(&owner);
        let task_login_owner = Arc::clone(&login_owner);
        let task_tx = signals_tx.clone();

        let signal_task = tokio::spawn(async move {
            while let Some(Ok(msg)) = stream.next().await {
                if msg.message_type() != zbus::message::Type::Signal {
                    continue;
                }
                let header = msg.header();
                let iface = header.interface().map(|i| i.as_str());
                let member = header.member().map(|m| m.as_str());
                let path = header.path().map(|p| p.as_str());
                let sender = header.sender().map(|s| s.as_str().to_string());

                match (iface, member) {
                    (Some("org.freedesktop.DBus"), Some("NameOwnerChanged")) => {
                        if let Ok((name, _old, new)) = msg.body().deserialize::<(String, String, String)>() {
                            let new_owner = (!new.is_empty()).then_some(new);
                            if name == LOGIN1_SERVICE {
                                *task_login_owner.write().expect("login owner lock") = new_owner;
                            } else if name == BLUEZ_SERVICE {
                                let present = new_owner.is_some();
                                *task_owner.write().expect("bluez owner lock") = new_owner;
                                let _ = task_tx.send(BluezSignal::Owner { present });
                            }
                        }
                    }
                    (Some("org.freedesktop.login1.Manager"), Some("PrepareForSleep")) => {
                        let expected_login = task_login_owner.read().expect("login owner lock").clone();
                        if path == Some(LOGIN1_PATH)
                            && sender.is_some()
                            && sender == expected_login
                            && let Ok(sleeping) = msg.body().deserialize::<bool>()
                        {
                            let _ = task_tx.send(BluezSignal::PrepareForSleep { sleeping });
                        }
                    }
                    (Some("org.freedesktop.DBus.Properties"), Some("PropertiesChanged")) => {
                        let expected_bluez = task_owner.read().expect("bluez owner lock").clone();
                        if sender.is_some()
                            && sender == expected_bluez
                            && let Some(obj_path) = path
                            && let Ok((iface_name, changed, removed)) = msg.body().deserialize::<(
                                String,
                                HashMap<String, OwnedVariant>,
                                Vec<String>,
                            )>()
                        {
                            let mut values = BTreeMap::new();
                            for (key, val) in changed {
                                values.insert(key, Some(convert_variant(&val)));
                            }
                            for key in removed {
                                values.insert(key, None);
                            }
                            let _ = task_tx.send(BluezSignal::Changed {
                                path: obj_path.to_string(),
                                interface_name: iface_name,
                                values,
                            });
                        }
                    }
                    (Some("org.freedesktop.DBus.ObjectManager"), Some("InterfacesAdded")) => {
                        let expected_bluez = task_owner.read().expect("bluez owner lock").clone();
                        if sender.is_some()
                            && sender == expected_bluez
                            && let Ok((obj_path, interfaces)) = msg.body().deserialize::<(
                                OwnedObjectPath,
                                HashMap<String, HashMap<String, OwnedVariant>>,
                            )>()
                        {
                            let mapped = convert_interfaces(interfaces);
                            let _ = task_tx.send(BluezSignal::Added {
                                path: obj_path.to_string(),
                                interfaces: mapped,
                            });
                        }
                    }
                    (Some("org.freedesktop.DBus.ObjectManager"), Some("InterfacesRemoved")) => {
                        let expected_bluez = task_owner.read().expect("bluez owner lock").clone();
                        if sender.is_some()
                            && sender == expected_bluez
                            && let Ok((obj_path, interfaces)) =
                                msg.body().deserialize::<(OwnedObjectPath, Vec<String>)>()
                        {
                            let _ = task_tx.send(BluezSignal::Removed {
                                path: obj_path.to_string(),
                                interfaces: interfaces.into_iter().collect::<BTreeSet<_>>(),
                            });
                        }
                    }
                    _ => {}
                }
            }
        });

        let ops = ZbusAudioOps {
            connection: connection.clone(),
            session: session.clone(),
        };
        Ok(Arc::new(Self {
            connection,
            session,
            audio: ProxyBluezAudioBus::new(ops),
            owner,
            signals_tx,
            signal_task,
        }))
    }
}

#[async_trait]
impl BluezAudioBus for NativeBluezBus {
    async fn connect_audio(&self, path: &str) -> Result<(), BluezError> {
        self.audio.connect_audio(path).await
    }

    async fn disconnect_audio(
        &self,
        path: &str,
        owner: &str,
        interfaces: &BluezInterfaces,
    ) -> Result<(), BluezError> {
        self.audio.disconnect_audio(path, owner, interfaces).await
    }

    async fn disconnect_le(&self, path: &str, owner: &str) -> Result<(), BluezError> {
        self.audio.disconnect_le(path, owner).await
    }
}

#[async_trait]
impl BluezBus for NativeBluezBus {
    fn owner(&self) -> Option<String> {
        self.owner.read().expect("bluez owner lock").clone()
    }

    fn signals(&self) -> broadcast::Receiver<BluezSignal> {
        self.signals_tx.subscribe()
    }

    async fn objects(&self) -> Result<BluezObjects, BluezError> {
        let proxy = ObjectManagerProxy::builder(&self.connection)
            .destination(BLUEZ_SERVICE)
            .path("/")
            .build()
            .await
            .map_err(map_zbus_error)?;
        let managed = timed(proxy.get_managed_objects(), map_fdo_error).await?;
        let mut objects = BluezObjects::new();
        for (path, interfaces) in managed {
            let mut mapped_ifaces = BluezInterfaces::new();
            for (iface, props) in interfaces {
                mapped_ifaces.insert(iface.to_string(), convert_properties(props));
            }
            objects.insert(path.to_string(), mapped_ifaces);
        }
        Ok(objects)
    }

    async fn connect_gatt(&self, path: &str, use_le_bearer: bool) -> Result<(), BluezError> {
        if use_le_bearer {
            call_method_no_reply(&self.connection, BLUEZ_SERVICE, path, LE_BEARER, "Connect", &())
                .await
        } else if let Some(session) = &self.session
            && let Some((adapter_name, addr)) = parse_device_path(path)
        {
            let adapter = session.adapter(adapter_name).map_err(map_bluer_error)?;
            let device = adapter.device(addr).map_err(map_bluer_error)?;
            timed(device.connect(), map_bluer_error).await
        } else {
            call_method_no_reply(
                &self.connection,
                BLUEZ_SERVICE,
                path,
                "org.bluez.Device1",
                "Connect",
                &(),
            )
            .await
        }
    }

    async fn start_notify(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        call_method_no_reply(&self.connection, owner, path, CHARACTERISTIC, "StartNotify", &())
            .await
    }

    async fn stop_notify(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        call_method_no_reply(&self.connection, owner, path, CHARACTERISTIC, "StopNotify", &())
            .await
    }

    async fn write(&self, path: &str, value: &[u8], with_response: bool) -> Result<(), BluezError> {
        if let Some(session) = &self.session
            && let Some((adapter_name, addr, service_id, char_id)) = parse_characteristic_path(path)
        {
            let adapter = session.adapter(adapter_name).map_err(map_bluer_error)?;
            let device = adapter.device(addr).map_err(map_bluer_error)?;
            let service = device.service(service_id).await.map_err(map_bluer_error)?;
            let characteristic = service
                .characteristic(char_id)
                .await
                .map_err(map_bluer_error)?;
            let req = CharacteristicWriteRequest {
                op_type: if with_response {
                    WriteOp::Request
                } else {
                    WriteOp::Command
                },
                ..Default::default()
            };
            return timed(characteristic.write_ext(value, &req), map_bluer_error).await;
        }
        let mut options: HashMap<&str, Variant<'_>> = HashMap::new();
        let mode = if with_response { "request" } else { "command" };
        options.insert("type", Variant::from(mode));
        call_method_no_reply(
            &self.connection,
            BLUEZ_SERVICE,
            path,
            CHARACTERISTIC,
            "WriteValue",
            &(value, options),
        )
        .await
    }

    async fn start_discovery(&self, path: &str) -> Result<(), BluezError> {
        let mut filter: HashMap<&str, Variant<'_>> = HashMap::new();
        filter.insert("Transport", Variant::from("le"));
        call_method_no_reply(
            &self.connection,
            BLUEZ_SERVICE,
            path,
            "org.bluez.Adapter1",
            "SetDiscoveryFilter",
            &(filter,),
        )
        .await?;
        call_method_no_reply(
            &self.connection,
            BLUEZ_SERVICE,
            path,
            "org.bluez.Adapter1",
            "StartDiscovery",
            &(),
        )
        .await
    }

    async fn stop_discovery(&self, path: &str) -> Result<(), BluezError> {
        call_method_no_reply(
            &self.connection,
            BLUEZ_SERVICE,
            path,
            "org.bluez.Adapter1",
            "StopDiscovery",
            &(),
        )
        .await
    }

    async fn close(&self) {
        self.signal_task.abort();
        let _ = self.connection.clone().close().await;
    }
}

async fn get_name_owner(dbus: &DBusProxy<'_>, name: &str) -> Option<String> {
    let bus_name = BusName::try_from(name).ok()?;
    let owner = timed(dbus.get_name_owner(bus_name), map_fdo_error)
        .await
        .ok()?;
    let text = owner.to_string();
    (!text.is_empty()).then_some(text)
}

async fn add_signal_matches(dbus: &DBusProxy<'_>) -> Result<(), BluezError> {
    let signal = || MatchRule::builder().msg_type(zbus::message::Type::Signal);
    let rules = [
        signal()
            .sender("org.freedesktop.DBus")
            .interface("org.freedesktop.DBus")
            .member("NameOwnerChanged")
            .build(),
        signal()
            .interface("org.freedesktop.login1.Manager")
            .member("PrepareForSleep")
            .path(LOGIN1_PATH)
            .build(),
        signal()
            .sender(BLUEZ_SERVICE)
            .interface("org.freedesktop.DBus.Properties")
            .member("PropertiesChanged")
            .build(),
        signal()
            .sender(BLUEZ_SERVICE)
            .interface("org.freedesktop.DBus.ObjectManager")
            .member("InterfacesAdded")
            .build(),
        signal()
            .sender(BLUEZ_SERVICE)
            .interface("org.freedesktop.DBus.ObjectManager")
            .member("InterfacesRemoved")
            .build(),
    ];
    for rule in rules {
        let rule = rule.map_err(map_zbus_error)?;
        timed(dbus.add_match_rule(rule), map_fdo_error).await?;
    }
    Ok(())
}

async fn timed<T, E>(
    future: impl Future<Output = Result<T, E>>,
    map_error: fn(E) -> BluezError,
) -> Result<T, BluezError> {
    match tokio::time::timeout(DBUS_CALL_TIMEOUT, future).await {
        Ok(result) => result.map_err(map_error),
        Err(_) => Err(BluezError::NoReply("BlueZ D-Bus 调用超时".to_string())),
    }
}

async fn call_method_no_reply<B: serde::ser::Serialize + zbus::DynamicType + Sync>(
    connection: &Connection,
    destination: &str,
    path: &str,
    interface: &str,
    method: &str,
    body: &B,
) -> Result<(), BluezError> {
    let proxy = Proxy::new(connection, destination, path, interface)
        .await
        .map_err(map_zbus_error)?;
    timed(proxy.call_noreply(method, body), map_zbus_error).await
}

fn parse_device_path(path: &str) -> Option<(&str, Address)> {
    let rest = path.strip_prefix("/org/bluez/")?;
    let (adapter, tail) = rest.split_once('/')?;
    let addr_part = tail.strip_prefix("dev_")?;
    let addr_str = addr_part.split('/').next()?.replace('_', ":");
    let addr = addr_str.parse::<Address>().ok()?;
    Some((adapter, addr))
}

fn parse_characteristic_path(path: &str) -> Option<(&str, Address, u16, u16)> {
    let rest = path.strip_prefix("/org/bluez/")?;
    let mut parts = rest.split('/');
    let adapter = parts.next()?;
    let dev_part = parts.next()?.strip_prefix("dev_")?;
    let addr = dev_part.replace('_', ":").parse::<Address>().ok()?;
    let svc_part = parts.next()?.strip_prefix("service")?;
    let char_part = parts.next()?.strip_prefix("char")?;
    if parts.next().is_some() {
        return None;
    }
    let service_id = u16::from_str_radix(svc_part, 16).ok()?;
    let char_id = u16::from_str_radix(char_part, 16).ok()?;
    Some((adapter, addr, service_id, char_id))
}

fn map_zbus_error(error: zbus::Error) -> BluezError {
    match error {
        zbus::Error::MethodError(name, detail, _) => {
            BluezError::from_dbus_name(name.as_str(), detail.unwrap_or_default())
        }
        zbus::Error::InputOutput(err) => {
            if err.kind() == std::io::ErrorKind::PermissionDenied {
                BluezError::AccessDenied(err.to_string())
            } else {
                BluezError::Other(err.to_string())
            }
        }
        other => BluezError::Other(other.to_string()),
    }
}

fn map_fdo_error(error: zbus::fdo::Error) -> BluezError {
    map_zbus_error(zbus::Error::from(error))
}

fn map_bluer_error(error: bluer::Error) -> BluezError {
    use bluer::{ErrorKind, InternalErrorKind};
    let msg = error.message;
    match error.kind {
        ErrorKind::NotAuthorized => BluezError::NotAuthorized(msg),
        ErrorKind::NotPermitted => BluezError::NotPermitted(msg),
        ErrorKind::NotSupported => BluezError::NotSupported(msg),
        ErrorKind::InvalidArguments => BluezError::InvalidArguments(msg),
        ErrorKind::InvalidLength => BluezError::InvalidValueLength(msg),
        ErrorKind::InvalidOffset => BluezError::InvalidOffset(msg),
        ErrorKind::AlreadyConnected => BluezError::AlreadyConnected(msg),
        ErrorKind::InProgress => BluezError::InProgress(msg),
        ErrorKind::NotReady => BluezError::NotReady(msg),
        ErrorKind::Failed => BluezError::Failed(msg),
        ErrorKind::Internal(InternalErrorKind::DBus(name)) => {
            BluezError::from_dbus_name(&name, msg)
        }
        other => BluezError::Other(if msg.is_empty() {
            other.to_string()
        } else {
            msg
        }),
    }
}

fn convert_interfaces(
    interfaces: HashMap<String, HashMap<String, OwnedVariant>>,
) -> BluezInterfaces {
    interfaces
        .into_iter()
        .map(|(iface, props)| (iface, convert_properties(props)))
        .collect()
}

fn convert_properties(props: HashMap<String, OwnedVariant>) -> BluezProperties {
    props
        .into_iter()
        .map(|(k, v)| (k, convert_variant(&v)))
        .collect()
}

fn convert_variant(variant: &Variant<'_>) -> BluezValue {
    match variant {
        Variant::Bool(value) => BluezValue::Bool(*value),
        Variant::Str(value) => BluezValue::String(value.to_string()),
        Variant::ObjectPath(value) => BluezValue::Path(value.to_string()),
        Variant::I16(value) => BluezValue::I16(*value),
        Variant::U16(value) => BluezValue::U16(*value),
        Variant::U32(value) => BluezValue::U32(*value),
        Variant::I32(value) => u32::try_from(*value)
            .map(BluezValue::U32)
            .unwrap_or(BluezValue::Other),
        Variant::Variant(inner) => convert_variant(inner),
        Variant::Array(array) => convert_array(array.inner()),
        Variant::Dict(dict) => {
            let mut manufacturer = BTreeMap::new();
            for (key, value) in dict.iter() {
                let Variant::U16(id) = key else { continue };
                if let BluezValue::Bytes(bytes) = convert_variant(value) {
                    manufacturer.insert(*id, bytes);
                }
            }
            if manufacturer.is_empty() {
                BluezValue::Other
            } else {
                BluezValue::ManufacturerData(manufacturer)
            }
        }
        _ => BluezValue::Other,
    }
}

fn convert_array(items: &[Variant<'_>]) -> BluezValue {
    if items.iter().all(|item| matches!(item, Variant::U8(_))) && !items.is_empty() {
        BluezValue::Bytes(
            items
                .iter()
                .filter_map(|item| match item {
                    Variant::U8(byte) => Some(*byte),
                    _ => None,
                })
                .collect(),
        )
    } else if items.iter().all(|item| matches!(item, Variant::Str(_))) {
        BluezValue::Strings(
            items
                .iter()
                .filter_map(|item| match item {
                    Variant::Str(text) => Some(text.to_string()),
                    _ => None,
                })
                .collect(),
        )
    } else {
        BluezValue::Other
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn variants_convert_to_the_values_the_drivers_read() {
        assert_eq!(convert_variant(&Variant::new(true)), BluezValue::Bool(true));
        assert_eq!(convert_variant(&Variant::new("a")), BluezValue::String("a".into()));
        assert_eq!(convert_variant(&Variant::new(-60i16)), BluezValue::I16(-60));
        assert_eq!(convert_variant(&Variant::new(100u16)), BluezValue::U16(100));
        assert_eq!(
            convert_variant(&Variant::new(vec![1u8, 2])),
            BluezValue::Bytes(vec![1, 2])
        );
        assert_eq!(
            convert_variant(&Variant::new(vec!["write".to_string(), "notify".to_string()])),
            BluezValue::Strings(vec!["write".into(), "notify".into()])
        );
        assert_eq!(
            convert_variant(&Variant::new(Vec::<String>::new())),
            BluezValue::Strings(Vec::new())
        );
        let manufacturer: BTreeMap<u16, Variant<'_>> =
            [(0x1234u16, Variant::new(vec![1u8, 2, 3]))].into_iter().collect();
        assert_eq!(
            convert_variant(&Variant::new(manufacturer)),
            BluezValue::ManufacturerData([(0x1234, vec![1, 2, 3])].into_iter().collect())
        );
    }

    #[test]
    fn bluer_and_dbus_errors_keep_their_access_denied_meaning() {
        let denied = |kind| bluer::Error {
            kind,
            message: "denied".to_string(),
        };
        assert!(map_bluer_error(denied(bluer::ErrorKind::NotAuthorized)).is_access_denied());
        assert!(map_bluer_error(denied(bluer::ErrorKind::NotPermitted)).is_access_denied());
        let bus_level = bluer::ErrorKind::Internal(bluer::InternalErrorKind::DBus(
            "org.freedesktop.DBus.Error.AccessDenied".to_string(),
        ));
        assert!(map_bluer_error(denied(bus_level)).is_access_denied());
        let missing = bluer::ErrorKind::Internal(bluer::InternalErrorKind::DBus(
            "org.freedesktop.DBus.Error.ServiceUnknown".to_string(),
        ));
        assert!(map_bluer_error(denied(missing)).is_service_unavailable());
    }

    #[test]
    fn device_and_characteristic_paths_resolve_to_bluer_handles() {
        let (adapter, address) = parse_device_path("/org/bluez/hci1/dev_00_11_22_33_44_55").expect("device");
        assert_eq!(adapter, "hci1");
        assert_eq!(address.to_string(), "00:11:22:33:44:55");
        let (_, _, service, characteristic) =
            parse_characteristic_path("/org/bluez/hci0/dev_00_11_22_33_44_55/service0010/char002a")
                .expect("characteristic");
        assert_eq!((service, characteristic), (0x10, 0x2a));
        assert!(parse_device_path("/org/bluez/hci0").is_none());
    }
}
