use crate::capabilities::{
    AudioControlCapabilities, CustomEqualizerCapabilities, EqualizerCapabilities,
    HeadsetCapabilities, NoiseControlCapabilities, NoiseLevelCapabilities,
    TouchControlCapabilities, basic_noise_modes, earbud_touch_slots, firmware_at_least,
};
use crate::field::{FirmwareVersion, HeadsetField, HeadsetSetting, TouchSlot};
use crate::model::{BatteryLayout, GattSpec, ModelProtocol, ModelSpec, ZtProtocol};
use std::collections::{BTreeMap, BTreeSet};

const ZT_GATT: GattSpec = GattSpec {
    service_uuid: "011bf5da-0000-1000-8000-00805f9b34fb",
    write_uuid: "00007777-0000-1000-8000-00805f9b34fb",
    notify_uuid: "00008888-0000-1000-8000-00805f9b34fb",
};

pub static EAR_FREE_I5: ModelSpec = ModelSpec {
    display_name: "EarFree i5",
    short_label: "i5",
    version_name: None,
    protocol: ModelProtocol::Zt(ZtProtocol::default_i5()),
    advertisement_prefixes: &["525300010001"],
    name_brand: "earfree",
    name_model_number: "i5",
    name_requires_model: false,
    name_brand_aliases: &[],
    gatt: ZT_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: None,
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[
        ("pop", "弱水经典"),
        ("hifi", "日系柔情"),
        ("rock", "乐器大师"),
        ("light", "清新空灵"),
    ],
    capabilities: ear_free_i5_capabilities,
    game_equalizer_presets: None,
};

pub static EAR_FREE_I3: ModelSpec = ModelSpec {
    display_name: "EarFree-i3",
    short_label: "i3",
    version_name: None,
    protocol: ModelProtocol::Zt(ZtProtocol {
        init_keys: &[
            "0101", "0602", "0201", "0603", "0801", "0401", "0a01", "0b01",
        ],
        three_byte_noise: true,
        independent_multipoint: false,
        level_write_offset: 0,
        prompt_volume_direct: false,
    }),
    advertisement_prefixes: &["f10203", "010203"],
    name_brand: "earfree",
    name_model_number: "i3",
    name_requires_model: true,
    name_brand_aliases: &[],
    gatt: ZT_GATT,
    spp_control_uuid: None,
    write_interval_millis: 100,
    advertised_classic_address: None,
    battery_layout: BatteryLayout::EarbudsAndCase,
    equalizer_preset_labels: &[],
    capabilities: ear_free_i3_capabilities,
    game_equalizer_presets: None,
};

pub static HEAD_FEEL: ModelSpec = ModelSpec {
    display_name: "HeadFeel",
    short_label: "HeadFeel",
    version_name: None,
    protocol: ModelProtocol::Zt(ZtProtocol {
        init_keys: &[
            "0101", "0602", "0201", "0202", "0203", "0603", "0801", "0d01", "0401", "0a01", "0103",
            "0801", "0802", "0803", "0604", "0e03", "0605",
        ],
        three_byte_noise: false,
        independent_multipoint: true,
        level_write_offset: 1,
        prompt_volume_direct: true,
    }),
    advertisement_prefixes: &["525300020002"],
    name_brand: "headfeel",
    name_model_number: "headfeel",
    name_requires_model: false,
    name_brand_aliases: &["headfree"],
    gatt: ZT_GATT,
    spp_control_uuid: Some("0cf12d31-fac3-4553-bd80-d6832e7b3931"),
    write_interval_millis: 100,
    advertised_classic_address: None,
    battery_layout: BatteryLayout::SingleUnit,
    equalizer_preset_labels: &[],
    capabilities: head_feel_capabilities,
    game_equalizer_presets: None,
};

fn strings(items: &[&str]) -> BTreeSet<String> {
    items.iter().map(|item| (*item).to_string()).collect()
}

