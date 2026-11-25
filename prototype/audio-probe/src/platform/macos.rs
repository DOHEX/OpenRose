use crate::classify::build_macos_snapshot;
use crate::cli::CliOptions;
use crate::diff::{diff_snapshots, filter_snapshot};
use crate::format::{
    format_event_json, format_event_text, format_snapshot_json, format_snapshot_text, now_timestamp,
};
use crate::model::{
    BluetoothStatus, MacAudioTransport, MacBluetoothObservation, MacCoreAudioObservation,
    ProbeSnapshot,
};
use objc2::rc::Retained;
use objc2::runtime::AnyObject;
use objc2::{define_class, msg_send, sel, AnyThread};
use objc2_core_audio::{
    kAudioDevicePropertyDeviceIsAlive, kAudioDevicePropertyDeviceIsRunningSomewhere,
    kAudioDevicePropertyDeviceUID, kAudioDevicePropertyNominalSampleRate,
    kAudioDevicePropertyStreamConfiguration, kAudioDevicePropertyTransportType,
    kAudioDeviceTransportTypeBluetooth, kAudioDeviceTransportTypeBluetoothLE,
    kAudioHardwarePropertyDevices, kAudioObjectPropertyElementMain, kAudioObjectPropertyName,
    kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyScopeInput,
    kAudioObjectPropertyScopeOutput, kAudioObjectSystemObject,
    AudioObjectAddPropertyListener, AudioObjectGetPropertyData, AudioObjectGetPropertyDataSize,
    AudioObjectID, AudioObjectPropertyAddress, AudioObjectPropertyScope,
    AudioObjectPropertySelector, AudioObjectRemovePropertyListener,
};
use objc2_core_audio_types::AudioBufferList;
use objc2_core_foundation::{
    kCFRunLoopDefaultMode, CFRetained, CFRunLoop, CFString,
};
use objc2_foundation::NSObject;
use objc2_io_bluetooth::{
    BluetoothHCIPowerState, IOBluetoothDevice, IOBluetoothHostController, IOBluetoothSDPUUID,
    IOBluetoothUserNotification,
};
use std::collections::{BTreeMap, BTreeSet};
use std::error::Error;
use std::ffi::c_void;
use std::mem::{align_of, size_of};
use std::ptr::{self, NonNull};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

static DIRTY: AtomicBool = AtomicBool::new(false);

fn mark_dirty() {
    DIRTY.store(true, Ordering::SeqCst);
    if let Some(rl) = CFRunLoop::main() {
        rl.wake_up();
    }
}

unsafe extern "C-unwind" fn coreaudio_property_listener(
    _in_object_id: AudioObjectID,
    _in_number_addresses: u32,
    _in_addresses: NonNull<AudioObjectPropertyAddress>,
    _in_client_data: *mut c_void,
) -> i32 {
    mark_dirty();
    0
}

struct ObserverIvars;

define_class!(
    #[unsafe(super(NSObject))]
    #[name = "OpenRoseAudioProbeObserver"]
    #[ivars = ObserverIvars]
    struct BluetoothObserver;

    impl BluetoothObserver {
        #[unsafe(method(onDeviceConnected:device:))]
        fn on_device_connected(
            &self,
            _notification: Option<&IOBluetoothUserNotification>,
            _device: Option<&IOBluetoothDevice>,
        ) {
            mark_dirty();
        }

        #[unsafe(method(onDeviceDisconnected:device:))]
        fn on_device_disconnected(
            &self,
            _notification: Option<&IOBluetoothUserNotification>,
            _device: Option<&IOBluetoothDevice>,
        ) {
            mark_dirty();
        }
    }
);

impl BluetoothObserver {
    fn new() -> Retained<Self> {
        let this = Self::alloc().set_ivars(ObserverIvars);
        unsafe { msg_send![super(this), init] }
    }
}

fn prop_addr(
    selector: AudioObjectPropertySelector,
    scope: AudioObjectPropertyScope,
) -> AudioObjectPropertyAddress {
    AudioObjectPropertyAddress {
        mSelector: selector,
        mScope: scope,
        mElement: kAudioObjectPropertyElementMain,
    }
}

