use super::Result;
use std::io::{self, BufRead};
use windows::{
    Devices::Bluetooth::Advertisement::{
        BluetoothLEAdvertisementReceivedEventArgs, BluetoothLEAdvertisementWatcher,
        BluetoothLEAdvertisementWatcherStoppedEventArgs, BluetoothLEScanningMode,
    },
    Foundation::TypedEventHandler,
    Storage::Streams::{DataReader, IBuffer},
};

pub fn bytes(buffer: &IBuffer) -> windows::core::Result<Vec<u8>> {
    let mut bytes = vec![0; buffer.Length()? as usize];
    DataReader::FromBuffer(buffer)?.ReadBytes(&mut bytes)?;
    Ok(bytes)
}

pub fn watch() -> Result<()> {
    let watcher = BluetoothLEAdvertisementWatcher::new()?;
    watcher.SetScanningMode(BluetoothLEScanningMode::Active)?;
    watcher.Received(&TypedEventHandler::<
        _,
        BluetoothLEAdvertisementReceivedEventArgs,
    >::new(|_, event| {
        let event = event.ok()?;
        let ad = event.Advertisement()?;
        let mut fields = vec![format!(
            "ad\t{:012X}\t{}\t{}",
            event.BluetoothAddress()?,
            hex::encode(ad.LocalName()?.to_string()),
            event.RawSignalStrengthInDBm()?
        )];
        for section in ad.ManufacturerData()? {
            fields.push(format!(
                "{}:{}",
                section.CompanyId()?,
                hex::encode(bytes(&section.Data()?)?)
            ));
        }
        println!("{}", fields.join("\t"));
        Ok(())
    }))?;
    watcher.Stopped(&TypedEventHandler::<
        _,
        BluetoothLEAdvertisementWatcherStoppedEventArgs,
    >::new(|_, event| {
        println!("Windows BLE 扫描已停止：{:?}", event.ok()?.Error()?);
        Ok(())
    }))?;
    watcher.Start()?;
    let _ = io::stdin().lock().lines().next();
    watcher.Stop()?;
    Ok(())
}

/// Paired Classic audio does not guarantee that Windows has cached the LE address.
/// Observe this exact address before opening an unpaired GATT device (including random addresses).
pub fn find_device(address: u64) -> Result<windows::Devices::Bluetooth::BluetoothLEDevice> {
    use std::sync::mpsc::sync_channel;
    use std::time::Duration;
    use windows::Devices::Bluetooth::BluetoothLEDevice;
    use windows::Win32::Foundation::E_ACCESSDENIED;

    match BluetoothLEDevice::FromBluetoothAddressAsync(address)?.get() {
        Ok(device) => return Ok(device),
        Err(error) if error.code() == E_ACCESSDENIED => return Err(error.into()),
        Err(_) => {}
    }
    let watcher = BluetoothLEAdvertisementWatcher::new()?;
    let (send, result) = sync_channel(1);
    watcher.Received(&TypedEventHandler::<
        _,
        BluetoothLEAdvertisementReceivedEventArgs,
    >::new(move |_, event| {
        let event = event.ok()?;
        if event.BluetoothAddress()? == address {
            let _ = send.try_send(event.BluetoothAddressType()?);
        }
        Ok(())
    }))?;
    watcher.Start()?;
    let kind = result.recv_timeout(Duration::from_secs(5));
    watcher.Stop()?;
    let kind = kind.map_err(|_| "未在 Windows BLE 扫描中找到目标耳机")?;
    Ok(
        BluetoothLEDevice::FromBluetoothAddressWithBluetoothAddressTypeAsync(address, kind)?
            .get()?,
    )
}
