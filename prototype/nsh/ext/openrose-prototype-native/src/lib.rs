use nsh_core::prelude::*;

/// Exports prototype host info and simulated headset state to TSX.
#[native_module("openrose-prototype-native")]
fn native(registry: &mut Registry) {
    registry.module::<ProtoHost>().class::<MockHeadset>();
}

/// Process and display environment inspection for prototype verification.
#[module]
pub struct ProtoHost;

#[module]
impl ProtoHost {
    /// Current process identifier for verifying single-instance handoff.
    pub fn pid() -> u32 {
        std::process::id()
    }

    /// Summary of OS, CPU architecture, and display session variables.
    pub fn runtime_env() -> String {
        let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_else(|_| "-".to_owned());
        let wayland = std::env::var("WAYLAND_DISPLAY").unwrap_or_else(|_| "-".to_owned());
        let x11 = std::env::var("DISPLAY").unwrap_or_else(|_| "-".to_owned());
        let gdk = std::env::var("GDK_BACKEND").unwrap_or_else(|_| "default".to_owned());
        format!(
            "{}/{} · desktop={} · wayland={} · display={} · gdk={}",
            std::env::consts::OS,
            std::env::consts::ARCH,
            desktop,
            wayland,
            x11,
            gdk,
        )
    }
}

/// Simulated ROSE EarFree i5 state kept in Rust to validate hybrid mode.
#[class]
pub struct MockHeadset {
    model_name: String,
    connected: bool,
    anc_mode: String,
    left_battery: u32,
    right_battery: u32,
    case_battery: u32,
    revision: u32,
}

#[class]
impl MockHeadset {
    /// Creates a connected simulated EarFree i5 headset.
    pub fn new() -> Self {
        Self {
            model_name: "ROSE EarFree i5".to_owned(),
            connected: true,
            anc_mode: "降噪".to_owned(),
            left_battery: 85,
            right_battery: 80,
            case_battery: 95,
            revision: 1,
        }
    }

    /// Model display name.
    #[getter]
    pub fn model_name(&self) -> String {
        self.model_name.clone()
    }

    /// Whether the simulated control session is connected.
    #[getter]
    pub fn connected(&self) -> bool {
        self.connected
    }

    /// Current noise control mode label.
    #[getter]
    pub fn anc_mode(&self) -> String {
        self.anc_mode.clone()
    }

    /// Simulated left earbud battery percentage.
    #[getter]
    pub fn left_battery(&self) -> u32 {
        self.left_battery
    }

    /// Simulated right earbud battery percentage.
    #[getter]
    pub fn right_battery(&self) -> u32 {
        self.right_battery
    }

    /// Simulated charging case battery percentage.
    #[getter]
    pub fn case_battery(&self) -> u32 {
        self.case_battery
    }

    /// Monotonic revision bumped on every state mutation.
    #[getter]
    pub fn revision(&self) -> u32 {
        self.revision
    }

    /// One-line summary suitable for tray tooltips and status rows.
    #[getter]
    pub fn summary(&self) -> String {
        if !self.connected {
            return format!("{} · 未连接", self.model_name);
        }
        format!(
            "{} · L{}% R{}% 盒{}% · {}",
            self.model_name,
            self.left_battery,
            self.right_battery,
            self.case_battery,
            self.anc_mode
        )
    }

    /// Toggles the simulated connection state and returns the new value.
    pub fn toggle_connected(&mut self) -> bool {
        self.connected = !self.connected;
        self.revision += 1;
        self.connected
    }

    /// Sets the noise control mode if valid and returns the active mode.
    pub fn set_anc_mode(&mut self, mode: String) -> String {
        if matches!(mode.as_str(), "降噪" | "通透" | "关闭") {
            self.anc_mode = mode;
            self.revision += 1;
        }
        self.anc_mode.clone()
    }

    /// Decrements simulated earbud battery levels by 5% for UI refresh testing.
    pub fn drain_battery_step(&mut self) -> u32 {
        self.left_battery = if self.left_battery <= 10 {
            100
        } else {
            self.left_battery - 5
        };
        self.right_battery = if self.right_battery <= 10 {
            100
        } else {
            self.right_battery - 5
        };
        self.revision += 1;
        self.left_battery
    }
}

#[cfg(test)]
mod tests {
    use super::MockHeadset;

    #[test]
    fn mock_headset_updates_mode_and_summary() {
        let mut headset = MockHeadset::new();
        assert!(headset.connected());
        assert_eq!(headset.anc_mode(), "降噪");
        assert_eq!(headset.set_anc_mode("通透".to_owned()), "通透");
        assert!(headset.summary().contains("通透"));
        assert!(!headset.toggle_connected());
        assert!(headset.summary().contains("未连接"));
    }

    #[test]
    fn mock_headset_ignores_unknown_anc_mode() {
        let mut headset = MockHeadset::new();
        let before = headset.revision();
        assert_eq!(headset.set_anc_mode("未知".to_owned()), "降噪");
        assert_eq!(headset.revision(), before);
    }
}
