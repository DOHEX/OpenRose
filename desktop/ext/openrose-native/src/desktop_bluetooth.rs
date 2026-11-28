use super::{ControlConfig, ControlRuntime, ObserverHub, OpenRoseClient, SHARED_RUNTIME, SystemClock};
use nsh_core::prelude::*;
use openrose_circulation::LocalAudio;
use openrose_desktop_platform::bluetooth::{
    DesktopBluetooth, bluetooth_settings_command, create_desktop_bluetooth, open_bluetooth_settings,
};
use std::sync::Arc;
use tokio::task::JoinHandle;

pub struct DesktopLink {
    bluetooth: DesktopBluetooth,
    watcher: JoinHandle<()>,
}

impl DesktopLink {
    pub(crate) fn local_audio(&self) -> Arc<dyn LocalAudio> {
        Arc::clone(&self.bluetooth.local_audio)
    }
}

impl Drop for DesktopLink {
    fn drop(&mut self) {
        self.watcher.abort();
        self.bluetooth.close();
    }
}

impl OpenRoseClient {
    pub(crate) fn create_desktop() -> Self {
        let _guard = SHARED_RUNTIME.enter();
        let bluetooth = create_desktop_bluetooth();
        let mut states = bluetooth.platform_states.clone();
        let platform = states.borrow_and_update().clone();
        let observer = Arc::new(ObserverHub::default());
        let runtime = Arc::new(ControlRuntime::new(
            Arc::clone(&bluetooth.transport),
            observer.clone(),
            ControlConfig::new(platform, Arc::new(SystemClock)),
        ));
        let weak = Arc::downgrade(&runtime);
        let watcher = SHARED_RUNTIME.spawn(async move {
            while states.changed().await.is_ok() {
                let Some(runtime) = weak.upgrade() else { break };
                let platform = states.borrow_and_update().clone();
                runtime.update_platform_state(platform);
            }
        });
        Self {
            runtime,
            observer,
            dev: None,
            desktop: Some(DesktopLink { bluetooth, watcher }),
        }
    }
}

/// System Bluetooth settings of the desktop host; the app never switches the adapter itself.
#[module]
pub struct OpenRoseBluetooth;

#[module]
impl OpenRoseBluetooth {
    /// Whether a known system Bluetooth settings program is installed on this host.
    pub fn settings_available() -> bool {
        bluetooth_settings_command().is_some()
    }

    /// Opens the system Bluetooth settings program, failing when none is installed.
    pub fn open_settings() -> Result<(), JsError> {
        open_bluetooth_settings().map_err(|error| JsError::Error(error.code().to_string()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use openrose_control::snapshot::{HostPlatform, TransportAvailability};

    #[test]
    fn a_non_development_client_runs_on_the_desktop_transport_without_the_simulator() {
        let client = OpenRoseClient::new(false);
        let snapshot = client.runtime.snapshot();
        assert!(client.dev.is_none());
        assert!(client.desktop.is_some());
        assert_ne!(snapshot.platform.host_platform, HostPlatform::Unknown);
        assert_ne!(snapshot.platform.transport, TransportAvailability::Disabled);
    }

    #[test]
    fn a_development_client_keeps_the_simulator() {
        let client = OpenRoseClient::new(true);
        assert!(client.dev.is_some());
        assert!(client.desktop.is_none());
    }

    #[test]
    fn the_settings_entry_exists_only_with_a_known_settings_program() {
        assert_eq!(
            OpenRoseBluetooth::settings_available(),
            bluetooth_settings_command().is_some()
        );
    }
}
