use super::Result;
use std::collections::HashSet;
use std::mem::size_of;
use windows::{
    core::{GUID, HSTRING, PWSTR},
    Devices::{
        Bluetooth::BluetoothDevice,
        Enumeration::{DeviceInformation, DeviceInformationKind},
    },
    Win32::{
        Devices::FunctionDiscovery::{PKEY_Device_ContainerId, PKEY_Device_FriendlyName},
        Media::{Audio::*, KernelStreaming::*},
        System::Com::{
            CoCreateInstance, CoTaskMemFree, StructuredStorage::*, CLSCTX_ALL, STGM_READ,
        },
    },
};
use windows_collections::IIterable;

fn take_string(string: PWSTR) -> Result<String> {
    unsafe {
        let text = string.to_string();
        CoTaskMemFree(Some(string.0.cast()));
        Ok(text?)
    }
}

pub fn container(endpoint: &IMMDevice) -> windows::core::Result<GUID> {
    unsafe {
        let store = endpoint.OpenPropertyStore(STGM_READ)?;
        let mut value = store.GetValue(&PKEY_Device_ContainerId)?;
        let guid = PropVariantToGUID(&value);
        PropVariantClear(&mut value)?;
        guid
    }
}

fn target_container(address: &str) -> Result<GUID> {
    let address = u64::from_str_radix(address, 16)?;
    let selector = BluetoothDevice::GetDeviceSelectorFromBluetoothAddress(address)?;
    let properties: IIterable<HSTRING> = vec![super::facts::CONTAINER.into()].into();
    let devices = DeviceInformation::FindAllAsyncWithKindAqsFilterAndAdditionalProperties(
        &selector,
        &properties,
        DeviceInformationKind::AssociationEndpoint,
    )?
    .get()?;
    for device in devices {
        if device.Pairing()?.IsPaired()? {
            return Ok(super::facts::property(&device, super::facts::CONTAINER)?.GetGuid()?);
        }
    }
    Err("Windows 尚未配对目标耳机".into())
}

fn endpoints(
    audio: &IMMDeviceEnumerator,
    id: GUID,
    flow: EDataFlow,
    states: DEVICE_STATE,
) -> Result<Vec<IMMDevice>> {
    unsafe {
        let collection = audio.EnumAudioEndpoints(flow, states)?;
        let mut matching = Vec::new();
        for index in 0..collection.GetCount()? {
            let endpoint = collection.Item(index)?;
            if container(&endpoint) == Ok(id) {
                matching.push(endpoint);
            }
        }
        Ok(matching)
    }
}

fn capture_active(endpoint: &IMMDevice) -> Result<bool> {
    unsafe {
        let manager: IAudioSessionManager2 = endpoint.Activate(CLSCTX_ALL, None)?;
        let sessions = manager.GetSessionEnumerator()?;
        for index in 0..sessions.GetCount()? {
            if sessions.GetSession(index)?.GetState()? == AudioSessionStateActive {
                return Ok(true);
            }
        }
        Ok(false)
    }
}

fn state(audio: &IMMDeviceEnumerator, id: GUID) -> Result<()> {
    let render = endpoints(audio, id, eRender, DEVICE_STATE_ACTIVE)?;
    let capture = endpoints(audio, id, eCapture, DEVICE_STATE_ACTIVE)?;
    let mut recording = false;
    for endpoint in &capture {
        if capture_active(endpoint)? {
            recording = true;
            break;
        }
    }
    println!(
        "{:?}/{}/{}/{}",
        id,
        u8::from(!render.is_empty()),
        u8::from(!capture.is_empty()),
        u8::from(recording)
    );
    Ok(())
}

