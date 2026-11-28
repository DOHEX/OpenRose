mod tray;
mod settings;

use nsh_core::prelude::*;
use nsh_core::JsonValue;
use nsh_core::element::Ret;
use openrose_control::scan::Advertisement;
use openrose_control::simulator::{SimulatedHeadsetHandle, SimulatedTransport};
use openrose_control::snapshot::{
    ControlSnapshot, HostPlatform, PermissionState, PlatformState, TransportAvailability,
};
use openrose_control::{
    ControlConfig, ControlError, ControlErrorCode, ControlObserver, ControlRuntime, LocalClock,
};
use openrose_headset::{
    FirmwareVersion, HeadsetCommand, HeadsetId, LocalDateTime, ModelProfile, NoiseMode,
    ProtocolFamily, decode_hex,
};
use serde::Serialize;
use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::runtime::{Builder, Runtime};
mod headset_flow;
use tokio::sync::{mpsc, oneshot};

mod circulation;
mod desktop_bluetooth;
mod login_launch;
static SHARED_RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("openrose-desktop-core")
        .enable_all()
        .build()
        .expect("openrose desktop tokio runtime")
});

const DEFAULT_DEV_REPLY_LATENCY_MS: u64 = 160;

#[native_module("openrose-native")]
fn native(registry: &mut Registry) {
    registry
        .module::<OpenRoseCatalog>()
        .module::<tray::OpenRoseTray>()
        .module::<settings::OpenRoseSettings>()
        .module::<login_launch::OpenRoseLoginLaunch>()
        .class::<OpenRoseClient>()
        .class::<circulation::OpenRoseCirculation>()
        .module::<headset_flow::OpenRoseHeadsetFlow>();
}

struct SystemClock;

impl LocalClock for SystemClock {
    fn now(&self) -> LocalDateTime {
        let secs = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let days = secs / 86_400;
        let sod = secs % 86_400;
        let hour = (sod / 3_600) as u8;
        let minute = ((sod % 3_600) / 60) as u8;
        let second = (sod % 60) as u8;
        let (year, month, day) = civil_from_days(days as i64);
        LocalDateTime::new(year as u16, month, day, hour, minute, second)
            .unwrap_or_else(|_| LocalDateTime::new(2026, 1, 1, 0, 0, 0).expect("fallback clock"))
    }
}

fn civil_from_days(days_since_epoch: i64) -> (i64, u8, u8) {
    let z = days_since_epoch + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = (yoe as i64) + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = y + if m <= 2 { 1 } else { 0 };
    (year, m as u8, d as u8)
}

#[derive(Default)]
struct ObserverHub {
    next_id: AtomicU64,
    senders: Mutex<BTreeMap<u64, mpsc::UnboundedSender<ControlSnapshot>>>,
}

impl ObserverHub {
    fn subscribe(&self) -> (u64, mpsc::UnboundedReceiver<ControlSnapshot>) {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        let (tx, rx) = mpsc::unbounded_channel();
        self.senders.lock().expect("observer hub lock").insert(id, tx);
        (id, rx)
    }

    fn remove(&self, id: u64) {
        self.senders.lock().expect("observer hub lock").remove(&id);
    }
}

impl ControlObserver for ObserverHub {
    fn state_changed(&self, snapshot: &ControlSnapshot) {
        let mut senders = self.senders.lock().expect("observer hub lock");
        senders.retain(|_, tx| tx.send(snapshot.clone()).is_ok());
    }
}

pub(crate) struct DevState {
    pub(crate) transport: Arc<SimulatedTransport>,
    pub(crate) active_id: Mutex<HeadsetId>,
    pub(crate) active_profile: Mutex<ModelProfile>,
    pub(crate) active_firmware: Mutex<FirmwareVersion>,
    pub(crate) active_handle: Mutex<SimulatedHeadsetHandle>,
    pub(crate) reply_latency_ms: AtomicU64,
    pub(crate) sequence: AtomicU64,
}

