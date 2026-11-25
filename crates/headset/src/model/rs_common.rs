use crate::capabilities::{
    AudioControlCapabilities, EqualizerCapabilities, HeadsetCapabilities, NoiseControlCapabilities,
    NoiseLevelCapabilities, TouchControlCapabilities,
};
use crate::field::{FirmwareVersion, HeadsetField, HeadsetSetting, TouchSlot};
use crate::model::{BatteryLayout, GattSpec, ModelProtocol, ModelSpec, rs_readings};
use std::collections::{BTreeMap, BTreeSet};

pub(crate) const TOUCH_GESTURES: [&str; 3] = ["single", "double", "triple"];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RsCommonInit {
    Default,
    BudsFree,
    OpenFeel,
    Cambrian,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RsCommonProtocol {
    pub initialization: RsCommonInit,
    pub touch_actions: &'static [(&'static str, u8)],
}

impl RsCommonProtocol {
    pub const DEFAULT_TOUCH_ACTIONS: &'static [(&'static str, u8)] = &[
        ("none", 0),
        ("play_pause", 1),
        ("previous_track", 2),
        ("next_track", 3),
        ("volume_up", 4),
        ("volume_down", 5),
    ];

    pub const fn new(
        initialization: RsCommonInit,
        touch_actions: &'static [(&'static str, u8)],
    ) -> Self {
        let mut index = 0;
        while index < touch_actions.len() {
            assert!(
                touch_actions[index].1 <= 9,
                "RS Common 触控动作码须为单个十进制数字"
            );
            index += 1;
        }
        Self {
            initialization,
            touch_actions,
        }
    }

    pub const fn with_default_touch_actions(initialization: RsCommonInit) -> Self {
        Self::new(initialization, Self::DEFAULT_TOUCH_ACTIONS)
    }
}

const RS_COMMON_GATT: GattSpec = GattSpec {
    service_uuid: "000000fe-0000-1000-8000-00805f9b34fb",
    write_uuid: "000000f1-0000-1000-8000-00805f9b34fb",
    notify_uuid: "000000f2-0000-1000-8000-00805f9b34fb",
};

const RS_COMMON_SPP_UUID: &str = "0cf12d31-fac3-4553-bd80-d6832e7b3931";

const CLASSIC_ADDRESS_LENGTH: usize = 12;
const DEFAULT_CLASSIC_ADDRESS_START: usize = 10;
const BUDS_FREE_CLASSIC_ADDRESS_START: usize = 16;

const BUDS_FREE_TOUCH_ACTIONS: &[(&str, u8)] = &[
    ("none", 0),
    ("play_pause", 1),
    ("previous_track", 2),
    ("next_track", 3),
    ("volume_up", 5),
    ("volume_down", 6),
];

const TOUCH_ACTIONS_WITH_NONE: &[&str] = &[
    "none",
    "play_pause",
    "previous_track",
    "next_track",
    "volume_up",
    "volume_down",
];

const TOUCH_ACTIONS_WITHOUT_NONE: &[&str] = &[
    "play_pause",
    "previous_track",
    "next_track",
    "volume_up",
    "volume_down",
];

const POP_HIFI_ROCK_LABELS: &[(&str, &str)] = &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")];
const OPEN_FEEL_PRESET_LABELS: &[(&str, &str)] = &[("pop", "BASS"), ("hifi", "BALANCE")];

pub static BUDS_FREE_I: ModelSpec = ModelSpec {
    display_name: "BudsFree i",
    short_label: "BudsFree i",
    version_name: None,
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::new(
        RsCommonInit::BudsFree,
        BUDS_FREE_TOUCH_ACTIONS,
    )),
    advertisement_prefixes: &["12cc3e4c"],
    name_brand: "budsfree",
    name_model_number: "i",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(buds_free_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: POP_HIFI_ROCK_LABELS,
    capabilities: buds_free_i_capabilities,
    game_equalizer_presets: None,
};

pub static CAMBRIAN: ModelSpec = ModelSpec {
    display_name: "Cambrian",
    short_label: "Cambrian",
    version_name: None,
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::Cambrian,
    )),
    advertisement_prefixes: &["0b0c0a0c"],
    name_brand: "cambrian",
    name_model_number: "cambrian",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: Some(RS_COMMON_SPP_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: POP_HIFI_ROCK_LABELS,
    capabilities: cambrian_capabilities,
    game_equalizer_presets: None,
};

