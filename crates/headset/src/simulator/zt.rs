use super::SimulatorError;
use crate::capabilities::NoiseMode;
use crate::field::{FieldValue, FirmwareVersion, HeadsetField, TouchBinding};
use crate::model::{BatteryLayout, ModelProfile, ModelProtocol, ZtProtocol};
use crate::protocol::zt::{
    ZT_ACTIONS, ZT_CYCLE_ORDER, ZT_GESTURES, ZT_NOISE_LEVELS, audio_modes, eq_presets,
};
use std::collections::BTreeMap;

const NOT_PROVIDED: u8 = 0xff;
const EQ_BANDS: usize = 10;

pub(super) struct ZtDevice {
    profile: ModelProfile,
    protocol: ZtProtocol,
    firmware: FirmwareVersion,
    acknowledge_writes: bool,
    left_battery: u8,
    right_battery: u8,
    case_battery: u8,
    left_charging: bool,
    right_charging: bool,
    noise_mode: NoiseMode,
    eq_preset: u8,
    game_mode: bool,
    audio_mode: u8,
    multipoint: bool,
    touch_actions: [u8; 8],
    touch_enabled: bool,
    noise_cycle: [bool; 4],
    ear_detection: bool,
    anc_level: u8,
    transparency_level: u8,
    prompt_volume: i32,
    custom_curve: [i32; EQ_BANDS],
    gain: u8,
}

fn zt_protocol(profile: ModelProfile) -> Result<ZtProtocol, SimulatorError> {
    match profile.spec().protocol {
        ModelProtocol::Zt(protocol) => Ok(protocol),
        ModelProtocol::RsCommonV2(_) | ModelProtocol::RsCommon(_) | ModelProtocol::F8(_) => {
            Err(SimulatorError::UnsupportedFamily(profile.family()))
        }
    }
}

fn reply_frame(key: [u8; 2], payload: &[u8]) -> Vec<u8> {
    let length = (payload.len() + 10) as u16;
    let [low, high] = length.to_le_bytes();
    let mut out = vec![0x09, 0xff, 0x00, 0x00, 0x01, key[0], key[1], low, high];
    out.extend_from_slice(payload);
    let checksum = out.iter().fold(0u8, |sum, &byte| sum.wrapping_add(byte));
    out.push(checksum);
    out
}

fn toggle_byte(value: bool, on: u8) -> u8 {
    if value { on } else { 1 - on }
}

fn encode_gain(gain: i32) -> u8 {
    if gain < 0 {
        0x80 | (-gain) as u8
    } else {
        gain as u8
    }
}

fn decode_gain(encoded: u8) -> i32 {
    if encoded & 0x80 != 0 {
        -i32::from(encoded & 0x7f)
    } else {
        i32::from(encoded)
    }
}