fn get_audio_u32(
    object_id: AudioObjectID,
    selector: AudioObjectPropertySelector,
    scope: AudioObjectPropertyScope,
) -> Option<u32> {
    let address = prop_addr(selector, scope);
    let mut size = size_of::<u32>() as u32;
    let mut value: u32 = 0;
    let status = unsafe {
        AudioObjectGetPropertyData(
            object_id,
            NonNull::from(&address),
            0,
            ptr::null(),
            NonNull::from(&mut size),
            NonNull::from(&mut value).cast(),
        )
    };
    if status == 0 {
        Some(value)
    } else {
        None
    }
}

fn get_audio_f64(
    object_id: AudioObjectID,
    selector: AudioObjectPropertySelector,
    scope: AudioObjectPropertyScope,
) -> Option<f64> {
    let address = prop_addr(selector, scope);
    let mut size = size_of::<f64>() as u32;
    let mut value: f64 = 0.0;
    let status = unsafe {
        AudioObjectGetPropertyData(
            object_id,
            NonNull::from(&address),
            0,
            ptr::null(),
            NonNull::from(&mut size),
            NonNull::from(&mut value).cast(),
        )
    };
    if status == 0 {
        Some(value)
    } else {
        None
    }
}

fn get_audio_cfstring(
    object_id: AudioObjectID,
    selector: AudioObjectPropertySelector,
) -> Option<String> {
    let address = prop_addr(selector, kAudioObjectPropertyScopeGlobal);
    let mut size = size_of::<*const CFString>() as u32;
    let mut raw: *const CFString = ptr::null();
    let status = unsafe {
        AudioObjectGetPropertyData(
            object_id,
            NonNull::from(&address),
            0,
            ptr::null(),
            NonNull::from(&mut size),
            NonNull::from(&mut raw).cast(),
        )
    };
    if status != 0 {
        return None;
    }
    let cf = NonNull::new(raw.cast_mut())?;
    let retained = unsafe { CFRetained::from_raw(cf) };
    Some(retained.to_string())
}

fn get_stream_channels(device_id: AudioObjectID, scope: AudioObjectPropertyScope) -> u32 {
    let address = prop_addr(kAudioDevicePropertyStreamConfiguration, scope);
    let mut size: u32 = 0;
    let status = unsafe {
        AudioObjectGetPropertyDataSize(
            device_id,
            NonNull::from(&address),
            0,
            ptr::null(),
            NonNull::from(&mut size),
        )
    };
    if status != 0 || (size as usize) < size_of::<AudioBufferList>() {
        return 0;
    }
    let word_count = (size as usize).div_ceil(align_of::<AudioBufferList>());
    let mut storage = vec![0usize; word_count.max(1)];
    let mut actual_size = size;
    let status = unsafe {
        AudioObjectGetPropertyData(
            device_id,
            NonNull::from(&address),
            0,
            ptr::null(),
            NonNull::from(&mut actual_size),
            NonNull::new(storage.as_mut_ptr().cast()).unwrap(),
        )
    };
    if status != 0 {
        return 0;
    }
    unsafe {
        let list = &*(storage.as_ptr().cast::<AudioBufferList>());
        let buffers =
            std::slice::from_raw_parts(list.mBuffers.as_ptr(), list.mNumberBuffers as usize);
        buffers.iter().map(|b| b.mNumberChannels).sum()
    }
}

fn list_coreaudio_device_ids() -> Vec<AudioObjectID> {
    let address = prop_addr(
        kAudioHardwarePropertyDevices,
        kAudioObjectPropertyScopeGlobal,
    );
    let mut size: u32 = 0;
    let status = unsafe {
        AudioObjectGetPropertyDataSize(
            kAudioObjectSystemObject as AudioObjectID,
            NonNull::from(&address),
            0,
            ptr::null(),
            NonNull::from(&mut size),
        )
    };
    if status != 0 || size == 0 {
        return Vec::new();
    }
    let count = (size as usize) / size_of::<AudioObjectID>();
    let mut ids = vec![0u32; count];
    let mut actual_size = size;
    let status = unsafe {
        AudioObjectGetPropertyData(
            kAudioObjectSystemObject as AudioObjectID,
            NonNull::from(&address),
            0,
            ptr::null(),
            NonNull::from(&mut actual_size),
            NonNull::new(ids.as_mut_ptr().cast()).unwrap(),
        )
    };
    if status != 0 {
        return Vec::new();
    }
    let actual_count = (actual_size as usize) / size_of::<AudioObjectID>();
    ids.truncate(actual_count);
    ids
}

