pub mod f8;
pub mod identification;
pub mod rs_common;
pub mod rs_common_v2;
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
            Self::F8 => crate::protocol::f8::f8_readable_fields(),
            Self::RsCommonV2 => crate::protocol::rs_common_v2::rs_common_v2_readable_fields(),
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
pub struct RsCommonV2Protocol {
    pub info_ids: Option<&'static str>,
    pub spatial_needs_ear_adaptive: bool,
    pub synchronize_clock_on_connect: bool,
    pub refresh_equalizer_after_game_mode: bool,
}

impl RsCommonV2Protocol {
    pub const fn with_info_ids(info_ids: &'static str) -> Self {
        Self {
            info_ids: Some(info_ids),
            spatial_needs_ear_adaptive: false,
            synchronize_clock_on_connect: false,
            refresh_equalizer_after_game_mode: false,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct F8Protocol {
    pub extended_init: bool,
    pub noise_normal_wire: u8,
    pub eq_at_two: Option<&'static str>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelProtocol {
    Zt(ZtProtocol),
    RsCommonV2(RsCommonV2Protocol),
    RsCommon(RsCommonProtocol),
    F8(F8Protocol),
}

impl ModelProtocol {
    pub fn family(self) -> ProtocolFamily {
        match self {
            Self::Zt(_) => ProtocolFamily::Zt,
            Self::RsCommonV2(_) => ProtocolFamily::RsCommonV2,
            Self::RsCommon(_) => ProtocolFamily::RsCommon,
            Self::F8(_) => ProtocolFamily::F8,
        }
    }

    pub fn refresh_equalizer_after_game_mode(self) -> bool {
        match self {
            Self::Zt(_) | Self::RsCommon(_) | Self::F8(_) => false,
            Self::RsCommonV2(protocol) => protocol.refresh_equalizer_after_game_mode,
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
    #[serde(rename = "I7_RS_V2")]
    I7RsV2,
    #[serde(rename = "BUDSFEEL_MK2_RS_V2")]
    BudsFeelMk2RsV2,
    #[serde(rename = "OPENFEEL_MK2_RS_V2")]
    OpenFeelMk2RsV2,
    #[serde(rename = "SWIFT2_RS_V2")]
    Swift2RsV2,
    #[serde(rename = "JASMINE_RS_V2")]
    JasmineRsV2,
    #[serde(rename = "CERAMICS_MK2_RS_V2")]
    CeramicsMk2RsV2,
    #[serde(rename = "CERAMICS_MK2_8912F_RS_V2")]
    CeramicsMk28912fRsV2,
    #[serde(rename = "CERAMICS_MK2_5736_RS_V2")]
    CeramicsMk25736RsV2,
    #[serde(rename = "CERAMICS_U_RS_V2")]
    CeramicsURsV2,
    #[serde(rename = "CERAMICS_U_HSC_2710_RS_V2")]
    CeramicsUHsc2710RsV2,
    #[serde(rename = "SPEAKFREE_RS_V2")]
    SpeakFreeRsV2,
    #[serde(rename = "MAGICIAN_RS_V2")]
    MagicianRsV2,
    #[serde(rename = "FURINA_RS_V2")]
    FurinaRsV2,
    #[serde(rename = "ZIRCON_RS_V2")]
    ZirconRsV2,
    #[serde(rename = "HYACINTH_RS_V2")]
    HyacinthRsV2,
    #[serde(rename = "ASTRO_X_RS_V2")]
    AstroXRsV2,
    #[serde(rename = "OPAL_RS_V2")]
    OpalRsV2,
    #[serde(rename = "MIRROR_RS_V2")]
    MirrorRsV2,
    #[serde(rename = "KY01_RS_V2")]
    Ky01RsV2,
    #[serde(rename = "CORAL_RS_V2")]
    CoralRsV2,
    #[serde(rename = "PATAMON_RS_V2")]
    PatamonRsV2,
    #[serde(rename = "TAILMON_RS_V2")]
    TailmonRsV2,
    #[serde(rename = "GABUMON_RS_V2")]
    GabumonRsV2,
    #[serde(rename = "AGUMON_RS_V2")]
    AgumonRsV2,
    #[serde(rename = "SPEAKFEEL2_RS_V2")]
    SpeakFeel2RsV2,
    #[serde(rename = "OPENFEEL_L_RS_V2")]
    OpenFeelLRsV2,
    #[serde(rename = "FIRST_ANGEL_RS_V2")]
    FirstAngelRsV2,
    #[serde(rename = "BUDSFEEL_AE_NEO_RS_V2")]
    BudsFeelAeNeoRsV2,
    #[serde(rename = "EARFEEL_IE_NEO_RS_V2")]
    EarFeelIeNeoRsV2,
    #[serde(rename = "EARFEEL_IE2_RS_V2")]
    EarFeelIe2RsV2,
    #[serde(rename = "SPORTFEEL_8912F_RS_V2")]
    SportFeel8912fRsV2,
    #[serde(rename = "MOKA_RS_V2")]
    MokaRsV2,
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
    #[serde(rename = "AGATE_F8")]
    AgateF8,
    #[serde(rename = "AGATE_X_F8")]
    AgateXF8,
    #[serde(rename = "CERAMICS_F8")]
    CeramicsF8,
    #[serde(rename = "CERAMICS_X_F8")]
    CeramicsXF8,
    #[serde(rename = "CERAMICS_X_7034_F8")]
    CeramicsX7034F8,
    #[serde(rename = "EXPLORER_F8")]
    ExplorerF8,
}

impl ModelProfile {
    pub const ALL: &'static [Self] = &[
        Self::I5Zt,
        Self::I3Zt,
        Self::HeadFeelZt,
        Self::I7RsV2,
        Self::BudsFeelMk2RsV2,
        Self::OpenFeelMk2RsV2,
        Self::Swift2RsV2,
        Self::JasmineRsV2,
        Self::CeramicsMk2RsV2,
        Self::CeramicsMk28912fRsV2,
        Self::CeramicsMk25736RsV2,
        Self::CeramicsURsV2,
        Self::CeramicsUHsc2710RsV2,
        Self::SpeakFreeRsV2,
        Self::MagicianRsV2,
        Self::FurinaRsV2,
        Self::ZirconRsV2,
        Self::HyacinthRsV2,
        Self::AstroXRsV2,
        Self::OpalRsV2,
        Self::MirrorRsV2,
        Self::Ky01RsV2,
        Self::CoralRsV2,
        Self::PatamonRsV2,
        Self::TailmonRsV2,
        Self::GabumonRsV2,
        Self::AgumonRsV2,
        Self::SpeakFeel2RsV2,
        Self::OpenFeelLRsV2,
        Self::FirstAngelRsV2,
        Self::BudsFeelAeNeoRsV2,
        Self::EarFeelIeNeoRsV2,
        Self::EarFeelIe2RsV2,
        Self::SportFeel8912fRsV2,
        Self::MokaRsV2,
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
        Self::AgateF8,
        Self::AgateXF8,
        Self::CeramicsF8,
        Self::CeramicsXF8,
        Self::CeramicsX7034F8,
        Self::ExplorerF8,
    ];

    pub fn spec(self) -> &'static ModelSpec {
        match self {
            Self::I5Zt => &zt::EAR_FREE_I5,
            Self::I3Zt => &zt::EAR_FREE_I3,
            Self::HeadFeelZt => &zt::HEAD_FEEL,
            Self::I7RsV2 => &rs_common_v2::EAR_FEEL_I7,
            Self::BudsFeelMk2RsV2 => &rs_common_v2::BUDS_FEEL_MK2,
            Self::OpenFeelMk2RsV2 => &rs_common_v2::OPEN_FEEL_MK2,
            Self::Swift2RsV2 => &rs_common_v2::SWIFT_2,
            Self::JasmineRsV2 => &rs_common_v2::JASMINE,
            Self::CeramicsMk2RsV2 => &rs_common_v2::CERAMICS_MK2,
            Self::CeramicsMk28912fRsV2 => &rs_common_v2::CERAMICS_MK2_8912F,
            Self::CeramicsMk25736RsV2 => &rs_common_v2::CERAMICS_MK2_5736,
            Self::CeramicsURsV2 => &rs_common_v2::CERAMICS_U,
            Self::CeramicsUHsc2710RsV2 => &rs_common_v2::CERAMICS_U_HSC_2710,
            Self::SpeakFreeRsV2 => &rs_common_v2::SPEAK_FREE,
            Self::MagicianRsV2 => &rs_common_v2::MAGICIAN,
            Self::FurinaRsV2 => &rs_common_v2::FURINA,
            Self::ZirconRsV2 => &rs_common_v2::ZIRCON,
            Self::HyacinthRsV2 => &rs_common_v2::HYACINTH,
            Self::AstroXRsV2 => &rs_common_v2::ASTRO_X,
            Self::OpalRsV2 => &rs_common_v2::OPAL,
            Self::MirrorRsV2 => &rs_common_v2::MIRROR,
            Self::Ky01RsV2 => &rs_common_v2::KY01,
            Self::CoralRsV2 => &rs_common_v2::CORAL,
            Self::PatamonRsV2 => &rs_common_v2::PATAMON,
            Self::TailmonRsV2 => &rs_common_v2::TAILMON,
            Self::GabumonRsV2 => &rs_common_v2::GABUMON,
            Self::AgumonRsV2 => &rs_common_v2::AGUMON,
            Self::SpeakFeel2RsV2 => &rs_common_v2::SPEAK_FEEL_2,
            Self::OpenFeelLRsV2 => &rs_common_v2::OPEN_FEEL_L,
            Self::FirstAngelRsV2 => &rs_common_v2::FIRST_ANGEL,
            Self::BudsFeelAeNeoRsV2 => &rs_common_v2::BUDS_FEEL_AE_NEO,
            Self::EarFeelIeNeoRsV2 => &rs_common_v2::EAR_FEEL_IE_NEO,
            Self::EarFeelIe2RsV2 => &rs_common_v2::EAR_FEEL_IE2,
            Self::SportFeel8912fRsV2 => &rs_common_v2::SPORT_FEEL_8912F,
            Self::MokaRsV2 => &rs_common_v2::MOKA,
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
            Self::AgateF8 => &f8::AGATE,
            Self::AgateXF8 => &f8::AGATE_X,
            Self::CeramicsF8 => &f8::CERAMICS,
            Self::CeramicsXF8 => &f8::CERAMICS_X,
            Self::CeramicsX7034F8 => &f8::CERAMICS_X_7034,
            Self::ExplorerF8 => &f8::EXPLORER,
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            Self::I5Zt => "I5_ZT",
            Self::I3Zt => "I3_ZT",
            Self::HeadFeelZt => "HEADFEEL_ZT",
            Self::I7RsV2 => "I7_RS_V2",
            Self::BudsFeelMk2RsV2 => "BUDSFEEL_MK2_RS_V2",
            Self::OpenFeelMk2RsV2 => "OPENFEEL_MK2_RS_V2",
            Self::Swift2RsV2 => "SWIFT2_RS_V2",
            Self::JasmineRsV2 => "JASMINE_RS_V2",
            Self::CeramicsMk2RsV2 => "CERAMICS_MK2_RS_V2",
            Self::CeramicsMk28912fRsV2 => "CERAMICS_MK2_8912F_RS_V2",
            Self::CeramicsMk25736RsV2 => "CERAMICS_MK2_5736_RS_V2",
            Self::CeramicsURsV2 => "CERAMICS_U_RS_V2",
            Self::CeramicsUHsc2710RsV2 => "CERAMICS_U_HSC_2710_RS_V2",
            Self::SpeakFreeRsV2 => "SPEAKFREE_RS_V2",
            Self::MagicianRsV2 => "MAGICIAN_RS_V2",
            Self::FurinaRsV2 => "FURINA_RS_V2",
            Self::ZirconRsV2 => "ZIRCON_RS_V2",
            Self::HyacinthRsV2 => "HYACINTH_RS_V2",
            Self::AstroXRsV2 => "ASTRO_X_RS_V2",
            Self::OpalRsV2 => "OPAL_RS_V2",
            Self::MirrorRsV2 => "MIRROR_RS_V2",
            Self::Ky01RsV2 => "KY01_RS_V2",
            Self::CoralRsV2 => "CORAL_RS_V2",
            Self::PatamonRsV2 => "PATAMON_RS_V2",
            Self::TailmonRsV2 => "TAILMON_RS_V2",
            Self::GabumonRsV2 => "GABUMON_RS_V2",
            Self::AgumonRsV2 => "AGUMON_RS_V2",
            Self::SpeakFeel2RsV2 => "SPEAKFEEL2_RS_V2",
            Self::OpenFeelLRsV2 => "OPENFEEL_L_RS_V2",
            Self::FirstAngelRsV2 => "FIRST_ANGEL_RS_V2",
            Self::BudsFeelAeNeoRsV2 => "BUDSFEEL_AE_NEO_RS_V2",
            Self::EarFeelIeNeoRsV2 => "EARFEEL_IE_NEO_RS_V2",
            Self::EarFeelIe2RsV2 => "EARFEEL_IE2_RS_V2",
            Self::SportFeel8912fRsV2 => "SPORTFEEL_8912F_RS_V2",
            Self::MokaRsV2 => "MOKA_RS_V2",
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
            Self::AgateF8 => "AGATE_F8",
            Self::AgateXF8 => "AGATE_X_F8",
            Self::CeramicsF8 => "CERAMICS_F8",
            Self::CeramicsXF8 => "CERAMICS_X_F8",
            Self::CeramicsX7034F8 => "CERAMICS_X_7034_F8",
            Self::ExplorerF8 => "EXPLORER_F8",
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
