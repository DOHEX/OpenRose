use crate::classify::build_windows_snapshot;
use crate::cli::CliOptions;
use crate::diff::{diff_snapshots, filter_snapshot};
use crate::format::{
    format_event_json, format_event_text, format_snapshot_json, format_snapshot_text, now_timestamp,
};
use crate::model::{
    BluetoothStatus, BluetoothTransport, ProbeSnapshot, WindowsAepServiceObservation,
    WindowsAudioEndpointObservation, WindowsDeviceObservation, WindowsEndpointFlow,
    WindowsEndpointState, WindowsTopologyFilter,
};
use std::collections::HashSet;
use std::error::Error;
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};
use windows::core::{implement, Interface, GUID, HSTRING, PCWSTR, PWSTR};
use windows::Devices::Bluetooth::{BluetoothAdapter, BluetoothDevice, BluetoothLEDevice};
use windows::Devices::Enumeration::{
    DeviceInformation, DeviceInformationKind, DeviceInformationUpdate, DeviceWatcher,
};
use windows::Devices::Radios::RadioState;
use windows::Foundation::{IPropertyValue, TypedEventHandler};
use windows::Win32::Devices::FunctionDiscovery::{
    PKEY_Device_ContainerId, PKEY_Device_DeviceDesc, PKEY_Device_FriendlyName,
    PKEY_Device_InstanceId,
};
use windows::Win32::Devices::Properties::DEVPKEY_Device_EnumeratorName;
use windows::Win32::Media::Audio::{
    eCapture, eRender, AudioSessionStateActive, EDataFlow, IAudioClient, IAudioSessionManager2,
    IDeviceTopology, IMMDevice, IMMDeviceEnumerator, IMMNotificationClient,
    IMMNotificationClient_Impl, MMDeviceEnumerator, DEVICE_STATE, DEVICE_STATE_ACTIVE,
    DEVICE_STATE_UNPLUGGED, ERole,
};
use windows::Win32::System::Com::StructuredStorage::{
    PropVariantClear, PropVariantToGUID, PropVariantToStringAlloc,
};
use windows::Win32::System::Com::{CoCreateInstance, CoTaskMemFree, CLSCTX_ALL, STGM_READ};
use windows::Win32::Foundation::PROPERTYKEY;
use windows::Win32::System::WinRT::{RoInitialize, RO_INIT_MULTITHREADED};
use windows::Win32::UI::Shell::PropertiesSystem::IPropertyStore;
use windows_collections::IIterable;

const PROP_CONTAINER: &str = "System.Devices.Aep.ContainerId";
const PROP_ADDRESS: &str = "System.Devices.Aep.DeviceAddress";
const PROP_CONNECTED: &str = "System.Devices.Aep.IsConnected";
const PROP_SERVICE_CLASS: &str = "System.Devices.AepService.ServiceClassId";

const AEP_AUDIO_SERVICES_AQS: &str = "(System.Devices.AepService.ProtocolId:=\"{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}\" OR System.Devices.AepService.ProtocolId:=\"{bb7bb05e-5972-42b5-94fc-76eaa7084d49}\")";

const PKEY_ENUMERATOR_NAME: PROPERTYKEY = PROPERTYKEY {
    fmtid: DEVPKEY_Device_EnumeratorName.fmtid,
    pid: DEVPKEY_Device_EnumeratorName.pid,
};

fn init_winrt() -> Result<(), Box<dyn Error>> {
    let _ = unsafe { RoInitialize(RO_INIT_MULTITHREADED) };
    Ok(())
}

fn format_guid(guid: GUID) -> String {
    format!(
        "{{{:08x}-{:04x}-{:04x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}}}",
        guid.data1,
        guid.data2,
        guid.data3,
        guid.data4[0],
        guid.data4[1],
        guid.data4[2],
        guid.data4[3],
        guid.data4[4],
        guid.data4[5],
        guid.data4[6],
        guid.data4[7],
    )
}

fn take_pwstr(ptr: PWSTR) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    let result = unsafe { ptr.to_string().ok() };
    unsafe { CoTaskMemFree(Some(ptr.0.cast())) };
    result
}

fn read_store_guid(store: &IPropertyStore, key: &PROPERTYKEY) -> Option<String> {
    unsafe {
        let mut value = store.GetValue(key).ok()?;
        let guid = PropVariantToGUID(&value).ok();
        let _ = PropVariantClear(&mut value);
        guid.map(format_guid)
    }
}

