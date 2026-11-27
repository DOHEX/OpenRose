use super::{HeadsetSide, SimulatorError, preferred_index, preferred_level};
use crate::capabilities::{HeadsetCapabilities, TouchControlCapabilities};
use crate::field::{FieldReading, FieldValue, FirmwareVersion, HeadsetField};
use crate::model::{BatteryLayout, ModelProfile};
use crate::protocol::rs_common_v2::wire::{
    ACTIONS, AUDIO_MODES, FIND_STATES, NOISE_MODES, PRESET_CODES, READ_PRESETS, TAP_SLOTS,
    TOUCH_SLOTS,
};
use crate::protocol::rs_common_v2::{read_record, record_fields, record_ids};
use std::collections::BTreeMap;

const BATTERY_RECORD: u8 = 0x0c;
const NOT_PROVIDED: u8 = 0xff;
const IN_CASE_FLAG: u8 = 0x80;
const REQUEST_HEADER: u8 = 0xff;
const RESPONSE_HEADER: u8 = 0xdd;
const FRAME_TRAILER: u8 = 0xaa;
const INFO_REQUEST: u8 = 0xfa;
const CAPTURED_TOUCH_ACTIONS: [u8; 8] = [1, 3, 2, 8, 1, 3, 2, 7];
const CAPTURED_TAP_ACTIONS: [u8; 4] = [0; 4];
const CAPTURED_NOISE_MODE: usize = 0;
const CAPTURED_AUDIO_MODE: usize = 0;
const CAPTURED_EQUALIZER_PRESET: &str = "pop";
const CAPTURED_NOISE_LEVEL: i32 = 3;
const CAPTURED_PROMPT_VOLUME: i32 = 3;

pub(super) struct RsCommonV2HeadsetSide {
    profile: ModelProfile,
    acknowledge_writes: bool,
    records: BTreeMap<u8, Vec<u8>>,
}

fn checksum(bytes: &[u8]) -> u8 {
    bytes.iter().fold(0u8, |sum, &byte| sum.wrapping_add(byte))
}

fn request_body(frame: &[u8]) -> Option<&[u8]> {
    let length = usize::from(*frame.get(2)?);
    if frame.len() != length + 5 || length == 0 {
        return None;
    }
    let (signed, tail) = frame.split_at(frame.len() - 2);
    if frame[0] != REQUEST_HEADER || tail[1] != FRAME_TRAILER || checksum(signed) != tail[0] {
        return None;
    }
    Some(&frame[3..3 + length])
}

fn reply_frame<'a>(records: impl IntoIterator<Item = (u8, &'a [u8])>) -> Vec<u8> {
    let mut out = vec![RESPONSE_HEADER, 0x00];
    for (id, value) in records {
        out.push((value.len() + 1) as u8);
        out.push(id);
        out.extend_from_slice(value);
    }
    out.push(checksum(&out));
    out.push(FRAME_TRAILER);
    out
}

fn pairs(slots: &[u8], actions: &[u8]) -> Vec<u8> {
    slots
        .iter()
        .zip(actions)
        .flat_map(|(&slot, &action)| [slot, action])
        .collect()
}

fn action_codes<const N: usize>(
    captured: [u8; N],
    control: Option<&TouchControlCapabilities>,
) -> [u8; N] {
    let actions = control
        .map(|control| control.actions.clone())
        .unwrap_or_default();
    captured.map(|code| preferred_index(&ACTIONS, usize::from(code), &actions) as u8)
}

fn read_code(name: &str) -> u8 {
    READ_PRESETS
        .iter()
        .find(|(_, preset)| *preset == name)
        .map_or(0, |(code, _)| *code)
}

fn equalizer_record(capabilities: &HeadsetCapabilities) -> Vec<u8> {
    let equalizer = capabilities.equalizer.as_ref();
    let presets = equalizer
        .map(|equalizer| equalizer.presets.clone())
        .unwrap_or_default();
    let names: Vec<&str> = PRESET_CODES.iter().map(|(name, _)| *name).collect();
    let offers_presets = names.iter().any(|name| presets.contains(*name));
    if let Some(level) = capabilities.bass_boost_levels.first()
        && !offers_presets
    {
        return vec![read_code("custom_slot"), *level as u8];
    }
    if equalizer.is_some_and(|equalizer| equalizer.custom.is_some()) && !offers_presets {
        return vec![read_code("custom")];
    }
    let captured = names
        .iter()
        .position(|name| *name == CAPTURED_EQUALIZER_PRESET)
        .unwrap_or(0);
    let (_, code) = PRESET_CODES[preferred_index(&names, captured, &presets)];
    vec![code]
}