fn send_to_filter(
    audio: &IMMDeviceEnumerator,
    filter_id: &str,
    action: KSPROPERTY_BTAUDIO,
) -> Result<()> {
    unsafe {
        let filter = audio.GetDevice(&HSTRING::from(filter_id))?;
        let control: IKsControl = filter.Activate(CLSCTX_ALL, None)?;
        let property = KSIDENTIFIER {
            Anonymous: KSIDENTIFIER_0 {
                Anonymous: KSIDENTIFIER_0_0 {
                    Set: KSPROPSETID_BtAudio,
                    Id: action.0 as u32,
                    Flags: KSPROPERTY_TYPE_GET,
                },
            },
        };
        let mut returned = 0;
        control.KsProperty(
            &property,
            size_of::<KSIDENTIFIER>() as u32,
            std::ptr::null_mut(),
            0,
            &mut returned,
        )?;
    }
    Ok(())
}

fn request(audio: &IMMDeviceEnumerator, id: GUID, action: KSPROPERTY_BTAUDIO) -> Result<()> {
    let eligible = endpoints(
        audio,
        id,
        eAll,
        DEVICE_STATE(DEVICE_STATE_ACTIVE.0 | DEVICE_STATE_UNPLUGGED.0),
    )?;
    let mut filters = HashSet::new();
    for endpoint in eligible {
        unsafe {
            let topology: IDeviceTopology = endpoint.Activate(CLSCTX_ALL, None)?;
            for index in 0..topology.GetConnectorCount()? {
                if let Ok(filter_id) = topology.GetConnector(index)?.GetDeviceIdConnectedTo() {
                    filters.insert(take_string(filter_id)?);
                }
            }
        }
    }
    let mut last_error = None;
    let mut accepted = false;
    for filter in filters {
        match send_to_filter(audio, &filter, action) {
            Ok(()) => accepted = true,
            Err(error) => last_error = Some(error),
        }
    }
    if accepted {
        Ok(())
    } else {
        Err(last_error.unwrap_or_else(|| "Windows 未找到可操作的蓝牙音频端点".into()))
    }
}

fn defaults(audio: &IMMDeviceEnumerator) -> Result<()> {
    unsafe {
        for role in [eConsole, eMultimedia, eCommunications] {
            let Ok(endpoint) = audio.GetDefaultAudioEndpoint(eRender, role) else {
                continue;
            };
            let id = take_string(endpoint.GetId()?)?;
            let store = endpoint.OpenPropertyStore(STGM_READ)?;
            let mut value = store.GetValue(&PKEY_Device_FriendlyName)?;
            let label = PropVariantToStringAlloc(&value);
            PropVariantClear(&mut value)?;
            println!(
                "{}/{}/{}/{:?}",
                role.0,
                hex::encode(id),
                hex::encode(take_string(label?)?),
                container(&endpoint)?
            );
        }
    }
    Ok(())
}

fn restore_default(role: &str, endpoint: &str) -> Result<()> {
    use com_policy_config::{IPolicyConfig, PolicyConfigClient};
    use windows::{
        core::HSTRING,
        Win32::{
            Media::Audio::ERole,
            System::Com::{CoCreateInstance, CLSCTX_ALL},
        },
    };
    let role: i32 = role.parse()?;
    if !(0..=2).contains(&role) {
        return Err("无效的系统音频角色".into());
    }
    let id = String::from_utf8(hex::decode(endpoint)?)?;
    unsafe {
        let policy: IPolicyConfig = CoCreateInstance(&PolicyConfigClient, None, CLSCTX_ALL)?;
        policy.SetDefaultEndpoint(&HSTRING::from(id), ERole(role))?;
    }
    Ok(())
}

pub fn run(args: &[String]) -> Result<()> {
    let args: Vec<_> = args.iter().map(String::as_str).collect();
    let audio: IMMDeviceEnumerator =
        unsafe { CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)? };
    match args.as_slice() {
        ["defaults"] => defaults(&audio),
        ["restore", role, endpoint] => restore_default(role, endpoint),
        ["state", address] => state(&audio, target_container(address)?),
        ["connect", address] => request(
            &audio,
            target_container(address)?,
            KSPROPERTY_ONESHOT_RECONNECT,
        ),
        ["disconnect", address] => request(
            &audio,
            target_container(address)?,
            KSPROPERTY_ONESHOT_DISCONNECT,
        ),
        _ => Err("未知的 Windows 音频操作".into()),
    }
}
