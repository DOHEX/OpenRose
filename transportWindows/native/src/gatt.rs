use super::{clean, scan::bytes, Result};
use std::{
    collections::HashMap,
    io::{self, BufRead},
};
use windows::{
    core::{Error, GUID},
    Devices::Bluetooth::{
        BluetoothCacheMode, BluetoothConnectionStatus, BluetoothLEDevice,
        GenericAttributeProfile::*,
    },
    Foundation::TypedEventHandler,
    Storage::Streams::DataWriter,
    Win32::Foundation::{E_ACCESSDENIED, E_INVALIDARG, E_NOTIMPL},
};

struct Session {
    address: u64,
    uuid: GUID,
    device: Option<BluetoothLEDevice>,
    service: Option<GattDeviceService>,
    characteristics: HashMap<u16, GattCharacteristic>,
    subscriptions: HashMap<u16, i64>,
}

fn check(status: GattCommunicationStatus) -> Result<()> {
    match status {
        GattCommunicationStatus::Success => Ok(()),
        GattCommunicationStatus::AccessDenied => {
            Err(Error::new(E_ACCESSDENIED, "Windows 拒绝访问 GATT").into())
        }
        GattCommunicationStatus::Unreachable => Err("耳机暂时无法连接".into()),
        _ => Err(format!("Windows GATT 操作失败 ({})", status.0).into()),
    }
}

