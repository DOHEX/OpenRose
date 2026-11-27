pub mod linux;

use openrose_control::snapshot::{HostPlatform, PermissionState, PlatformState, TransportAvailability, TransportUnavailableReason};
use openrose_control::transport::ControlTransport;
use std::sync::Arc;
use tokio::sync::watch;

pub struct DesktopBluetooth {
    pub transport: Arc<dyn ControlTransport>,
    pub platform_states: watch::Receiver<PlatformState>,
    shutdown: Box<dyn Fn() + Send + Sync>,
}

impl DesktopBluetooth {
    pub fn close(&self) {
        (self.shutdown)();
    }
}

pub fn create_linux_bluetooth() -> DesktopBluetooth {
    let transport = linux::transport::BluezControlTransport::open();
    let mut availability = transport.availability();
    let initial_availability = availability.borrow().clone();
    let initial_state = linux_platform_state(initial_availability);
    let (tx, rx) = watch::channel(initial_state);
    let watcher_transport = Arc::clone(&transport);
    let handle = tokio::spawn(async move {
        while availability.changed().await.is_ok() {
            let next = linux_platform_state(availability.borrow().clone());
            if tx.send(next).is_err() {
                break;
            }
        }
        drop(watcher_transport);
    });
    let close_transport = Arc::clone(&transport);
    DesktopBluetooth {
        transport,
        platform_states: rx,
        shutdown: Box::new(move || {
            handle.abort();
            close_transport.close_now();
        }),
    }
}

pub fn linux_platform_state(transport: TransportAvailability) -> PlatformState {
    PlatformState::new(PermissionState::NotRequired, transport).on(HostPlatform::Linux)
}

pub fn initial_unavailable_state(platform: HostPlatform) -> PlatformState {
    PlatformState::new(
        PermissionState::NotRequired,
        TransportAvailability::Unavailable {
            reason: TransportUnavailableReason::UnsupportedPlatform {
                platform: Some(std::env::consts::OS.to_string()),
            },
        },
    )
    .on(platform)
}

pub fn bluetooth_settings_command() -> Option<Vec<String>> {
    #[cfg(target_os = "linux")]
    {
        linux::settings::bluetooth_settings_command()
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

pub fn open_bluetooth_settings() -> Result<(), String> {
    let command = bluetooth_settings_command().ok_or_else(|| "未找到系统蓝牙设置程序".to_string())?;
    let (program, args) = command
        .split_first()
        .ok_or_else(|| "蓝牙设置命令为空".to_string())?;
    std::process::Command::new(program)
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("无法打开系统蓝牙设置: {error}"))
}
