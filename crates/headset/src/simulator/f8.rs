use super::{HeadsetSide, SimulatorError, preferred_index, preferred_level, preferred_level_index};
use crate::capabilities::HeadsetCapabilities;
use crate::field::{FieldReading, FieldValue, FirmwareVersion, HeadsetField};
use crate::model::{BatteryLayout, F8Protocol, ModelProfile};
use crate::model::ProtocolFamily;
use crate::protocol::f8::{
    BASIC_TOUCH_GESTURES, BASIC_TOUCH_OFFSETS, COMMAND_ANC_LEVEL, COMMAND_AUDIO,
    COMMAND_EAR_DETECTION, COMMAND_EQ, COMMAND_GAME_MODE, COMMAND_NOISE_MODE,
    COMMAND_PROMPT_LANGUAGE, COMMAND_PROMPT_VOLUME, COMMAND_QUERY, COMMAND_TOUCH,
    COMMAND_TOUCH_ENABLED, COMMAND_TRANSPARENCY_LEVEL, EXTENDED_TOUCH_OFFSETS, HEADER_LENGTH,
    NOISE_LEVEL_BY_WIRE, TAG_ANC_LEVEL, TAG_AUDIO, TAG_BATTERY, TAG_EAR_DETECTION, TAG_EQ,
    TAG_FIRMWARE, TAG_GAME_MODE, TAG_NOISE_MODE, TAG_PROMPT_LANGUAGE, TAG_PROMPT_VOLUME, TAG_TOUCH,
    TAG_TOUCH_ENABLED, TAG_TRANSPARENCY_LEVEL, TOUCH_ACTIONS, TOUCH_GESTURES, read_frame,
    record_tags,
};
use std::collections::BTreeMap;

const CHARGING_FLAG: u8 = 0x80;
const STATUS: u8 = 0x00;
const PREFIXED_VALUE: usize = 2;
const NORMAL_READ_WHEN_WIRE_IS_THREE: u8 = 6;
const CAPTURED_LEFT_BATTERY: u8 = 100;
const CAPTURED_RIGHT_BATTERY: u8 = 98;
const CAPTURED_CASE_BATTERY: u8 = 80;
const CAPTURED_EQUALIZER_PRESET: usize = 1;
const CAPTURED_TOUCH_ACTIONS: [&str; 4] = ["play_pause", "next_track", "previous_track", "none"];
const CAPTURED_NOISE_MODE: usize = 0;
const CAPTURED_NOISE_LEVEL: usize = 1;
const CAPTURED_PROMPT_VOLUME: i32 = 3;
const NOISE_MODES: [&str; 4] = ["anc", "normal", "transparency", "wind"];
const PROMPT_LANGUAGES: [&str; 2] = ["chinese", "english"];
const AUDIO_MODES: [&str; 2] = ["aac_sbc", "ldac"];
const AUDIO_WIRE_BY_MODE: [u8; 2] = [2, 1];

pub(super) struct F8HeadsetSide {
    profile: ModelProfile,
    protocol: F8Protocol,
    acknowledge_writes: bool,
    records: BTreeMap<u8, Vec<u8>>,
}

fn request(frame: &[u8]) -> Option<(u8, &[u8])> {
    if frame.len() < HEADER_LENGTH
        || frame[2] != 1
        || frame[3] != 0
        || frame.len() != HEADER_LENGTH + usize::from(frame[4])
    {
        return None;
    }
    Some((frame[1], &frame[HEADER_LENGTH..]))
}

fn reply_frame(payload: &[u8]) -> Vec<u8> {
    let mut out = vec![0, COMMAND_QUERY, 1, 0, payload.len() as u8];
    out.extend_from_slice(payload);
    out
}

fn equalizer_table(protocol: &F8Protocol) -> [&str; 3] {
    ["hifi", "pop", protocol.eq_at_two.unwrap_or("")]
}

