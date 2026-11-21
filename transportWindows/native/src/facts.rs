use super::Result;
use std::collections::HashSet;
use windows::{
    core::{Interface, GUID, HSTRING},
    Devices::{
        Bluetooth::{BluetoothAdapter, BluetoothDevice},
        Enumeration::{DeviceInformation, DeviceInformationKind},
        Radios::RadioState,
    },
    Foundation::IPropertyValue,
    Win32::{
        Media::Audio::{eRender, IMMDeviceEnumerator, MMDeviceEnumerator, DEVICE_STATE_ACTIVE},
        System::Com::{CoCreateInstance, CLSCTX_ALL},
    },
};
use windows_collections::IIterable;

pub const CONTAINER: &str = "System.Devices.Aep.ContainerId";
const ADDRESS: &str = "System.Devices.Aep.DeviceAddress";

fn active_render_containers() -> Result<HashSet<GUID>> {
    let mut active = HashSet::new();
    unsafe {
        let audio: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let endpoints = audio.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)?;
        for index in 0..endpoints.GetCount()? {
            if let Ok(id) = crate::audio::container(&endpoints.Item(index)?) {
                active.insert(id);
            }
        }
    }
    Ok(active)
}

pub fn property(device: &DeviceInformation, name: &str) -> windows::core::Result<IPropertyValue> {
    device.Properties()?.Lookup(&HSTRING::from(name))?.cast()
}

pub fn snapshot() -> Result<String> {
    let adapter = BluetoothAdapter::GetDefaultAsync()?.get()?;
    if !adapter.IsLowEnergySupported()? {
        return Ok("unavailable\tWindows 蓝牙适配器不支持 BLE".into());
    }
    match adapter.GetRadioAsync()?.get()?.State()? {
        RadioState::On => (),
        RadioState::Off | RadioState::Disabled => return Ok("disabled".into()),
        _ => return Ok("unavailable\tWindows 蓝牙适配器状态未知".into()),
    }
    let selector = BluetoothDevice::GetDeviceSelectorFromPairingState(true)?;
    let properties: IIterable<HSTRING> = vec![CONTAINER.into(), ADDRESS.into()].into();
    let devices = DeviceInformation::FindAllAsyncWithKindAqsFilterAndAdditionalProperties(
        &selector,
        &properties,
        DeviceInformationKind::AssociationEndpoint,
    )?
    .get()?;
    let active = active_render_containers()?;
    let mut fields = vec!["available".to_owned()];
    for device in devices {
        let Some(address) = property(&device, ADDRESS)
            .ok()
            .and_then(|p| p.GetString().ok())
        else {
            continue;
        };
        let address = address.to_string().replace([':', '-'], "");
        if address.len() != 12 || !address.bytes().all(|b| b.is_ascii_hexdigit()) {
            continue;
        }
        let connected = property(&device, CONTAINER)
            .and_then(|p| p.GetGuid())
            .is_ok_and(|id| active.contains(&id));
        fields.push(format!(
            "{}/{}/{}",
            address,
            hex::encode(device.Name()?.to_string()),
            u8::from(connected)
        ));
    }
    Ok(fields.join("\t"))
}
