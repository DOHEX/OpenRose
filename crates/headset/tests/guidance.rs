use openrose_headset::{
    FieldReading, FieldValue, HeadsetField, HeadsetReadings, HeadsetSetting, ModelProfile,
    ReconnectGuidance, SettingPrerequisite, setting_prerequisite,
};
use std::collections::BTreeMap;

#[test]
fn only_models_with_a_charging_case_ask_the_user_to_open_it() {
    assert_eq!(
        ReconnectGuidance::OpenCaseAutoConnect,
        ModelProfile::I5Zt.reconnect_guidance()
    );
    assert_eq!(
        ReconnectGuidance::WaitSystemBluetooth,
        ModelProfile::HeadFeelZt.reconnect_guidance()
    );
}

#[test]
fn setting_prerequisites_report_typed_categories_instead_of_ui_text() {
    let mut fields = BTreeMap::new();
    assert_eq!(
        Some(SettingPrerequisite::SpatialModeRequired),
        setting_prerequisite(&fields, HeadsetSetting::SpatialContent)
    );
    assert_eq!(
        Some(SettingPrerequisite::IndependentMicActivationRequired),
        setting_prerequisite(&fields, HeadsetSetting::IndependentMicMode)
    );
    assert_eq!(None, setting_prerequisite(&fields, HeadsetSetting::NoiseMode));

    fields.insert(
        HeadsetField::SpatialMode,
        FieldReading::Known {
            value: FieldValue::text("off"),
            raw_hex: "00".to_string(),
        },
    );
    fields.insert(
        HeadsetField::IndependentMicActivation,
        FieldReading::Known {
            value: FieldValue::Toggle(false),
            raw_hex: "00".to_string(),
        },
    );
    let readings = HeadsetReadings::from_fields(ModelProfile::I7RsV2, fields.clone());
    assert_eq!(
        Some(SettingPrerequisite::SpatialModeRequired),
        readings.setting_prerequisite(HeadsetSetting::SpatialContent)
    );
    assert_eq!(
        Some(SettingPrerequisite::IndependentMicActivationRequired),
        readings.setting_prerequisite(HeadsetSetting::IndependentMicMode)
    );

    fields.insert(
        HeadsetField::SpatialMode,
        FieldReading::Known {
            value: FieldValue::text("head_tracking"),
            raw_hex: "01".to_string(),
        },
    );
    fields.insert(
        HeadsetField::IndependentMicActivation,
        FieldReading::Known {
            value: FieldValue::Toggle(true),
            raw_hex: "01".to_string(),
        },
    );
    let ready = HeadsetReadings::from_fields(ModelProfile::I7RsV2, fields);
    assert_eq!(
        None,
        ready.setting_prerequisite(HeadsetSetting::SpatialContent)
    );
    assert_eq!(
        None,
        ready.setting_prerequisite(HeadsetSetting::IndependentMicMode)
    );
}
