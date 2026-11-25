use super::wire::{
    ACTIONS, AUDIO_MODES, CUSTOM_EQ_BANDS, CYCLE_ORDER, FIND_STATES, GESTURES, NOISE_MODES,
    PROMPT_LANGUAGES, READ_PRESETS, SPATIAL_CONTENTS, SPATIAL_MODES, TAP_SLOTS, TOUCH_SLOTS,
};
use crate::field::{FieldReading, FieldValue, FirmwareVersion, HeadsetField, TouchBinding};
use crate::hex::encode_hex;
use crate::model::{BatteryLayout, ModelProfile};
use std::collections::{BTreeMap, BTreeSet};

struct Record {
    id: u8,
    fields: &'static [HeadsetField],
    read: fn(&mut Payload),
}

static RECORDS: &[Record] = &[
    Record {
        id: 0x01,
        fields: &[HeadsetField::TouchKeys],
        read: |payload| payload.bindings(HeadsetField::TouchKeys, &TOUCH_SLOTS, None),
    },
    Record {
        id: 0x07,
        fields: &[HeadsetField::TouchEnabled],
        read: |payload| payload.toggle(HeadsetField::TouchEnabled, 0),
    },
    Record {
        id: 0x08,
        fields: &[HeadsetField::EarDetection],
        read: |payload| payload.toggle(HeadsetField::EarDetection, 0),
    },
    Record {
        id: 0x09,
        fields: &[HeadsetField::NoiseMode],
        read: |payload| {
            payload.single_byte(HeadsetField::NoiseMode, |raw| {
                let index = usize::from(raw).checked_sub(1)?;
                NOISE_MODES.get(index).map(|mode| FieldValue::text(*mode))
            })
        },
    },
    Record {
        id: 0x0c,
        fields: &[
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
            HeadsetField::LeftInCase,
            HeadsetField::RightInCase,
            HeadsetField::CaseFlag,
        ],
        read: |payload| payload.battery(),
    },
    Record {
        id: 0x0d,
        fields: &[HeadsetField::Firmware],
        read: |payload| {
            if payload.value.len() >= 3 {
                let parts = payload.value[..3]
                    .iter()
                    .map(|&part| i32::from(part))
                    .collect();
                payload.put(
                    HeadsetField::Firmware,
                    Some(FieldValue::Firmware(FirmwareVersion::from_parts(parts))),
                );
            }
        },
    },
    Record {
        id: 0x0e,
        fields: &[HeadsetField::GameMode],
        read: |payload| payload.toggle(HeadsetField::GameMode, 1),
    },
    Record {
        id: 0x2a,
        fields: &[HeadsetField::Eq],
        read: |payload| payload.equalizer(),
    },
    Record {
        id: 0x2b,
        fields: &[HeadsetField::AudioMode],
        read: |payload| {
            payload.decimal(HeadsetField::AudioMode, |number| {
                AUDIO_MODES
                    .get(usize::try_from(number).ok()?)
                    .map(|mode| FieldValue::text(*mode))
            })
        },
    },
    Record {
        id: 0x2c,
        fields: &[HeadsetField::AncLevel],
        read: |payload| payload.decimal(HeadsetField::AncLevel, noise_level),
    },
    Record {
        id: 0x2d,
        fields: &[HeadsetField::TransparencyLevel],
        read: |payload| payload.decimal(HeadsetField::TransparencyLevel, noise_level),
    },
    Record {
        id: 0x2e,
        fields: &[HeadsetField::PromptVolume],
        read: |payload| {
            payload.decimal(HeadsetField::PromptVolume, |level| {
                (0..=5)
                    .contains(&level)
                    .then_some(FieldValue::Number(level))
            })
        },
    },
    Record {
        id: 0x2f,
        fields: &[HeadsetField::FindState],
        read: |payload| {
            payload.single_byte(HeadsetField::FindState, |raw| {
                FIND_STATES
                    .iter()
                    .find(|(code, _)| *code == raw)
                    .map(|(_, state)| FieldValue::text(*state))
            })
        },
    },
    Record {
        id: 0x31,
        fields: &[HeadsetField::PromptLanguage],
        read: |payload| {
            payload.single_byte(HeadsetField::PromptLanguage, |raw| {
                PROMPT_LANGUAGES
                    .get(usize::from(raw))
                    .map(|language| FieldValue::text(*language))
            })
        },
    },
    Record {
        id: 0x32,
        fields: &[HeadsetField::Multipoint],
        read: |payload| payload.toggle(HeadsetField::Multipoint, 1),
    },
    Record {
        id: 0x36,
        fields: &[HeadsetField::NoiseCycle],
        read: |payload| payload.noise_cycle(),
    },
    Record {
        id: 0x38,
        fields: &[HeadsetField::SpatialMode],
        read: |payload| {
            payload.single_byte(HeadsetField::SpatialMode, |raw| {
                SPATIAL_MODES
                    .get(usize::from(raw))
                    .map(|mode| FieldValue::text(*mode))
            })
        },
    },
    Record {
        id: 0x3a,
        fields: &[HeadsetField::EarAdaptive],
        read: |payload| payload.toggle(HeadsetField::EarAdaptive, 1),
    },
    Record {
        id: 0x3d,
        fields: &[HeadsetField::SpatialContent],
        read: |payload| {
            payload.single_byte(HeadsetField::SpatialContent, |raw| {
                SPATIAL_CONTENTS
                    .get(usize::from(raw))
                    .map(|content| FieldValue::text(*content))
            })
        },
    },
    Record {
        id: 0x49,
        fields: &[
            HeadsetField::IndependentMicActivation,
            HeadsetField::IndependentMicMode,
        ],
        read: |payload| payload.microphone(),
    },
    Record {
        id: 0x3b,
        fields: &[HeadsetField::TapEnabled],
        read: |payload| payload.toggle(HeadsetField::TapEnabled, 1),
    },
    Record {
        id: 0x3c,
        fields: &[HeadsetField::TapKeys],
        read: |payload| {
            let actions = payload
                .profile
                .capabilities(None, None)
                .tap_control
                .map(|tap| tap.actions)
                .unwrap_or_default();
            payload.bindings(HeadsetField::TapKeys, &TAP_SLOTS, Some(&actions));
        },
    },
    Record {
        id: 0x3e,
        fields: &[HeadsetField::CustomEq],
        read: |payload| {
            let curve = read_curve(payload.value);
            payload.put(HeadsetField::CustomEq, curve);
        },
    },
    Record {
        id: 0x45,
        fields: &[HeadsetField::Gain],
        read: |payload| {
            payload.single_byte(HeadsetField::Gain, |raw| {
                (raw <= 2).then_some(FieldValue::Number(i32::from(raw)))
            })
        },
    },
    Record {
        id: 0x46,
        fields: &[HeadsetField::HearingProtection],
        read: |payload| {
            payload.single_byte(HeadsetField::HearingProtection, |raw| {
                (raw <= 6).then_some(FieldValue::Number(i32::from(raw)))
            })
        },
    },
];

