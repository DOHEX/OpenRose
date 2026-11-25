use crate::capabilities::{
    AudioControlCapabilities, CustomEqualizerCapabilities, EqualizerCapabilities,
    HeadsetCapabilities, NoiseControlCapabilities, NoiseLevelCapabilities,
    TouchControlCapabilities, basic_noise_modes, earbud_touch_slots, firmware_at_least,
    prompt_volume_levels,
};
use crate::field::{FirmwareVersion, HeadsetField, HeadsetSetting, TouchSlot};
use crate::model::{
    BatteryLayout, GattSpec, ModelProtocol, ModelSpec, RsCommonV2Protocol, rs_readings,
};
use std::collections::BTreeSet;
use std::ops::RangeInclusive;

const RS_V2_GATT: GattSpec = GattSpec {
    service_uuid: "000000fe-0000-1000-8000-00805f9b34fb",
    write_uuid: "000000f1-0000-1000-8000-00805f9b34fb",
    notify_uuid: "000000f2-0000-1000-8000-00805f9b34fb",
};

const RS_V2_SPP_CONTROL_UUID: &str = "0cf12d31-fac3-4553-bd80-d6832e7b3931";

const fn info(info_ids: &'static str) -> ModelProtocol {
    ModelProtocol::RsCommonV2(RsCommonV2Protocol::with_info_ids(info_ids))
}

pub fn rs_v2_classic_address(normalized_manufacturer_data_hex: &str) -> Option<String> {
    if normalized_manufacturer_data_hex.len() < 34
        || !normalized_manufacturer_data_hex
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit())
    {
        return None;
    }
    let normalized = normalized_manufacturer_data_hex.to_ascii_uppercase();
    if &normalized[14..16] != "01" {
        return None;
    }
    let digits = &normalized[22..34];
    if digits == "000000000000" || digits == "FFFFFFFFFFFF" {
        return None;
    }
    Some(
        (0..6)
            .map(|index| &digits[index * 2..index * 2 + 2])
            .collect::<Vec<_>>()
            .join(":"),
    )
}

pub static EAR_FEEL_I7: ModelSpec = ModelSpec {
    display_name: "EarFeel i7",
    short_label: "i7",
    version_name: None,
    protocol: info("0d0c092c2d2a45460e322b01072f082e36"),
    advertisement_prefixes: &["b8e5290f"],
    name_brand: "earfeel",
    name_model_number: "i7",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[
        ("pop", "弱水经典"),
        ("hifi", "原声再现"),
        ("rock", "古典乐器"),
        ("light", "日系甜美"),
        ("eap", "欧美流行"),
    ],
    capabilities: ear_feel_i7_capabilities,
    game_equalizer_presets: None,
};

pub static BUDS_FEEL_MK2: ModelSpec = ModelSpec {
    display_name: "BudsFeel MK2",
    short_label: "BudsFeel MK2",
    version_name: None,
    protocol: info("0d0c092a0e322b01072f2e36"),
    advertisement_prefixes: &["0b150a0b"],
    name_brand: "budsfeel",
    name_model_number: "mk2",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: buds_feel_mk2_capabilities,
    game_equalizer_presets: None,
};

pub static OPEN_FEEL_MK2: ModelSpec = ModelSpec {
    display_name: "OpenFeel MK2",
    short_label: "OpenFeel MK2",
    version_name: None,
    protocol: info("0d0c2a45460e322b01072f2e"),
    advertisement_prefixes: &["0b0eb657"],
    name_brand: "openfeel",
    name_model_number: "mk2",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: open_feel_mk2_capabilities,
    game_equalizer_presets: None,
};

pub static SWIFT_2: ModelSpec = ModelSpec {
    display_name: "SWIFT 2",
    short_label: "SWIFT 2",
    version_name: None,
    protocol: info("0d0c0e3201072f2e"),
    advertisement_prefixes: &["5999064c"],
    name_brand: "swift",
    name_model_number: "swift2",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: &[],
    capabilities: swift_2_capabilities,
    game_equalizer_presets: None,
};

pub static JASMINE: ModelSpec = ModelSpec {
    display_name: "Jasmine",
    short_label: "Jasmine",
    version_name: None,
    protocol: info("0d0c0e322f0107312e"),
    advertisement_prefixes: &["0b0a0a11"],
    name_brand: "jasmine",
    name_model_number: "jasmine",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: jasmine_capabilities,
    game_equalizer_presets: None,
};