impl DevState {
    fn new(
        transport: Arc<SimulatedTransport>,
        id: HeadsetId,
        profile: ModelProfile,
        firmware: FirmwareVersion,
        handle: SimulatedHeadsetHandle,
        reply_latency_ms: u64,
    ) -> Self {
        Self {
            transport,
            active_id: Mutex::new(id),
            active_profile: Mutex::new(profile),
            active_firmware: Mutex::new(firmware),
            active_handle: Mutex::new(handle),
            reply_latency_ms: AtomicU64::new(reply_latency_ms),
            sequence: AtomicU64::new(1),
        }
    }

    fn config_snapshot(&self) -> DevHeadsetConfig {
        let id = self.active_id.lock().expect("dev id lock").as_str().to_string();
        let profile = *self.active_profile.lock().expect("dev profile lock");
        let firmware = self.active_firmware.lock().expect("dev firmware lock").clone();
        DevHeadsetConfig {
            headset_id: id,
            model_id: profile.id().to_string(),
            display_name: profile.display_name().to_string(),
            version_name: profile.version_name().map(str::to_string),
            family: profile.family().display_name().to_string(),
            firmware: firmware.display_text,
            reply_latency_ms: self.reply_latency_ms.load(Ordering::SeqCst),
        }
    }
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ModelCatalogEntry {
    id: &'static str,
    display_name: &'static str,
    short_label: &'static str,
    version_name: Option<&'static str>,
    family: ProtocolFamily,
    family_label: &'static str,
    battery_layout: openrose_headset::BatteryLayout,
    default_firmware: &'static str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DevHeadsetConfig {
    headset_id: String,
    model_id: String,
    display_name: String,
    version_name: Option<String>,
    family: String,
    firmware: String,
    reply_latency_ms: u64,
}

pub(crate) fn catalog_advertisement(profile: ModelProfile, id: &HeadsetId) -> Advertisement {
    let prefix = decode_hex(profile.advertisement_prefixes()[0]).expect("advertisement prefix");
    let company = u16::from(prefix[0]) | (u16::from(prefix[1]) << 8);
    let name = match profile {
        ModelProfile::I5Zt => "i5",
        ModelProfile::I7RsV2 => "i7",
        _ => profile.display_name(),
    };
    Advertisement {
        name: Some(name.to_string()),
        manufacturer_data: [(company, prefix[2..].to_vec())].into(),
        ..Advertisement::new(id.clone())
    }
}

fn desktop_host_platform() -> HostPlatform {
    match std::env::consts::OS {
        "macos" => HostPlatform::MacOs,
        "windows" => HostPlatform::Windows,
        "linux" => HostPlatform::Linux,
        _ => HostPlatform::Unknown,
    }
}

fn desktop_platform_state() -> PlatformState {
    PlatformState::new(PermissionState::NotRequired, TransportAvailability::Available)
        .on(desktop_host_platform())
}

fn snapshot_to_json(snapshot: &ControlSnapshot) -> JsonValue {
    serde_json::to_value(snapshot).expect("control snapshot serializes to json")
}

pub(crate) fn error_code(code: ControlErrorCode) -> String {
    format!("{code:?}")
}

pub(crate) fn control_error_code(err: ControlError) -> String {
    error_code(err.code)
}

pub(crate) fn run_on_core<T: Send + Ret + 'static>(
    work: impl Future<Output = Result<T, String>> + Send + 'static,
) -> Later<T> {
    Later::new(async move {
        let (tx, rx) = oneshot::channel();
        SHARED_RUNTIME.spawn(async move {
            let result = work.await;
            let _ = tx.send(result);
        });
        rx.await
            .map_err(|_| JsError::Error(error_code(ControlErrorCode::Disconnected)))?
            .map_err(JsError::Error)
    })
}

/// Model catalog exported from the Rust headset core for dev mode selection.
#[module]
pub struct OpenRoseCatalog;

#[module]
impl OpenRoseCatalog {
    /// Lists every supported ROSE headset model profile and default firmware metadata.
    pub fn list_models() -> JsonValue {
        let entries: Vec<ModelCatalogEntry> = ModelProfile::ALL
            .iter()
            .copied()
            .map(|profile| ModelCatalogEntry {
                id: profile.id(),
                display_name: profile.display_name(),
                short_label: profile.short_label(),
                version_name: profile.version_name(),
                family: profile.family(),
                family_label: profile.family().display_name(),
                battery_layout: profile.battery_layout(),
                default_firmware: "125",
            })
            .collect();
        serde_json::to_value(entries).expect("model catalog serializes")
    }
}