fn read_store_string(store: &IPropertyStore, key: &PROPERTYKEY) -> Option<String> {
    unsafe {
        let mut value = store.GetValue(key).ok()?;
        let text = PropVariantToStringAlloc(&value).ok().and_then(take_pwstr);
        let _ = PropVariantClear(&mut value);
        text.filter(|s| !s.is_empty())
    }
}

fn aep_property(device: &DeviceInformation, name: &str) -> Option<IPropertyValue> {
    device
        .Properties()
        .ok()?
        .Lookup(&HSTRING::from(name))
        .ok()?
        .cast()
        .ok()
}

fn query_paired_aep(
    selector: &HSTRING,
    transport: BluetoothTransport,
) -> Result<Vec<WindowsDeviceObservation>, Box<dyn Error>> {
    let properties: IIterable<HSTRING> = vec![
        PROP_CONTAINER.into(),
        PROP_ADDRESS.into(),
        PROP_CONNECTED.into(),
    ]
    .into();
    let devices = DeviceInformation::FindAllAsyncWithKindAqsFilterAndAdditionalProperties(
        selector,
        &properties,
        DeviceInformationKind::AssociationEndpoint,
    )?
    .get()?;
    let mut out = Vec::new();
    for device in devices {
        let id = device.Id()?.to_string();
        let name = device.Name()?.to_string();
        let address = aep_property(&device, PROP_ADDRESS).and_then(|p| p.GetString().ok()).map(|s| s.to_string());
        let container_id = aep_property(&device, PROP_CONTAINER)
            .and_then(|p| p.GetGuid().ok())
            .map(format_guid);
        let is_connected = aep_property(&device, PROP_CONNECTED)
            .and_then(|p| p.GetBoolean().ok())
            .unwrap_or(false);
        out.push(WindowsDeviceObservation {
            id,
            name,
            address,
            container_id,
            transport,
            is_connected,
        });
    }
    Ok(out)
}

fn is_audio_service_guid(guid: &str) -> bool {
    let lower = guid.trim_matches(|c| c == '{' || c == '}').to_ascii_lowercase();
    matches!(
        lower.as_str(),
        "0000110b-0000-1000-8000-00805f9b34fb"
            | "0000110a-0000-1000-8000-00805f9b34fb"
            | "0000111e-0000-1000-8000-00805f9b34fb"
            | "0000111f-0000-1000-8000-00805f9b34fb"
            | "00001108-0000-1000-8000-00805f9b34fb"
            | "0000110e-0000-1000-8000-00805f9b34fb"
            | "0000184e-0000-1000-8000-00805f9b34fb"
            | "00001850-0000-1000-8000-00805f9b34fb"
            | "00001855-0000-1000-8000-00805f9b34fb"
            | "00001846-0000-1000-8000-00805f9b34fb"
    )
}

fn query_audio_aep_services() -> Vec<WindowsAepServiceObservation> {
    let selector = HSTRING::from(AEP_AUDIO_SERVICES_AQS);
    let properties: IIterable<HSTRING> = vec![
        PROP_CONTAINER.into(),
        PROP_ADDRESS.into(),
        PROP_CONNECTED.into(),
        PROP_SERVICE_CLASS.into(),
    ]
    .into();
    let Ok(op) = DeviceInformation::FindAllAsyncWithKindAqsFilterAndAdditionalProperties(
        &selector,
        &properties,
        DeviceInformationKind::AssociationEndpointService,
    ) else {
        return Vec::new();
    };
    let Ok(services) = op.get() else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for svc in services {
        let Some(class_guid) = aep_property(&svc, PROP_SERVICE_CLASS)
            .and_then(|p| p.GetGuid().ok())
            .map(format_guid)
        else {
            continue;
        };
        if !is_audio_service_guid(&class_guid) {
            continue;
        }
        let container_id = aep_property(&svc, PROP_CONTAINER)
            .and_then(|p| p.GetGuid().ok())
            .map(format_guid);
        let address = aep_property(&svc, PROP_ADDRESS)
            .and_then(|p| p.GetString().ok())
            .map(|s| s.to_string());
        let is_connected = aep_property(&svc, PROP_CONNECTED)
            .and_then(|p| p.GetBoolean().ok())
            .unwrap_or(false);
        out.push(WindowsAepServiceObservation {
            container_id,
            address,
            service_class_id: class_guid,
            is_connected,
        });
    }
    out
}