fn query_coreaudio_bluetooth_devices() -> Vec<MacCoreAudioObservation> {
    let mut out = Vec::new();
    for device_id in list_coreaudio_device_ids() {
        let Some(transport_raw) = get_audio_u32(
            device_id,
            kAudioDevicePropertyTransportType,
            kAudioObjectPropertyScopeGlobal,
        ) else {
            continue;
        };
        let transport = if transport_raw == kAudioDeviceTransportTypeBluetooth {
            MacAudioTransport::BluetoothClassic
        } else if transport_raw == kAudioDeviceTransportTypeBluetoothLE {
            MacAudioTransport::BluetoothLe
        } else {
            continue;
        };
        let uid = get_audio_cfstring(device_id, kAudioDevicePropertyDeviceUID)
            .unwrap_or_else(|| format!("device-{device_id}"));
        let name = get_audio_cfstring(device_id, kAudioObjectPropertyName).unwrap_or_default();
        let alive = get_audio_u32(
            device_id,
            kAudioDevicePropertyDeviceIsAlive,
            kAudioObjectPropertyScopeGlobal,
        )
        .unwrap_or(1)
            != 0;
        let output_channels = get_stream_channels(device_id, kAudioObjectPropertyScopeOutput);
        let input_channels = get_stream_channels(device_id, kAudioObjectPropertyScopeInput);
        let nominal_sample_rate_hz = get_audio_f64(
            device_id,
            kAudioDevicePropertyNominalSampleRate,
            kAudioObjectPropertyScopeGlobal,
        );
        let output_running = get_audio_u32(
            device_id,
            kAudioDevicePropertyDeviceIsRunningSomewhere,
            kAudioObjectPropertyScopeOutput,
        )
        .unwrap_or(0)
            != 0;
        let input_running = get_audio_u32(
            device_id,
            kAudioDevicePropertyDeviceIsRunningSomewhere,
            kAudioObjectPropertyScopeInput,
        )
        .unwrap_or(0)
            != 0;
        out.push(MacCoreAudioObservation {
            device_id,
            uid,
            name,
            transport,
            alive,
            output_channels,
            input_channels,
            nominal_sample_rate_hz,
            output_running,
            input_running,
        });
    }
    out
}

fn sdp_profiles_for_device(device: &IOBluetoothDevice) -> Vec<String> {
    let checks: &[(u16, &str)] = &[
        (0x110B, "a2dp-sink"),
        (0x110A, "a2dp-source"),
        (0x111E, "hfp"),
        (0x111F, "hfp-ag"),
        (0x1108, "hsp"),
        (0x110E, "avrcp"),
    ];
    let mut profiles = Vec::new();
    for &(uuid16, label) in checks {
        let uuid = unsafe { IOBluetoothSDPUUID::uuid16(uuid16) };
        if let Some(uuid) = uuid {
            let record = unsafe { device.getServiceRecordForUUID(Some(&uuid)) };
            if record.is_some() {
                profiles.push((*label).to_owned());
            }
        }
    }
    profiles
}

fn paired_iobluetooth_devices() -> Vec<Retained<IOBluetoothDevice>> {
    let mut out = Vec::new();
    let Some(array) = (unsafe { IOBluetoothDevice::pairedDevices() }) else {
        return out;
    };
    let count = array.count();
    for index in 0..count {
        let obj = array.objectAtIndex(index);
        if let Ok(device) = obj.downcast::<IOBluetoothDevice>() {
            out.push(device);
        }
    }
    out
}

fn query_iobluetooth_observations() -> Vec<MacBluetoothObservation> {
    let mut out = Vec::new();
    for device in paired_iobluetooth_devices() {
        let name = unsafe { device.nameOrAddress() }
            .map(|s| s.to_string())
            .unwrap_or_default();
        let address = unsafe { device.addressString() }
            .map(|s| s.to_string())
            .unwrap_or_default();
        let connected = unsafe { device.isConnected() };
        let hands_free_gateway = unsafe { device.isHandsFreeAudioGateway() };
        let hands_free_device = unsafe { device.isHandsFreeDevice() };
        let sdp_profiles = sdp_profiles_for_device(&device);
        out.push(MacBluetoothObservation {
            name,
            address,
            connected,
            hands_free_gateway,
            hands_free_device,
            sdp_profiles,
        });
    }
    out
}