fn ear_free_i5_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    HeadsetCapabilities {
        readable_fields: BTreeSet::from([
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
            HeadsetField::LeftCharging,
            HeadsetField::RightCharging,
            HeadsetField::Firmware,
            HeadsetField::NoiseMode,
            HeadsetField::NoiseCycle,
            HeadsetField::Eq,
            HeadsetField::GameMode,
            HeadsetField::AudioMode,
            HeadsetField::Multipoint,
            HeadsetField::TouchKeys,
            HeadsetField::TouchEnabled,
            HeadsetField::EarDetection,
            HeadsetField::AncLevel,
            HeadsetField::TransparencyLevel,
            HeadsetField::PromptVolume,
        ]),
        writable_settings: BTreeSet::from([
            HeadsetSetting::NoiseMode,
            HeadsetSetting::NoiseCycle,
            HeadsetSetting::Eq,
            HeadsetSetting::GameMode,
            HeadsetSetting::AudioMode,
            HeadsetSetting::Multipoint,
            HeadsetSetting::TouchBinding,
            HeadsetSetting::TouchEnabled,
            HeadsetSetting::EarDetection,
            HeadsetSetting::AncLevel,
            HeadsetSetting::TransparencyLevel,
            HeadsetSetting::PromptVolume,
        ]),
        noise_control: Some(NoiseControlCapabilities {
            modes: basic_noise_modes(),
            cycle_modes: basic_noise_modes(),
            anc_level: Some(NoiseLevelCapabilities {
                levels: BTreeSet::from([1, 3, 5]),
                visible_modes: strings(&["anc"]),
            }),
            transparency_level: Some(NoiseLevelCapabilities {
                levels: BTreeSet::from([1, 3, 5]),
                visible_modes: strings(&["transparency"]),
            }),
            minimum_cycle_selections: 0,
        }),
        equalizer: Some(EqualizerCapabilities {
            presets: strings(&["pop", "hifi", "rock", "light"]),
            custom: None,
        }),
        touch_control: Some(TouchControlCapabilities {
            slots: earbud_touch_slots(),
            actions: strings(&[
                "none",
                "play_pause",
                "previous_track",
                "next_track",
                "volume_up",
                "volume_down",
                "voice_assistant",
                "noise_cycle",
            ]),
            slot_labels: BTreeMap::new(),
        }),
        tap_control: None,
        audio_control: Some(AudioControlCapabilities {
            modes: strings(&["aac_sbc", "ldac"]),
            multipoint_coupled: true,
        }),
        prompt_volume_levels: (1..=5).collect(),
        ..HeadsetCapabilities::default()
    }
}

fn ear_free_i3_capabilities(_firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let mut slots = BTreeSet::new();
    for side in ["left", "right"] {
        for gesture in ["single", "double", "triple"] {
            slots.insert(TouchSlot::new(side, gesture));
        }
    }
    HeadsetCapabilities {
        readable_fields: BTreeSet::from([
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
            HeadsetField::LeftCharging,
            HeadsetField::RightCharging,
            HeadsetField::NoiseMode,
            HeadsetField::Eq,
            HeadsetField::GameMode,
            HeadsetField::AudioMode,
            HeadsetField::Multipoint,
            HeadsetField::TouchKeys,
            HeadsetField::TouchEnabled,
            HeadsetField::HideSearch,
        ]),
        writable_settings: BTreeSet::from([
            HeadsetSetting::NoiseMode,
            HeadsetSetting::Eq,
            HeadsetSetting::GameMode,
            HeadsetSetting::AudioMode,
            HeadsetSetting::Multipoint,
            HeadsetSetting::TouchBinding,
            HeadsetSetting::TouchEnabled,
        ]),
        noise_control: Some(NoiseControlCapabilities {
            modes: strings(&["normal", "transparency", "anc"]),
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
            slots,
            actions: strings(&[
                "volume_up",
                "volume_down",
                "previous_track",
                "next_track",
                "play_pause",
                "none",
            ]),
            slot_labels: BTreeMap::new(),
        }),
        tap_control: None,
        audio_control: Some(AudioControlCapabilities {
            modes: strings(&["aac_sbc", "ldac"]),
            multipoint_coupled: true,
        }),
        ..HeadsetCapabilities::default()
    }
}