fn inspect_topology_filters(
    enumerator: &IMMDeviceEnumerator,
    endpoint: &IMMDevice,
) -> Vec<WindowsTopologyFilter> {
    let mut filters = Vec::new();
    let mut seen = HashSet::new();
    unsafe {
        let Ok(topology) = endpoint.Activate::<IDeviceTopology>(CLSCTX_ALL, None) else {
            return filters;
        };
        let Ok(count) = topology.GetConnectorCount() else {
            return filters;
        };
        for index in 0..count {
            let Ok(connector) = topology.GetConnector(index) else {
                continue;
            };
            let Ok(raw_id) = connector.GetDeviceIdConnectedTo() else {
                continue;
            };
            let Some(filter_id) = take_pwstr(raw_id) else {
                continue;
            };
            if !seen.insert(filter_id.clone()) {
                continue;
            }
            let (enumerator_name, instance_id, friendly_name) =
                if let Ok(filter_dev) = enumerator.GetDevice(&HSTRING::from(&filter_id)) {
                    if let Ok(store) = filter_dev.OpenPropertyStore(STGM_READ) {
                        (
                            read_store_string(&store, &PKEY_ENUMERATOR_NAME),
                            read_store_string(&store, &PKEY_Device_InstanceId),
                            read_store_string(&store, &PKEY_Device_FriendlyName),
                        )
                    } else {
                        (None, None, None)
                    }
                } else {
                    (None, None, None)
                };
            filters.push(WindowsTopologyFilter {
                id: filter_id,
                enumerator: enumerator_name,
                instance_id,
                friendly_name,
            });
        }
    }
    filters
}

fn capture_session_active(endpoint: &IMMDevice) -> bool {
    unsafe {
        let Ok(manager) = endpoint.Activate::<IAudioSessionManager2>(CLSCTX_ALL, None) else {
            return false;
        };
        let Ok(sessions) = manager.GetSessionEnumerator() else {
            return false;
        };
        let Ok(count) = sessions.GetCount() else {
            return false;
        };
        for index in 0..count {
            if let Ok(session) = sessions.GetSession(index) {
                if session.GetState().ok() == Some(AudioSessionStateActive) {
                    return true;
                }
            }
        }
        false
    }
}

fn mix_format(endpoint: &IMMDevice) -> (Option<u32>, Option<u16>) {
    unsafe {
        let Ok(client) = endpoint.Activate::<IAudioClient>(CLSCTX_ALL, None) else {
            return (None, None);
        };
        let Ok(fmt_ptr) = client.GetMixFormat() else {
            return (None, None);
        };
        if fmt_ptr.is_null() {
            return (None, None);
        }
        let sample_rate = (*fmt_ptr).nSamplesPerSec;
        let channels = (*fmt_ptr).nChannels;
        CoTaskMemFree(Some(fmt_ptr.cast()));
        (Some(sample_rate), Some(channels))
    }
}

fn query_audio_endpoints() -> Result<Vec<WindowsAudioEndpointObservation>, Box<dyn Error>> {
    let mut out = Vec::new();
    unsafe {
        let enumerator: IMMDeviceEnumerator =
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let mask = DEVICE_STATE(DEVICE_STATE_ACTIVE.0 | DEVICE_STATE_UNPLUGGED.0);
        for (raw_flow, flow) in [
            (eRender, WindowsEndpointFlow::Render),
            (eCapture, WindowsEndpointFlow::Capture),
        ] {
            let collection = enumerator.EnumAudioEndpoints(raw_flow, mask)?;
            let count = collection.GetCount()?;
            for index in 0..count {
                let endpoint = collection.Item(index)?;
                let id = take_pwstr(endpoint.GetId()?).unwrap_or_default();
                let raw_state = endpoint.GetState()?;
                let state = if raw_state == DEVICE_STATE_ACTIVE {
                    WindowsEndpointState::Active
                } else if raw_state == DEVICE_STATE_UNPLUGGED {
                    WindowsEndpointState::Unplugged
                } else {
                    WindowsEndpointState::Other
                };
                let store = endpoint.OpenPropertyStore(STGM_READ)?;
                let container_id = read_store_guid(&store, &PKEY_Device_ContainerId);
                let friendly_name =
                    read_store_string(&store, &PKEY_Device_FriendlyName).unwrap_or_default();
                let device_desc = read_store_string(&store, &PKEY_Device_DeviceDesc);
                let topology_filters = inspect_topology_filters(&enumerator, &endpoint);
                let (capture_active, sample_rate_hz, channels) =
                    if state == WindowsEndpointState::Active {
                        let cap = if flow == WindowsEndpointFlow::Capture {
                            capture_session_active(&endpoint)
                        } else {
                            false
                        };
                        let (rate, ch) = mix_format(&endpoint);
                        (cap, rate, ch)
                    } else {
                        (false, None, None)
                    };
                out.push(WindowsAudioEndpointObservation {
                    id,
                    container_id,
                    friendly_name,
                    device_desc,
                    flow,
                    state,
                    topology_filters,
                    capture_session_active: capture_active,
                    sample_rate_hz,
                    channels,
                });
            }
        }
    }
    Ok(out)
}