impl ZtDevice {
    pub(super) fn new(
        profile: ModelProfile,
        firmware: FirmwareVersion,
    ) -> Result<Self, SimulatorError> {
        if firmware.parts.len() != 3 || firmware.parts.iter().any(|part| !(0..=15).contains(part)) {
            return Err(SimulatorError::InvalidFirmware(
                "ZT 固件版本需要三个 0 到 15 的数字".to_string(),
            ));
        }
        let single_unit = profile.battery_layout() == BatteryLayout::SingleUnit;
        Ok(Self {
            profile,
            protocol: zt_protocol(profile)?,
            firmware,
            acknowledge_writes: false,
            left_battery: if single_unit { NOT_PROVIDED } else { 100 },
            right_battery: if single_unit { NOT_PROVIDED } else { 98 },
            case_battery: if single_unit { 80 } else { NOT_PROVIDED },
            left_charging: false,
            right_charging: false,
            noise_mode: NoiseMode::Wind,
            eq_preset: 1,
            game_mode: false,
            audio_mode: 1,
            multipoint: false,
            touch_actions: [6, 5, 2, 4, 6, 5, 3, 7],
            touch_enabled: true,
            noise_cycle: [true; 4],
            ear_detection: false,
            anc_level: 2,
            transparency_level: 0,
            prompt_volume: 2,
            custom_curve: [0; EQ_BANDS],
            gain: 1,
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
        let Some((key, payload)) = parse_request(frame) else {
            return Vec::new();
        };
        let is_write = key[1] & 0x80 != 0 || (key == [0x0e, 0x03] && !payload.is_empty());
        if is_write {
            self.apply_write(key, payload);
            if self.acknowledge_writes {
                return vec![reply_frame(key, payload)];
            }
            return Vec::new();
        }
        self.reply(key).into_iter().collect()
    }

    fn apply_write(&mut self, key: [u8; 2], payload: &[u8]) {
        let first = payload.first().copied();
        match (key, first) {
            ([0x06, 0x82], _) => {
                if let Some(mode) = self.noise_mode_from_write(payload) {
                    self.noise_mode = mode;
                }
            }
            ([0x06, 0x87], Some(byte)) => {
                self.anc_level = self.level_from_write(byte);
            }
            ([0x06, 0x84], Some(byte)) => {
                self.transparency_level = self.level_from_write(byte);
            }
            ([0x06, 0x85], _) if payload.len() == 4 => {
                for (flag, &byte) in self.noise_cycle.iter_mut().zip(payload) {
                    *flag = byte != 0;
                }
            }
            ([0x02, 0x81], Some(byte)) => self.eq_preset = byte,
            ([0x02, 0x82], _) if payload.len() == EQ_BANDS => {
                for (gain, &byte) in self.custom_curve.iter_mut().zip(payload) {
                    *gain = decode_gain(byte);
                }
            }
            ([0x02, 0x83], Some(byte)) => self.gain = byte.saturating_sub(1),
            ([0x0e, 0x83], Some(byte)) => self.prompt_volume = i32::from(byte) + 1,
            ([0x0e, 0x03], Some(byte)) => self.prompt_volume = i32::from(byte),
            ([0x08, 0x81], Some(byte)) => {
                self.audio_mode = byte;
                if !self.protocol.independent_multipoint {
                    self.multipoint = byte == 0;
                }
            }
            ([0x08, 0x82], Some(byte)) => self.multipoint = byte == 1,
            ([0x04, 0x81], _) if payload.len() == 3 => {
                let slot = usize::from(payload[0] != 0) * 4 + usize::from(payload[1] & 0x03);
                self.touch_actions[slot] = payload[2];
            }
            ([0x06, 0x83], Some(byte)) => self.game_mode = byte == 0,
            ([0x0a, 0x81], Some(byte)) => self.touch_enabled = byte == 0,
            ([0x0c, 0x81], Some(byte)) => self.ear_detection = byte == 0,
            _ => {}
        }
    }

    fn noise_mode_from_write(&self, payload: &[u8]) -> Option<NoiseMode> {
        if self.protocol.three_byte_noise {
            match payload {
                [1, 0, 0] => Some(NoiseMode::Anc),
                [0, 0, 1] => Some(NoiseMode::Normal),
                [0, 1, 0] => Some(NoiseMode::Transparency),
                _ => None,
            }
        } else {
            match payload {
                [1, 0, 0, 0] => Some(NoiseMode::Anc),
                [0, 1, 0, 0] => Some(NoiseMode::Normal),
                [0, 0, 1, 0] => Some(NoiseMode::Wind),
                [0, 0, 0, 1] => Some(NoiseMode::Transparency),
                _ => None,
            }
        }
    }

    fn level_from_write(&self, byte: u8) -> u8 {
        let index = i32::from(byte) - self.protocol.level_write_offset;
        index.clamp(0, ZT_NOISE_LEVELS.len() as i32 - 1) as u8
    }

    fn reply(&self, key: [u8; 2]) -> Option<Vec<u8>> {
        let single_unit = self.profile.battery_layout() == BatteryLayout::SingleUnit;
        let payload: Vec<u8> = match key {
            [0x01, 0x01] if single_unit => return None,
            [0x01, 0x01] => vec![
                0x00,
                0x01,
                self.left_battery,
                self.right_battery,
                u8::from(self.left_charging),
                u8::from(self.right_charging),
                self.case_battery,
            ],
            [0x01, 0x03] => vec![self.case_battery],
            [0x06, 0x02] => self.noise_payload()?,
            [0x02, 0x01] => vec![self.eq_preset],
            [0x02, 0x02] => self.custom_curve.iter().map(|&g| encode_gain(g)).collect(),
            [0x02, 0x03] => vec![self.gain + 1],
            [0x06, 0x03] => vec![toggle_byte(self.game_mode, 0)],
            [0x08, 0x01] => vec![self.audio_mode],
            [0x08, 0x02] => vec![u8::from(self.multipoint)],
            [0x08, 0x03] | [0x0b, 0x01] => vec![0x00],
            [0x0d, 0x01] => {
                let parts = &self.firmware.parts;
                vec![parts[0] as u8, parts[1] as u8, parts[2] as u8, 0x00]
            }
            [0x04, 0x01] => self.touch_actions.to_vec(),
            [0x0a, 0x01] => vec![toggle_byte(self.touch_enabled, 0)],
            [0x06, 0x05] => self
                .noise_cycle
                .iter()
                .map(|&flag| u8::from(flag))
                .collect(),
            [0x0c, 0x01] => vec![toggle_byte(self.ear_detection, 0)],
            [0x06, 0x07] => vec![self.anc_level],
            [0x06, 0x04] => vec![self.transparency_level],
            [0x0e, 0x03] => vec![if self.protocol.prompt_volume_direct {
                self.prompt_volume as u8
            } else {
                (self.prompt_volume - 1) as u8
            }],
            _ => return None,
        };
        Some(reply_frame(key, &payload))
    }

    fn noise_payload(&self) -> Option<Vec<u8>> {
        match self.noise_mode {
            NoiseMode::Anc => Some(vec![1, 0, 0, 0]),
            NoiseMode::Normal => Some(vec![0, 1, 0, 0]),
            NoiseMode::Wind => Some(vec![0, 0, 1, 0]),
            NoiseMode::Transparency => Some(vec![0, 0, 0, 1]),
            NoiseMode::Auto => Some(vec![2, 0, 0, 0]),
            NoiseMode::Dynamic | NoiseMode::Deep => None,
        }
    }

    pub(super) fn readings(&self) -> BTreeMap<HeadsetField, FieldValue> {
        let single_unit = self.profile.battery_layout() == BatteryLayout::SingleUnit;
        let mut readings = BTreeMap::new();
        let mut battery = |field, value: u8| {
            if value != NOT_PROVIDED {
                readings.insert(field, FieldValue::Number(i32::from(value)));
            }
        };
        if !single_unit {
            battery(HeadsetField::LeftBattery, self.left_battery);
            battery(HeadsetField::RightBattery, self.right_battery);
        }
        battery(HeadsetField::CaseBattery, self.case_battery);
        readings.insert(
            HeadsetField::LeftCharging,
            FieldValue::Toggle(self.left_charging),
        );
        readings.insert(
            HeadsetField::RightCharging,
            FieldValue::Toggle(self.right_charging),
        );
        readings.insert(
            HeadsetField::Firmware,
            FieldValue::Firmware(self.firmware.clone()),
        );
        readings.insert(
            HeadsetField::NoiseMode,
            FieldValue::text(self.noise_mode.id()),
        );
        readings.insert(HeadsetField::GameMode, FieldValue::Toggle(self.game_mode));
        let presets = eq_presets(self.protocol);
        readings.insert(
            HeadsetField::Eq,
            FieldValue::text(presets[usize::from(self.eq_preset).min(presets.len() - 1)]),
        );
        readings.insert(
            HeadsetField::TouchKeys,
            FieldValue::TouchKeys(self.touch_bindings()),
        );
        readings.insert(
            HeadsetField::AudioMode,
            FieldValue::text(self.audio_mode_name()),
        );
        readings.insert(
            HeadsetField::Multipoint,
            FieldValue::Toggle(self.multipoint),
        );
        readings.insert(
            HeadsetField::TouchEnabled,
            FieldValue::Toggle(self.touch_enabled),
        );
        readings.insert(
            HeadsetField::EarDetection,
            FieldValue::Toggle(self.ear_detection),
        );
        readings.insert(
            HeadsetField::NoiseCycle,
            FieldValue::NoiseCycle(
                ZT_CYCLE_ORDER
                    .iter()
                    .zip(self.noise_cycle)
                    .map(|(&mode, flag)| (mode.to_string(), Some(flag)))
                    .collect(),
            ),
        );
        readings.insert(
            HeadsetField::AncLevel,
            FieldValue::Number(ZT_NOISE_LEVELS[usize::from(self.anc_level)]),
        );
        readings.insert(
            HeadsetField::TransparencyLevel,
            FieldValue::Number(ZT_NOISE_LEVELS[usize::from(self.transparency_level)]),
        );
        readings.insert(
            HeadsetField::PromptVolume,
            FieldValue::Number(self.prompt_volume),
        );
        readings.insert(HeadsetField::Gain, FieldValue::Number(i32::from(self.gain)));
        readings.insert(
            HeadsetField::CustomEq,
            FieldValue::IntegerList(self.custom_curve.to_vec()),
        );
        readings
    }

    fn audio_mode_name(&self) -> &'static str {
        let modes = audio_modes(self.protocol);
        modes[usize::from(self.audio_mode).min(modes.len() - 1)]
    }

    fn touch_bindings(&self) -> Vec<TouchBinding> {
        self.touch_actions
            .iter()
            .enumerate()
            .map(|(index, &code)| TouchBinding {
                side: if index < 4 { "left" } else { "right" }.to_string(),
                gesture: ZT_GESTURES[index % 4].to_string(),
                action: ZT_ACTIONS.get(usize::from(code)).map(|name| name.to_string()),
                raw_action: i32::from(code),
            })
            .collect()
    }

    pub(super) fn set(
        &mut self,
        field: HeadsetField,
        value: FieldValue,
    ) -> Result<Vec<Vec<u8>>, SimulatorError> {
        let invalid = || {
            SimulatorError::InvalidValue(format!(
                "模拟耳机不能把{}设为 {}",
                field.display_name(),
                value.display_text()
            ))
        };
        let key = match (field, &value) {
            (HeadsetField::LeftBattery, FieldValue::Number(level)) => {
                self.left_battery = percentage(*level).ok_or_else(invalid)?;
                [0x01, 0x01]
            }
            (HeadsetField::RightBattery, FieldValue::Number(level)) => {
                self.right_battery = percentage(*level).ok_or_else(invalid)?;
                [0x01, 0x01]
            }
            (HeadsetField::CaseBattery, FieldValue::Number(level)) => {
                self.case_battery = percentage(*level).ok_or_else(invalid)?;
                if self.profile.battery_layout() == BatteryLayout::SingleUnit {
                    [0x01, 0x03]
                } else {
                    [0x01, 0x01]
                }
            }
            (HeadsetField::LeftCharging, FieldValue::Toggle(on)) => {
                self.left_charging = *on;
                [0x01, 0x01]
            }
            (HeadsetField::RightCharging, FieldValue::Toggle(on)) => {
                self.right_charging = *on;
                [0x01, 0x01]
            }
            (HeadsetField::NoiseMode, FieldValue::Text(id)) => {
                let mode = NoiseMode::from_id(id).ok_or_else(invalid)?;
                let previous = self.noise_mode;
                self.noise_mode = mode;
                if self.noise_payload().is_none() {
                    self.noise_mode = previous;
                    return Err(invalid());
                }
                [0x06, 0x02]
            }
            (HeadsetField::GameMode, FieldValue::Toggle(on)) => {
                self.game_mode = *on;
                [0x06, 0x03]
            }
            (HeadsetField::TouchEnabled, FieldValue::Toggle(on)) => {
                self.touch_enabled = *on;
                [0x0a, 0x01]
            }
            (HeadsetField::EarDetection, FieldValue::Toggle(on)) => {
                self.ear_detection = *on;
                [0x0c, 0x01]
            }
            (HeadsetField::AncLevel, FieldValue::Number(level)) => {
                self.anc_level = level_index(*level).ok_or_else(invalid)?;
                [0x06, 0x07]
            }
            (HeadsetField::TransparencyLevel, FieldValue::Number(level)) => {
                self.transparency_level = level_index(*level).ok_or_else(invalid)?;
                [0x06, 0x04]
            }
            (HeadsetField::PromptVolume, FieldValue::Number(level)) if (1..=5).contains(level) => {
                self.prompt_volume = *level;
                [0x0e, 0x03]
            }
            _ => return Err(invalid()),
        };
        Ok(self.reply(key).into_iter().collect())
    }
}

fn percentage(level: i32) -> Option<u8> {
    (1..=100).contains(&level).then_some(level as u8)
}

fn level_index(level: i32) -> Option<u8> {
    ZT_NOISE_LEVELS
        .iter()
        .position(|&item| item == level)
        .map(|index| index as u8)
}

fn parse_request(frame: &[u8]) -> Option<([u8; 2], &[u8])> {
    if frame.len() < 10 || frame[0] != 0x08 {
        return None;
    }
    let declared = usize::from(u16::from_le_bytes([frame[7], frame[8]]));
    if declared != frame.len() {
        return None;
    }
    let (body, checksum) = frame.split_at(frame.len() - 1);
    let expected = body.iter().fold(0u8, |sum, &byte| sum.wrapping_add(byte));
    if checksum[0] != expected {
        return None;
    }
    Some(([frame[5], frame[6]], &frame[9..frame.len() - 1]))
}