pub static AGUMON: ModelSpec = ModelSpec {
    display_name: "Agumon",
    short_label: "Agumon",
    version_name: None,
    protocol: info("0d0c0e3201072f2e"),
    advertisement_prefixes: &["0b0a34fd"],
    name_brand: "agumon",
    name_model_number: "agumon",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: agumon_capabilities,
    game_equalizer_presets: None,
};

pub static ASTRO_X: ModelSpec = ModelSpec {
    display_name: "Astro X",
    short_label: "Astro X",
    version_name: None,
    protocol: ModelProtocol::RsCommonV2(RsCommonV2Protocol {
        info_ids: Some("0d0c383d2a45460e322b01073b3c2f082e3a"),
        spatial_needs_ear_adaptive: true,
        synchronize_clock_on_connect: true,
        refresh_equalizer_after_game_mode: false,
    }),
    advertisement_prefixes: &["0b0e0a0b"],
    name_brand: "astro",
    name_model_number: "astrox",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "BASS"), ("hifi", "BALANCE"), ("rock", "BASS+")],
    capabilities: astro_x_capabilities,
    game_equalizer_presets: None,
};

pub static BUDS_FEEL_AE_NEO: ModelSpec = ModelSpec {
    display_name: "BudsFeel AE Neo",
    short_label: "BudsFeel AE Neo",
    version_name: None,
    protocol: info("0d0c2a45460e3201072f2e"),
    advertisement_prefixes: &["45d789b0"],
    name_brand: "budsfeel",
    name_model_number: "aeneo",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: buds_feel_ae_neo_capabilities,
    game_equalizer_presets: None,
};

pub static CERAMICS_MK2: ModelSpec = ModelSpec {
    display_name: "CERAMICS-MK2",
    short_label: "CERAMICS-MK2",
    version_name: None,
    protocol: info("0d0c092c2d2a0e322b2f0107312e36"),
    advertisement_prefixes: &["0b0e0a0c"],
    name_brand: "ceramics",
    name_model_number: "mk2",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: ceramics_mk2_capabilities,
    game_equalizer_presets: None,
};

pub static CERAMICS_MK2_5736: ModelSpec = ModelSpec {
    display_name: "CERAMICS-MK2 5736",
    short_label: "CERAMICS-MK2 5736",
    version_name: None,
    protocol: info("0d0c092c2d2a0e322f01072e36"),
    advertisement_prefixes: &["45d7b286"],
    name_brand: "ceramics",
    name_model_number: "mk2",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: ceramics_mk2_5736_capabilities,
    game_equalizer_presets: None,
};

pub static CERAMICS_MK2_8912F: ModelSpec = ModelSpec {
    display_name: "CERAMICS-MK2 8912F",
    short_label: "CERAMICS-MK2 8912F",
    version_name: None,
    protocol: info("0d0c092c2d2a0e322b2f0107312e36"),
    advertisement_prefixes: &["0b0e208b"],
    name_brand: "ceramics",
    name_model_number: "mk2",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: ceramics_mk2_8912f_capabilities,
    game_equalizer_presets: None,
};

pub static CERAMICS_U: ModelSpec = ModelSpec {
    display_name: "CERAMICS-U",
    short_label: "CERAMICS-U",
    version_name: None,
    protocol: info("0d0c092c2d2a450e322b2f0107312e36"),
    advertisement_prefixes: &["0b0d0a0b"],
    name_brand: "ceramics",
    name_model_number: "ceramicsu",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("light", "经典调音")],
    capabilities: ceramics_u_capabilities,
    game_equalizer_presets: None,
};

pub static CERAMICS_U_HSC_2710: ModelSpec = ModelSpec {
    display_name: "CERAMICS-U HSC-2710",
    short_label: "CERAMICS-U HSC-2710",
    version_name: None,
    protocol: info("0d0c092c2d2a450e322b2f01072e36"),
    advertisement_prefixes: &["0b137957"],
    name_brand: "ceramics",
    name_model_number: "ceramicsu",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("light", "经典调音")],
    capabilities: ceramics_u_hsc_2710_capabilities,
    game_equalizer_presets: None,
};

pub static CORAL: ModelSpec = ModelSpec {
    display_name: "Coral",
    short_label: "Coral",
    version_name: None,
    protocol: info("0d0c092a0e322b01072f2e"),
    advertisement_prefixes: &["0b0e0a0e"],
    name_brand: "coral",
    name_model_number: "coral",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: coral_capabilities,
    game_equalizer_presets: None,
};