fn bluetooth_status() -> BluetoothStatus {
    let adapter = match BluetoothAdapter::GetDefaultAsync().and_then(|op| op.get()) {
        Ok(adapter) => adapter,
        Err(err) => {
            return BluetoothStatus::unavailable(
                "unavailable",
                format!("no default Bluetooth adapter: {err}"),
            )
        }
    };
    let radio = match adapter.GetRadioAsync().and_then(|op| op.get()) {
        Ok(radio) => radio,
        Err(err) => {
            return BluetoothStatus::unavailable(
                "unavailable",
                format!("failed to query Bluetooth radio: {err}"),
            )
        }
    };
    match radio.State() {
        Ok(RadioState::On) => BluetoothStatus::available(),
        Ok(RadioState::Off | RadioState::Disabled) => {
            BluetoothStatus::unavailable("disabled", "Bluetooth radio is turned off")
        }
        Ok(_) => BluetoothStatus::unavailable("unavailable", "Bluetooth radio state is unknown"),
        Err(err) => BluetoothStatus::unavailable("unavailable", format!("{err}")),
    }
}

pub fn collect_snapshot() -> Result<ProbeSnapshot, Box<dyn Error>> {
    init_winrt()?;
    let bt_status = bluetooth_status();
    if !bt_status.available {
        return Ok(build_windows_snapshot(bt_status, &[], &[], &[]));
    }

    let mut devices = Vec::new();
    let classic_selector = BluetoothDevice::GetDeviceSelectorFromPairingState(true)?;
    devices.extend(query_paired_aep(
        &classic_selector,
        BluetoothTransport::Classic,
    )?);
    if let Ok(le_selector) = BluetoothLEDevice::GetDeviceSelectorFromPairingState(true) {
        if let Ok(le_devices) = query_paired_aep(&le_selector, BluetoothTransport::Le) {
            devices.extend(le_devices);
        }
    }
    let services = query_audio_aep_services();
    let endpoints = query_audio_endpoints()?;
    Ok(build_windows_snapshot(
        bt_status, &devices, &services, &endpoints,
    ))
}

#[implement(IMMNotificationClient)]
struct EndpointNotificationClient {
    tx: Sender<&'static str>,
}

impl IMMNotificationClient_Impl for EndpointNotificationClient_Impl {
    fn OnDeviceStateChanged(
        &self,
        _pwstrdeviceid: &PCWSTR,
        _dwnewstate: DEVICE_STATE,
    ) -> windows::core::Result<()> {
        let _ = self.tx.send("mmdevice-state");
        Ok(())
    }

    fn OnDeviceAdded(&self, _pwstrdeviceid: &PCWSTR) -> windows::core::Result<()> {
        let _ = self.tx.send("mmdevice-added");
        Ok(())
    }

    fn OnDeviceRemoved(&self, _pwstrdeviceid: &PCWSTR) -> windows::core::Result<()> {
        let _ = self.tx.send("mmdevice-removed");
        Ok(())
    }

    fn OnDefaultDeviceChanged(
        &self,
        _flow: EDataFlow,
        _role: ERole,
        _pwstrdefaultdeviceid: &PCWSTR,
    ) -> windows::core::Result<()> {
        let _ = self.tx.send("mmdevice-default");
        Ok(())
    }