fn initial_record(
    id: u8,
    layout: BatteryLayout,
    firmware: &FirmwareVersion,
    capabilities: &HeadsetCapabilities,
) -> Vec<u8> {
    let noise = capabilities.noise_control.as_ref();
    match id {
        0x01 => pairs(
            &TOUCH_SLOTS,
            &action_codes(CAPTURED_TOUCH_ACTIONS, capabilities.touch_control.as_ref()),
        ),
        0x09 => {
            let modes = noise.map(|noise| noise.modes.clone()).unwrap_or_default();
            vec![preferred_index(&NOISE_MODES, CAPTURED_NOISE_MODE, &modes) as u8 + 1]
        }
        0x0c => match layout {
            BatteryLayout::SingleUnit => vec![0xff, 0xff, 0x50],
            BatteryLayout::EarbudsOnly => vec![0x64, 0x32, 0xff],
            BatteryLayout::EarbudsAndCase => vec![0xe4, 0x32, 0x50],
        },
        0x0d => firmware.parts.iter().map(|&part| part as u8).collect(),
        0x2a => equalizer_record(capabilities),
        0x2b => {
            let modes = capabilities
                .audio_control
                .as_ref()
                .map(|audio| audio.modes.clone())
                .unwrap_or_default();
            vec![preferred_index(&AUDIO_MODES, CAPTURED_AUDIO_MODE, &modes) as u8]
        }
        0x2c => {
            let levels = noise
                .and_then(|noise| noise.anc_level.as_ref())
                .map(|noise| noise.levels.clone())
                .unwrap_or_default();
            vec![preferred_level(CAPTURED_NOISE_LEVEL, &levels) as u8]
        }
        0x2d => {
            let levels = noise
                .and_then(|noise| noise.transparency_level.as_ref())
                .map(|noise| noise.levels.clone())
                .unwrap_or_default();
            vec![preferred_level(CAPTURED_NOISE_LEVEL, &levels) as u8]
        }
        0x2e => {
            vec![preferred_level(CAPTURED_PROMPT_VOLUME, &capabilities.prompt_volume_levels) as u8]
        }
        0x2f => vec![4],
        0x36 => vec![1, 0, 1, 1],
        0x3c => pairs(
            &TAP_SLOTS,
            &action_codes(CAPTURED_TAP_ACTIONS, capabilities.tap_control.as_ref()),
        ),
        0x3e => vec![0; 10],
        0x49 => vec![0, 0],
        _ => vec![0],
    }
}

impl RsCommonV2HeadsetSide {
    pub(super) fn new(
        profile: ModelProfile,
        firmware: &FirmwareVersion,
        capabilities: &HeadsetCapabilities,
    ) -> Result<Self, SimulatorError> {
        if firmware.parts.len() != 3 || firmware.parts.iter().any(|part| !(0..=255).contains(part))
        {
            return Err(SimulatorError::InvalidFirmware(
                "RS Common V2 固件版本需要三个 0 到 255 的数字".to_string(),
            ));
        }
        let layout = profile.battery_layout();
        let records = record_ids()
            .filter(|&id| {
                record_fields(id)
                    .iter()
                    .any(|field| capabilities.readable_fields.contains(field))
            })
            .map(|id| (id, initial_record(id, layout, firmware, capabilities)))
            .collect();
        Ok(Self {
            profile,
            acknowledge_writes: false,
            records,
        })
    }

    fn write(&mut self, id: u8, payload: &[u8]) {
        let Some(record) = self.records.get_mut(&id) else {
            return;
        };
        match (id, payload) {
            (0x01 | 0x3c, &[slot, action]) => {
                if let Some(pair) = record.chunks_exact_mut(2).find(|pair| pair[0] == slot) {
                    pair[1] = action;
                }
            }
            (0x49, &[selector, value]) => {
                if let Some(byte) = record.get_mut(usize::from(selector)) {
                    *byte = value;
                }
            }
            (0x2a, _) => *record = payload.to_vec(),
            (_, _) if payload.len() == record.len() => *record = payload.to_vec(),
            _ => {}
        }
    }

    fn answer(&self, ids: &[u8]) -> Vec<Vec<u8>> {
        let records: Vec<(u8, &[u8])> = ids
            .iter()
            .filter_map(|id| Some((*id, self.records.get(id)?.as_slice())))
            .collect();
        if records.is_empty() {
            Vec::new()
        } else {
            vec![reply_frame(records)]
        }
    }