pub static EAR_FEEL_IE2: ModelSpec = ModelSpec {
    display_name: "EarFeel iE2",
    short_label: "EarFeel iE2",
    version_name: None,
    protocol: info("0d0c092a0e322b01072f2e36"),
    advertisement_prefixes: &["0b154572"],
    name_brand: "earfeel",
    name_model_number: "ie2",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: ear_feel_ie2_capabilities,
    game_equalizer_presets: None,
};

pub static EAR_FEEL_IE_NEO: ModelSpec = ModelSpec {
    display_name: "EarFeel iE Neo",
    short_label: "EarFeel iE Neo",
    version_name: None,
    protocol: info("0d0c092a45460e3201072f2e36"),
    advertisement_prefixes: &["45d7aa12"],
    name_brand: "earfeel",
    name_model_number: "ieneo",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: ear_feel_ie_neo_capabilities,
    game_equalizer_presets: None,
};

pub static FIRST_ANGEL: ModelSpec = ModelSpec {
    display_name: "FIRST ANGEL",
    short_label: "FIRST ANGEL",
    version_name: None,
    protocol: ModelProtocol::RsCommonV2(RsCommonV2Protocol {
        info_ids: Some("0d0c2a0e3201072f3f2e"),
        spatial_needs_ear_adaptive: false,
        synchronize_clock_on_connect: false,
        refresh_equalizer_after_game_mode: true,
    }),
    advertisement_prefixes: &["0b0e64be"],
    name_brand: "first angel",
    name_model_number: "first angel",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("game_1", "战术调律")],
    capabilities: first_angel_capabilities,
    game_equalizer_presets: Some(&["game_1"]),
};

pub static FURINA: ModelSpec = ModelSpec {
    display_name: "FURINA",
    short_label: "FURINA",
    version_name: None,
    protocol: info("0d0c092c2d2a0e322b2f010731082e36"),
    advertisement_prefixes: &["0b110a0c"],
    name_brand: "furina",
    name_model_number: "furina",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: furina_capabilities,
    game_equalizer_presets: None,
};

pub static GABUMON: ModelSpec = ModelSpec {
    display_name: "Gabumon",
    short_label: "Gabumon",
    version_name: None,
    protocol: info("0d0c0e3201072f2e"),
    advertisement_prefixes: &["0b0a2aed"],
    name_brand: "gabumon",
    name_model_number: "gabumon",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: gabumon_capabilities,
    game_equalizer_presets: None,
};

pub static HYACINTH: ModelSpec = ModelSpec {
    display_name: "Hyacinth",
    short_label: "Hyacinth",
    version_name: None,
    protocol: info("0d0c2a0e322b2f0107312e"),
    advertisement_prefixes: &["0b120a0b"],
    name_brand: "hyacinth",
    name_model_number: "hyacinth",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: hyacinth_capabilities,
    game_equalizer_presets: None,
};

pub static KY01: ModelSpec = ModelSpec {
    display_name: "KY01",
    short_label: "KY01",
    version_name: None,
    protocol: info("0d0c2a0e320107312f2e"),
    advertisement_prefixes: &["0a0b0a0d"],
    name_brand: "ky01",
    name_model_number: "ky01",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: ky01_capabilities,
    game_equalizer_presets: None,
};

pub static MAGICIAN: ModelSpec = ModelSpec {
    display_name: "Magician",
    short_label: "Magician",
    version_name: None,
    protocol: ModelProtocol::RsCommonV2(RsCommonV2Protocol {
        info_ids: None,
        spatial_needs_ear_adaptive: false,
        synchronize_clock_on_connect: false,
        refresh_equalizer_after_game_mode: false,
    }),
    advertisement_prefixes: &["0b0e0a0d"],
    name_brand: "magician",
    name_model_number: "magician",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: &[],
    capabilities: magician_capabilities,
    game_equalizer_presets: None,
};

pub static MIRROR: ModelSpec = ModelSpec {
    display_name: "Mirror",
    short_label: "Mirror",
    version_name: None,
    protocol: info("0d0c2a0e3201072f2e"),
    advertisement_prefixes: &["0b0a0a13"],
    name_brand: "mirror",
    name_model_number: "mirror",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: mirror_capabilities,
    game_equalizer_presets: None,
};