fn record(id: u8) -> Option<&'static Record> {
    RECORDS.iter().find(|record| record.id == id)
}

pub(super) fn record_fields(id: u8) -> &'static [HeadsetField] {
    record(id).map_or(&[], |record| record.fields)
}

pub(crate) fn rs_common_v2_readable_fields() -> BTreeSet<HeadsetField> {
    RECORDS
        .iter()
        .flat_map(|record| record.fields.iter().copied())
        .collect()
}

pub(super) fn read_record(
    id: u8,
    profile: ModelProfile,
    value: &[u8],
) -> BTreeMap<HeadsetField, FieldReading> {
    let Some(record) = record(id) else {
        return BTreeMap::new();
    };
    let mut payload = Payload {
        profile,
        value,
        readings: BTreeMap::new(),
    };
    (record.read)(&mut payload);
    payload.readings
}

fn noise_level(number: i32) -> Option<FieldValue> {
    matches!(number, 1 | 3 | 5).then_some(FieldValue::Number(number))
}

fn in_case(value: u8) -> bool {
    value & 0x80 != 0 && value != 0xff
}

fn read_curve(bytes: &[u8]) -> Option<FieldValue> {
    let gains: Vec<i32> = bytes
        .iter()
        .map(|&byte| {
            let magnitude = i32::from(byte & 0x7f);
            if byte & 0x80 != 0 {
                -magnitude
            } else {
                magnitude
            }
        })
        .collect();
    (gains.len() == CUSTOM_EQ_BANDS && gains.iter().all(|gain| (-6..=6).contains(gain)))
        .then_some(FieldValue::IntegerList(gains))
}

struct Payload<'a> {
    profile: ModelProfile,
    value: &'a [u8],
    readings: BTreeMap<HeadsetField, FieldReading>,
}

