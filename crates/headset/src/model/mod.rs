pub mod identification;
pub mod rs_common;
pub mod zt;

use crate::capabilities::HeadsetCapabilities;
use crate::field::{FieldReading, FieldValue, FirmwareVersion, HeadsetField};
use rs_common::RsCommonProtocol;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};
use std::ops::RangeInclusive;

pub const ROSELINK_PREFIX_LENGTHS: [usize; 3] = [12, 8, 6];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GattSpec {
    pub service_uuid: &'static str,
    pub write_uuid: &'static str,
    pub notify_uuid: &'static str,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct GattProfile {
    pub service_uuid: String,
    pub write_uuid: String,
    pub notify_uuid: String,
}

impl From<GattSpec> for GattProfile {
    fn from(spec: GattSpec) -> Self {
        Self {
            service_uuid: spec.service_uuid.to_string(),
            write_uuid: spec.write_uuid.to_string(),
            notify_uuid: spec.notify_uuid.to_string(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub enum ProtocolFamily {
    Zt,
    RsCommonV2,
    F8,
    RsCommon,
}

impl ProtocolFamily {
    pub fn display_name(self) -> &'static str {
        match self {
            Self::Zt => "ZT",
            Self::RsCommonV2 => "RS Common V2",
            Self::F8 => "F8",
            Self::RsCommon => "RS Common",
        }
    }

    pub fn prompt_volume_levels(self) -> RangeInclusive<i32> {
        match self {
            Self::Zt => 1..=5,
            Self::RsCommonV2 => 0..=5,
            Self::F8 => 0..=9,
            Self::RsCommon => 1..=5,
        }
    }

    pub fn readable_fields(self) -> BTreeSet<HeadsetField> {
        match self {
            Self::Zt => crate::protocol::zt::zt_readable_fields(),
            Self::RsCommon => crate::protocol::rs_common::rs_common_readable_fields(),
            Self::RsCommonV2 | Self::F8 => BTreeSet::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub enum BatteryLayout {
    EarbudsAndCase,
    EarbudsOnly,
    SingleUnit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ZtProtocol {
    pub init_keys: &'static [&'static str],
    pub three_byte_noise: bool,
    pub independent_multipoint: bool,
    pub level_write_offset: i32,
    pub prompt_volume_direct: bool,
}

impl ZtProtocol {
    pub const fn default_i5() -> Self {
        Self {
            init_keys: &[],
            three_byte_noise: false,
            independent_multipoint: false,
            level_write_offset: 0,
            prompt_volume_direct: false,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelProtocol {
    Zt(ZtProtocol),
    RsCommon(RsCommonProtocol),
}

impl ModelProtocol {
    pub fn family(self) -> ProtocolFamily {
        match self {
            Self::Zt(_) => ProtocolFamily::Zt,
            Self::RsCommon(_) => ProtocolFamily::RsCommon,
        }
    }

    pub fn refresh_equalizer_after_game_mode(self) -> bool {
        match self {
            Self::Zt(_) => false,
            Self::RsCommon(_) => false,
        }
    }
}

pub struct ModelSpec {
    pub display_name: &'static str,
    pub short_label: &'static str,
    pub version_name: Option<&'static str>,
    pub protocol: ModelProtocol,
    pub advertisement_prefixes: &'static [&'static str],
    pub name_brand: &'static str,
    pub name_model_number: &'static str,
    pub name_requires_model: bool,
    pub name_brand_aliases: &'static [&'static str],
    pub gatt: GattSpec,
    pub spp_control_uuid: Option<&'static str>,
    pub write_interval_millis: u64,
    pub advertised_classic_address: Option<fn(&str) -> Option<String>>,
    pub battery_layout: BatteryLayout,
    pub equalizer_preset_labels: &'static [(&'static str, &'static str)],
    pub capabilities: fn(Option<&FirmwareVersion>) -> HeadsetCapabilities,
    pub game_equalizer_presets: Option<&'static [&'static str]>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub enum ModelProfile {
    #[serde(rename = "I5_ZT")]
    I5Zt,
    #[serde(rename = "I3_ZT")]
    I3Zt,
    #[serde(rename = "HEADFEEL_ZT")]
    HeadFeelZt,
    #[serde(rename = "BUDSFREE_I_RS_COMMON")]
    BudsFreeIRsCommon,
    #[serde(rename = "CAMBRIAN_RS_COMMON")]
    CambrianRsCommon,
    #[serde(rename = "OPENFEEL_LDAC_RS_COMMON")]
    OpenFeelLdacRsCommon,
    #[serde(rename = "OPENFEEL_LHDC_RS_COMMON")]
    OpenFeelLhdcRsCommon,
    #[serde(rename = "OPENFEEL_DUAL_RS_COMMON")]
    OpenFeelDualRsCommon,
    #[serde(rename = "OPENFUN_RS_COMMON")]
    OpenFunRsCommon,
    #[serde(rename = "OPENFUN_TOUCH_RS_COMMON")]
    OpenFunTouchRsCommon,
    #[serde(rename = "SPORTFEEL_RS_COMMON")]
    SportFeelRsCommon,
    #[serde(rename = "SWIFT_RS_COMMON")]
    SwiftRsCommon,
    #[serde(rename = "OPENLINK_RS_COMMON")]
    OpenLinkRsCommon,
    #[serde(rename = "CAMBRIAN_S_RS_COMMON")]
    CambrianSRsCommon,
}

impl ModelProfile {
    pub const ALL: &'static [Self] = &[
        Self::I5Zt,
        Self::I3Zt,
        Self::HeadFeelZt,
        Self::BudsFreeIRsCommon,
        Self::CambrianRsCommon,
        Self::OpenFeelLdacRsCommon,
        Self::OpenFeelLhdcRsCommon,
        Self::OpenFeelDualRsCommon,
        Self::OpenFunRsCommon,
        Self::OpenFunTouchRsCommon,
        Self::SportFeelRsCommon,
        Self::SwiftRsCommon,
        Self::OpenLinkRsCommon,
        Self::CambrianSRsCommon,
    ];

    pub fn spec(self) -> &'static ModelSpec {
        match self {
            Self::I5Zt => &zt::EAR_FREE_I5,
            Self::I3Zt => &zt::EAR_FREE_I3,
            Self::HeadFeelZt => &zt::HEAD_FEEL,
            Self::BudsFreeIRsCommon => &rs_common::BUDS_FREE_I,
            Self::CambrianRsCommon => &rs_common::CAMBRIAN,
            Self::OpenFeelLdacRsCommon => &rs_common::OPEN_FEEL_LDAC,
            Self::OpenFeelLhdcRsCommon => &rs_common::OPEN_FEEL_LHDC,
            Self::OpenFeelDualRsCommon => &rs_common::OPEN_FEEL_DUAL,
            Self::OpenFunRsCommon => &rs_common::OPEN_FUN,
            Self::OpenFunTouchRsCommon => &rs_common::OPEN_FUN_TOUCH,
            Self::SportFeelRsCommon => &rs_common::SPORT_FEEL,
            Self::SwiftRsCommon => &rs_common::SWIFT,
            Self::OpenLinkRsCommon => &rs_common::OPEN_LINK,
            Self::CambrianSRsCommon => &rs_common::CAMBRIAN_S,
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            Self::I5Zt => "I5_ZT",
            Self::I3Zt => "I3_ZT",
            Self::HeadFeelZt => "HEADFEEL_ZT",
            Self::BudsFreeIRsCommon => "BUDSFREE_I_RS_COMMON",
            Self::CambrianRsCommon => "CAMBRIAN_RS_COMMON",
            Self::OpenFeelLdacRsCommon => "OPENFEEL_LDAC_RS_COMMON",
            Self::OpenFeelLhdcRsCommon => "OPENFEEL_LHDC_RS_COMMON",
            Self::OpenFeelDualRsCommon => "OPENFEEL_DUAL_RS_COMMON",
            Self::OpenFunRsCommon => "OPENFUN_RS_COMMON",
            Self::OpenFunTouchRsCommon => "OPENFUN_TOUCH_RS_COMMON",
            Self::SportFeelRsCommon => "SPORTFEEL_RS_COMMON",
            Self::SwiftRsCommon => "SWIFT_RS_COMMON",
            Self::OpenLinkRsCommon => "OPENLINK_RS_COMMON",
            Self::CambrianSRsCommon => "CAMBRIAN_S_RS_COMMON",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        Self::ALL.iter().copied().find(|profile| profile.id() == id)
    }

    pub fn display_name(self) -> &'static str {
        self.spec().display_name
    }

    pub fn short_label(self) -> &'static str {
        self.spec().short_label
    }

    pub fn version_name(self) -> Option<&'static str> {
        self.spec().version_name
    }

    pub fn family(self) -> ProtocolFamily {
        self.spec().protocol.family()
    }

    pub fn gatt(self) -> GattProfile {
        self.spec().gatt.into()
    }

    pub fn spp_control_uuid(self) -> Option<&'static str> {
        self.spec().spp_control_uuid
    }

    pub fn write_interval_millis(self) -> u64 {
        self.spec().write_interval_millis
    }

    pub fn battery_layout(self) -> BatteryLayout {
        self.spec().battery_layout
    }

    pub fn refresh_equalizer_after_game_mode(self) -> bool {
        self.spec().protocol.refresh_equalizer_after_game_mode()
    }

    pub fn advertisement_prefixes(self) -> &'static [&'static str] {
        self.spec().advertisement_prefixes
    }

    pub fn equalizer_preset_label(self, preset: &str) -> Option<&'static str> {
        self.spec()
            .equalizer_preset_labels
            .iter()
            .find_map(|&(key, label)| if key == preset { Some(label) } else { None })
    }

