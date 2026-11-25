use crate::capabilities::{
    AudioControlCapabilities, EqualizerCapabilities, HeadsetCapabilities, NoiseControlCapabilities,
    NoiseLevelCapabilities, TouchControlCapabilities, earbud_touch_slots,
};
use crate::field::{FirmwareVersion, HeadsetField, HeadsetSetting, TouchSlot};
use crate::model::{BatteryLayout, F8Protocol, GattSpec, ModelProtocol, ModelSpec};
use std::collections::{BTreeMap, BTreeSet};

const F8_GATT: GattSpec = GattSpec {
    service_uuid: "0000fdb3-0000-1000-8000-00805f9b34fb",
    write_uuid: "0000ff16-0000-1000-8000-00805f9b34fb",
    notify_uuid: "0000ff17-0000-1000-8000-00805f9b34fb",
};

const F8_SPP_CONTROL_UUID: &str = "0cf12d31-fac3-4553-bd80-d6832e7b3931";

const BASIC_PROTOCOL_POP_AT_TWO: F8Protocol = F8Protocol {
    extended_init: false,
    noise_normal_wire: 3,
    eq_at_two: Some("pop"),
};

const EXTENDED_PROTOCOL: F8Protocol = F8Protocol {
    extended_init: true,
    noise_normal_wire: 3,
    eq_at_two: Some("rock"),
};