impl Payload<'_> {
    fn put(&mut self, field: HeadsetField, semantic: Option<FieldValue>) {
        self.put_from(field, semantic, self.value);
    }

    fn put_from(&mut self, field: HeadsetField, semantic: Option<FieldValue>, source: &[u8]) {
        let raw_hex = encode_hex(source);
        let reading = match semantic {
            Some(value) => FieldReading::known(value, raw_hex),
            None => FieldReading::unknown(raw_hex),
        };
        self.readings.insert(field, reading);
    }

    fn single_byte(&mut self, field: HeadsetField, decode: impl Fn(u8) -> Option<FieldValue>) {
        if let [raw] = *self.value {
            self.put(field, decode(raw));
        }
    }

    fn toggle(&mut self, field: HeadsetField, on: u8) {
        self.single_byte(field, |raw| {
            (raw <= 1).then_some(FieldValue::Toggle(raw == on))
        });
    }

    fn decimal(&mut self, field: HeadsetField, decode: impl Fn(i32) -> Option<FieldValue>) {
        if !self.value.is_empty() {
            let number = encode_hex(self.value).parse::<i32>().ok();
            self.put(field, number.and_then(decode));
        }
    }

    fn battery(&mut self) {
        if self.value.len() < 3 {
            return;
        }
        let (left, right, case) = (self.value[0], self.value[1], self.value[2]);
        let reports_case = self.profile.battery_layout() == BatteryLayout::SingleUnit
            || in_case(left)
            || in_case(right);
        let case_available = (1..=100).contains(&(case & 0x7f)) && reports_case;
        let batteries = [
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
        ];
        for (index, field) in batteries.into_iter().enumerate() {
            let byte = self.value[index];
            let source = [byte];
            let level = i32::from(byte & 0x7f);
            let reading = if byte == 0xff {
                FieldReading::not_provided(encode_hex(&source))
            } else if index == 2 && !case_available {
                FieldReading::unknown(encode_hex(&source))
            } else if (1..=100).contains(&level) {
                FieldReading::known(FieldValue::Number(level), encode_hex(&source))
            } else {
                FieldReading::unknown(encode_hex(&source))
            };
            self.readings.insert(field, reading);
        }
        let flags = [
            HeadsetField::LeftInCase,
            HeadsetField::RightInCase,
            HeadsetField::CaseFlag,
        ];
        for (index, field) in flags.into_iter().enumerate() {
            let byte = self.value[index];
            let semantic = if index < 2 && byte == 0xff {
                None
            } else {
                Some(FieldValue::Toggle(byte & 0x80 != 0))
            };
            self.put_from(field, semantic, &[byte]);
        }
    }

    fn bindings(
        &mut self,
        field: HeadsetField,
        slots: &[u8],
        allowed_actions: Option<&BTreeSet<String>>,
    ) {
        if self.value.len() < slots.len() * 2 || !self.value.len().is_multiple_of(2) {
            return;
        }
        let pairs: BTreeMap<u8, u8> = self
            .value
            .chunks_exact(2)
            .map(|pair| (pair[0], pair[1]))
            .collect();
        if !slots.iter().all(|slot| pairs.contains_key(slot)) {
            return;
        }
        let bindings = slots
            .iter()
            .map(|&slot| {
                let action = pairs[&slot];
                let name = ACTIONS
                    .get(usize::from(action))
                    .filter(|name| allowed_actions.is_none_or(|allowed| allowed.contains(**name)))
                    .map(|name| (*name).to_string());
                TouchBinding {
                    side: if slot < 0x10 { "left" } else { "right" }.to_string(),
                    gesture: GESTURES[usize::from(slot & 0x0f) - 1].to_string(),
                    action: name,
                    raw_action: i32::from(action),
                }
            })
            .collect();
        self.put(field, Some(FieldValue::TouchKeys(bindings)));
    }

    fn equalizer(&mut self) {
        if !(1..=2).contains(&self.value.len()) {
            return;
        }
        let raw = self.value[0];
        let index = if self.value.len() == 1 {
            match raw {
                0 => 1,
                1 => 0,
                other => other,
            }
        } else {
            raw
        };
        let preset = READ_PRESETS
            .iter()
            .find(|(code, _)| *code == index)
            .map(|(_, name)| FieldValue::text(*name));
        self.put(HeadsetField::Eq, preset);
        if self.value.len() == 2 && raw == 5 {
            self.put(
                HeadsetField::EqSlot,
                Some(FieldValue::Number(i32::from(self.value[1]))),
            );
        }
    }

    fn noise_cycle(&mut self) {
        if self.value.len() < 4 {
            return;
        }
        let supported = self
            .profile
            .capabilities(None, None)
            .noise_control
            .map(|noise| noise.cycle_modes)
            .unwrap_or_default();
        let flags = CYCLE_ORDER
            .iter()
            .enumerate()
            .filter(|(_, name)| supported.contains(**name))
            .map(|(index, name)| {
                let flag = self.value[index];
                ((*name).to_string(), (flag <= 1).then_some(flag == 1))
            })
            .collect();
        self.put(
            HeadsetField::NoiseCycle,
            Some(FieldValue::NoiseCycle(flags)),
        );
    }

    fn microphone(&mut self) {
        if self.value.len() != 2 || self.value.iter().any(|&byte| byte > 1) {
            return;
        }
        let activated = self.value[0] == 1;
        self.put(
            HeadsetField::IndependentMicActivation,
            Some(FieldValue::Toggle(activated)),
        );
        self.put(
            HeadsetField::IndependentMicMode,
            Some(FieldValue::Toggle(activated && self.value[1] == 1)),
        );
    }
}