    pub fn capabilities(
        self,
        firmware: Option<&FirmwareVersion>,
        game_mode: Option<bool>,
    ) -> HeadsetCapabilities {
        let mut capabilities = (self.spec().capabilities)(firmware);
        if let Some(game_presets) = self.spec().game_equalizer_presets {
            let game_set: BTreeSet<String> =
                game_presets.iter().map(|s| (*s).to_string()).collect();
            let eq = capabilities
                .equalizer
                .as_mut()
                .expect("models with game_equalizer_presets must define equalizer capabilities");
            let presets = match game_mode {
                Some(true) => game_set,
                Some(false) => eq.presets.clone(),
                None => eq.presets.intersection(&game_set).cloned().collect(),
            };
            eq.presets = presets;
        }
        capabilities.without_writes_without_options()
    }

    pub fn capabilities_from_readings(
        self,
        readings: &BTreeMap<HeadsetField, FieldReading>,
    ) -> HeadsetCapabilities {
        CapabilityFacts::from_readings(readings).resolve(self)
    }

    pub fn match_name(name: Option<&str>) -> Option<Self> {
        identification::match_profile_name(name)
    }

    pub fn name_pattern() -> String {
        identification::wake_name_pattern()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CapabilityFacts {
    pub firmware: Option<FirmwareVersion>,
    pub game_mode: Option<bool>,
}

impl CapabilityFacts {
    pub fn from_readings(readings: &BTreeMap<HeadsetField, FieldReading>) -> Self {
        let firmware = match readings.get(&HeadsetField::Firmware) {
            Some(FieldReading::Known {
                value: FieldValue::Firmware(fw),
                ..
            }) => Some(fw.clone()),
            _ => None,
        };
        let game_mode = match readings.get(&HeadsetField::GameMode) {
            Some(FieldReading::Known {
                value: FieldValue::Toggle(value),
                ..
            }) => Some(*value),
            _ => None,
        };
        Self {
            firmware,
            game_mode,
        }
    }

    pub fn resolve(&self, profile: ModelProfile) -> HeadsetCapabilities {
        profile.capabilities(self.firmware.as_ref(), self.game_mode)
    }
}

pub fn rs_readings(layout: BatteryLayout, extra: &[HeadsetField]) -> BTreeSet<HeadsetField> {
    let mut fields = BTreeSet::new();
    fields.insert(HeadsetField::Firmware);
    fields.insert(HeadsetField::GameMode);
    if layout != BatteryLayout::EarbudsOnly {
        fields.insert(HeadsetField::CaseBattery);
    }
    if layout != BatteryLayout::SingleUnit {
        fields.insert(HeadsetField::LeftBattery);
        fields.insert(HeadsetField::RightBattery);
        fields.insert(HeadsetField::LeftInCase);
        fields.insert(HeadsetField::RightInCase);
    }
    fields.extend(extra.iter().copied());
    fields
}