pub static AGATE: ModelSpec = ModelSpec {
    display_name: "AGATE",
    short_label: "AGATE",
    version_name: None,
    protocol: ModelProtocol::F8(BASIC_PROTOCOL_POP_AT_TWO),
    advertisement_prefixes: &["18840505"],
    name_brand: "agate",
    name_model_number: "agate",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: F8_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(f8_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[("pop", "Rock"), ("hifi", "HIFI")],
    capabilities: basic_capabilities,
    game_equalizer_presets: None,
};

pub static AGATE_X: ModelSpec = ModelSpec {
    display_name: "AGATE X",
    short_label: "AGATE X",
    version_name: None,
    protocol: ModelProtocol::F8(BASIC_PROTOCOL_POP_AT_TWO),
    advertisement_prefixes: &["18840506"],
    name_brand: "agatex",
    name_model_number: "agatex",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: F8_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(f8_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[("pop", "Rock"), ("hifi", "POP")],
    capabilities: agate_x_capabilities,
    game_equalizer_presets: None,
};

pub static CERAMICS: ModelSpec = ModelSpec {
    display_name: "CERAMICS",
    short_label: "CERAMICS",
    version_name: None,
    protocol: ModelProtocol::F8(F8Protocol {
        extended_init: false,
        noise_normal_wire: 3,
        eq_at_two: None,
    }),
    advertisement_prefixes: &["18840100", "18840103"],
    name_brand: "ceramics",
    name_model_number: "ceramics",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: F8_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(f8_address),
    battery_layout: BatteryLayout::EarbudsOnly,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: basic_capabilities,
    game_equalizer_presets: None,
};

pub static CERAMICS_X: ModelSpec = ModelSpec {
    display_name: "CERAMICS-X",
    short_label: "CERAMICS-X",
    version_name: Some("标准版"),
    protocol: ModelProtocol::F8(EXTENDED_PROTOCOL),
    advertisement_prefixes: &["18840109"],
    name_brand: "ceramicsx",
    name_model_number: "ceramicsx",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: F8_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: Some(f8_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: extended_capabilities,
    game_equalizer_presets: None,
};

pub static CERAMICS_X_7034: ModelSpec = ModelSpec {
    display_name: "CERAMICS-X",
    short_label: "CERAMICS-X",
    version_name: Some("7034"),
    protocol: ModelProtocol::F8(EXTENDED_PROTOCOL),
    advertisement_prefixes: &["18840112"],
    name_brand: "ceramicsx",
    name_model_number: "ceramicsx",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: F8_GATT,
    spp_control_uuid: Some(F8_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(f8_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: extended_capabilities,
    game_equalizer_presets: None,
};

pub static EXPLORER: ModelSpec = ModelSpec {
    display_name: "EXPLORER",
    short_label: "EXPLORER",
    version_name: None,
    protocol: ModelProtocol::F8(F8Protocol {
        extended_init: true,
        noise_normal_wire: 0,
        eq_at_two: Some("rock"),
    }),
    advertisement_prefixes: &["18840107"],
    name_brand: "explorer",
    name_model_number: "explorer",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: F8_GATT,
    spp_control_uuid: Some(F8_SPP_CONTROL_UUID),
    write_interval_millis: 100,
    advertised_classic_address: Some(f8_address),
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[("pop", "POP"), ("hifi", "HIFI"), ("rock", "ROCK")],
    capabilities: explorer_capabilities,
    game_equalizer_presets: None,
};

fn f8_address(raw: &str) -> Option<String> {
    if raw.len() < 22 || !raw.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    let octets = raw[10..22].to_ascii_uppercase();
    if octets.bytes().all(|byte| byte == b'0') || octets.bytes().all(|byte| byte == b'F') {
        return None;
    }
    let mut address = String::with_capacity(17);
    for (index, pair) in octets.as_bytes().chunks(2).enumerate() {
        if index > 0 {
            address.push(':');
        }
        address.push(char::from(pair[0]));
        address.push(char::from(pair[1]));
    }
    Some(address)
}

fn strings(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|item| (*item).to_string()).collect()
}

fn touch_actions(with_noise_cycle: bool) -> BTreeSet<String> {
    let mut actions = strings(&[
        "none",
        "voice_assistant",
        "previous_track",
        "next_track",
        "volume_up",
        "volume_down",
        "play_pause",
    ]);
    if with_noise_cycle {
        actions.insert("noise_cycle".to_string());
    }
    actions
}

fn three_gesture_slots() -> BTreeSet<TouchSlot> {
    earbud_touch_slots()
        .into_iter()
        .filter(|slot| slot.gesture != "triple")
        .collect()
}

fn basic_readable_fields() -> BTreeSet<HeadsetField> {
    BTreeSet::from([
        HeadsetField::LeftBattery,
        HeadsetField::RightBattery,
        HeadsetField::LeftCharging,
        HeadsetField::RightCharging,
        HeadsetField::Firmware,
        HeadsetField::Eq,
        HeadsetField::TouchKeys,
        HeadsetField::GameMode,
    ])
}

fn extended_readable_fields() -> BTreeSet<HeadsetField> {
    let mut fields = basic_readable_fields();
    fields.extend([
        HeadsetField::CaseBattery,
        HeadsetField::EarDetection,
        HeadsetField::PromptLanguage,
        HeadsetField::AudioMode,
        HeadsetField::Multipoint,
        HeadsetField::NoiseMode,
        HeadsetField::PromptVolume,
    ]);
    fields
}

fn basic_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    HeadsetCapabilities {
        readable_fields: basic_readable_fields(),
        writable_settings: BTreeSet::from([
            HeadsetSetting::Eq,
            HeadsetSetting::TouchBinding,
            HeadsetSetting::GameMode,
        ]),
        equalizer: Some(EqualizerCapabilities {
            presets: strings(&["pop", "hifi"]),
            custom: None,
        }),
        touch_control: Some(TouchControlCapabilities {
            slots: three_gesture_slots(),
            actions: touch_actions(true),
            slot_labels: BTreeMap::new(),
        }),
        ..HeadsetCapabilities::default()
    }
}

fn agate_x_capabilities(firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let mut capabilities = basic_capabilities(firmware);
    capabilities.writable_settings =
        BTreeSet::from([HeadsetSetting::Eq, HeadsetSetting::TouchBinding]);
    if let Some(touch) = capabilities.touch_control.as_mut() {
        touch.actions = touch_actions(false);
    }
    capabilities
}

fn extended_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    HeadsetCapabilities {
        readable_fields: extended_readable_fields(),
        writable_settings: BTreeSet::from([
            HeadsetSetting::Eq,
            HeadsetSetting::TouchBinding,
            HeadsetSetting::GameMode,
            HeadsetSetting::NoiseMode,
            HeadsetSetting::AudioMode,
            HeadsetSetting::Multipoint,
            HeadsetSetting::PromptLanguage,
        ]),
        noise_control: Some(NoiseControlCapabilities {
            modes: strings(&["anc", "normal", "transparency", "wind"]),
            cycle_modes: BTreeSet::new(),
            anc_level: None,
            transparency_level: None,
            minimum_cycle_selections: 0,
        }),
        equalizer: Some(EqualizerCapabilities {
            presets: strings(&["pop", "hifi", "rock"]),
            custom: None,
        }),
        touch_control: Some(TouchControlCapabilities {
            slots: earbud_touch_slots(),
            actions: touch_actions(true),
            slot_labels: BTreeMap::new(),
        }),
        audio_control: Some(AudioControlCapabilities {
            modes: strings(&["aac_sbc", "ldac"]),
            multipoint_coupled: true,
        }),
        prompt_languages: strings(&["chinese", "english"]),
        ..HeadsetCapabilities::default()
    }
}

fn explorer_capabilities(firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let mut capabilities = extended_capabilities(firmware);
    capabilities.readable_fields.extend([
        HeadsetField::AncLevel,
        HeadsetField::TransparencyLevel,
        HeadsetField::TouchEnabled,
    ]);
    capabilities.writable_settings.extend([
        HeadsetSetting::EarDetection,
        HeadsetSetting::TouchEnabled,
        HeadsetSetting::AncLevel,
        HeadsetSetting::TransparencyLevel,
        HeadsetSetting::PromptVolume,
    ]);
    capabilities.noise_control = Some(NoiseControlCapabilities {
        modes: strings(&["anc", "normal", "transparency"]),
        cycle_modes: BTreeSet::new(),
        anc_level: Some(NoiseLevelCapabilities {
            levels: BTreeSet::from([1, 3, 5]),
            visible_modes: strings(&["anc"]),
        }),
        transparency_level: Some(NoiseLevelCapabilities {
            levels: BTreeSet::from([1, 3, 5]),
            visible_modes: strings(&["transparency"]),
        }),
        minimum_cycle_selections: 0,
    });
    capabilities.prompt_volume_levels = (1..=5).collect();
    capabilities
}