fn device_listener_addresses() -> [AudioObjectPropertyAddress; 4] {
    [
        prop_addr(
            kAudioDevicePropertyNominalSampleRate,
            kAudioObjectPropertyScopeGlobal,
        ),
        prop_addr(
            kAudioDevicePropertyDeviceIsRunningSomewhere,
            kAudioObjectPropertyScopeOutput,
        ),
        prop_addr(
            kAudioDevicePropertyDeviceIsRunningSomewhere,
            kAudioObjectPropertyScopeInput,
        ),
        prop_addr(
            kAudioDevicePropertyDeviceIsAlive,
            kAudioObjectPropertyScopeGlobal,
        ),
    ]
}

fn sync_device_listeners(
    watched: &mut BTreeSet<AudioObjectID>,
    current_bt_ids: &[AudioObjectID],
) {
    let desired: BTreeSet<AudioObjectID> = current_bt_ids.iter().copied().collect();
    let addrs = device_listener_addresses();

    let removed: Vec<AudioObjectID> = watched.difference(&desired).copied().collect();
    for id in removed {
        for addr in &addrs {
            unsafe {
                let _ = AudioObjectRemovePropertyListener(
                    id,
                    NonNull::from(addr),
                    Some(coreaudio_property_listener),
                    ptr::null_mut(),
                );
            }
        }
        watched.remove(&id);
    }

    let added: Vec<AudioObjectID> = desired.difference(watched).copied().collect();
    for id in added {
        for addr in &addrs {
            unsafe {
                let _ = AudioObjectAddPropertyListener(
                    id,
                    NonNull::from(addr),
                    Some(coreaudio_property_listener),
                    ptr::null_mut(),
                );
            }
        }
        watched.insert(id);
    }
}

fn sync_disconnect_notifications(
    observer: &BluetoothObserver,
    registered: &mut BTreeMap<String, Retained<IOBluetoothUserNotification>>,
) {
    let mut currently_connected = BTreeSet::new();
    for device in paired_iobluetooth_devices() {
        if !unsafe { device.isConnected() } {
            continue;
        }
        let Some(addr) = (unsafe { device.addressString() }).map(|s| s.to_string()) else {
            continue;
        };
        currently_connected.insert(addr.clone());
        if let std::collections::btree_map::Entry::Vacant(slot) = registered.entry(addr) {
            if let Some(notif) = unsafe {
                device.registerForDisconnectNotification_selector(
                    Some(observer as &AnyObject),
                    Some(sel!(onDeviceDisconnected:device:)),
                )
            } {
                slot.insert(notif);
            }
        }
    }
    registered.retain(|addr, notif| {
        if currently_connected.contains(addr) {
            true
        } else {
            unsafe { notif.unregister() };
            false
        }
    });
}

fn bluetooth_status() -> BluetoothStatus {
    let Some(controller) = (unsafe { IOBluetoothHostController::defaultController() }) else {
        return BluetoothStatus::unavailable(
            "unavailable",
            "no default IOBluetoothHostController",
        );
    };
    match unsafe { controller.powerState() } {
        BluetoothHCIPowerState::ON => BluetoothStatus::available(),
        BluetoothHCIPowerState::OFF => {
            BluetoothStatus::unavailable("disabled", "macOS Bluetooth controller is off")
        }
        BluetoothHCIPowerState::Unintialized => BluetoothStatus::unavailable(
            "unavailable",
            "macOS Bluetooth controller is uninitialized or permission denied",
        ),
        other => BluetoothStatus::unavailable(
            "unavailable",
            format!("unknown IOBluetooth power state {}", other.0),
        ),
    }
}

pub fn collect_snapshot() -> Result<ProbeSnapshot, Box<dyn Error>> {
    let bt_status = bluetooth_status();
    let bt_devices = query_iobluetooth_observations();
    let audio_devices = query_coreaudio_bluetooth_devices();
    Ok(build_macos_snapshot(bt_status, &bt_devices, &audio_devices))
}