fn head_feel_capabilities(firmware: Option<&FirmwareVersion>) -> HeadsetCapabilities {
    let firmware_since_130 = firmware_at_least(firmware, 130) == Some(true);
    let mut writable_settings = BTreeSet::from([
        HeadsetSetting::NoiseMode,
        HeadsetSetting::NoiseCycle,
        HeadsetSetting::Eq,
        HeadsetSetting::GameMode,
        HeadsetSetting::AudioMode,
        HeadsetSetting::Multipoint,
        HeadsetSetting::TouchBinding,
        HeadsetSetting::TouchEnabled,
        HeadsetSetting::AncLevel,
        HeadsetSetting::TransparencyLevel,
        HeadsetSetting::PromptVolume,
    ]);
    if firmware_since_130 {
        writable_settings.insert(HeadsetSetting::Gain);
        writable_settings.insert(HeadsetSetting::CustomEq);
    }

    let slots = BTreeSet::from([
        TouchSlot::new("left", "single"),
        TouchSlot::new("left", "double"),
        TouchSlot::new("left", "triple"),
        TouchSlot::new("right", "double"),
        TouchSlot::new("right", "triple"),
    ]);
    let slot_labels = BTreeMap::from([
        (TouchSlot::new("left", "single"), "电源键".to_string()),
        (TouchSlot::new("left", "double"), "电源键".to_string()),
        (TouchSlot::new("left", "triple"), "电源键".to_string()),
        (TouchSlot::new("right", "double"), "ANC键".to_string()),
        (TouchSlot::new("right", "triple"), "ANC键".to_string()),
    ]);

    HeadsetCapabilities {
        readable_fields: BTreeSet::from([
            HeadsetField::CaseBattery,
            HeadsetField::Firmware,
            HeadsetField::NoiseMode,
            HeadsetField::NoiseCycle,
            HeadsetField::Eq,
            HeadsetField::CustomEq,
            HeadsetField::Gain,
            HeadsetField::GameMode,
            HeadsetField::AudioMode,
            HeadsetField::Multipoint,
            HeadsetField::TouchKeys,
            HeadsetField::TouchEnabled,
            HeadsetField::AncLevel,
            HeadsetField::TransparencyLevel,
            HeadsetField::PromptVolume,
        ]),
        writable_settings,
        noise_control: Some(NoiseControlCapabilities {
            modes: basic_noise_modes(),
            cycle_modes: basic_noise_modes(),
            anc_level: Some(NoiseLevelCapabilities {
                levels: BTreeSet::from([1, 3, 5]),
                visible_modes: strings(&["anc"]),
            }),
            transparency_level: Some(NoiseLevelCapabilities {
                levels: BTreeSet::from([1, 3, 5]),
                visible_modes: strings(&["transparency"]),
            }),
            minimum_cycle_selections: 0,
        }),
        equalizer: Some(EqualizerCapabilities {
            presets: if firmware_since_130 {
                strings(&["pop", "hifi", "rock", "custom"])
            } else {
                strings(&["pop", "hifi", "rock"])
            },
            custom: if firmware_since_130 {
                Some(CustomEqualizerCapabilities {
                    bands_hz: vec![20, 100, 300, 500, 1000, 2000, 3000, 5000, 8000, 15000],
                    min_gain: -6,
                    max_gain: 6,
                })
            } else {
                None
            },
        }),
        touch_control: Some(TouchControlCapabilities {
            slots,
            actions: strings(&[
                "volume_up",
                "volume_down",
                "previous_track",
                "next_track",
                "voice_assistant",
                "play_pause",
                "none",
                "noise_cycle",
            ]),
            slot_labels,
        }),
        tap_control: None,
        audio_control: Some(AudioControlCapabilities {
            modes: strings(&["aac_sbc", "ldac", "lhdc", "ldac_lhdc"]),
            multipoint_coupled: false,
        }),
        prompt_volume_levels: (1..=5).collect(),
        gain_levels: if firmware_since_130 {
            (0..=2).collect()
        } else {
            BTreeSet::new()
        },
        ..HeadsetCapabilities::default()
    }
}
