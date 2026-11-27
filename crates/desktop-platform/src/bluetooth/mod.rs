pub mod linux;
pub mod unsupported;

use openrose_control::snapshot::{
    HostPlatform, PermissionState, PlatformState, TransportAvailability, TransportUnavailableReason,
};
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

#[cfg(target_os = "linux")]
pub fn create_desktop_bluetooth() -> DesktopBluetooth {
    let transport = linux::transport::BluezControlTransport::open();
    let mut availability = transport.availability();
    let initial = linux_platform_state(availability.borrow().clone());
    let (tx, rx) = watch::channel(initial);
    let watcher = tokio::spawn(async move {
        while availability.changed().await.is_ok() {
            let next = linux_platform_state(availability.borrow().clone());
            if tx.send(next).is_err() {
                break;
            }
        }
    });
    let closing = Arc::clone(&transport);
    DesktopBluetooth {
        transport,
        platform_states: rx,
        shutdown: Box::new(move || {
            watcher.abort();
            closing.close_now();
        }),
    }
}

#[cfg(not(target_os = "linux"))]
pub fn create_desktop_bluetooth() -> DesktopBluetooth {
    let (_, rx) = watch::channel(unsupported_platform_state());
    DesktopBluetooth {
        transport: Arc::new(unsupported::UnsupportedTransport),
        platform_states: rx,
        shutdown: Box::new(|| {}),
    }
}

pub fn linux_platform_state(transport: TransportAvailability) -> PlatformState {
    PlatformState::new(PermissionState::NotRequired, transport).on(HostPlatform::Linux)
}

pub fn unsupported_platform_state() -> PlatformState {
    let host = match std::env::consts::OS {
        "macos" => HostPlatform::MacOs,
        "windows" => HostPlatform::Windows,
        "linux" => HostPlatform::Linux,
        _ => HostPlatform::Unknown,
    };
    PlatformState::new(
        PermissionState::NotRequired,
        TransportAvailability::Unavailable {
            reason: TransportUnavailableReason::UnsupportedPlatform {
                platform: Some(std::env::consts::OS.to_string()),
            },
        },
    )
    .on(host)
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
    let command =
        bluetooth_settings_command().ok_or_else(|| "未找到系统蓝牙设置程序".to_string())?;
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