pub static CAMBRIAN_S: ModelSpec = ModelSpec {
    display_name: "Cambrian S",
    short_label: "Cambrian S",
    version_name: None,
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::Cambrian,
    )),
    advertisement_prefixes: &["0b0c2b46"],
    name_brand: "cambrian",
    name_model_number: "cambrian s",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: Some(RS_COMMON_SPP_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: POP_HIFI_ROCK_LABELS,
    capabilities: cambrian_capabilities,
    game_equalizer_presets: None,
};

pub static OPEN_FEEL_LDAC: ModelSpec = ModelSpec {
    display_name: "OpenFeel",
    short_label: "OpenFeel",
    version_name: Some("LDAC"),
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::OpenFeel,
    )),
    advertisement_prefixes: &["0b0a0a0b"],
    name_brand: "openfeel",
    name_model_number: "openfeel",
    name_requires_model: false,
    name_brand_aliases: &["openfree"],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: OPEN_FEEL_PRESET_LABELS,
    capabilities: open_feel_ldac_capabilities,
    game_equalizer_presets: None,
};

pub static OPEN_FEEL_LHDC: ModelSpec = ModelSpec {
    display_name: "OpenFeel",
    short_label: "OpenFeel",
    version_name: Some("LHDC"),
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::OpenFeel,
    )),
    advertisement_prefixes: &["0b0a0a0c"],
    name_brand: "openfeel",
    name_model_number: "openfeel",
    name_requires_model: false,
    name_brand_aliases: &["openfree"],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: OPEN_FEEL_PRESET_LABELS,
    capabilities: open_feel_lhdc_capabilities,
    game_equalizer_presets: None,
};

pub static OPEN_FEEL_DUAL: ModelSpec = ModelSpec {
    display_name: "OpenFeel",
    short_label: "OpenFeel",
    version_name: Some("LDAC + LHDC"),
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::OpenFeel,
    )),
    advertisement_prefixes: &["0b0a0a0d"],
    name_brand: "openfeel",
    name_model_number: "openfeel",
    name_requires_model: false,
    name_brand_aliases: &["openfree"],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: OPEN_FEEL_PRESET_LABELS,
    capabilities: open_feel_dual_capabilities,
    game_equalizer_presets: None,
};

pub static OPEN_FUN: ModelSpec = ModelSpec {
    display_name: "OpenFun",
    short_label: "OpenFun",
    version_name: Some("无触控开关"),
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::Default,
    )),
    advertisement_prefixes: &["0b0a0a0e"],
    name_brand: "openfun",
    name_model_number: "openfun",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: Some(RS_COMMON_SPP_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: open_fun_capabilities,
    game_equalizer_presets: None,
};

pub static OPEN_FUN_TOUCH: ModelSpec = ModelSpec {
    display_name: "OpenFun",
    short_label: "OpenFun",
    version_name: Some("有触控开关"),
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::Default,
    )),
    advertisement_prefixes: &["0b0a0a10"],
    name_brand: "openfun",
    name_model_number: "openfun",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: Some(RS_COMMON_SPP_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: open_touch_capabilities,
    game_equalizer_presets: None,
};

pub static OPEN_LINK: ModelSpec = ModelSpec {
    display_name: "OpenLink",
    short_label: "OpenLink",
    version_name: None,
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::Default,
    )),
    advertisement_prefixes: &["0b0a5b9d"],
    name_brand: "openlink",
    name_model_number: "openlink",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: Some(RS_COMMON_SPP_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: open_touch_capabilities,
    game_equalizer_presets: None,
};

pub static SPORT_FEEL: ModelSpec = ModelSpec {
    display_name: "SportFeel",
    short_label: "SportFeel",
    version_name: None,
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::Default,
    )),
    advertisement_prefixes: &["0b0a0a0f"],
    name_brand: "sportfeel",
    name_model_number: "sportfeel",
    name_requires_model: false,
    name_brand_aliases: &["sportfree"],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: Some(RS_COMMON_SPP_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[],
    capabilities: sport_feel_capabilities,
    game_equalizer_presets: None,
};

pub static SWIFT: ModelSpec = ModelSpec {
    display_name: "SWIFT",
    short_label: "SWIFT",
    version_name: None,
    protocol: ModelProtocol::RsCommon(RsCommonProtocol::with_default_touch_actions(
        RsCommonInit::Default,
    )),
    advertisement_prefixes: &["0b0b0a0b"],
    name_brand: "swift",
    name_model_number: "swift",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: RS_COMMON_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(default_classic_address),
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: &[],
    capabilities: swift_capabilities,
    game_equalizer_presets: None,
};