pub static MOKA: ModelSpec = ModelSpec {
    display_name: "MOKA",
    short_label: "MOKA",
    version_name: None,
    protocol: ModelProtocol::RsCommonV2(RsCommonV2Protocol {
        info_ids: Some("0d0c2a0e322b2e"),
        spatial_needs_ear_adaptive: false,
        synchronize_clock_on_connect: false,
        refresh_equalizer_after_game_mode: true,
    }),
    advertisement_prefixes: &["68f5b8eb"],
    name_brand: "moka",
    name_model_number: "moka",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: moka_capabilities,
    game_equalizer_presets: None,
};

pub static OPAL: ModelSpec = ModelSpec {
    display_name: "Opal",
    short_label: "Opal",
    version_name: None,
    protocol: info("0d0c092a0e3201072f2e"),
    advertisement_prefixes: &["0b0a0a12"],
    name_brand: "opal",
    name_model_number: "opal",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: opal_capabilities,
    game_equalizer_presets: None,
};

pub static OPEN_FEEL_L: ModelSpec = ModelSpec {
    display_name: "OpenFeel L",
    short_label: "OpenFeel L",
    version_name: None,
    protocol: info("0d0c2a45460e322b01072f2e"),
    advertisement_prefixes: &["45d7cbed"],
    name_brand: "openfeel",
    name_model_number: "openfeell",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "BASS"), ("hifi", "BALANCE")],
    capabilities: open_feel_l_capabilities,
    game_equalizer_presets: None,
};

pub static PATAMON: ModelSpec = ModelSpec {
    display_name: "Patamon",
    short_label: "Patamon",
    version_name: None,
    protocol: info("0d0c0e3201072f2e"),
    advertisement_prefixes: &["0b0ae916"],
    name_brand: "patamon",
    name_model_number: "patamon",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: patamon_capabilities,
    game_equalizer_presets: None,
};

pub static SPEAK_FEEL_2: ModelSpec = ModelSpec {
    display_name: "SPEAKFEEL (speakfeel2)",
    short_label: "SPEAKFEEL (speakfeel2)",
    version_name: None,
    protocol: info("0d0c2a45460e49322b012f3f2e"),
    advertisement_prefixes: &["0b0e3ce6"],
    name_brand: "speakfeel",
    name_model_number: "speakfeel",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: &[],
    capabilities: speak_feel_2_capabilities,
    game_equalizer_presets: None,
};

pub static SPEAK_FREE: ModelSpec = ModelSpec {
    display_name: "SpeakFeel (speakfree)",
    short_label: "SpeakFeel (speakfree)",
    version_name: None,
    protocol: info("0d0c2a0e322b2f312e"),
    advertisement_prefixes: &["0b0f0a0b"],
    name_brand: "speakfeel",
    name_model_number: "speakfeel",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: speak_free_capabilities,
    game_equalizer_presets: None,
};

pub static SPORT_FEEL_8912F: ModelSpec = ModelSpec {
    display_name: "SportFeel 8912F",
    short_label: "SportFeel 8912F",
    version_name: None,
    protocol: info("0d0c2a0e322b2f01072e"),
    advertisement_prefixes: &["5d2f61f0"],
    name_brand: "sportfeel",
    name_model_number: "sportfeel",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: sport_feel_8912f_capabilities,
    game_equalizer_presets: None,
};

pub static TAILMON: ModelSpec = ModelSpec {
    display_name: "Tailmon",
    short_label: "Tailmon",
    version_name: None,
    protocol: info("0d0c0e3201072f2e"),
    advertisement_prefixes: &["0b0a941f"],
    name_brand: "tailmon",
    name_model_number: "tailmon",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: tailmon_capabilities,
    game_equalizer_presets: None,
};

pub static ZIRCON: ModelSpec = ModelSpec {
    display_name: "Zircon",
    short_label: "Zircon",
    version_name: None,
    protocol: info("0d0c092c2d2a0e322b2f010731082e36"),
    advertisement_prefixes: &["0b110a0b"],
    name_brand: "zircon",
    name_model_number: "zircon",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_V2_GATT,
    spp_control_uuid: Some(RS_V2_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(rs_v2_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: zircon_capabilities,
    game_equalizer_presets: None,
};

#[derive(Default)]
struct Panel {
    noise: Option<NoiseControlCapabilities>,
    equalizer: Option<EqualizerCapabilities>,
    touch: Option<TouchControlCapabilities>,
    tap: Option<TouchControlCapabilities>,
    audio_modes: &'static [&'static str],
    touch_enabled: bool,
    ear_detection: bool,
    prompt_languages: bool,
    gain: bool,
    protection: bool,
    bass_boost_max: Option<i32>,
    find_events: bool,
    spatial_modes: &'static [&'static str],
    spatial_contents: &'static [&'static str],
    ear_adaptive: bool,
    independent_mic: bool,
}

