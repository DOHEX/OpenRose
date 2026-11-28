use nsh_core::JsonValue;
use nsh_core::prelude::*;
use serde::Serialize;

#[derive(Serialize)]
enum TrayUnavailableReason {
    NoSessionBus,
    QueryFailed,
    MissingGdbus,
    NoWatcher,
    NoHost,
    HostStateUnreadable,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct TrayAvailabilityReport {
    available: bool,
    reason: Option<TrayUnavailableReason>,
}

impl TrayAvailabilityReport {
    fn available() -> Self {
        Self {
            available: true,
            reason: None,
        }
    }

    fn unavailable(reason: TrayUnavailableReason) -> Self {
        Self {
            available: false,
            reason: Some(reason),
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
        return TrayAvailabilityReport::unavailable(TrayUnavailableReason::NoSessionBus);
    }
    let watcher_owned = match gdbus_session_call(
        "org.freedesktop.DBus",
        "/org/freedesktop/DBus",
        "org.freedesktop.DBus.NameHasOwner",
        &["org.kde.StatusNotifierWatcher"],
    ) {
        Ok(Some(reply)) => reply.contains("true"),
        Ok(None) => return TrayAvailabilityReport::unavailable(TrayUnavailableReason::QueryFailed),
        Err(_) => return TrayAvailabilityReport::unavailable(TrayUnavailableReason::MissingGdbus),
    };
    if !watcher_owned {
        return TrayAvailabilityReport::unavailable(TrayUnavailableReason::NoWatcher);
    }
    match gdbus_session_call(
        "org.kde.StatusNotifierWatcher",
        "/StatusNotifierWatcher",
        "org.freedesktop.DBus.Properties.Get",
        &["org.kde.StatusNotifierWatcher", "IsStatusNotifierHostRegistered"],
    ) {
        Ok(Some(reply)) if reply.contains("true") => TrayAvailabilityReport::available(),
        Ok(Some(_)) => TrayAvailabilityReport::unavailable(TrayUnavailableReason::NoHost),
        _ => TrayAvailabilityReport::unavailable(TrayUnavailableReason::HostStateUnreadable),
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
}
