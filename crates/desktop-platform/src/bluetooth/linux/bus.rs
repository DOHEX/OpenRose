use async_trait::async_trait;
use openrose_control::audio::normalize_bluetooth_address;
use openrose_control::gatt::{GattError, GattFailureKind};
use openrose_control::snapshot::TransportUnavailableReason;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use tokio::sync::broadcast;

pub const DEVICE: &str = "org.bluez.Device1";
pub const ADAPTER: &str = "org.bluez.Adapter1";
pub const SERVICE: &str = "org.bluez.GattService1";
pub const CHARACTERISTIC: &str = "org.bluez.GattCharacteristic1";
pub const MEDIA_TRANSPORT: &str = "org.bluez.MediaTransport1";
pub const CLASSIC_BEARER: &str = "org.bluez.Bearer.BREDR1";
pub const LE_BEARER: &str = "org.bluez.Bearer.LE1";
pub const AUDIO_SINK_UUID: &str = "0000110b-0000-1000-8000-00805f9b34fb";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BluezValue {
    Bool(bool),
    String(String),
    Path(String),
    I16(i16),
    U16(u16),
    U32(u32),
    Bytes(Vec<u8>),
    Strings(Vec<String>),
    ManufacturerData(BTreeMap<u16, Vec<u8>>),
    Other,
}

impl BluezValue {
    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Self::Bool(value) => Some(*value),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Self::String(value) | Self::Path(value) => Some(value.as_str()),
            _ => None,
        }
    }

    pub fn as_i16(&self) -> Option<i16> {
        match self {
            Self::I16(value) => Some(*value),
            _ => None,
        }
    }

    pub fn as_u16(&self) -> Option<u16> {
        match self {
            Self::U16(value) => Some(*value),
            Self::U32(value) => u16::try_from(*value).ok(),
            Self::I16(value) => u16::try_from(*value).ok(),
            _ => None,
        }
    }

    pub fn as_bytes(&self) -> Option<&[u8]> {
        match self {
            Self::Bytes(value) => Some(value.as_slice()),
            _ => None,
        }
    }

    pub fn as_strings(&self) -> Option<&[String]> {
        match self {
            Self::Strings(value) => Some(value.as_slice()),
            _ => None,
        }
    }

    pub fn as_manufacturer_data(&self) -> Option<&BTreeMap<u16, Vec<u8>>> {
        match self {
            Self::ManufacturerData(value) => Some(value),
            _ => None,
        }
    }
}

pub type BluezProperties = BTreeMap<String, BluezValue>;
pub type BluezInterfaces = BTreeMap<String, BluezProperties>;
pub type BluezObjects = BTreeMap<String, BluezInterfaces>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BluezError {
    NotAuthorized(String),
    NotPermitted(String),
    AccessDenied(String),
    NotSupported(String),
    InvalidArguments(String),
    InvalidValueLength(String),
    InvalidOffset(String),
    NotConnected(String),
    AlreadyConnected(String),
    InProgress(String),
    NotReady(String),
    Failed(String),
    NoReply(String),
    ServiceUnknown(String),
    Other(String),
}

impl BluezError {
    pub fn from_dbus_name(name: &str, message: impl Into<String>) -> Self {
        let message = message.into();
        match name {
            "org.bluez.Error.NotAuthorized" => Self::NotAuthorized(message),
            "org.bluez.Error.NotPermitted" => Self::NotPermitted(message),
            "org.freedesktop.DBus.Error.AccessDenied" => Self::AccessDenied(message),
            "org.bluez.Error.NotSupported" => Self::NotSupported(message),
            "org.bluez.Error.InvalidArguments" => Self::InvalidArguments(message),
            "org.bluez.Error.InvalidValueLength" => Self::InvalidValueLength(message),
            "org.bluez.Error.InvalidOffset" => Self::InvalidOffset(message),
            "org.bluez.Error.NotConnected" => Self::NotConnected(message),
            "org.bluez.Error.AlreadyConnected" => Self::AlreadyConnected(message),
            "org.bluez.Error.InProgress" => Self::InProgress(message),
            "org.bluez.Error.NotReady" => Self::NotReady(message),
            "org.bluez.Error.Failed" => Self::Failed(message),
            "org.freedesktop.DBus.Error.NoReply"
            | "org.freedesktop.DBus.Error.Timeout"
            | "org.freedesktop.DBus.Error.TimedOut" => Self::NoReply(message),
            "org.freedesktop.DBus.Error.ServiceUnknown"
            | "org.freedesktop.DBus.Error.NameHasNoOwner" => Self::ServiceUnknown(message),
            _ => Self::Other(if message.is_empty() {
                name.to_string()
            } else {
                message
            }),
        }
    }