fn strings(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|item| (*item).to_string()).collect()
}

fn levels(range: RangeInclusive<i32>) -> BTreeSet<i32> {
    range.collect()
}

fn basic_noise_modes_with(extra: &[&str]) -> BTreeSet<String> {
    let mut modes = basic_noise_modes();
    modes.extend(extra.iter().map(|mode| (*mode).to_string()));
    modes
}

fn plain_noise(modes: BTreeSet<String>, cycle_modes: BTreeSet<String>) -> NoiseControlCapabilities {
    NoiseControlCapabilities {
        modes,
        cycle_modes,
        anc_level: None,
        transparency_level: None,
        minimum_cycle_selections: 0,
    }
}

fn leveled_noise(modes: BTreeSet<String>, transparency_levels: &[i32]) -> NoiseControlCapabilities {
    NoiseControlCapabilities {
        anc_level: Some(NoiseLevelCapabilities {
            levels: BTreeSet::from([1, 3, 5]),
            visible_modes: strings(&["anc"]),
        }),
        transparency_level: Some(NoiseLevelCapabilities {
            levels: transparency_levels.iter().copied().collect(),
            visible_modes: strings(&["transparency"]),
        }),
        ..plain_noise(modes, basic_noise_modes())
    }
}

fn presets(names: &[&str], custom: Option<CustomEqualizerCapabilities>) -> EqualizerCapabilities {
    EqualizerCapabilities {
        presets: strings(names),
        custom,
    }
}

fn custom_curve() -> CustomEqualizerCapabilities {
    CustomEqualizerCapabilities {
        bands_hz: vec![20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000],
        min_gain: -6,
        max_gain: 6,
    }
}

fn touch(noise_cycle: bool) -> TouchControlCapabilities {
    let mut actions = strings(&[
        "none",
        "play_pause",
        "previous_track",
        "next_track",
        "volume_up",
        "volume_down",
        "game_mode",
        "voice_assistant",
    ]);
    if noise_cycle {
        actions.insert("noise_cycle".to_string());
    }
    TouchControlCapabilities {
        slots: earbud_touch_slots(),
        actions,
        slot_labels: Default::default(),
    }
}

