use crate::{DevState, OpenRoseClient, catalog_advertisement, control_error_code, error_code, run_on_core};
use nsh_core::prelude::*;
use nsh_core::JsonValue;
use openrose_control::{ControlErrorCode, ControlRuntime};
use openrose_control::scan::Advertisement;
use openrose_control::snapshot::PlatformState;
use openrose_headset::{HeadsetId, ModelProfile};
use serde::Deserialize;
use std::sync::Arc;
use std::sync::atomic::Ordering;
use std::time::Duration;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DevBondedEntry {
    id: String,
    model_id: String,
    firmware: Option<String>,
}

fn client_parts(
    client: &Instance<OpenRoseClient>,
) -> Result<(Arc<ControlRuntime>, Option<Arc<DevState>>), JsError> {
    if client.is_disposed() {
        return Err(JsError::Error(error_code(ControlErrorCode::Disconnected)));
    }
    Ok(client.with(|borrowed| (Arc::clone(&borrowed.runtime), borrowed.dev.clone())))
}

fn require_dev(dev: Option<Arc<DevState>>) -> Result<Arc<DevState>, String> {
    dev.ok_or_else(|| error_code(ControlErrorCode::Unsupported))
}

/// Add-headset control flow and dev-mode simulation helpers exported to the desktop UI.
#[module]
pub struct OpenRoseHeadsetFlow;

#[module]
impl OpenRoseHeadsetFlow {
    /// Connects a discovered headset as part of the add-headset flow (control ready without requiring audio pairing).
    pub fn connect_for_add_headset(
        client: Instance<OpenRoseClient>,
        headset_id: String,
    ) -> Later<()> {
        let runtime = match client_parts(&client) {
            Ok((runtime, _)) => runtime,
            Err(err) => return Later::new(async move { Err(err) }),
        };
        run_on_core(async move {
            runtime
                .connect_for_add_headset(HeadsetId::new(headset_id))
                .await
                .map_err(control_error_code)
        })
    }

    /// Cancels an in-flight add-headset connection attempt.
    pub fn cancel_add_headset(client: Instance<OpenRoseClient>) -> Later<()> {
        let runtime = match client_parts(&client) {
            Ok((runtime, _)) => runtime,
            Err(err) => return Later::new(async move { Err(err) }),
        };
        run_on_core(async move {
            runtime.cancel_add_headset().await;
            Ok(())
        })
    }

    /// Replaces the dev-mode bonded headset list for testing empty onboarding and multi-headset switching.
    pub fn dev_set_bonded_headsets(
        client: Instance<OpenRoseClient>,
        entries_json: String,
    ) -> Later<()> {
        let (runtime, dev) = match client_parts(&client) {
            Ok(parts) => parts,
            Err(err) => return Later::new(async move { Err(err) }),
        };
        run_on_core(async move {
            let dev = require_dev(dev)?;
            let entries: Vec<DevBondedEntry> = serde_json::from_str(&entries_json)
                .map_err(|_| error_code(ControlErrorCode::InvalidArgument))?;
            let _ = runtime.disconnect().await;
            let existing = runtime.snapshot().bonded_headsets;
            for item in existing {
                dev.transport.audio().unpair(item.id.as_str());
            }
            let latency = dev.reply_latency_ms.load(Ordering::SeqCst);
            let mut first_entry: Option<(
                HeadsetId,
                ModelProfile,
                openrose_headset::FirmwareVersion,
                openrose_control::simulator::SimulatedHeadsetHandle,
            )> = None;
            for entry in entries {
                let profile = ModelProfile::from_id(&entry.model_id)
                    .ok_or_else(|| error_code(ControlErrorCode::InvalidArgument))?;
                let firmware = match entry.firmware {
                    Some(text) => profile
                        .family()
                        .parse_firmware(&text)
                        .map_err(|_| error_code(ControlErrorCode::InvalidArgument))?,
                    None => profile.family().default_firmware(),
                };
                let id = HeadsetId::new(entry.id);
                let handle = dev
                    .transport
                    .add_headset(id.clone(), profile, firmware.clone())
                    .map_err(|_| error_code(ControlErrorCode::Failed))?;
                handle.reply_latency(Duration::from_millis(latency));
                if first_entry.is_none() {
                    first_entry = Some((id, profile, firmware, handle));
                }
            }
            if let Some((id, profile, firmware, handle)) = first_entry {
                *dev.active_id.lock().expect("dev id lock") = id;
                *dev.active_profile.lock().expect("dev profile lock") = profile;
                *dev.active_firmware.lock().expect("dev firmware lock") = firmware;
                *dev.active_handle.lock().expect("dev handle lock") = handle;
            }
            runtime.refresh_bonded_headsets().await;
            Ok(())
        })
    }