    fn encode(&self, field: HeadsetField, value: &FieldValue) -> Option<(u8, Vec<u8>)> {
        match (field, value) {
            (HeadsetField::LeftBattery, FieldValue::Number(level)) => {
                self.battery(0, |byte| battery_level(byte, *level))
            }
            (HeadsetField::RightBattery, FieldValue::Number(level)) => {
                self.battery(1, |byte| battery_level(byte, *level))
            }
            (HeadsetField::CaseBattery, FieldValue::Number(level)) => {
                self.battery(2, |byte| battery_level(byte, *level))
            }
            (HeadsetField::LeftInCase, FieldValue::Toggle(inside)) => {
                self.battery(0, |byte| Some(in_case_flag(byte, *inside)))
            }
            (HeadsetField::RightInCase, FieldValue::Toggle(inside)) => {
                self.battery(1, |byte| Some(in_case_flag(byte, *inside)))
            }
            _ => {
                let (id, byte) = single_byte(field, value)?;
                self.records.contains_key(&id).then(|| (id, vec![byte]))
            }
        }
    }

    fn battery(
        &self,
        index: usize,
        change: impl FnOnce(u8) -> Option<u8>,
    ) -> Option<(u8, Vec<u8>)> {
        let mut bytes = self.records.get(&BATTERY_RECORD)?.clone();
        let current = *bytes.get(index)?;
        if current == NOT_PROVIDED {
            return None;
        }
        bytes[index] = change(current)?;
        Some((BATTERY_RECORD, bytes))
    }
}

impl HeadsetSide for RsCommonV2HeadsetSide {
    fn acknowledge_writes(&mut self, enabled: bool) {
        self.acknowledge_writes = enabled;
    }

    fn receive(&mut self, frame: &[u8]) -> Vec<Vec<u8>> {
        let Some((&kind, rest)) = request_body(frame).and_then(<[u8]>::split_first) else {
            return Vec::new();
        };
        if kind == INFO_REQUEST {
            return self.answer(rest);
        }
        self.write(kind, rest);
        if self.acknowledge_writes {
            return self.answer(&[kind]);
        }
        Vec::new()
    }

    fn readings(&self) -> BTreeMap<HeadsetField, FieldValue> {
        self.records
            .iter()
            .flat_map(|(&id, value)| read_record(id, self.profile, value))
            .filter_map(|(field, reading)| match reading {
                FieldReading::Known { value, .. } => Some((field, value)),
                _ => None,
            })
            .collect()
    }

    fn set(
        &mut self,
        field: HeadsetField,
        value: FieldValue,
    ) -> Result<Vec<Vec<u8>>, SimulatorError> {
        let Some((id, bytes)) = self.encode(field, &value) else {
            return Err(SimulatorError::refused(field, &value));
        };
        self.records.insert(id, bytes);
        Ok(self.answer(&[id]))
    }
}

fn single_byte(field: HeadsetField, value: &FieldValue) -> Option<(u8, u8)> {
    match (field, value) {
        (HeadsetField::NoiseMode, FieldValue::Text(mode)) => {
            let index = NOISE_MODES.iter().position(|name| name == mode)?;
            Some((0x09, index as u8 + 1))
        }
        (HeadsetField::GameMode, FieldValue::Toggle(on)) => Some((0x0e, u8::from(*on))),
        (HeadsetField::TouchEnabled, FieldValue::Toggle(on)) => Some((0x07, u8::from(!*on))),
        (HeadsetField::EarDetection, FieldValue::Toggle(on)) => Some((0x08, u8::from(!*on))),
        (HeadsetField::Multipoint, FieldValue::Toggle(on)) => Some((0x32, u8::from(*on))),
        (HeadsetField::AncLevel, FieldValue::Number(level)) => {
            Some((0x2c, u8::try_from(*level).ok()?))
        }
        (HeadsetField::TransparencyLevel, FieldValue::Number(level)) => {
            Some((0x2d, u8::try_from(*level).ok()?))
        }
        (HeadsetField::PromptVolume, FieldValue::Number(level)) => {
            Some((0x2e, u8::try_from(*level).ok()?))
        }
        (HeadsetField::FindState, FieldValue::Text(state)) => FIND_STATES
            .iter()
            .find(|(_, name)| *name == state.as_str())
            .map(|(code, _)| (0x2f, *code)),
        _ => None,
    }
}

fn battery_level(byte: u8, level: i32) -> Option<u8> {
    (1..=100)
        .contains(&level)
        .then_some((byte & IN_CASE_FLAG) | level as u8)
}

fn in_case_flag(byte: u8, inside: bool) -> u8 {
    if inside {
        byte | IN_CASE_FLAG
    } else {
        byte & !IN_CASE_FLAG
    }
}