    pub fn message(&self) -> &str {
        match self {
            Self::NotAuthorized(msg)
            | Self::NotPermitted(msg)
            | Self::AccessDenied(msg)
            | Self::NotSupported(msg)
            | Self::InvalidArguments(msg)
            | Self::InvalidValueLength(msg)
            | Self::InvalidOffset(msg)
            | Self::NotConnected(msg)
            | Self::AlreadyConnected(msg)
            | Self::InProgress(msg)
            | Self::NotReady(msg)
            | Self::Failed(msg)
            | Self::NoReply(msg)
            | Self::ServiceUnknown(msg)
            | Self::Other(msg) => msg,
        }
    }

    pub fn is_access_denied(&self) -> bool {
        matches!(
            self,
            Self::NotAuthorized(_) | Self::NotPermitted(_) | Self::AccessDenied(_)
        )
    }

    pub fn is_service_unavailable(&self) -> bool {
        matches!(self, Self::ServiceUnknown(_))
    }

    pub fn is_not_connected_failed(&self) -> bool {
        matches!(self, Self::Failed(msg) if msg == "Not connected")
    }

    pub fn into_gatt_error(self, recover_not_connected: bool) -> GattError {
        let kind = match &self {
            Self::NotSupported(_) => GattFailureKind::Unsupported,
            Self::InvalidArguments(_) | Self::InvalidValueLength(_) | Self::InvalidOffset(_) => {
                GattFailureKind::InvalidValue
            }
            Self::NotConnected(_) => {
                if recover_not_connected {
                    GattFailureKind::Transient
                } else {
                    GattFailureKind::Disconnected
                }
            }
            Self::ServiceUnknown(_) => GattFailureKind::Disconnected,
            Self::InProgress(_) | Self::NotReady(_) | Self::NoReply(_) => {
                GattFailureKind::Transient
            }
            other if other.is_access_denied() => GattFailureKind::Permission,
            _ => GattFailureKind::Unknown,
        };
        GattError::new(kind, self.message().to_string())
    }

    pub fn into_transport_unavailable(self) -> TransportUnavailableReason {
        if self.is_access_denied() {
            TransportUnavailableReason::AccessDenied
        } else if self.is_service_unavailable() {
            TransportUnavailableReason::ServiceUnavailable
        } else {
            TransportUnavailableReason::BackendUnavailable {
                detail: Some(self.message().to_string()),
            }
        }
    }
}

impl fmt::Display for BluezError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for BluezError {}

#[derive(Debug, Clone)]
pub enum BluezSignal {
    Changed {
        path: String,
        interface_name: String,
        values: BTreeMap<String, Option<BluezValue>>,
    },
    Added {
        path: String,
        interfaces: BluezInterfaces,
    },
    Removed {
        path: String,
        interfaces: BTreeSet<String>,
    },
    PrepareForSleep {
        sleeping: bool,
    },
    Owner {
        present: bool,
    },
}

pub fn adapter_of(device_path: &str) -> &str {
    device_path
        .rsplit_once('/')
        .map(|(adapter, _)| adapter)
        .unwrap_or("")
}

pub fn on_powered_adapter(objects: &BluezObjects, device_path: &str) -> bool {
    objects
        .get(adapter_of(device_path))
        .and_then(|ifaces| ifaces.get(ADAPTER))
        .and_then(|props| props.get("Powered"))
        .and_then(BluezValue::as_bool)
        == Some(true)
}

pub fn media_belongs_to(
    device_path: &str,
    media_path: &str,
    properties: &BluezProperties,
) -> bool {
    match properties.get("Device").and_then(BluezValue::as_str) {
        Some(owner) => owner == device_path,
        None => {
            media_path == device_path
                || media_path
                    .strip_prefix(device_path)
                    .is_some_and(|rest| rest.starts_with('/'))
        }
    }
}

pub fn has_media_transport(objects: &BluezObjects, device_path: &str) -> bool {
    objects.iter().any(|(path, interfaces)| {
        interfaces
            .get(MEDIA_TRANSPORT)
            .is_some_and(|props| media_belongs_to(device_path, path, props))
    })
}

