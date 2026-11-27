use super::SimulatorError;
use crate::field::{FieldReading, FieldValue, FirmwareVersion, HeadsetField};
use crate::model::{BatteryLayout, ModelProfile, ProtocolFamily};
use crate::protocol::rs_common_v2::wire::{FIND_STATES, NOISE_MODES, TAP_SLOTS, TOUCH_SLOTS};
use crate::protocol::rs_common_v2::{read_record, record_fields, record_ids};
use std::collections::BTreeMap;

const BATTERY_RECORD: u8 = 0x0c;
const NOT_PROVIDED: u8 = 0xff;
const IN_CASE_FLAG: u8 = 0x80;
const REQUEST_HEADER: u8 = 0xff;
const RESPONSE_HEADER: u8 = 0xdd;
const FRAME_TRAILER: u8 = 0xaa;
const INFO_REQUEST: u8 = 0xfa;

pub(super) struct RsV2Device {
    profile: ModelProfile,
    firmware: FirmwareVersion,
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

fn initial_record(id: u8, layout: BatteryLayout, firmware: &FirmwareVersion) -> Vec<u8> {
    match id {
        0x01 => pairs(&TOUCH_SLOTS, &[1, 3, 2, 8, 1, 3, 2, 7]),
        0x09 => vec![1],
        0x0c => match layout {
            BatteryLayout::SingleUnit => vec![0xff, 0xff, 0x50],
            BatteryLayout::EarbudsOnly => vec![0x64, 0x32, 0xff],
            BatteryLayout::EarbudsAndCase => vec![0xe4, 0x32, 0x50],
        },
        0x0d => firmware.parts.iter().map(|&part| part as u8).collect(),
        0x2a => vec![1],
        0x2c..=0x2e => vec![3],
        0x2f => vec![4],
        0x36 => vec![1, 0, 1, 1],
        0x3c => pairs(&TAP_SLOTS, &[0; 4]),
        0x3e => vec![0; 10],
        0x49 => vec![0, 0],
        _ => vec![0],
    }
}

impl RsV2Device {
    pub(super) fn new(
        profile: ModelProfile,
        firmware: FirmwareVersion,
    ) -> Result<Self, SimulatorError> {
        match profile.family() {
            ProtocolFamily::RsCommonV2 => {}
            family @ (ProtocolFamily::Zt | ProtocolFamily::F8 | ProtocolFamily::RsCommon) => {
                return Err(SimulatorError::UnsupportedFamily(family));
            }
        }
        if firmware.parts.len() != 3 || firmware.parts.iter().any(|part| !(0..=255).contains(part))
        {
            return Err(SimulatorError::InvalidFirmware(
                "RS Common V2 固件版本需要三个 0 到 255 的数字".to_string(),
            ));
        }
        let readable = profile.capabilities(Some(&firmware), None).readable_fields;
        let layout = profile.battery_layout();
        let records = record_ids()
            .filter(|&id| {
                record_fields(id)
                    .iter()
                    .any(|field| readable.contains(field))
            })
            .map(|id| (id, initial_record(id, layout, &firmware)))
            .collect();
        Ok(Self {
            profile,
            firmware,
            acknowledge_writes: false,
            records,
        })
    }

    pub(super) fn profile(&self) -> ModelProfile {
        self.profile
    }

    pub(super) fn firmware(&self) -> &FirmwareVersion {
        &self.firmware
    }

    pub(super) fn acknowledge_writes(&mut self, enabled: bool) {
        self.acknowledge_writes = enabled;
    }

    pub(super) fn receive(&mut self, frame: &[u8]) -> Vec<Vec<u8>> {
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

    pub(super) fn readings(&self) -> BTreeMap<HeadsetField, FieldValue> {
        self.records
            .iter()
            .flat_map(|(&id, value)| read_record(id, self.profile, value))
            .filter_map(|(field, reading)| match reading {
                FieldReading::Known { value, .. } => Some((field, value)),
                _ => None,
            })
            .collect()
    }

    pub(super) fn set(
        &mut self,
        field: HeadsetField,
        value: FieldValue,
    ) -> Result<Vec<Vec<u8>>, SimulatorError> {
        let Some((id, bytes)) = self.encode(field, &value) else {
            return Err(SimulatorError::InvalidValue(format!(
                "模拟耳机不能把 {field:?} 设为 {value:?}"
            )));
        };
        self.records.insert(id, bytes);
        Ok(self.answer(&[id]))
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
                let (id, byte) = self.single_byte(field, value)?;
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

    fn single_byte(&self, field: HeadsetField, value: &FieldValue) -> Option<(u8, u8)> {
        match (field, value) {
            (HeadsetField::NoiseMode, FieldValue::Text(mode)) => {
                let noise = self
                    .profile
                    .capabilities(Some(&self.firmware), None)
                    .noise_control?;
                let index = NOISE_MODES.iter().position(|name| name == mode)?;
                noise
                    .modes
                    .contains(mode)
                    .then_some((0x09, index as u8 + 1))
            }
            (HeadsetField::GameMode, FieldValue::Toggle(on)) => Some((0x0e, u8::from(*on))),
            (HeadsetField::TouchEnabled, FieldValue::Toggle(on)) => Some((0x07, u8::from(!*on))),
            (HeadsetField::EarDetection, FieldValue::Toggle(on)) => Some((0x08, u8::from(!*on))),
            (HeadsetField::Multipoint, FieldValue::Toggle(on)) => Some((0x32, u8::from(*on))),
            (HeadsetField::AncLevel, FieldValue::Number(level @ (1 | 3 | 5))) => {
                Some((0x2c, *level as u8))
            }
            (HeadsetField::TransparencyLevel, FieldValue::Number(level @ (1 | 3 | 5))) => {
                Some((0x2d, *level as u8))
            }
            (HeadsetField::PromptVolume, FieldValue::Number(level @ 0..=5)) => {
                Some((0x2e, *level as u8))
            }
            (HeadsetField::FindState, FieldValue::Text(state)) => FIND_STATES
                .iter()
                .find(|(_, name)| *name == state.as_str())
                .map(|(code, _)| (0x2f, *code)),
            _ => None,
        }
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