    fn OnPropertyValueChanged(
        &self,
        _pwstrdeviceid: &PCWSTR,
        _key: &PROPERTYKEY,
    ) -> windows::core::Result<()> {
        let _ = self.tx.send("mmdevice-property");
        Ok(())
    }
}

fn start_aep_watcher(
    selector: &HSTRING,
    tx: Sender<&'static str>,
) -> windows::core::Result<DeviceWatcher> {
    let properties: IIterable<HSTRING> = vec![
        PROP_CONTAINER.into(),
        PROP_ADDRESS.into(),
        PROP_CONNECTED.into(),
    ]
    .into();
    let watcher = DeviceInformation::CreateWatcherWithKindAqsFilterAndAdditionalProperties(
        selector,
        &properties,
        DeviceInformationKind::AssociationEndpoint,
    )?;
    let tx_added = tx.clone();
    watcher.Added(&TypedEventHandler::<DeviceWatcher, DeviceInformation>::new(
        move |_, _| {
            let _ = tx_added.send("winrt-added");
            Ok(())
        },
    ))?;
    let tx_updated = tx.clone();
    watcher.Updated(&TypedEventHandler::<DeviceWatcher, DeviceInformationUpdate>::new(
        move |_, _| {
            let _ = tx_updated.send("winrt-updated");
            Ok(())
        },
    ))?;
    watcher.Removed(&TypedEventHandler::<DeviceWatcher, DeviceInformationUpdate>::new(
        move |_, _| {
            let _ = tx.send("winrt-removed");
            Ok(())
        },
    ))?;
    watcher.Start()?;
    Ok(watcher)
}

pub fn watch(options: &CliOptions) -> Result<(), Box<dyn Error>> {
    init_winrt()?;
    let (tx, rx) = mpsc::channel::<&'static str>();

    let enumerator: IMMDeviceEnumerator =
        unsafe { CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)? };
    let client: IMMNotificationClient = EndpointNotificationClient { tx: tx.clone() }.into();
    unsafe { enumerator.RegisterEndpointNotificationCallback(&client)? };

    let mut watchers = Vec::new();
    if let Ok(selector) = BluetoothDevice::GetDeviceSelectorFromPairingState(true) {
        if let Ok(watcher) = start_aep_watcher(&selector, tx.clone()) {
            watchers.push(watcher);
        }
    }
    if let Ok(selector) = BluetoothLEDevice::GetDeviceSelectorFromPairingState(true) {
        if let Ok(watcher) = start_aep_watcher(&selector, tx.clone()) {
            watchers.push(watcher);
        }
    }

    let initial = filter_snapshot(&collect_snapshot()?, options.filter.as_deref());
    let ts = now_timestamp();
    if options.json {
        println!("{}", format_snapshot_json(&ts, &initial));
    } else {
        print!("{}", format_snapshot_text(&ts, &initial));
    }
    if options.once {
        for watcher in watchers {
            let _ = watcher.Stop();
        }
        unsafe {
            let _ = enumerator.UnregisterEndpointNotificationCallback(&client);
        }
        return Ok(());
    }

    let mut previous = initial;
    let poll = Duration::from_millis(options.poll_ms);
    let debounce = Duration::from_millis(150);

    loop {
        match rx.recv_timeout(poll) {
            Ok(_) => {
                let deadline = Instant::now() + debounce;
                while let Some(remaining) = deadline.checked_duration_since(Instant::now()) {
                    if rx.recv_timeout(remaining).is_err() {
                        break;
                    }
                }
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => break,
        }

        let next = match collect_snapshot() {
            Ok(snapshot) => filter_snapshot(&snapshot, options.filter.as_deref()),
            Err(err) => {
                eprintln!("[{}] ERROR {err}", now_timestamp());
                continue;
            }
        };
        let events = diff_snapshots(&previous, &next);
        if !events.is_empty() {
            let ts = now_timestamp();
            for event in &events {
                if options.json {
                    println!("{}", format_event_json(&ts, event));
                } else {
                    println!("{}", format_event_text(&ts, event));
                }
            }
        }
        previous = next;
    }

    for watcher in watchers {
        let _ = watcher.Stop();
    }
    unsafe {
        let _ = enumerator.UnregisterEndpointNotificationCallback(&client);
    }
    Ok(())
}
