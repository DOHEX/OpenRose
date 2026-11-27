use super::{HeadsetSide, SimulatorError, preferred_index, preferred_level, rs_battery};
use crate::capabilities::HeadsetCapabilities;
use crate::field::{FieldReading, FieldValue, FirmwareVersion, HeadsetField};
use crate::model::ModelProfile;
use crate::model::ProtocolFamily;
use crate::model::rs_common::{RsCommonProtocol, TOUCH_GESTURES};
use crate::protocol::rs_common::{
    AUDIO_MODES, EQ_PRESETS, NOISE_READS, NOISE_WRITES, TOUCH_REPLY_MIN_SIZE, TOUCH_VALUE_OFFSETS,
    fields_for_key, read_record, record_ids,
};
use std::collections::BTreeMap;

const REQUEST_START: u8 = 0xff;
const FRAME_END: u8 = 0xaa;
const FRAME_OVERHEAD: usize = 4;
const QUERY: u8 = 0xfa;
const BATTERY_RECORD: u8 = 0x0c;
const RIGHT_SIDE_SLOT_OFFSET: u8 = 0x10;
const CAPTURED_TOUCH_ACTIONS: [&str; 3] = ["play_pause", "next_track", "previous_track"];
const CAPTURED_NOISE_MODE: usize = 0;
const CAPTURED_EQUALIZER_PRESET: usize = 1;
const CAPTURED_AUDIO_MODE: usize = 0;
const CAPTURED_NOISE_LEVEL: i32 = 3;
const CAPTURED_PROMPT_VOLUME: i32 = 3;

pub(super) struct RsCommonHeadsetSide {
    profile: ModelProfile,
    protocol: RsCommonProtocol,
    acknowledge_writes: bool,
    records: BTreeMap<u8, Vec<u8>>,
}

fn request_body(frame: &[u8]) -> Option<&[u8]> {
    let length = usize::from(*frame.get(2)?);
    if length == 0
        || frame.len() != length + FRAME_OVERHEAD
        || frame[0] != REQUEST_START
        || frame.last() != Some(&FRAME_END)
    {
        return None;
    }
    Some(&frame[3..3 + length])
}

fn record_frame(id: u8, value: &[u8]) -> Vec<u8> {
    let mut out = vec![REQUEST_START, 0x00, (value.len() + 1) as u8, id];
    out.extend_from_slice(value);
    out.push(FRAME_END);
    out
}

fn touch_record(protocol: &RsCommonProtocol, capabilities: &HeadsetCapabilities) -> Vec<u8> {
    let actions = capabilities
        .touch_control
        .as_ref()
        .map(|touch| touch.actions.clone())
        .unwrap_or_default();
    let names: Vec<&str> = protocol
        .touch_actions
        .iter()
        .map(|&(name, _)| name)
        .collect();
    let mut value = vec![0; TOUCH_REPLY_MIN_SIZE];
    for (index, &offset) in TOUCH_VALUE_OFFSETS.iter().enumerate() {
        let gesture = index % TOUCH_GESTURES.len();
        let preferred = names
            .iter()
            .position(|&name| name == CAPTURED_TOUCH_ACTIONS[gesture])
            .unwrap_or(0);
        value[offset - 1] = slot(index);
        value[offset] = protocol.touch_actions[preferred_index(&names, preferred, &actions)].1;
    }
    value
}

fn slot(index: usize) -> u8 {
    let gesture = (index % TOUCH_GESTURES.len()) as u8 + 1;
    if index < TOUCH_GESTURES.len() {
        gesture
    } else {
        RIGHT_SIDE_SLOT_OFFSET + gesture
    }
}

fn initial_record(
    id: u8,
    protocol: &RsCommonProtocol,
    profile: ModelProfile,
    firmware: &FirmwareVersion,
    capabilities: &HeadsetCapabilities,
) -> Vec<u8> {
    let noise = capabilities.noise_control.as_ref();
    match id {
        0x01 => touch_record(protocol, capabilities),
        0x07 => vec![1],
        0x09 => {
            let modes = noise.map(|noise| noise.modes.clone()).unwrap_or_default();
            vec![preferred_index(&NOISE_READS, CAPTURED_NOISE_MODE, &modes) as u8 + 1]
        }
        BATTERY_RECORD => rs_battery::initial_bytes(profile.battery_layout()),
        0x0d => firmware.parts.iter().map(|&part| part as u8).collect(),
        0x2a => {
            let presets = capabilities
                .equalizer
                .as_ref()
                .map(|equalizer| equalizer.presets.clone())
                .unwrap_or_default();
            vec![preferred_index(&EQ_PRESETS, CAPTURED_EQUALIZER_PRESET, &presets) as u8]
        }
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
        _ => vec![0],
    }
}

impl RsCommonHeadsetSide {
    pub(super) fn new(
        profile: ModelProfile,
        protocol: RsCommonProtocol,
        firmware: &FirmwareVersion,
        capabilities: &HeadsetCapabilities,
    ) -> Result<Self, SimulatorError> {
        ProtocolFamily::RsCommon
            .validate_firmware(firmware)
            .map_err(|err| SimulatorError::InvalidFirmware(err.to_string()))?;
        let records = record_ids()
            .filter(|&id| {
                fields_for_key(id)
                    .iter()
                    .any(|field| capabilities.readable_fields.contains(field))
            })
            .map(|id| {
                (
                    id,
                    initial_record(id, &protocol, profile, firmware, capabilities),
                )
            })
            .collect();
        Ok(Self {
            profile,
            protocol,
            acknowledge_writes: false,
            records,
        })
    }