pub fn device_path(
    objects: &BluezObjects,
    address: &str,
    paired_only: bool,
    scan_adapter: Option<&str>,
) -> Option<String> {
    let mut candidates: Vec<&String> = objects
        .iter()
        .filter_map(|(path, ifaces)| {
            let device = ifaces.get(DEVICE)?;
            if !on_powered_adapter(objects, path) {
                return None;
            }
            if paired_only && device.get("Paired").and_then(BluezValue::as_bool) != Some(true) {
                return None;
            }
            let raw_addr = device.get("Address").and_then(BluezValue::as_str)?;
            (normalize_bluetooth_address(raw_addr).as_deref() == Some(address)).then_some(path)
        })
        .collect();
    candidates.sort();
    if candidates.len() <= 1 {
        return candidates.first().map(|p| (*p).clone());
    }
    let bool_prop = |path: &str, iface: &str, key: &str| {
        objects
            .get(path)
            .and_then(|ifaces| ifaces.get(iface))
            .and_then(|props| props.get(key))
            .and_then(BluezValue::as_bool)
            == Some(true)
    };
    candidates
        .iter()
        .find(|path| has_media_transport(objects, path))
        .or_else(|| {
            candidates
                .iter()
                .find(|path| bool_prop(path, CLASSIC_BEARER, "Connected"))
        })
        .or_else(|| {
            candidates
                .iter()
                .find(|path| bool_prop(path, LE_BEARER, "Connected"))
        })
        .or_else(|| {
            candidates
                .iter()
                .find(|path| bool_prop(path, DEVICE, "Connected"))
        })
        .or_else(|| {
            candidates
                .iter()
                .find(|path| Some(adapter_of(path)) == scan_adapter)
        })
        .or_else(|| candidates.first())
        .map(|p| (*p).clone())
}

#[async_trait]
pub trait BluezAudioBus: Send + Sync {
    async fn connect_audio(&self, path: &str) -> Result<(), BluezError>;

    async fn disconnect_audio(
        &self,
        path: &str,
        owner: &str,
        interfaces: &BluezInterfaces,
    ) -> Result<(), BluezError>;

    async fn disconnect_le(&self, path: &str, owner: &str) -> Result<(), BluezError>;
}

#[async_trait]
pub trait BluezBus: BluezAudioBus {
    fn owner(&self) -> Option<String>;

    fn signals(&self) -> broadcast::Receiver<BluezSignal>;

    async fn objects(&self) -> Result<BluezObjects, BluezError>;

    async fn connect_gatt(&self, path: &str, use_le_bearer: bool) -> Result<(), BluezError>;

    async fn start_notify(&self, owner: &str, path: &str) -> Result<(), BluezError>;

    async fn stop_notify(&self, owner: &str, path: &str) -> Result<(), BluezError>;

    async fn write(&self, path: &str, value: &[u8], with_response: bool) -> Result<(), BluezError>;

    async fn start_discovery(&self, path: &str) -> Result<(), BluezError>;

    async fn stop_discovery(&self, path: &str) -> Result<(), BluezError>;

    async fn close(&self);
}

#[async_trait]
pub trait AudioEndpointOps: Send + Sync {
    async fn connect_profile(&self, owner: &str, path: &str, uuid: &str) -> Result<(), BluezError>;
    async fn disconnect_device(&self, owner: &str, path: &str) -> Result<(), BluezError>;
    async fn disconnect_classic_bearer(&self, owner: &str, path: &str) -> Result<(), BluezError>;
    async fn disconnect_le_bearer(&self, owner: &str, path: &str) -> Result<(), BluezError>;
}

pub struct ProxyBluezAudioBus<T> {
    ops: T,
}

impl<T> ProxyBluezAudioBus<T> {
    pub fn new(ops: T) -> Self {
        Self { ops }
    }
}

async fn disconnect_if_connected(
    result: Result<(), BluezError>,
) -> Result<(), BluezError> {
    match result {
        Ok(()) | Err(BluezError::NotConnected(_)) => Ok(()),
        Err(error) => Err(error),
    }
}

#[async_trait]
impl<T: AudioEndpointOps> BluezAudioBus for ProxyBluezAudioBus<T> {
    async fn connect_audio(&self, path: &str) -> Result<(), BluezError> {
        self.ops
            .connect_profile("org.bluez", path, AUDIO_SINK_UUID)
            .await
    }

    async fn disconnect_audio(
        &self,
        path: &str,
        owner: &str,
        interfaces: &BluezInterfaces,
    ) -> Result<(), BluezError> {
        let classic = interfaces
            .get(CLASSIC_BEARER)
            .and_then(|props| props.get("Connected"))
            .and_then(BluezValue::as_bool);
        let le = interfaces
            .get(LE_BEARER)
            .and_then(|props| props.get("Connected"))
            .and_then(BluezValue::as_bool);
        match (classic, le) {
            (None, _) | (_, None) => {
                disconnect_if_connected(self.ops.disconnect_device(owner, path).await).await
            }
            (Some(classic_connected), Some(le_connected)) => {
                if classic_connected {
                    disconnect_if_connected(self.ops.disconnect_classic_bearer(owner, path).await)
                        .await?;
                }
                if le_connected {
                    self.disconnect_le(path, owner).await?;
                }
                Ok(())
            }
        }
    }

    async fn disconnect_le(&self, path: &str, owner: &str) -> Result<(), BluezError> {
        disconnect_if_connected(self.ops.disconnect_le_bearer(owner, path).await).await
    }
}