fn rs_v2_capabilities(
    layout: BatteryLayout,
    prompt_volume: BTreeSet<i32>,
    panel: Panel,
) -> HeadsetCapabilities {
    let mut fields = rs_readings(layout, &[]);
    let mut settings = BTreeSet::new();
    let mut control = |available: bool, setting: HeadsetSetting, field: HeadsetField| {
        if available {
            settings.insert(setting);
            fields.insert(field);
        }
    };
    let noise = panel.noise.as_ref();
    let bass_boost_levels: BTreeSet<i32> = panel
        .bass_boost_max
        .map(|max| (0..=max).collect())
        .unwrap_or_default();
    control(true, HeadsetSetting::Multipoint, HeadsetField::Multipoint);
    control(true, HeadsetSetting::GameMode, HeadsetField::GameMode);
    control(
        true,
        HeadsetSetting::PromptVolume,
        HeadsetField::PromptVolume,
    );
    control(
        noise.is_some_and(|n| !n.modes.is_empty()),
        HeadsetSetting::NoiseMode,
        HeadsetField::NoiseMode,
    );
    control(
        noise.is_some_and(|n| !n.cycle_modes.is_empty()),
        HeadsetSetting::NoiseCycle,
        HeadsetField::NoiseCycle,
    );
    control(
        noise.is_some_and(|n| n.anc_level.is_some()),
        HeadsetSetting::AncLevel,
        HeadsetField::AncLevel,
    );
    control(
        noise.is_some_and(|n| n.transparency_level.is_some()),
        HeadsetSetting::TransparencyLevel,
        HeadsetField::TransparencyLevel,
    );
    control(
        panel
            .equalizer
            .as_ref()
            .is_some_and(|eq| !eq.presets.is_empty()),
        HeadsetSetting::Eq,
        HeadsetField::Eq,
    );
    control(
        panel
            .equalizer
            .as_ref()
            .is_some_and(|eq| eq.custom.is_some()),
        HeadsetSetting::CustomEq,
        HeadsetField::CustomEq,
    );
    control(
        !bass_boost_levels.is_empty(),
        HeadsetSetting::BassBoost,
        HeadsetField::EqSlot,
    );
    control(
        panel.touch.is_some(),
        HeadsetSetting::TouchBinding,
        HeadsetField::TouchKeys,
    );
    control(
        panel.touch_enabled,
        HeadsetSetting::TouchEnabled,
        HeadsetField::TouchEnabled,
    );
    control(
        panel.tap.is_some(),
        HeadsetSetting::TapEnabled,
        HeadsetField::TapEnabled,
    );
    control(
        panel.tap.is_some(),
        HeadsetSetting::TapBinding,
        HeadsetField::TapKeys,
    );
    control(
        !panel.audio_modes.is_empty(),
        HeadsetSetting::AudioMode,
        HeadsetField::AudioMode,
    );
    control(panel.gain, HeadsetSetting::Gain, HeadsetField::Gain);
    control(
        panel.protection,
        HeadsetSetting::HearingProtection,
        HeadsetField::HearingProtection,
    );
    control(
        panel.ear_detection,
        HeadsetSetting::EarDetection,
        HeadsetField::EarDetection,
    );
    control(
        panel.prompt_languages,
        HeadsetSetting::PromptLanguage,
        HeadsetField::PromptLanguage,
    );
    control(
        !panel.spatial_modes.is_empty(),
        HeadsetSetting::SpatialMode,
        HeadsetField::SpatialMode,
    );
    control(
        !panel.spatial_contents.is_empty(),
        HeadsetSetting::SpatialContent,
        HeadsetField::SpatialContent,
    );
    control(
        panel.ear_adaptive,
        HeadsetSetting::EarAdaptive,
        HeadsetField::EarAdaptive,
    );
    control(
        panel.independent_mic,
        HeadsetSetting::IndependentMicActivation,
        HeadsetField::IndependentMicActivation,
    );
    control(
        panel.independent_mic,
        HeadsetSetting::IndependentMicMode,
        HeadsetField::IndependentMicMode,
    );
    if panel.equalizer.is_some() || !bass_boost_levels.is_empty() {
        fields.insert(HeadsetField::Eq);
    }
    if layout == BatteryLayout::EarbudsAndCase {
        fields.insert(HeadsetField::CaseFlag);
    }
    if panel.find_events {
        fields.insert(HeadsetField::FindState);
    }
    HeadsetCapabilities {
        readable_fields: fields,
        writable_settings: settings,
        noise_control: panel.noise,
        equalizer: panel.equalizer,
        touch_control: panel.touch,
        tap_control: panel.tap,
        audio_control: (!panel.audio_modes.is_empty()).then(|| AudioControlCapabilities {
            modes: strings(panel.audio_modes),
            multipoint_coupled: false,
        }),
        prompt_volume_levels: prompt_volume,
        gain_levels: if panel.gain {
            levels(0..=2)
        } else {
            BTreeSet::new()
        },
        hearing_protection_levels: if panel.protection {
            levels(0..=6)
        } else {
            BTreeSet::new()
        },
        bass_boost_levels,
        prompt_languages: if panel.prompt_languages {
            strings(&["chinese", "english"])
        } else {
            BTreeSet::new()
        },
        spatial_modes: strings(panel.spatial_modes),
        spatial_contents: strings(panel.spatial_contents),
    }
}

fn ear_feel_i7_capabilities(firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let gain_and_protection = firmware_at_least(firmware, 213) == Some(true);
    let mut preset_names = vec!["pop", "hifi", "rock", "light"];
    if firmware_at_least(firmware, 152) == Some(true) {
        preset_names.push("eap");
    }
    let mut capabilities = rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        prompt_volume_levels(firmware, 304),
        Panel {
            noise: Some(leveled_noise(
                basic_noise_modes_with(&["auto", "dynamic"]),
                &[1, 3, 5],
            )),
            equalizer: Some(presets(&preset_names, Some(custom_curve()))),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac", "lhdc", "lc3"],
            touch_enabled: true,
            ear_detection: true,
            gain: gain_and_protection,
            protection: gain_and_protection,
            find_events: true,
            ..Panel::default()
        },
    );
    capabilities
        .readable_fields
        .extend([HeadsetField::Gain, HeadsetField::HearingProtection]);
    capabilities
}

fn buds_feel_mk2_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            noise: Some(plain_noise(basic_noise_modes(), basic_noise_modes())),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac", "lc3"],
            touch_enabled: true,
            bass_boost_max: Some(4),
            find_events: true,
            ..Panel::default()
        },
    )
}

