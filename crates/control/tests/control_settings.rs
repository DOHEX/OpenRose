use openrose_control::settings::ControlSettings;
use std::collections::BTreeMap;

fn values(entries: &[(&str, &str)]) -> BTreeMap<String, String> {
    entries
        .iter()
        .map(|(key, value)| (key.to_string(), value.to_string()))
        .collect()
}

#[test]
fn force_ble_round_trips_through_its_own_storage_key() {
    for enabled in [false, true] {
        let settings = ControlSettings { force_ble: enabled };
        assert_eq!(
            values(&[("core.force_ble", if enabled { "true" } else { "false" })]),
            settings.encode()
        );
        assert_eq!(settings, ControlSettings::decode(&settings.encode()));
    }
}

#[test]
fn missing_and_unknown_control_values_use_defaults() {
    assert_eq!(
        ControlSettings::default(),
        ControlSettings::decode(&values(&[]))
    );
    assert!(!ControlSettings::default().force_ble);
    for value in ["maybe", "", "TRUE", "True", "1"] {
        assert_eq!(
            ControlSettings::default(),
            ControlSettings::decode(&values(&[("core.force_ble", value)])),
            "{value:?}"
        );
    }
}

#[test]
fn old_and_foreign_keys_cannot_change_control_preferences() {
    assert_eq!(
        ControlSettings::default(),
        ControlSettings::decode(&values(&[
            ("force_ble", "true"),
            ("androidIntegration.force_ble", "true"),
        ]))
    );
    assert_eq!(
        ControlSettings { force_ble: true },
        ControlSettings::decode(&values(&[
            ("core.force_ble", "true"),
            ("force_ble", "false"),
        ]))
    );
}