fn failure_kind(error: &(dyn std::error::Error + 'static)) -> &'static str {
    match error.downcast_ref::<Error>().map(Error::code) {
        Some(E_ACCESSDENIED) => "PERMISSION",
        Some(E_INVALIDARG) => "INVALID_VALUE",
        Some(E_NOTIMPL) => "UNSUPPORTED",
        _ => "TRANSIENT",
    }
}

impl Session {
    fn connect(&mut self) -> Result<()> {
        let device = crate::scan::find_device(self.address)?;
        device.ConnectionStatusChanged(&TypedEventHandler::<BluetoothLEDevice, _>::new(
            |device, _| {
                if device.ok()?.ConnectionStatus()? == BluetoothConnectionStatus::Disconnected {
                    println!("gone");
                }
                Ok(())
            },
        ))?;
        device.GattServicesChanged(&TypedEventHandler::new(|_, _| {
            println!("changed");
            Ok(())
        }))?;
        self.device = Some(device);
        Ok(())
    }

    fn discover(&mut self) -> Result<String> {
        self.characteristics.clear();
        if let Some(service) = self.service.take() {
            service.Close()?;
        }
        let device = self.device.as_ref().ok_or("GATT 未连接")?;
        let result = device
            .GetGattServicesForUuidWithCacheModeAsync(self.uuid, BluetoothCacheMode::Uncached)?
            .get()?;
        check(result.Status()?)?;
        let services = result.Services()?;
        if services.Size()? == 0 {
            return Ok(String::new());
        }
        let service = services.GetAt(0)?;
        let result = service
            .GetCharacteristicsWithCacheModeAsync(BluetoothCacheMode::Uncached)?
            .get()?;
        check(result.Status()?)?;
        let mut fields = vec![service.Session()?.MaxPduSize()?.to_string()];
        for characteristic in result.Characteristics()? {
            let id = characteristic.AttributeHandle()?;
            let props = characteristic.CharacteristicProperties()?;
            let mut flags = String::new();
            for (flag, ch) in [
                (GattCharacteristicProperties::Write, 'w'),
                (GattCharacteristicProperties::WriteWithoutResponse, 'c'),
                (GattCharacteristicProperties::Notify, 'n'),
                (GattCharacteristicProperties::Indicate, 'i'),
            ] {
                if props.contains(flag) {
                    flags.push(ch);
                }
            }
            fields.push(
                format!("{}/{:?}/{}", id, characteristic.Uuid()?, flags).to_ascii_lowercase(),
            );
            self.characteristics.insert(id, characteristic);
        }
        self.service = Some(service);
        Ok(fields.join("\t"))
    }

    fn command(&mut self, cmd: &[&str]) -> Result<String> {
        match cmd {
            ["connect"] => {
                self.connect()?;
            }
            ["discover"] => return self.discover(),
            [op, id, args @ ..] => {
                let id: u16 = id.parse()?;
                let characteristic = self.characteristics.get(&id).ok_or("未知 GATT 特征")?;
                match (*op, args) {
                    ("route", ["true"]) => {
                        let token = characteristic.ValueChanged(&TypedEventHandler::<
                            _,
                            GattValueChangedEventArgs,
                        >::new(
                            move |_, value| {
                                println!(
                                    "value\t{id}\t{}",
                                    hex::encode(bytes(&value.ok()?.CharacteristicValue()?)?)
                                );
                                Ok(())
                            },
                        ))?;
                        self.subscriptions.insert(id, token);
                    }
                    ("route", ["false"]) => {
                        if let Some(token) = self.subscriptions.remove(&id) {
                            characteristic.RemoveValueChanged(token)?;
                        }
                    }
                    ("subscribe", [kind]) => {
                        let value = match *kind {
                            "NOTIFY" => {
                                GattClientCharacteristicConfigurationDescriptorValue::Notify
                            }
                            "INDICATE" => {
                                GattClientCharacteristicConfigurationDescriptorValue::Indicate
                            }
                            "DISABLED" => {
                                GattClientCharacteristicConfigurationDescriptorValue::None
                            }
                            _ => return Err("未知订阅类型".into()),
                        };
                        check(
                            characteristic
                                .WriteClientCharacteristicConfigurationDescriptorAsync(value)?
                                .get()?,
                        )?;
                    }
                    ("write", [response, payload]) => {
                        let option = if *response == "true" {
                            GattWriteOption::WriteWithResponse
                        } else {
                            GattWriteOption::WriteWithoutResponse
                        };
                        let writer = DataWriter::new()?;
                        writer.WriteBytes(&hex::decode(payload)?)?;
                        check(
                            characteristic
                                .WriteValueWithOptionAsync(&writer.DetachBuffer()?, option)?
                                .get()?,
                        )?;
                    }
                    _ => return Err("未知 GATT 请求".into()),
                }
            }
            _ => return Err("未知 GATT 请求".into()),
        }
        Ok(String::new())
    }
}

pub fn serve(address: &str, service: &str) -> Result<()> {
    let mut session = Session {
        address: u64::from_str_radix(address, 16)?,
        uuid: GUID::try_from(service)?,
        device: None,
        service: None,
        characteristics: HashMap::new(),
        subscriptions: HashMap::new(),
    };
    for line in io::stdin().lock().lines() {
        let line = line?;
        let fields: Vec<_> = line.split('\t').collect();
        if let Some((id, cmd)) = fields.split_first() {
            match session.command(cmd) {
                Ok(response) if response.is_empty() => println!("{id}\tok"),
                Ok(response) => println!("{id}\tok\t{response}"),
                Err(error) => {
                    let kind = failure_kind(error.as_ref());
                    let message = clean(error);
                    println!("{id}\terr\t{kind}\t{message}");
                }
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hresult_controls_recovery_independently_of_the_display_message() {
        for (code, expected) in [
            (E_ACCESSDENIED, "PERMISSION"),
            (E_INVALIDARG, "INVALID_VALUE"),
            (E_NOTIMPL, "UNSUPPORTED"),
        ] {
            assert_eq!(
                failure_kind(&Error::new(code, "Native operation failed")),
                expected
            );
        }
    }

    #[test]
    fn gatt_status_and_native_access_denied_use_the_same_wire_kind() {
        let denied = check(GattCommunicationStatus::AccessDenied).unwrap_err();
        assert_eq!(failure_kind(denied.as_ref()), "PERMISSION");
        let unreachable = check(GattCommunicationStatus::Unreachable).unwrap_err();
        assert_eq!(failure_kind(unreachable.as_ref()), "TRANSIENT");
    }
}