fn touch_layout(protocol: &F8Protocol) -> (&'static [usize], &'static [&'static str]) {
    if protocol.extended_init {
        (&EXTENDED_TOUCH_OFFSETS, &TOUCH_GESTURES)
    } else {
        (&BASIC_TOUCH_OFFSETS, &BASIC_TOUCH_GESTURES)
    }
}

fn touch_payload(protocol: &F8Protocol, capabilities: &HeadsetCapabilities) -> Vec<u8> {
    let (offsets, gestures) = touch_layout(protocol);
    let allowed = capabilities
        .touch_control
        .as_ref()
        .map(|touch| touch.actions.clone())
        .unwrap_or_default();
    let names: Vec<&str> = TOUCH_ACTIONS.iter().map(|&(_, name)| name).collect();
    let mut payload =
        vec![0; offsets.iter().copied().max().unwrap_or_default() + 1 - HEADER_LENGTH];
    payload[0] = TAG_TOUCH;
    for (index, &offset) in offsets.iter().enumerate() {
        let gesture = index % gestures.len();
        let preferred = names
            .iter()
            .position(|&name| name == CAPTURED_TOUCH_ACTIONS[gesture])
            .unwrap_or(0);
        payload[offset - HEADER_LENGTH] =
            TOUCH_ACTIONS[preferred_index(&names, preferred, &allowed)].0;
    }
    payload
}

fn noise_read(protocol: &F8Protocol, mode: &str) -> u8 {
    match mode {
        "anc" => 1,
        "transparency" => 2,
        "normal" if protocol.noise_normal_wire == 3 => NORMAL_READ_WHEN_WIRE_IS_THREE,
        _ => 0,
    }
}

fn initial_payload(
    tag: u8,
    protocol: &F8Protocol,
    profile: ModelProfile,
    firmware: &FirmwareVersion,
    capabilities: &HeadsetCapabilities,
) -> Vec<u8> {
    let noise = capabilities.noise_control.as_ref();
    let value = |value: u8| vec![tag, STATUS, value];
    match tag {
        TAG_BATTERY => {
            let case = if profile.battery_layout() == BatteryLayout::EarbudsAndCase {
                CAPTURED_CASE_BATTERY
            } else {
                0
            };
            vec![
                tag,
                STATUS,
                CAPTURED_LEFT_BATTERY,
                CAPTURED_RIGHT_BATTERY,
                case,
            ]
        }
        TAG_FIRMWARE => {
            let [high, low] = (firmware.parts[0] as u16).to_be_bytes();
            vec![tag, STATUS, 0, 0, high, low]
        }
        TAG_EQ => {
            let presets = capabilities
                .equalizer
                .as_ref()
                .map(|equalizer| equalizer.presets.clone())
                .unwrap_or_default();
            value(preferred_index(
                &equalizer_table(protocol),
                CAPTURED_EQUALIZER_PRESET,
                &presets,
            ) as u8)
        }
        TAG_TOUCH => touch_payload(protocol, capabilities),
        TAG_AUDIO => {
            let modes = capabilities
                .audio_control
                .as_ref()
                .map(|audio| audio.modes.clone())
                .unwrap_or_default();
            value(AUDIO_WIRE_BY_MODE[preferred_index(&AUDIO_MODES, 0, &modes)])
        }
        TAG_NOISE_MODE => {
            let modes = noise.map(|noise| noise.modes.clone()).unwrap_or_default();
            let mode = NOISE_MODES[preferred_index(&NOISE_MODES, CAPTURED_NOISE_MODE, &modes)];
            value(noise_read(protocol, mode))
        }
        TAG_ANC_LEVEL | TAG_TRANSPARENCY_LEVEL => {
            let levels = noise
                .and_then(|noise| {
                    if tag == TAG_ANC_LEVEL {
                        noise.anc_level.as_ref()
                    } else {
                        noise.transparency_level.as_ref()
                    }
                })
                .map(|noise| noise.levels.clone())
                .unwrap_or_default();
            value(preferred_level_index(&NOISE_LEVEL_BY_WIRE, CAPTURED_NOISE_LEVEL, &levels) as u8)
        }
        TAG_PROMPT_VOLUME => {
            value(preferred_level(CAPTURED_PROMPT_VOLUME, &capabilities.prompt_volume_levels) as u8)
        }
        TAG_PROMPT_LANGUAGE => {
            let languages = capabilities.prompt_languages.clone();
            value(preferred_index(&PROMPT_LANGUAGES, 0, &languages) as u8)
        }
        TAG_TOUCH_ENABLED => value(1),
        _ => value(0),
    }
}

impl F8HeadsetSide {
    pub(super) fn new(
        profile: ModelProfile,
        protocol: F8Protocol,
        firmware: &FirmwareVersion,
        capabilities: &HeadsetCapabilities,
    ) -> Result<Self, SimulatorError> {
        ProtocolFamily::F8
            .validate_firmware(firmware)
            .map_err(|err| SimulatorError::InvalidFirmware(err.to_string()))?;
        let records = record_tags()
            .map(|tag| {
                (
                    tag,
                    initial_payload(tag, &protocol, profile, firmware, capabilities),
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

    fn answer(&self, tag: u8) -> Vec<Vec<u8>> {
        self.records
            .get(&tag)
            .map(|payload| reply_frame(payload))
            .into_iter()
            .collect()
    }

    fn store(&mut self, tag: u8, value: u8) -> Option<u8> {
        let payload = self.records.get_mut(&tag)?;
        payload[PREFIXED_VALUE] = value;
        Some(tag)
    }

    fn write(&mut self, command: u8, payload: &[u8]) -> Option<u8> {
        match (command, payload) {
            (COMMAND_EQ, &[_, wire]) => self.store(TAG_EQ, wire),
            (COMMAND_TOUCH, &[slot, _, code]) => self.write_touch(slot, code),
            (COMMAND_GAME_MODE, &[value]) => self.store(TAG_GAME_MODE, value),
            (COMMAND_EAR_DETECTION, &[value]) => self.store(TAG_EAR_DETECTION, value),
            (COMMAND_PROMPT_LANGUAGE, &[value]) => self.store(TAG_PROMPT_LANGUAGE, value),
            (COMMAND_AUDIO, &[value]) => self.store(TAG_AUDIO, value),
            (COMMAND_NOISE_MODE, &[wire]) => {
                let read = if wire == self.protocol.noise_normal_wire && wire == 3 {
                    NORMAL_READ_WHEN_WIRE_IS_THREE
                } else {
                    wire
                };
                self.store(TAG_NOISE_MODE, read)
            }
            (COMMAND_ANC_LEVEL, &[wire]) => self.store(TAG_ANC_LEVEL, wire),
            (COMMAND_TRANSPARENCY_LEVEL, &[wire]) => self.store(TAG_TRANSPARENCY_LEVEL, wire),
            (COMMAND_PROMPT_VOLUME, &[level]) => self.store(TAG_PROMPT_VOLUME, level),
            (COMMAND_TOUCH_ENABLED, &[value]) => self.store(TAG_TOUCH_ENABLED, value),
            _ => None,
        }
    }

    fn write_touch(&mut self, slot: u8, code: u8) -> Option<u8> {
        let (offsets, gestures) = touch_layout(&self.protocol);
        let slot = slot.checked_sub(1)?;
        let gesture = TOUCH_GESTURES.get(usize::from(slot / 2))?;
        let position = gestures.iter().position(|candidate| candidate == gesture)?;
        let index = position + if slot % 2 == 1 { gestures.len() } else { 0 };
        let payload = self.records.get_mut(&TAG_TOUCH)?;
        *payload.get_mut(offsets.get(index)? - HEADER_LENGTH)? = code;
        Some(TAG_TOUCH)
    }

    fn encode(&self, field: HeadsetField, value: &FieldValue) -> Option<(u8, Vec<u8>)> {
        let current = |tag: u8| self.records.get(&tag).cloned();
        match (field, value) {
            (HeadsetField::LeftBattery, FieldValue::Number(level)) => {
                battery(current(TAG_BATTERY)?, 2, *level)
            }
            (HeadsetField::RightBattery, FieldValue::Number(level)) => {
                battery(current(TAG_BATTERY)?, 3, *level)
            }
            (HeadsetField::CaseBattery, FieldValue::Number(level))
                if self.profile.battery_layout() == BatteryLayout::EarbudsAndCase =>
            {
                battery(current(TAG_BATTERY)?, 4, *level)
            }
            (HeadsetField::LeftCharging, FieldValue::Toggle(on)) => {
                Some(charging(current(TAG_BATTERY)?, 2, *on))
            }
            (HeadsetField::RightCharging, FieldValue::Toggle(on)) => {
                Some(charging(current(TAG_BATTERY)?, 3, *on))
            }
            _ => {
                let (tag, byte) = self.single_byte(field, value)?;
                let mut payload = current(tag)?;
                payload[PREFIXED_VALUE] = byte;
                Some((tag, payload))
            }
        }
    }

    fn single_byte(&self, field: HeadsetField, value: &FieldValue) -> Option<(u8, u8)> {
        match (field, value) {
            (HeadsetField::Eq, FieldValue::Text(preset)) => {
                let wire = equalizer_table(&self.protocol)
                    .iter()
                    .position(|name| name == preset)?;
                Some((TAG_EQ, wire as u8))
            }
            (HeadsetField::GameMode, FieldValue::Toggle(on)) => {
                Some((TAG_GAME_MODE, u8::from(*on)))
            }
            (HeadsetField::EarDetection, FieldValue::Toggle(on)) => {
                Some((TAG_EAR_DETECTION, u8::from(*on)))
            }
            (HeadsetField::TouchEnabled, FieldValue::Toggle(on)) => {
                Some((TAG_TOUCH_ENABLED, u8::from(*on)))
            }
            (HeadsetField::PromptLanguage, FieldValue::Text(language)) => {
                let index = PROMPT_LANGUAGES.iter().position(|name| name == language)?;
                Some((TAG_PROMPT_LANGUAGE, index as u8))
            }
            (HeadsetField::AudioMode, FieldValue::Text(mode)) => {
                let index = AUDIO_MODES.iter().position(|name| name == mode)?;
                Some((TAG_AUDIO, AUDIO_WIRE_BY_MODE[index]))
            }
            (HeadsetField::Multipoint, FieldValue::Toggle(on)) => {
                Some((TAG_AUDIO, if *on { 0 } else { 2 }))
            }
            (HeadsetField::NoiseMode, FieldValue::Text(mode)) => {
                if !NOISE_MODES.contains(&mode.as_str())
                    || (mode == "wind" && self.protocol.noise_normal_wire == 0)
                {
                    return None;
                }
                Some((TAG_NOISE_MODE, noise_read(&self.protocol, mode)))
            }
            (HeadsetField::AncLevel, FieldValue::Number(level)) => {
                Some((TAG_ANC_LEVEL, level_wire(*level)?))
            }
            (HeadsetField::TransparencyLevel, FieldValue::Number(level)) => {
                Some((TAG_TRANSPARENCY_LEVEL, level_wire(*level)?))
            }
            (HeadsetField::PromptVolume, FieldValue::Number(level)) => {
                Some((TAG_PROMPT_VOLUME, u8::try_from(*level).ok()?))
            }
            _ => None,
        }
    }
}

fn level_wire(level: i32) -> Option<u8> {
    NOISE_LEVEL_BY_WIRE
        .iter()
        .position(|&candidate| candidate == level)
        .map(|wire| wire as u8)
}

fn battery(mut payload: Vec<u8>, index: usize, level: i32) -> Option<(u8, Vec<u8>)> {
    if !(1..=100).contains(&level) {
        return None;
    }
    payload[index] = (payload[index] & CHARGING_FLAG) | level as u8;
    Some((TAG_BATTERY, payload))
}

fn charging(mut payload: Vec<u8>, index: usize, on: bool) -> (u8, Vec<u8>) {
    if on {
        payload[index] |= CHARGING_FLAG;
    } else {
        payload[index] &= !CHARGING_FLAG;
    }
    (TAG_BATTERY, payload)
}

impl HeadsetSide for F8HeadsetSide {
    fn acknowledge_writes(&mut self, enabled: bool) {
        self.acknowledge_writes = enabled;
    }

    fn receive(&mut self, frame: &[u8]) -> Vec<Vec<u8>> {
        let Some((command, payload)) = request(frame) else {
            return Vec::new();
        };
        if command == COMMAND_QUERY {
            return match payload {
                &[tag] => self.answer(tag),
                _ => Vec::new(),
            };
        }
        match self.write(command, payload) {
            Some(tag) if self.acknowledge_writes => self.answer(tag),
            _ => Vec::new(),
        }
    }

    fn readings(&self) -> BTreeMap<HeadsetField, FieldValue> {
        self.records
            .values()
            .flat_map(|payload| read_frame(self.profile, self.protocol, &reply_frame(payload)))
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
        let Some((tag, payload)) = self.encode(field, &value) else {
            return Err(SimulatorError::refused(field, &value));
        };
        self.records.insert(tag, payload);
        Ok(self.answer(tag))
    }
}
