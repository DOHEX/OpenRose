use nsh_core::JsonValue;
use nsh_core::prelude::*;
use openrose_headset::{HeadsetReadings, HeadsetSetting, ModelProfile};
use serde::Serialize;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TrayAvailabilityReport {
    available: bool,
    reason: Option<String>,
}

impl TrayAvailabilityReport {
    fn available() -> Self {
        Self {
            available: true,
            reason: None,
        }
    }

    fn unavailable(reason: &str) -> Self {
        Self {
            available: false,
            reason: Some(reason.to_string()),
        }
    }
}

#[cfg(target_os = "linux")]
fn gdbus_session_call(
    dest: &str,
    object_path: &str,
    method: &str,
    arguments: &[&str],
) -> std::io::Result<Option<String>> {
    let output = std::process::Command::new("gdbus")
        .args([
            "call",
            "--session",
            "--dest",
            dest,
            "--object-path",
            object_path,
            "--method",
            method,
        ])
        .args(arguments)
        .output()?;
    Ok(output
        .status
        .success()
        .then(|| String::from_utf8_lossy(&output.stdout).into_owned()))
}

#[cfg(target_os = "linux")]
fn probe_tray() -> TrayAvailabilityReport {
    if std::env::var_os("DBUS_SESSION_BUS_ADDRESS").is_none_or(|value| value.is_empty()) {
        return TrayAvailabilityReport::unavailable("未连接到会话 D-Bus");
    }
    let watcher_owned = match gdbus_session_call(
        "org.freedesktop.DBus",
        "/org/freedesktop/DBus",
        "org.freedesktop.DBus.NameHasOwner",
        &["org.kde.StatusNotifierWatcher"],
    ) {
        Ok(Some(reply)) => reply.contains("true"),
        Ok(None) => return TrayAvailabilityReport::unavailable("无法查询会话 D-Bus 上的托盘服务"),
        Err(_) => return TrayAvailabilityReport::unavailable("未找到 D-Bus 查询工具 gdbus"),
    };
    if !watcher_owned {
        return TrayAvailabilityReport::unavailable("会话总线上没有 StatusNotifierWatcher");
    }
    match gdbus_session_call(
        "org.kde.StatusNotifierWatcher",
        "/StatusNotifierWatcher",
        "org.freedesktop.DBus.Properties.Get",
        &["org.kde.StatusNotifierWatcher", "IsStatusNotifierHostRegistered"],
    ) {
        Ok(Some(reply)) if reply.contains("true") => TrayAvailabilityReport::available(),
        Ok(Some(_)) => TrayAvailabilityReport::unavailable("没有已注册的系统托盘宿主"),
        _ => TrayAvailabilityReport::unavailable("无法读取系统托盘宿主状态"),
    }
}

#[cfg(not(target_os = "linux"))]
fn probe_tray() -> TrayAvailabilityReport {
    TrayAvailabilityReport::available()
}

/// System tray availability and tray-panel labels exported to the desktop shell.
#[module]
pub struct OpenRoseTray;

#[module]
impl OpenRoseTray {
    /// Checks whether a system tray host is available in the current desktop session.
    pub fn check_availability() -> JsonValue {
        serde_json::to_value(probe_tray()).expect("tray availability serializes")
    }

    /// Returns the model's own label for an equalizer preset, or null when the model has none.
    pub fn equalizer_preset_label(profile_id: String, preset: String) -> Option<String> {
        ModelProfile::from_id(&profile_id)?
            .equalizer_preset_label(&preset)
            .map(str::to_string)
    }

    /// Names the typed prerequisite the core requires before a setting can be written, or null when the setting is writable now.
    pub fn setting_prerequisite(readings: JsonValue, setting: String) -> Option<String> {
        let readings: HeadsetReadings = serde_json::from_value(readings).ok()?;
        let setting: HeadsetSetting = serde_json::from_value(JsonValue::String(setting)).ok()?;
        let category = serde_json::to_value(readings.setting_prerequisite(setting)?).ok()?;
        category.as_str().map(str::to_string)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tray_availability_report_contains_boolean_status() {
        let value = OpenRoseTray::check_availability();
        let obj = value.as_object().expect("tray availability object");
        assert!(obj.get("available").and_then(|v| v.as_bool()).is_some());
    }

    #[test]
    fn equalizer_preset_label_follows_the_model_profile() {
        let profile = ModelProfile::ALL
            .iter()
            .copied()
            .find(|profile| profile.equalizer_preset_label("pop").is_some())
            .expect("a model with preset labels");
        let expected = profile.equalizer_preset_label("pop").map(str::to_string);
        assert_eq!(
            OpenRoseTray::equalizer_preset_label(profile.id().to_string(), "pop".to_string()),
            expected
        );
        assert_eq!(
            OpenRoseTray::equalizer_preset_label("UNKNOWN_MODEL".to_string(), "pop".to_string()),
            None
        );
    }

    #[test]
    fn setting_prerequisite_reports_the_core_category() {
        let readings = HeadsetReadings::new(ModelProfile::ALL[0]);
        let json = serde_json::to_value(&readings).expect("readings serialize");
        assert_eq!(
            OpenRoseTray::setting_prerequisite(json.clone(), "SpatialContent".to_string()),
            Some("SpatialModeRequired".to_string())
        );
        assert_eq!(
            OpenRoseTray::setting_prerequisite(json, "GameMode".to_string()),
            None
        );
    }
}