fn open_feel_mk2_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(0..=5),
        Panel {
            equalizer: Some(presets(&[], Some(custom_curve()))),
            touch: Some(touch(false)),
            audio_modes: &["aac_sbc", "ldac", "lhdc", "lc3"],
            touch_enabled: true,
            gain: true,
            protection: true,
            bass_boost_max: Some(2),
            find_events: true,
            ..Panel::default()
        },
    )
}

fn swift_2_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::SingleUnit,
        levels(1..=5),
        Panel {
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn jasmine_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsOnly,
        levels(1..=5),
        Panel {
            touch: Some(touch(false)),
            touch_enabled: true,
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn agumon_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsOnly,
        levels(1..=5),
        Panel {
            touch: Some(touch(false)),
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn astro_x_capabilities(firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let gain_and_protection = firmware_at_least(firmware, 152) == Some(true);
    let touch = touch(false);
    let tap = TouchControlCapabilities {
        slots: BTreeSet::from([
            TouchSlot::new("left", "double"),
            TouchSlot::new("left", "triple"),
            TouchSlot::new("right", "double"),
            TouchSlot::new("right", "triple"),
        ]),
        ..touch.clone()
    };
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            equalizer: Some(presets(&["pop", "hifi", "rock"], None)),
            touch: Some(touch),
            tap: Some(tap),
            audio_modes: &["aac_sbc", "ldac", "lhdc"],
            touch_enabled: true,
            ear_detection: true,
            gain: gain_and_protection,
            protection: gain_and_protection,
            find_events: true,
            spatial_modes: &["off", "head_tracking", "fixed"],
            spatial_contents: &["tv", "music", "movie", "game"],
            ear_adaptive: true,
            ..Panel::default()
        },
    )
}

fn buds_feel_ae_neo_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsOnly,
        levels(0..=5),
        Panel {
            equalizer: Some(presets(&["pop", "hifi", "rock"], Some(custom_curve()))),
            touch: Some(touch(false)),
            touch_enabled: true,
            gain: true,
            protection: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn ceramics_mk2_capabilities(firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let custom = (firmware_at_least(firmware, 154) == Some(true)).then(custom_curve);
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        prompt_volume_levels(firmware, 153),
        Panel {
            noise: Some(leveled_noise(basic_noise_modes(), &[1, 3, 5])),
            equalizer: Some(presets(&["pop", "hifi", "rock"], custom)),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac"],
            touch_enabled: true,
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn ceramics_mk2_5736_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsOnly,
        levels(0..=5),
        Panel {
            noise: Some(leveled_noise(basic_noise_modes(), &[1, 3, 5])),
            equalizer: Some(presets(&["pop", "hifi", "rock"], Some(custom_curve()))),
            touch: Some(touch(true)),
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn ceramics_mk2_8912f_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(0..=5),
        Panel {
            noise: Some(leveled_noise(basic_noise_modes(), &[1, 3, 5])),
            equalizer: Some(presets(&["pop", "hifi", "rock"], Some(custom_curve()))),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac"],
            touch_enabled: true,
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn ceramics_u_capabilities(firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let since_33 = firmware_at_least(firmware, 33) == Some(true);
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        prompt_volume_levels(firmware, 33),
        Panel {
            noise: Some(leveled_noise(
                basic_noise_modes_with(&["auto", "deep"]),
                &[1, 5],
            )),
            equalizer: Some(presets(
                &["pop", "hifi", "rock", "light"],
                since_33.then(custom_curve),
            )),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac", "lhdc"],
            touch_enabled: true,
            prompt_languages: true,
            gain: since_33,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn ceramics_u_hsc_2710_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(0..=5),
        Panel {
            noise: Some(leveled_noise(basic_noise_modes_with(&["deep"]), &[1, 5])),
            equalizer: Some(presets(
                &["pop", "hifi", "rock", "light"],
                Some(custom_curve()),
            )),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac", "lhdc"],
            touch_enabled: true,
            gain: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn coral_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            noise: Some(plain_noise(strings(&["anc", "normal"]), BTreeSet::new())),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac", "lhdc", "lc3"],
            touch_enabled: true,
            bass_boost_max: Some(2),
            find_events: true,
            ..Panel::default()
        },
    )
}

fn ear_feel_ie2_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            noise: Some(plain_noise(basic_noise_modes(), basic_noise_modes())),
            equalizer: Some(presets(
                &["pop", "hifi", "rock", "light"],
                Some(custom_curve()),
            )),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac"],
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn ear_feel_ie_neo_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let modes = strings(&["anc", "normal", "transparency"]);
    rs_v2_capabilities(
        BatteryLayout::EarbudsOnly,
        levels(0..=5),
        Panel {
            noise: Some(plain_noise(modes.clone(), modes)),
            equalizer: Some(presets(&["pop", "hifi", "rock"], Some(custom_curve()))),
            touch: Some(touch(true)),
            touch_enabled: true,
            gain: true,
            protection: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn first_angel_capabilities(firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::SingleUnit,
        prompt_volume_levels(firmware, 510),
        Panel {
            equalizer: Some(presets(&["pop", "hifi"], Some(custom_curve()))),
            touch: Some(touch(false)),
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn furina_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            noise: Some(leveled_noise(basic_noise_modes_with(&["deep"]), &[1, 3, 5])),
            equalizer: Some(presets(&["pop", "hifi", "rock"], None)),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac", "lhdc", "lc3"],
            touch_enabled: true,
            ear_detection: true,
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn gabumon_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsOnly,
        levels(1..=5),
        Panel {
            touch: Some(touch(false)),
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn hyacinth_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            equalizer: Some(presets(&["pop", "hifi", "rock"], None)),
            touch: Some(touch(false)),
            audio_modes: &["aac_sbc", "ldac"],
            touch_enabled: true,
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn ky01_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            equalizer: Some(presets(&["pop", "hifi", "rock"], None)),
            touch: Some(touch(true)),
            touch_enabled: true,
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn magician_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::SingleUnit,
        levels(1..=5),
        Panel {
            audio_modes: &["aac_sbc", "ldac", "aptx", "aptx_hd"],
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn mirror_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            equalizer: Some(presets(&["pop", "hifi", "rock"], None)),
            touch: Some(touch(false)),
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn moka_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::SingleUnit,
        levels(1..=5),
        Panel {
            equalizer: Some(presets(&["pop", "hifi", "rock"], Some(custom_curve()))),
            audio_modes: &["aac_sbc", "ldac"],
            ..Panel::default()
        },
    )
}

fn opal_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            noise: Some(plain_noise(
                strings(&["normal", "transparency", "anc"]),
                BTreeSet::new(),
            )),
            equalizer: Some(presets(&["pop", "hifi", "rock"], None)),
            touch: Some(touch(true)),
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn open_feel_l_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(0..=5),
        Panel {
            equalizer: Some(presets(&["pop", "hifi"], Some(custom_curve()))),
            touch: Some(touch(false)),
            audio_modes: &["aac_sbc", "ldac"],
            touch_enabled: true,
            gain: true,
            protection: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn patamon_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsOnly,
        levels(1..=5),
        Panel {
            touch: Some(touch(false)),
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn speak_feel_2_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::SingleUnit,
        levels(0..=5),
        Panel {
            touch: Some(touch(false)),
            audio_modes: &["aac_sbc", "ldac", "lhdc"],
            touch_enabled: true,
            gain: true,
            protection: true,
            bass_boost_max: Some(1),
            independent_mic: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn speak_free_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::SingleUnit,
        levels(1..=5),
        Panel {
            equalizer: Some(presets(&["pop", "hifi", "rock"], None)),
            audio_modes: &["aac_sbc", "ldac", "aptx", "aptx_hd", "aptx_adaptive"],
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn sport_feel_8912f_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(0..=5),
        Panel {
            equalizer: Some(presets(&["hifi"], None)),
            touch: Some(touch(false)),
            audio_modes: &["aac_sbc", "ldac"],
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn tailmon_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsOnly,
        levels(1..=5),
        Panel {
            touch: Some(touch(false)),
            touch_enabled: true,
            find_events: true,
            ..Panel::default()
        },
    )
}

fn zircon_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    rs_v2_capabilities(
        BatteryLayout::EarbudsAndCase,
        levels(1..=5),
        Panel {
            noise: Some(leveled_noise(basic_noise_modes_with(&["deep"]), &[1, 3, 5])),
            equalizer: Some(presets(&["pop", "hifi", "rock"], None)),
            touch: Some(touch(true)),
            audio_modes: &["aac_sbc", "ldac", "lhdc", "lc3"],
            touch_enabled: true,
            ear_detection: true,
            prompt_languages: true,
            find_events: true,
            ..Panel::default()
        },
    )
}