pub fn watch(options: &CliOptions) -> Result<(), Box<dyn Error>> {
    let observer = BluetoothObserver::new();
    let connect_notif = unsafe {
        IOBluetoothDevice::registerForConnectNotifications_selector(
            Some(&*observer as &AnyObject),
            Some(sel!(onDeviceConnected:device:)),
        )
    };
    let mut disconnect_notifs: BTreeMap<String, Retained<IOBluetoothUserNotification>> =
        BTreeMap::new();
    sync_disconnect_notifications(&observer, &mut disconnect_notifs);

    let devices_addr = prop_addr(
        kAudioHardwarePropertyDevices,
        kAudioObjectPropertyScopeGlobal,
    );
    unsafe {
        let _ = AudioObjectAddPropertyListener(
            kAudioObjectSystemObject as AudioObjectID,
            NonNull::from(&devices_addr),
            Some(coreaudio_property_listener),
            ptr::null_mut(),
        );
    }

    let mut watched_audio_ids = BTreeSet::new();
    let initial_audio = query_coreaudio_bluetooth_devices();
    let initial_ids: Vec<AudioObjectID> = initial_audio.iter().map(|d| d.device_id).collect();
    sync_device_listeners(&mut watched_audio_ids, &initial_ids);

    let initial_raw = build_macos_snapshot(
        bluetooth_status(),
        &query_iobluetooth_observations(),
        &initial_audio,
    );
    let initial = filter_snapshot(&initial_raw, options.filter.as_deref());
    let ts = now_timestamp();
    if options.json {
        println!("{}", format_snapshot_json(&ts, &initial));
    } else {
        print!("{}", format_snapshot_text(&ts, &initial));
    }

    if options.once {
        cleanup_listeners(connect_notif, disconnect_notifs, &watched_audio_ids, &devices_addr);
        return Ok(());
    }

    let mut previous = initial;
    let poll = Duration::from_millis(options.poll_ms);
    let debounce = Duration::from_millis(150);
    let mut last_refresh = Instant::now();
    let mut pending_since: Option<Instant> = None;

    loop {
        if let Some(mode) = unsafe { kCFRunLoopDefaultMode } {
            CFRunLoop::run_in_mode(Some(mode), 0.1, false);
        }
        if DIRTY.swap(false, Ordering::SeqCst) {
            pending_since.get_or_insert_with(Instant::now);
        }

        let should_refresh = match pending_since {
            Some(since) => since.elapsed() >= debounce,
            None => last_refresh.elapsed() >= poll,
        };
        if !should_refresh {
            continue;
        }
        pending_since = None;
        last_refresh = Instant::now();

        sync_disconnect_notifications(&observer, &mut disconnect_notifs);
        let audio = query_coreaudio_bluetooth_devices();
        let ids: Vec<AudioObjectID> = audio.iter().map(|d| d.device_id).collect();
        sync_device_listeners(&mut watched_audio_ids, &ids);

        let raw = build_macos_snapshot(
            bluetooth_status(),
            &query_iobluetooth_observations(),
            &audio,
        );
        let next = filter_snapshot(&raw, options.filter.as_deref());
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
}

fn cleanup_listeners(
    connect_notif: Option<Retained<IOBluetoothUserNotification>>,
    disconnect_notifs: BTreeMap<String, Retained<IOBluetoothUserNotification>>,
    watched_audio_ids: &BTreeSet<AudioObjectID>,
    devices_addr: &AudioObjectPropertyAddress,
) {
    if let Some(notif) = connect_notif {
        unsafe { notif.unregister() };
    }
    for notif in disconnect_notifs.into_values() {
        unsafe { notif.unregister() };
    }
    let addrs = device_listener_addresses();
    for &id in watched_audio_ids {
        for addr in &addrs {
            unsafe {
                let _ = AudioObjectRemovePropertyListener(
                    id,
                    NonNull::from(addr),
                    Some(coreaudio_property_listener),
                    ptr::null_mut(),
                );
            }
        }
    }
    unsafe {
        let _ = AudioObjectRemovePropertyListener(
            kAudioObjectSystemObject as AudioObjectID,
            NonNull::from(devices_addr),
            Some(coreaudio_property_listener),
            ptr::null_mut(),
        );
    }
}