/// Composes OpenRose ControlRuntime and the active transport without storing a snapshot copy.
#[class]
pub struct OpenRoseClient {
    pub(crate) runtime: Arc<ControlRuntime>,
    observer: Arc<ObserverHub>,
    pub(crate) dev: Option<Arc<DevState>>,
    desktop: Option<desktop_bluetooth::DesktopLink>,
}

impl OpenRoseClient {
    fn create_dev(reply_latency_ms: u64) -> Self {
        let transport = Arc::new(SimulatedTransport::new());
        let profile = ModelProfile::I5Zt;
        let firmware = profile.family().default_firmware();
        let id = HeadsetId::new("sim:I5_ZT:1");
        let handle = transport
            .add_headset(id.clone(), profile, firmware.clone())
            .expect("default simulated i5 headset");
        handle.reply_latency(Duration::from_millis(reply_latency_ms));
        let observer = Arc::new(ObserverHub::default());
        let config = ControlConfig::new(desktop_platform_state(), Arc::new(SystemClock));
        let runtime = {
            let _guard = SHARED_RUNTIME.enter();
            Arc::new(ControlRuntime::new(
                transport.clone(),
                observer.clone(),
                config,
            ))
        };
        let dev = Arc::new(DevState::new(
            transport,
            id,
            profile,
            firmware,
            handle,
            reply_latency_ms,
        ));
        Self {
            runtime,
            observer,
            dev: Some(dev),
            desktop: None,
        }
    }