fn strings(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|item| (*item).to_string()).collect()
}

fn touch_slots() -> BTreeSet<TouchSlot> {
    ["left", "right"]
        .into_iter()
        .flat_map(|side| {
            TOUCH_GESTURES
                .into_iter()
                .map(move |gesture| TouchSlot::new(side, gesture))
        })
        .collect()
}

fn touch_control(
    actions: &[&str],
    slot_labels: BTreeMap<TouchSlot, String>,
) -> TouchControlCapabilities {
    TouchControlCapabilities {
        slots: touch_slots(),
        actions: strings(actions),
        slot_labels,
    }
}

fn classic_address(hex: &str, start: usize) -> Option<String> {
    let address = hex.get(start..start + CLASSIC_ADDRESS_LENGTH)?;
    if !address.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    let address = address.to_ascii_uppercase();
    if address == "0".repeat(CLASSIC_ADDRESS_LENGTH)
        || address == "F".repeat(CLASSIC_ADDRESS_LENGTH)
    {
        return None;
    }
    let octets: Vec<&str> = (0..CLASSIC_ADDRESS_LENGTH)
        .step_by(2)
        .map(|offset| &address[offset..offset + 2])
        .collect();
    Some(octets.join(":"))
}

fn default_classic_address(hex: &str) -> Option<String> {
    classic_address(hex, DEFAULT_CLASSIC_ADDRESS_START)
}

fn buds_free_classic_address(hex: &str) -> Option<String> {
    classic_address(hex, BUDS_FREE_CLASSIC_ADDRESS_START)
}

fn buds_free_i_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    HeadsetCapabilities {
        readable_fields: rs_readings(
            BatteryLayout::EarbudsOnly,
            &[
                HeadsetField::TouchKeys,
                HeadsetField::TouchEnabled,
                HeadsetField::NoiseMode,
                HeadsetField::Eq,
                HeadsetField::AudioMode,
            ],
        ),
        writable_settings: BTreeSet::from([
            HeadsetSetting::GameMode,
            HeadsetSetting::TouchBinding,
            HeadsetSetting::TouchEnabled,
            HeadsetSetting::NoiseMode,
            HeadsetSetting::Eq,
            HeadsetSetting::AudioMode,
        ]),
        noise_control: Some(NoiseControlCapabilities {
            modes: strings(&["anc", "normal", "transparency"]),
            cycle_modes: BTreeSet::new(),
            anc_level: None,
            transparency_level: None,
            minimum_cycle_selections: 0,
        }),
        equalizer: Some(EqualizerCapabilities {
            presets: strings(&["pop", "hifi", "rock"]),
            custom: None,
        }),
        touch_control: Some(touch_control(TOUCH_ACTIONS_WITH_NONE, BTreeMap::new())),
        audio_control: Some(AudioControlCapabilities {
            modes: strings(&["aac_sbc", "ldac"]),
            multipoint_coupled: true,
        }),
        ..HeadsetCapabilities::default()
    }
}

fn cambrian_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let slot_labels = touch_slots()
        .into_iter()
        .map(|slot| {
            let label = if slot.side == "left" {
                "音量−"
            } else {
                "音量＋"
            };
            (slot, label.to_string())
        })
        .collect();
    HeadsetCapabilities {
        readable_fields: rs_readings(
            BatteryLayout::SingleUnit,
            &[
                HeadsetField::TouchKeys,
                HeadsetField::TouchEnabled,
                HeadsetField::NoiseMode,
                HeadsetField::Eq,
                HeadsetField::AudioMode,
                HeadsetField::Multipoint,
                HeadsetField::TransparencyLevel,
                HeadsetField::PromptVolume,
            ],
        ),
        writable_settings: BTreeSet::from([
            HeadsetSetting::GameMode,
            HeadsetSetting::TouchBinding,
            HeadsetSetting::Multipoint,
            HeadsetSetting::TouchEnabled,
            HeadsetSetting::PromptVolume,
            HeadsetSetting::NoiseMode,
            HeadsetSetting::TransparencyLevel,
            HeadsetSetting::Eq,
            HeadsetSetting::AudioMode,
        ]),
        noise_control: Some(NoiseControlCapabilities {
            modes: strings(&["anc", "normal", "transparency", "wind"]),
            cycle_modes: BTreeSet::new(),
            anc_level: None,
            transparency_level: Some(NoiseLevelCapabilities {
                levels: BTreeSet::from([1, 2, 3]),
                visible_modes: strings(&["transparency"]),
            }),
            minimum_cycle_selections: 0,
        }),
        equalizer: Some(EqualizerCapabilities {
            presets: strings(&["pop", "hifi", "rock"]),
            custom: None,
        }),
        touch_control: Some(touch_control(TOUCH_ACTIONS_WITH_NONE, slot_labels)),
        audio_control: Some(AudioControlCapabilities {
            modes: strings(&["aac_sbc", "ldac"]),
            multipoint_coupled: true,
        }),
        prompt_volume_levels: (1..=5).collect(),
        ..HeadsetCapabilities::default()
    }
}

