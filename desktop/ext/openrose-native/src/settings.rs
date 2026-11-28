use nsh_core::prelude::*;
use nsh_core::JsonValue;
use openrose_headset::{
    FieldReading, HeadsetField, HeadsetSetting, ModelProfile, SettingPrerequisite,
    hearing_protection_threshold_db, setting_prerequisite,
};
use serde::Serialize;
use std::collections::BTreeMap;

const PREREQUISITE_SETTINGS: [HeadsetSetting; 2] = [
    HeadsetSetting::SpatialContent,
    HeadsetSetting::IndependentMicMode,
];

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ProfileLabels {
    version_name: Option<&'static str>,
    equalizer_preset_labels: BTreeMap<&'static str, &'static str>,
}

/// Profile display metadata exported from the Rust headset core for settings pages.
#[module]
pub struct OpenRoseSettings;

#[module]
impl OpenRoseSettings {
    /// Returns model-specific version and equalizer preset display labels from the core profile.
    pub fn profile_labels(model_id: String) -> JsonValue {
        let labels = ModelProfile::from_id(&model_id)
            .map(|profile| ProfileLabels {
                version_name: profile.version_name(),
                equalizer_preset_labels: profile
                    .spec()
                    .equalizer_preset_labels
                    .iter()
                    .copied()
                    .collect(),
            })
            .unwrap_or(ProfileLabels {
                version_name: None,
                equalizer_preset_labels: BTreeMap::new(),
            });
        serde_json::to_value(labels).expect("profile labels serialize")
    }

    /// Returns the hearing protection threshold in dB for a protection level when applicable.
    pub fn hearing_protection_threshold_db(level: i32) -> Option<i32> {
        hearing_protection_threshold_db(level)
    }