    async fn connect_dev_headset(
        runtime: &ControlRuntime,
        dev: &DevState,
        id: HeadsetId,
        profile: ModelProfile,
    ) -> Result<(), String> {
        let _ = runtime.start_scan().await;
        dev.transport
            .scan()
            .advertise(catalog_advertisement(profile, &id));
        let observed = tokio::time::timeout(Duration::from_secs(2), async {
            while !runtime
                .snapshot()
                .candidates
                .iter()
                .any(|candidate| candidate.id == id && candidate.profile.is_some())
            {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await;
        if observed.is_err() {
            runtime.stop_scan().await;
            return Err(error_code(ControlErrorCode::Timeout));
        }
        let result = runtime.connect_for_add_headset(id).await;
        runtime.stop_scan().await;
        result.map_err(control_error_code)
    }
}

#[class]
impl OpenRoseClient {
    /// Creates an OpenRose client: a simulated ROSE EarFree i5 in development, the desktop Bluetooth transport otherwise.
    pub fn new(development: bool) -> Self {
        if development {
            Self::create_dev(DEFAULT_DEV_REPLY_LATENCY_MS)
        } else {
            Self::create_desktop()
        }
    }

    /// Reads the latest ControlSnapshot directly from ControlRuntime without a local copy.
    pub fn snapshot(&self) -> JsonValue {
        snapshot_to_json(&self.runtime.snapshot())
    }

    /// Subscribes to ControlRuntime snapshot updates and forwards each pushed snapshot to JS.
    pub fn subscribe(&self, cx: CallCx<'_>, on_snapshot: Callback<JsonValue>) -> Stop {
        let (subscription, mut rx) = self.observer.subscribe();
        let initial = snapshot_to_json(&self.runtime.snapshot());
        on_snapshot.post(initial);
        cx.start(async move {
            while let Some(snapshot) = rx.recv().await {
                on_snapshot.post(snapshot_to_json(&snapshot));
            }
        });
        let observer = Arc::clone(&self.observer);
        Stop::new(move || {
            observer.remove(subscription);
        })
    }

    /// Connects the active dev-mode simulated headset or a known headset id.
    pub fn connect(&self, headset_id: Option<String>) -> Later<()> {
        let runtime = Arc::clone(&self.runtime);
        let dev = self.dev.clone();
        run_on_core(async move {
            if let Some(id) = headset_id {
                runtime
                    .connect(HeadsetId::new(id))
                    .await
                    .map_err(control_error_code)
            } else if let Some(dev) = dev {
                let id = dev.active_id.lock().expect("dev id lock").clone();
                let profile = *dev.active_profile.lock().expect("dev profile lock");
                Self::connect_dev_headset(&runtime, &dev, id, profile).await
            } else {
                Err(error_code(ControlErrorCode::InvalidArgument))
            }
        })
    }

    /// Disconnects the current control session.
    pub fn disconnect(&self) -> Later<bool> {
        let runtime = Arc::clone(&self.runtime);
        run_on_core(async move { Ok(runtime.disconnect().await) })
    }

    /// Refreshes bonded headsets from the active audio driver.
    pub fn refresh_bonded_headsets(&self) -> Later<()> {
        let runtime = Arc::clone(&self.runtime);
        run_on_core(async move {
            runtime.refresh_bonded_headsets().await;
            Ok(())
        })
    }

    /// Starts scanning for nearby ROSE headsets via the active transport.
    pub fn start_scan(&self) -> Later<()> {
        let runtime = Arc::clone(&self.runtime);
        run_on_core(async move { runtime.start_scan().await.map_err(control_error_code) })
    }

    /// Stops any active headset discovery scan.
    pub fn stop_scan(&self) -> Later<()> {
        let runtime = Arc::clone(&self.runtime);
        run_on_core(async move {
            runtime.stop_scan().await;
            Ok(())
        })
    }

    /// Selects the current headset by identifier when multiple headsets are paired.
    pub fn select_current_headset(&self, headset_id: String) {
        self.runtime.select_current_headset(HeadsetId::new(headset_id));
    }

    /// Switches the headset noise control mode and resolves after headset confirmation.
    pub fn set_noise_mode(&self, mode: String, expected_session: Option<u64>) -> Later<()> {
        let runtime = Arc::clone(&self.runtime);
        run_on_core(async move {
            let parsed = NoiseMode::from_id(&mode)
                .ok_or_else(|| error_code(ControlErrorCode::InvalidArgument))?;
            runtime
                .set(HeadsetCommand::NoiseMode(parsed), expected_session)
                .await
                .map_err(control_error_code)
        })
    }

    /// Executes a serialized HeadsetCommand against the ControlRuntime and resolves after headset confirmation.
    pub fn execute_command(&self, command_json: String, expected_session: Option<u64>) -> Later<()> {
        let runtime = Arc::clone(&self.runtime);
        run_on_core(async move {
            let command: HeadsetCommand = serde_json::from_str(&command_json)
                .map_err(|_| error_code(ControlErrorCode::InvalidArgument))?;
            runtime
                .set(command, expected_session)
                .await
                .map_err(control_error_code)
        })
    }

    /// Replaces the dev-mode simulated headset with the chosen model and firmware and connects it.
    pub fn switch_simulated_headset(&self, model_id: String, firmware: String) -> Later<JsonValue> {
        let runtime = Arc::clone(&self.runtime);
        let dev = self.dev.clone();
        run_on_core(async move {
            let dev = dev.ok_or_else(|| error_code(ControlErrorCode::Unsupported))?;
            let profile = ModelProfile::from_id(&model_id)
                .ok_or_else(|| error_code(ControlErrorCode::InvalidArgument))?;
            let parsed_fw = profile
                .family()
                .parse_firmware(&firmware)
                .map_err(|_| error_code(ControlErrorCode::InvalidArgument))?;
            let seq = dev.sequence.fetch_add(1, Ordering::SeqCst) + 1;
            let new_id = HeadsetId::new(format!("sim:{}:{seq}", profile.id()));
            let handle = dev
                .transport
                .add_headset(new_id.clone(), profile, parsed_fw.clone())
                .map_err(|_| error_code(ControlErrorCode::Failed))?;
            let latency = dev.reply_latency_ms.load(Ordering::SeqCst);
            handle.reply_latency(Duration::from_millis(latency));
            let old_id = {
                let mut guard = dev.active_id.lock().expect("dev id lock");
                std::mem::replace(&mut *guard, new_id.clone())
            };
            *dev.active_profile.lock().expect("dev profile lock") = profile;
            *dev.active_firmware.lock().expect("dev firmware lock") = parsed_fw;
            *dev.active_handle.lock().expect("dev handle lock") = handle;
            let _ = runtime.disconnect().await;
            dev.transport.audio().unpair(old_id.as_str());
            runtime.refresh_bonded_headsets().await;
            Self::connect_dev_headset(&runtime, &dev, new_id, profile).await?;
            Ok(serde_json::to_value(dev.config_snapshot()).expect("dev config serializes"))
        })
    }

    /// Updates the dev-mode simulated reply latency in milliseconds.
    pub fn set_simulated_latency_ms(&self, millis: u32) -> Result<(), JsError> {
        let dev = self
            .dev
            .as_ref()
            .ok_or_else(|| JsError::Error(error_code(ControlErrorCode::Unsupported)))?;
        dev.reply_latency_ms
            .store(u64::from(millis), Ordering::SeqCst);
        dev.active_handle
            .lock()
            .expect("dev handle lock")
            .reply_latency(Duration::from_millis(u64::from(millis)));
        Ok(())
    }

    /// Returns the active dev-mode simulated model and firmware configuration.
    pub fn dev_config(&self) -> Option<JsonValue> {
        self.dev
            .as_ref()
            .map(|dev| serde_json::to_value(dev.config_snapshot()).expect("dev config serializes"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use openrose_control::snapshot::{ConnectionState, SettingStage};
    use openrose_headset::{FieldValue, HeadsetField};

    #[test]
    fn simulated_i5_connects_reads_battery_shows_pending_then_confirmed_and_switches_model() {
        let client = OpenRoseClient::create_dev(40);
        let (_, mut rx) = client.observer.subscribe();
        let runtime = Arc::clone(&client.runtime);
        let dev = client.dev.as_ref().expect("dev state").clone();

        SHARED_RUNTIME.block_on(async move {
            let id = dev.active_id.lock().expect("id").clone();
            OpenRoseClient::connect_dev_headset(&runtime, &dev, id, ModelProfile::I5Zt)
                .await
                .expect("connect i5");

            let connected = runtime.snapshot();
            assert!(matches!(
                connected.connection,
                ConnectionState::Connected {
                    profile: ModelProfile::I5Zt,
                    ..
                }
            ));
            let headset = connected.headset.expect("headset state");
            assert_eq!(
                headset
                    .readings
                    .known_value(HeadsetField::LeftBattery)
                    .cloned(),
                Some(FieldValue::Number(100))
            );
            assert_eq!(
                headset
                    .readings
                    .known_value(HeadsetField::NoiseMode)
                    .cloned(),
                Some(FieldValue::text("wind"))
            );

            while rx.try_recv().is_ok() {}

            runtime
                .set(HeadsetCommand::NoiseMode(NoiseMode::Normal), None)
                .await
                .expect("noise mode change confirmed");

            let mut stages = Vec::new();
            let confirmed = tokio::time::timeout(Duration::from_secs(2), async {
                while let Some(snap) = rx.recv().await {
                    stages.extend(snap.setting_operations.into_iter().map(|op| op.stage));
                    if stages.contains(&SettingStage::Confirmed) {
                        return true;
                    }
                }
                false
            })
            .await;
            assert!(matches!(confirmed, Ok(true)), "the confirmed stage reaches the observer");
            assert!(stages.contains(&SettingStage::Submitted));
            assert_eq!(
                runtime
                    .snapshot()
                    .headset
                    .expect("headset")
                    .readings
                    .known_value(HeadsetField::NoiseMode)
                    .cloned(),
                Some(FieldValue::text("normal"))
            );
        });
    }

    #[test]
    fn observer_hub_stop_detaches_only_its_own_subscription() {
        let client = OpenRoseClient::create_dev(10);
        let snapshot = client.runtime.snapshot();
        let hub = ObserverHub::default();
        let (first, mut first_rx) = hub.subscribe();
        let (_second, mut second_rx) = hub.subscribe();

        hub.state_changed(&snapshot);
        assert!(first_rx.try_recv().is_ok());
        assert!(second_rx.try_recv().is_ok());

        hub.remove(first);
        hub.state_changed(&snapshot);
        assert!(first_rx.try_recv().is_err());
        assert!(second_rx.try_recv().is_ok());
    }
}