    /// Advertises a simulated ROSE headset (or unidentified BLE device) for add-headset scanning without audio pairing.
    pub fn dev_advertise_scan_candidate(
        client: Instance<OpenRoseClient>,
        headset_id: String,
        model_id: Option<String>,
        name: Option<String>,
        rssi: Option<i32>,
    ) -> Result<(), JsError> {
        let (runtime, dev) = client_parts(&client)?;
        let dev = require_dev(dev).map_err(JsError::Error)?;
        let id = HeadsetId::new(headset_id);
        let rssi_i16 =
            rssi.map(|value| value.clamp(i32::from(i16::MIN), i32::from(i16::MAX)) as i16);
        let already_bonded = runtime
            .snapshot()
            .bonded_headsets
            .iter()
            .any(|candidate| candidate.id == id);
        let mut advertisement = if let Some(model_id) = model_id {
            let profile = ModelProfile::from_id(&model_id)
                .ok_or_else(|| JsError::Error(error_code(ControlErrorCode::InvalidArgument)))?;
            let firmware = profile.family().default_firmware();
            let handle = dev
                .transport
                .add_headset(id.clone(), profile, firmware)
                .map_err(|_| JsError::Error(error_code(ControlErrorCode::Failed)))?;
            let latency = dev.reply_latency_ms.load(Ordering::SeqCst);
            handle.reply_latency(Duration::from_millis(latency));
            if !already_bonded {
                dev.transport.audio().unpair(id.as_str());
            }
            let mut adv = catalog_advertisement(profile, &id);
            if let Some(custom_name) = name {
                adv.name = Some(custom_name);
            }
            adv
        } else {
            Advertisement {
                name,
                ..Advertisement::new(id)
            }
        };
        advertisement.rssi = rssi_i16;
        dev.transport.scan().advertise(advertisement);
        Ok(())
    }

    /// Updates the dev-mode PlatformState on ControlRuntime to exercise permission and transport-unavailable states.
    pub fn dev_set_platform_state(
        client: Instance<OpenRoseClient>,
        state_json: String,
    ) -> Result<(), JsError> {
        let (runtime, dev) = client_parts(&client)?;
        let _dev = require_dev(dev).map_err(JsError::Error)?;
        let state: PlatformState = serde_json::from_str(&state_json)
            .map_err(|_| JsError::Error(error_code(ControlErrorCode::InvalidArgument)))?;
        let _guard = crate::SHARED_RUNTIME.enter();
        runtime.update_platform_state(state);
        Ok(())
    }

    /// Returns the current headset chosen by the control core rule (connection target, active session, selection, first bonded).
    pub fn current_headset(client: Instance<OpenRoseClient>) -> Result<Option<JsonValue>, JsError> {
        let (runtime, _) = client_parts(&client)?;
        Ok(runtime
            .snapshot()
            .current_headset()
            .map(|headset| serde_json::to_value(headset).expect("headset candidate serializes")))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::SHARED_RUNTIME;
    use openrose_control::snapshot::{
        ConnectionBlockReason, ConnectionState, HostPlatform, PermissionState, ScanState,
        TransportAvailability, TransportUnavailableReason,
    };

    #[test]
    fn add_headset_scan_establishes_control_without_audio_pairing_and_supports_switching() {
        let client = OpenRoseClient::create_dev(10);
        let runtime = Arc::clone(&client.runtime);
        let dev = client.dev.as_ref().expect("dev").clone();

        SHARED_RUNTIME.block_on(async move {
            let headfeel_id = HeadsetId::new("sim:HEADFEEL_ZT:scan");
            let headfeel = ModelProfile::HeadFeelZt;
            let handle = dev
                .transport
                .add_headset(
                    headfeel_id.clone(),
                    headfeel,
                    headfeel.family().default_firmware(),
                )
                .expect("add simulated headfeel");
            handle.reply_latency(Duration::from_millis(10));
            dev.transport.audio().unpair(headfeel_id.as_str());
            runtime.refresh_bonded_headsets().await;

            runtime.start_scan().await.expect("start scan");
            let mut adv = catalog_advertisement(headfeel, &headfeel_id);
            adv.rssi = Some(-48);
            dev.transport.scan().advertise(adv);
            dev.transport.scan().advertise(Advertisement {
                name: Some("Other BLE".to_string()),
                rssi: Some(-70),
                ..Advertisement::new(HeadsetId::new("other:ble:1"))
            });
            tokio::time::sleep(Duration::from_millis(30)).await;

            let scanned = runtime.snapshot();
            assert_eq!(scanned.scan, ScanState::Scanning);
            assert!(
                scanned
                    .candidates
                    .iter()
                    .any(|c| c.id == headfeel_id && c.profile == Some(ModelProfile::HeadFeelZt))
            );
            assert!(
                scanned
                    .candidates
                    .iter()
                    .any(|c| c.id == HeadsetId::new("other:ble:1") && c.profile.is_none())
            );

            runtime
                .connect_for_add_headset(headfeel_id.clone())
                .await
                .expect("connect for add headset");
            runtime.stop_scan().await;

            let connected = runtime.snapshot();
            assert!(matches!(
                connected.connection,
                ConnectionState::Connected {
                    profile: ModelProfile::HeadFeelZt,
                    ..
                }
            ));
            assert_eq!(connected.audio_headset_id, None);
            assert!(
                !connected
                    .bonded_headsets
                    .iter()
                    .any(|c| c.id == headfeel_id)
            );

            runtime.disconnect().await;
            runtime.update_platform_state(
                PlatformState::new(
                    PermissionState::Granted,
                    TransportAvailability::Unavailable {
                        reason: TransportUnavailableReason::MissingDependency {
                            detail: Some("bluez dbus".to_string()),
                        },
                    },
                )
                .on(HostPlatform::Linux),
            );
            let blocked = runtime.snapshot();
            assert_eq!(
                blocked.connection_block_reason,
                Some(ConnectionBlockReason::TransportUnavailable {
                    reason: TransportUnavailableReason::MissingDependency {
                        detail: Some("bluez dbus".to_string()),
                    },
                })
            );
        });
    }
}
