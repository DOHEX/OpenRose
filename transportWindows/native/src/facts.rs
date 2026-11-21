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
        Devices::FunctionDiscovery::PKEY_Device_ContainerId,
        Media::Audio::{eRender, IMMDeviceEnumerator, MMDeviceEnumerator, DEVICE_STATE_ACTIVE},
        System::Com::{
            CoCreateInstance,
            StructuredStorage::{PropVariantClear, PropVariantToGUID},
            CLSCTX_ALL, STGM_READ,
        },
    },
};
use windows_collections::IIterable;

const CONTAINER: &str = "System.Devices.Aep.ContainerId";
const ADDRESS: &str = "System.Devices.Aep.DeviceAddress";

fn active_render_containers() -> Result<HashSet<GUID>> {
    let mut active = HashSet::new();
    unsafe {
        let audio: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let endpoints = audio.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)?;
        for index in 0..endpoints.GetCount()? {
            let store = endpoints.Item(index)?.OpenPropertyStore(STGM_READ)?;
            if let Ok(mut value) = store.GetValue(&PKEY_Device_ContainerId) {
                if let Ok(id) = PropVariantToGUID(&value) {
                    active.insert(id);
                }
                PropVariantClear(&mut value)?;
            }
        }
    }
    Ok(active)
}

fn property(device: &DeviceInformation, name: &str) -> windows::core::Result<IPropertyValue> {
    device.Properties()?.Lookup(&HSTRING::from(name))?.cast()
}

pub fn snapshot() -> Result<String> {
    let adapter = BluetoothAdapter::GetDefaultAsync()?.join()?;
    if !adapter.IsLowEnergySupported()? {
        return Ok("unavailable\tWindows 蓝牙适配器不支持 BLE".into());
    }
    match adapter.GetRadioAsync()?.join()?.State()? {
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
    .join()?;
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