    fn answer(&self, ids: &[u8]) -> Vec<Vec<u8>> {
        ids.iter()
            .filter_map(|id| Some(record_frame(*id, self.records.get(id)?)))
            .collect()
    }

    fn store(&mut self, id: u8, value: u8) -> Vec<u8> {
        match self.records.get_mut(&id) {
            Some(record) => {
                *record = vec![value];
                vec![id]
            }
            None => Vec::new(),
        }
    }

    fn write(&mut self, body: &[u8]) -> Vec<u8> {
        match *body {
            [0x01, slot, code] => self.write_touch(slot, code),
            [0x12, data] => match NOISE_WRITES.iter().find(|&&(_, wire)| wire == data) {
                Some(&(name, _)) => {
                    let raw = NOISE_READS.iter().position(|&mode| mode == name);
                    raw.map_or_else(Vec::new, |index| self.store(0x09, index as u8 + 1))
                }
                None => Vec::new(),
            },
            [0x2a, data] => self.store(0x2a, data),
            [0x2b, data] => self.store(0x2b, data),
            [0x0e, data] => {
                let mut tags = self.store(0x0e, data & 1);
                tags.extend(self.store(0x07, u8::from(data & 2 == 0)));
                tags
            }
            [0x58, data] => self.store(0x2c, data),
            [0x5a, data] => self.store(0x2d, data),
            [0x5c, data] => self.store(0x2e, data),
            [0x33, data] => self.store(0x33, data),
            _ => Vec::new(),
        }
    }

    fn write_touch(&mut self, slot: u8, code: u8) -> Vec<u8> {
        let right = slot >= RIGHT_SIDE_SLOT_OFFSET;
        let gesture = usize::from(slot & 0x0f).wrapping_sub(1);
        if gesture >= TOUCH_GESTURES.len() {
            return Vec::new();
        }
        let index = gesture + if right { TOUCH_GESTURES.len() } else { 0 };
        match self.records.get_mut(&0x01) {
            Some(record) if record.len() >= TOUCH_REPLY_MIN_SIZE => {
                record[TOUCH_VALUE_OFFSETS[index]] = code;
                vec![0x01]
            }
            _ => Vec::new(),
        }
    }

    fn encode(&self, field: HeadsetField, value: &FieldValue) -> Option<(u8, Vec<u8>)> {
        if let Some(bytes) = self.records.get(&BATTERY_RECORD)
            && let Some(changed) = rs_battery::changed(bytes, field, value)
        {
            return Some((BATTERY_RECORD, changed));
        }
        let (id, byte) = single_byte(field, value)?;
        self.records.contains_key(&id).then(|| (id, vec![byte]))
    }
}

impl HeadsetSide for RsCommonHeadsetSide {
    fn acknowledge_writes(&mut self, enabled: bool) {
        self.acknowledge_writes = enabled;
    }

    fn receive(&mut self, frame: &[u8]) -> Vec<Vec<u8>> {
        let Some(body) = request_body(frame) else {
            return Vec::new();
        };
        if let [QUERY, ids @ ..] = body {
            return self.answer(ids);
        }
        let touched = self.write(body);
        if self.acknowledge_writes {
            return self.answer(&touched);
        }
        Vec::new()
    }

    fn readings(&self) -> BTreeMap<HeadsetField, FieldValue> {
        self.records
            .iter()
            .flat_map(|(&id, value)| read_record(self.profile, &self.protocol, id, value))
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
            let index = NOISE_READS.iter().position(|name| name == mode)?;
            Some((0x09, index as u8 + 1))
        }
        (HeadsetField::Eq, FieldValue::Text(preset)) => {
            let index = EQ_PRESETS.iter().position(|name| name == preset)?;
            Some((0x2a, index as u8))
        }
        (HeadsetField::AudioMode, FieldValue::Text(mode)) => {
            let index = AUDIO_MODES.iter().position(|name| name == mode)?;
            Some((0x2b, index as u8))
        }
        (HeadsetField::Multipoint, FieldValue::Toggle(on)) => Some((0x2b, if *on { 4 } else { 0 })),
        (HeadsetField::GameMode, FieldValue::Toggle(on)) => Some((0x0e, u8::from(*on))),
        (HeadsetField::TouchEnabled, FieldValue::Toggle(on)) => Some((0x07, u8::from(*on))),
        (HeadsetField::EarDetection, FieldValue::Toggle(on)) => Some((0x08, u8::from(*on))),
        (HeadsetField::SpatialAudio, FieldValue::Toggle(on)) => Some((0x33, u8::from(*on))),
        (HeadsetField::AncLevel, FieldValue::Number(level)) => {
            Some((0x2c, u8::try_from(*level).ok()?))
        }
        (HeadsetField::TransparencyLevel, FieldValue::Number(level)) => {
            Some((0x2d, u8::try_from(*level).ok()?))
        }
        (HeadsetField::PromptVolume, FieldValue::Number(level)) => {
            Some((0x2e, u8::try_from(*level).ok()?))
        }
        _ => None,
    }
}