fn open_feel_capabilities(touch_actions: &[&str], audio_modes: &[&str]) -> HeadsetCapabilities {
    HeadsetCapabilities {
        readable_fields: rs_readings(
            BatteryLayout::EarbudsAndCase,
            &[
                HeadsetField::TouchKeys,
                HeadsetField::TouchEnabled,
                HeadsetField::Eq,
                HeadsetField::AudioMode,
                HeadsetField::Multipoint,
            ],
        ),
        writable_settings: BTreeSet::from([
            HeadsetSetting::GameMode,
            HeadsetSetting::TouchBinding,
            HeadsetSetting::Multipoint,
            HeadsetSetting::TouchEnabled,
            HeadsetSetting::Eq,
            HeadsetSetting::AudioMode,
        ]),
        equalizer: Some(EqualizerCapabilities {
            presets: strings(&["pop", "hifi"]),
            custom: None,
        }),
        touch_control: Some(touch_control(touch_actions, BTreeMap::new())),
        audio_control: Some(AudioControlCapabilities {
            modes: strings(audio_modes),
            multipoint_coupled: true,
        }),
        ..HeadsetCapabilities::default()
    }
}

fn open_feel_ldac_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    open_feel_capabilities(TOUCH_ACTIONS_WITHOUT_NONE, &["aac_sbc", "ldac", "lc3"])
}

fn open_feel_lhdc_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    open_feel_capabilities(TOUCH_ACTIONS_WITHOUT_NONE, &["aac_sbc", "lhdc", "lc3"])
}

fn open_feel_dual_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    open_feel_capabilities(TOUCH_ACTIONS_WITH_NONE, &["aac_sbc", "ldac", "lhdc", "lc3"])
}

fn open_earbuds_capabilities(
    extra_readable: &[HeadsetField],
    writable_settings: &[HeadsetSetting],
) -> HeadsetCapabilities {
    let mut readable = vec![
        HeadsetField::TouchKeys,
        HeadsetField::TouchEnabled,
        HeadsetField::AudioMode,
        HeadsetField::Multipoint,
        HeadsetField::PromptVolume,
    ];
    readable.extend_from_slice(extra_readable);
    let mut writable = vec![
        HeadsetSetting::GameMode,
        HeadsetSetting::TouchBinding,
        HeadsetSetting::Multipoint,
        HeadsetSetting::PromptVolume,
    ];
    writable.extend_from_slice(writable_settings);
    HeadsetCapabilities {
        readable_fields: rs_readings(BatteryLayout::EarbudsOnly, &readable),
        writable_settings: writable.into_iter().collect(),
        touch_control: Some(touch_control(TOUCH_ACTIONS_WITH_NONE, BTreeMap::new())),
        prompt_volume_levels: (1..=5).collect(),
        ..HeadsetCapabilities::default()
    }
}

fn open_fun_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    open_earbuds_capabilities(&[], &[])
}

fn open_touch_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    open_earbuds_capabilities(&[], &[HeadsetSetting::TouchEnabled])
}

fn sport_feel_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    open_earbuds_capabilities(
        &[HeadsetField::SpatialAudio],
        &[HeadsetSetting::TouchEnabled, HeadsetSetting::SpatialAudio],
    )
}

fn swift_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    HeadsetCapabilities {
        readable_fields: rs_readings(
            BatteryLayout::SingleUnit,
            &[HeadsetField::TouchKeys, HeadsetField::TouchEnabled],
        ),
        writable_settings: BTreeSet::from([HeadsetSetting::GameMode, HeadsetSetting::TouchBinding]),
        touch_control: Some(touch_control(TOUCH_ACTIONS_WITH_NONE, BTreeMap::new())),
        ..HeadsetCapabilities::default()
    }
}