    /// Reports which settings are blocked by an unmet core prerequisite, given the readings fields.
    pub fn setting_prerequisites(fields: JsonValue) -> JsonValue {
        let fields: BTreeMap<HeadsetField, FieldReading> =
            serde_json::from_value(fields).unwrap_or_default();
        let blocked: BTreeMap<HeadsetSetting, SettingPrerequisite> = PREREQUISITE_SETTINGS
            .into_iter()
            .filter_map(|setting| setting_prerequisite(&fields, setting).map(|p| (setting, p)))
            .collect();
        serde_json::to_value(blocked).expect("setting prerequisites serialize")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use openrose_headset::HeadsetCommand;

    #[test]
    fn exposes_profile_labels_from_model_spec() {
        let i7 = OpenRoseSettings::profile_labels("I7_RS_V2".to_string());
        assert_eq!(i7["versionName"], JsonValue::Null);
        assert_eq!(i7["equalizerPresetLabels"]["pop"], "弱水经典");
        assert_eq!(i7["equalizerPresetLabels"]["eap"], "欧美流行");

        let ceramics_x = OpenRoseSettings::profile_labels("CERAMICS_X_F8".to_string());
        assert_eq!(ceramics_x["versionName"], "标准版");
        assert_eq!(ceramics_x["equalizerPresetLabels"]["pop"], "POP");

        assert_eq!(OpenRoseSettings::hearing_protection_threshold_db(0), None);
        assert_eq!(OpenRoseSettings::hearing_protection_threshold_db(6), Some(100));
    }

    #[test]
    fn reports_unmet_prerequisites_from_snapshot_fields() {
        let blocked = OpenRoseSettings::setting_prerequisites(serde_json::json!({
            "SpatialMode": {"Known": {"value": {"Text": "head_tracking"}, "raw_hex": "01"}},
        }));
        assert!(blocked.get("SpatialContent").is_none());
        assert_eq!(blocked["IndependentMicMode"], "IndependentMicActivationRequired");
    }

    #[test]
    fn deserializes_all_setting_command_payloads() {
        let payloads = [
            r#"{"NoiseMode":"Anc"}"#,
            r#"{"AncLevel":3}"#,
            r#"{"TransparencyLevel":3}"#,
            r#"{"NoiseCycle":{"anc":true,"normal":true,"transparency":false,"wind":false}}"#,
            r#"{"EqualizerPreset":"hifi"}"#,
            r#"{"CustomEqualizer":[2,-1,0,1,3,-2,0,1,2,0]}"#,
            r#"{"TouchBinding":{"side":"left","gesture":"single","action":"play_pause"}}"#,
            r#"{"TapBinding":{"side":"right","gesture":"double","action":"next_track"}}"#,
            r#"{"PromptVolume":4}"#,
            r#"{"Gain":2}"#,
            r#"{"HearingProtection":3}"#,
            r#"{"BassBoost":2}"#,
            r#"{"GameMode":true}"#,
            r#"{"Multipoint":false}"#,
            r#"{"TouchEnabled":true}"#,
            r#"{"EarDetection":true}"#,
            r#"{"TapEnabled":true}"#,
            r#"{"SpatialAudio":true}"#,
            r#"{"EarAdaptive":true}"#,
            r#"{"IndependentMicActivation":true}"#,
            r#"{"IndependentMicMode":true}"#,
            r#"{"AudioMode":"ldac"}"#,
            r#"{"PromptLanguage":"english"}"#,
            r#"{"SpatialMode":"head_tracking"}"#,
            r#"{"SpatialContent":"movie"}"#,
        ];
        for payload in payloads {
            serde_json::from_str::<HeadsetCommand>(payload)
                .unwrap_or_else(|err| panic!("failed to parse {payload}: {err}"));
        }
    }

    #[test]
    fn executes_representative_commands_across_all_four_families() {
        use openrose_control::scan::Advertisement;
        use openrose_control::simulator::SimulatedTransport;
        use openrose_control::snapshot::{
            HostPlatform, PermissionState, PlatformState, SettingStage, TransportAvailability,
        };
        use openrose_control::{ControlConfig, ControlObserver, ControlRuntime, LocalClock};
        use openrose_headset::{HeadsetId, LocalDateTime, decode_hex};
        use std::sync::Arc;
        use std::time::Duration;

        struct TestClock;
        impl LocalClock for TestClock {
            fn now(&self) -> LocalDateTime {
                LocalDateTime::new(2026, 11, 27, 21, 0, 0).expect("clock")
            }
        }

        struct NoopObserver;
        impl ControlObserver for NoopObserver {
            fn state_changed(&self, _snapshot: &openrose_control::snapshot::ControlSnapshot) {}
        }

        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("tokio runtime");

        rt.block_on(async {
            type FamilyCase = (
                &'static str,
                &'static str,
                &'static [&'static str],
                &'static [&'static str],
                &'static [&'static str],
            );
            let cases: &[FamilyCase] = &[
                (
                    "HEADFEEL_ZT",
                    "125",
                    &[r#"{"EqualizerPreset":"hifi"}"#],
                    &[],
                    &["Gain", "CustomEq"],
                ),
                (
                    "HEADFEEL_ZT",
                    "130",
                    &[r#"{"Gain":2}"#, r#"{"CustomEqualizer":[1,2,0,-1,0,1,2,1,0,-1]}"#],
                    &["Gain", "CustomEq"],
                    &[],
                ),
                (
                    "I7_RS_V2",
                    "212",
                    &[r#"{"PromptVolume":1}"#],
                    &[],
                    &["Gain", "HearingProtection"],
                ),
                (
                    "I7_RS_V2",
                    "304",
                    &[r#"{"PromptVolume":0}"#, r#"{"HearingProtection":4}"#],
                    &["Gain", "HearingProtection"],
                    &[],
                ),
                (
                    "ASTRO_X_RS_V2",
                    "152",
                    &[
                        r#"{"SpatialMode":"head_tracking"}"#,
                        r#"{"SpatialContent":"movie"}"#,
                        r#"{"TapEnabled":true}"#,
                    ],
                    &["SpatialMode", "SpatialContent", "TapEnabled"],
                    &[],
                ),
                (
                    "SPEAKFEEL2_RS_V2",
                    "125",
                    &[
                        r#"{"IndependentMicActivation":true}"#,
                        r#"{"IndependentMicMode":true}"#,
                    ],
                    &["IndependentMicActivation", "IndependentMicMode"],
                    &[],
                ),
                (
                    "CAMBRIAN_RS_COMMON",
                    "125",
                    &[r#"{"TransparencyLevel":2}"#],
                    &["TransparencyLevel"],
                    &[],
                ),
                (
                    "EXPLORER_F8",
                    "125",
                    &[r#"{"PromptLanguage":"english"}"#],
                    &["PromptLanguage"],
                    &[],
                ),
            ];

            for &(model_id, fw, commands, present, absent) in cases {
                let transport = Arc::new(SimulatedTransport::new());
                let profile = ModelProfile::from_id(model_id).expect("profile");
                let firmware = profile.family().parse_firmware(fw).expect("firmware");
                let id = HeadsetId::new(format!("sim:{model_id}"));
                let handle = transport
                    .add_headset(id.clone(), profile, firmware)
                    .expect("add headset");
                handle.reply_latency(Duration::from_millis(5));
                let prefix = decode_hex(profile.advertisement_prefixes()[0]).expect("prefix");
                let company = u16::from(prefix[0]) | (u16::from(prefix[1]) << 8);
                let mut adv = Advertisement::new(id.clone());
                adv.name = Some(profile.display_name().to_string());
                adv.manufacturer_data.insert(company, prefix[2..].to_vec());

                let platform = PlatformState::new(
                    PermissionState::NotRequired,
                    TransportAvailability::Available,
                )
                .on(HostPlatform::Linux);
                let runtime = ControlRuntime::new(
                    transport.clone(),
                    Arc::new(NoopObserver),
                    ControlConfig::new(platform, Arc::new(TestClock)),
                );
                let _ = runtime.start_scan().await;
                transport.scan().advertise(adv);
                tokio::task::yield_now().await;
                runtime
                    .connect_for_add_headset(id)
                    .await
                    .expect("connect");
                runtime.stop_scan().await;

                let writable = runtime
                    .snapshot()
                    .headset
                    .expect("headset state")
                    .readings
                    .capabilities
                    .writable_settings;
                for (names, expected) in [(present, true), (absent, false)] {
                    for name in names {
                        let setting: HeadsetSetting =
                            serde_json::from_value(serde_json::json!(name)).expect("setting name");
                        assert_eq!(
                            writable.contains(&setting),
                            expected,
                            "{model_id} {fw} {name}"
                        );
                    }
                }

                for &cmd_json in commands {
                    let cmd: HeadsetCommand = serde_json::from_str(cmd_json).expect("cmd json");
                    runtime.set(cmd, None).await.expect("command confirmed");
                }
                let snap = runtime.snapshot();
                assert!(
                    snap.setting_operations
                        .iter()
                        .all(|op| op.stage == SettingStage::Confirmed),
                    "{model_id} expected all confirmed"
                );
            }
        });
    }
}
