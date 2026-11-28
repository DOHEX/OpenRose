use crate::capabilities::{HeadsetCapabilities, TouchControlCapabilities};
use crate::error::ProtocolError;
use crate::field::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting, TouchBinding,
};
use crate::local_time::LocalDateTime;
use crate::hex::encode_hex;
use crate::model::rs_common::{RsCommonInit, RsCommonProtocol, TOUCH_GESTURES};
use crate::model::{BatteryLayout, ModelProfile};
use crate::protocol::{
    ProtocolCodec, ProtocolQuery, ProtocolReply, ProtocolSetting, validated_touch_assignment,
};
use std::collections::{BTreeMap, BTreeSet};

const FRAME_START: u8 = 0xff;
const FRAME_END: u8 = 0xaa;
const FRAME_OVERHEAD: usize = 4;
const QUERY_COMMAND: u8 = 0xfa;
const TOUCH_COMMAND: u8 = 0x01;
const RIGHT_SIDE_SLOT_OFFSET: u8 = 16;
const MIN_INIT_WRITE_SIZE: usize = 19;
const CAMBRIAN_REPEAT_DELAY_MILLIS: u64 = 500;
const BUDS_FREE_FOLLOW_UP_SEQUENCE: u8 = 5;

const INFO_IDS: [u8; 14] = [
    0x01, 0x07, 0x08, 0x09, 0x0c, 0x0d, 0x0e, 0x12, 0x2a, 0x2b, 0x2c, 0x2d, 0x2e, 0x33,
];
const BUDS_FREE_FOLLOW_UP_IDS: [u8; 7] = [0x2a, 0x09, 0x0c, 0x0e, 0x07, 0x01, 0x2b];
const AUDIO_MODE_ID: u8 = 0x2b;

pub(crate) const AUDIO_MODES: [&str; 4] = ["aac_sbc", "ldac", "lhdc", "lc3"];
pub(crate) const NOISE_READS: [&str; 4] = ["anc", "normal", "transparency", "wind"];
pub(crate) const EQ_PRESETS: [&str; 3] = ["hifi", "pop", "rock"];
pub(crate) const NOISE_WRITES: [(&str, u8); 4] =
    [("anc", 2), ("normal", 4), ("transparency", 6), ("wind", 8)];
const BATTERY_FIELDS: [HeadsetField; 3] = [
    HeadsetField::LeftBattery,
    HeadsetField::RightBattery,
    HeadsetField::CaseBattery,
];
pub(crate) const TOUCH_VALUE_OFFSETS: [usize; 6] = [1, 3, 5, 11, 13, 15];
pub(crate) const TOUCH_REPLY_MIN_SIZE: usize = 16;

const FIELDS_BY_KEY: &[(u8, &[HeadsetField])] = &[
    (0x01, &[HeadsetField::TouchKeys]),
    (0x07, &[HeadsetField::TouchEnabled]),
    (0x08, &[HeadsetField::EarDetection]),
    (0x09, &[HeadsetField::NoiseMode]),
    (
        0x0c,
        &[
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
            HeadsetField::LeftInCase,
            HeadsetField::RightInCase,
        ],
    ),
    (0x0d, &[HeadsetField::Firmware]),
    (0x0e, &[HeadsetField::GameMode]),
    (0x2a, &[HeadsetField::Eq]),
    (0x2b, &[HeadsetField::AudioMode, HeadsetField::Multipoint]),
    (0x2c, &[HeadsetField::AncLevel]),
    (0x2d, &[HeadsetField::TransparencyLevel]),
    (0x2e, &[HeadsetField::PromptVolume]),
    (0x33, &[HeadsetField::SpatialAudio]),
];

pub(crate) fn fields_for_key(key: u8) -> &'static [HeadsetField] {
    FIELDS_BY_KEY
        .iter()
        .find_map(|&(candidate, fields)| (candidate == key).then_some(fields))
        .unwrap_or(&[])
}

pub(crate) fn record_ids() -> impl Iterator<Item = u8> {
    FIELDS_BY_KEY.iter().map(|&(id, _)| id)
}

pub(crate) fn rs_common_readable_fields() -> BTreeSet<HeadsetField> {
    FIELDS_BY_KEY
        .iter()
        .flat_map(|&(_, fields)| fields.iter().copied())
        .collect()
}

pub struct RsCommonCodec {
    profile: ModelProfile,
    protocol: RsCommonProtocol,
    readable_fields: BTreeSet<HeadsetField>,
    buffer: Vec<u8>,
}

impl RsCommonCodec {
    pub fn new(profile: ModelProfile, protocol: RsCommonProtocol) -> Self {
        Self {
            profile,
            protocol,
            readable_fields: profile.capabilities(None, None).readable_fields,
            buffer: Vec::new(),
        }
    }

    fn query(&self, ids: &[u8], sequence: u8, delay_before_millis: u64) -> ProtocolQuery {
        let mut body = Vec::with_capacity(ids.len() + 1);
        body.push(QUERY_COMMAND);
        body.extend_from_slice(ids);
        let mut response_keys = BTreeSet::new();
        let mut expected_fields = BTreeSet::new();
        for &id in ids {
            let readable: Vec<HeadsetField> = fields_for_key(id)
                .iter()
                .copied()
                .filter(|field| self.readable_fields.contains(field))
                .collect();
            if !readable.is_empty() {
                response_keys.insert(encode_hex(&[id]));
                expected_fields.extend(readable);
            }
        }
        ProtocolQuery {
            key: format!("fa{}", encode_hex(ids)),
            bytes: frame(&body, sequence),
            response_keys,
            expected_fields,
            delay_before_millis,
        }
    }

    fn next_frame(&mut self) -> Option<Vec<u8>> {
        let Some(start) = self.buffer.iter().position(|&byte| byte == FRAME_START) else {
            self.buffer.clear();
            return None;
        };
        self.buffer.drain(..start);
        let size = usize::from(*self.buffer.get(2)?) + FRAME_OVERHEAD;
        if self.buffer.len() < size {
            return None;
        }
        Some(self.buffer.drain(..size).collect())
    }

    fn prepare_touch(
        &self,
        value: &FieldValue,
        touch_control: Option<&TouchControlCapabilities>,
    ) -> Result<ProtocolSetting, ProtocolError> {
        let (side, gesture, action) = validated_touch_assignment(value, touch_control)?;
        let gesture_index = TOUCH_GESTURES
            .iter()
            .position(|&candidate| candidate == gesture)
            .expect("validated RS Common touch gesture");
        let slot = gesture_index as u8
            + 1
            + if side == "right" {
                RIGHT_SIDE_SLOT_OFFSET
            } else {
                0
            };
        let action_code = self
            .protocol
            .touch_actions
            .iter()
            .find_map(|&(name, code)| (name == action).then_some(code))
            .ok_or_else(|| ProtocolError::Unsupported("该型号不支持此触控动作".to_string()))?;
        Ok(ProtocolSetting {
            write_frames: vec![frame(&[TOUCH_COMMAND, slot, action_code], 0)],
            readback: self.query(&[TOUCH_COMMAND], 0, 0),
            expected_values: BTreeMap::from([(HeadsetField::TouchKeys, value.clone())]),
            additional_readbacks: Vec::new(),
        })
    }

    fn decode(&self, raw: &[u8]) -> ProtocolReply {
        let tag = raw[3];
        let value = &raw[4..raw.len() - 1];
        let hex = encode_hex(raw);
        if value.is_empty() {
            return ProtocolReply::new(hex, BTreeSet::new(), BTreeMap::new(), Vec::new());
        }
        let readings = read_record(self.profile, &self.protocol, tag, value);
        let response_keys = if readings.is_empty() {
            BTreeSet::new()
        } else {
            BTreeSet::from([encode_hex(&[tag])])
        };
        ProtocolReply::new(hex, response_keys, readings, Vec::new())
    }
}

pub(crate) fn read_record(
    profile: ModelProfile,
    protocol: &RsCommonProtocol,
    tag: u8,
    value: &[u8],
) -> BTreeMap<HeadsetField, FieldReading> {
    match tag {
        0x0c => decode_battery(profile, value),
        0x0d => decode_firmware(value),
        0x01 => decode_touch(protocol, value),
        0x07 | 0x08 | 0x0e | 0x33 => decode_toggle(tag, value),
        0x09 | 0x2a | 0x2b => decode_mode(tag, value),
        0x2c..=0x2e => decode_level(tag, value),
        _ => BTreeMap::new(),
    }
}

fn decode_battery(profile: ModelProfile, value: &[u8]) -> BTreeMap<HeadsetField, FieldReading> {
    if value.len() < 3 {
        return BTreeMap::new();
    }
    let indices: &[usize] = match profile.battery_layout() {
        BatteryLayout::SingleUnit => &[2],
        BatteryLayout::EarbudsOnly => &[0, 1],
        BatteryLayout::EarbudsAndCase => &[0, 1, 2],
    };
    let mut readings = BTreeMap::new();
    for &index in indices {
        let byte = value[index];
        let amount = if index < 2 { byte & 0x7f } else { byte };
        let raw = encode_hex(&[byte]);
        readings.insert(
            BATTERY_FIELDS[index],
            reading(
                (1..=100)
                    .contains(&amount)
                    .then(|| FieldValue::Number(i32::from(amount))),
                raw.clone(),
            ),
        );
        if index < 2 {
            let field = if index == 0 {
                HeadsetField::LeftInCase
            } else {
                HeadsetField::RightInCase
            };
            readings.insert(
                field,
                reading(
                    (byte != 0xff).then_some(FieldValue::Toggle(byte & 0x80 != 0)),
                    raw,
                ),
            );
        }
    }
    readings
}

fn decode_touch(protocol: &RsCommonProtocol, value: &[u8]) -> BTreeMap<HeadsetField, FieldReading> {
    if value.len() < TOUCH_REPLY_MIN_SIZE {
        return BTreeMap::new();
    }
    let bindings = TOUCH_VALUE_OFFSETS
        .iter()
        .enumerate()
        .map(|(index, &offset)| {
            let code = value[offset] & 0x0f;
            TouchBinding {
                side: if index < 3 { "left" } else { "right" }.to_string(),
                gesture: TOUCH_GESTURES[index % 3].to_string(),
                action: protocol
                    .touch_actions
                    .iter()
                    .find_map(|&(name, candidate)| (candidate == code).then(|| name.to_string())),
                raw_action: i32::from(code),
            }
        })
        .collect();
    BTreeMap::from([(
        HeadsetField::TouchKeys,
        FieldReading::known(FieldValue::TouchKeys(bindings), encode_hex(value)),
    )])
}

impl ProtocolCodec for RsCommonCodec {
    fn profile(&self) -> ModelProfile {
        self.profile
    }

    fn init_queries(
        &mut self,
        max_write_size: usize,
        _now: LocalDateTime,
    ) -> Result<Vec<ProtocolQuery>, ProtocolError> {
        if max_write_size < MIN_INIT_WRITE_SIZE {
            return Err(ProtocolError::InvalidArgument(
                "RS Common getInfo requires a 19-byte write capacity".to_string(),
            ));
        }
        let first = self.query(&INFO_IDS, 0, 0);
        Ok(match self.protocol.initialization {
            RsCommonInit::Default => vec![first],
            RsCommonInit::BudsFree => vec![
                first,
                self.query(&BUDS_FREE_FOLLOW_UP_IDS, BUDS_FREE_FOLLOW_UP_SEQUENCE, 0),
            ],
            RsCommonInit::OpenFeel => vec![first, self.query(&[AUDIO_MODE_ID], 0, 0)],
            RsCommonInit::Cambrian => vec![
                first,
                self.query(&INFO_IDS, 0, CAMBRIAN_REPEAT_DELAY_MILLIS),
            ],
        })
    }

    fn prepare_setting(
        &mut self,
        setting: HeadsetSetting,
        value: &FieldValue,
        firmware: Option<&FirmwareVersion>,
        _current_fields: &BTreeMap<HeadsetField, FieldReading>,
    ) -> Result<ProtocolSetting, ProtocolError> {
        let caps = self.profile.capabilities(firmware, None);
        if !caps.writable_settings.contains(&setting) {
            return Err(ProtocolError::Unsupported("该型号不支持此设置".to_string()));
        }
        if setting == HeadsetSetting::TouchBinding {
            return self.prepare_touch(value, caps.touch_control.as_ref());
        }
        let command = setting_command(setting, value, &caps)?;
        Ok(ProtocolSetting {
            write_frames: vec![frame(&[command.write, command.data], 0)],
            readback: self.query(&[command.read], 0, 0),
            expected_values: BTreeMap::from([(command.field, value.clone())]),
            additional_readbacks: Vec::new(),
        })
    }

    fn feed_notification(&mut self, bytes: &[u8]) -> Vec<ProtocolReply> {
        self.buffer.extend_from_slice(bytes);
        let mut replies = Vec::new();
        while let Some(raw) = self.next_frame() {
            if raw.len() > FRAME_OVERHEAD && raw.last() == Some(&FRAME_END) {
                replies.push(self.decode(&raw));
            }
        }
        replies
    }

    fn reset(&mut self) {
        self.buffer.clear();
    }
}

struct SettingCommand {
    write: u8,
    read: u8,
    data: u8,
    field: HeadsetField,
}

fn frame(body: &[u8], sequence: u8) -> Vec<u8> {
    let mut out = Vec::with_capacity(body.len() + FRAME_OVERHEAD);
    out.extend_from_slice(&[FRAME_START, sequence, body.len() as u8]);
    out.extend_from_slice(body);
    out.push(FRAME_END);
    out
}

fn setting_command(
    setting: HeadsetSetting,
    value: &FieldValue,
    caps: &HeadsetCapabilities,
) -> Result<SettingCommand, ProtocolError> {
    match setting {
        HeadsetSetting::NoiseMode => {
            let mode = text_in(
                value,
                caps.noise_control.as_ref().map(|noise| &noise.modes),
                "降噪模式需要该型号支持的模式名称",
            )?;
            let data = NOISE_WRITES
                .iter()
                .find_map(|&(name, code)| (name == mode).then_some(code))
                .ok_or_else(|| ProtocolError::Unsupported("该型号不支持该降噪模式".to_string()))?;
            Ok(SettingCommand {
                write: 0x12,
                read: 0x09,
                data,
                field: HeadsetField::NoiseMode,
            })
        }
        HeadsetSetting::Eq => {
            let preset = text_in(
                value,
                caps.equalizer.as_ref().map(|eq| &eq.presets),
                "均衡器需要该型号支持的预设名称",
            )?;
            let data = EQ_PRESETS
                .iter()
                .position(|&name| name == preset)
                .ok_or_else(|| {
                    ProtocolError::Unsupported("该型号不支持该均衡器预设".to_string())
                })?;
            Ok(SettingCommand {
                write: 0x2a,
                read: 0x2a,
                data: data as u8,
                field: HeadsetField::Eq,
            })
        }
        HeadsetSetting::AudioMode => {
            let mode = text_in(
                value,
                caps.audio_control.as_ref().map(|audio| &audio.modes),
                "音频模式需要该型号支持的配置名称",
            )?;
            let data = AUDIO_MODES
                .iter()
                .position(|&name| name == mode)
                .ok_or_else(|| ProtocolError::Unsupported("该型号不支持该音频配置".to_string()))?;
            Ok(SettingCommand {
                write: AUDIO_MODE_ID,
                read: AUDIO_MODE_ID,
                data: data as u8,
                field: HeadsetField::AudioMode,
            })
        }
        HeadsetSetting::GameMode => toggle_command(value, 0x0e, 0x0e, 1, 0, HeadsetField::GameMode),
        HeadsetSetting::TouchEnabled => {
            toggle_command(value, 0x0e, 0x07, 0, 2, HeadsetField::TouchEnabled)
        }
        HeadsetSetting::Multipoint => toggle_command(
            value,
            AUDIO_MODE_ID,
            AUDIO_MODE_ID,
            4,
            0,
            HeadsetField::Multipoint,
        ),
        HeadsetSetting::SpatialAudio => {
            toggle_command(value, 0x33, 0x33, 1, 0, HeadsetField::SpatialAudio)
        }
        HeadsetSetting::AncLevel => {
            let levels = caps
                .noise_control
                .as_ref()
                .and_then(|noise| noise.level_capabilities(setting))
                .map(|level| &level.levels);
            level_command(value, levels, 0x58, 0x2c, HeadsetField::AncLevel)
        }
        HeadsetSetting::TransparencyLevel => {
            let levels = caps
                .noise_control
                .as_ref()
                .and_then(|noise| noise.level_capabilities(setting))
                .map(|level| &level.levels);
            level_command(value, levels, 0x5a, 0x2d, HeadsetField::TransparencyLevel)
        }
        HeadsetSetting::PromptVolume => level_command(
            value,
            Some(&caps.prompt_volume_levels),
            0x5c,
            0x2e,
            HeadsetField::PromptVolume,
        ),
        _ => Err(ProtocolError::Unsupported("该型号不支持此设置".to_string())),
    }
}

fn text_in<'a>(
    value: &'a FieldValue,
    allowed: Option<&BTreeSet<String>>,
    requirement: &str,
) -> Result<&'a str, ProtocolError> {
    match value {
        FieldValue::Text(text) if allowed.is_some_and(|set| set.contains(text)) => {
            Ok(text.as_str())
        }
        _ => Err(ProtocolError::InvalidArgument(requirement.to_string())),
    }
}

fn toggle_command(
    value: &FieldValue,
    write: u8,
    read: u8,
    on: u8,
    off: u8,
    field: HeadsetField,
) -> Result<SettingCommand, ProtocolError> {
    let FieldValue::Toggle(enabled) = value else {
        return Err(ProtocolError::InvalidArgument(
            "该设置需要明确开关".to_string(),
        ));
    };
    Ok(SettingCommand {
        write,
        read,
        data: if *enabled { on } else { off },
        field,
    })
}

fn level_command(
    value: &FieldValue,
    levels: Option<&BTreeSet<i32>>,
    write: u8,
    read: u8,
    field: HeadsetField,
) -> Result<SettingCommand, ProtocolError> {
    match value {
        FieldValue::Number(level) if levels.is_some_and(|set| set.contains(level)) => {
            Ok(SettingCommand {
                write,
                read,
                data: *level as u8,
                field,
            })
        }
        _ => Err(ProtocolError::InvalidArgument(
            "该档位不在该型号支持的范围内".to_string(),
        )),
    }
}

fn reading(value: Option<FieldValue>, raw_hex: String) -> FieldReading {
    match value {
        Some(value) => FieldReading::known(value, raw_hex),
        None => FieldReading::unknown(raw_hex),
    }
}

fn decode_firmware(value: &[u8]) -> BTreeMap<HeadsetField, FieldReading> {
    if value.len() < 3 {
        return BTreeMap::new();
    }
    let parts = vec![
        i32::from(value[0] & 0x0f),
        i32::from(value[1]),
        i32::from(value[2]),
    ];
    BTreeMap::from([(
        HeadsetField::Firmware,
        FieldReading::known(
            FieldValue::Firmware(FirmwareVersion::from_parts(parts)),
            encode_hex(value),
        ),
    )])
}

fn decode_toggle(tag: u8, value: &[u8]) -> BTreeMap<HeadsetField, FieldReading> {
    let field = match tag {
        0x07 => HeadsetField::TouchEnabled,
        0x08 => HeadsetField::EarDetection,
        0x0e => HeadsetField::GameMode,
        _ => HeadsetField::SpatialAudio,
    };
    let toggle = value
        .first()
        .filter(|&&byte| byte <= 1)
        .map(|&byte| FieldValue::Toggle(byte != 0));
    BTreeMap::from([(field, reading(toggle, encode_hex(value)))])
}

fn decode_mode(tag: u8, value: &[u8]) -> BTreeMap<HeadsetField, FieldReading> {
    let Some(&first) = value.first() else {
        return BTreeMap::new();
    };
    let raw = encode_hex(value);
    match tag {
        AUDIO_MODE_ID => BTreeMap::from([
            (
                HeadsetField::AudioMode,
                reading(
                    AUDIO_MODES
                        .get(usize::from(first))
                        .map(|&mode| FieldValue::text(mode)),
                    raw.clone(),
                ),
            ),
            (
                HeadsetField::Multipoint,
                reading((first <= 4).then_some(FieldValue::Toggle(first == 4)), raw),
            ),
        ]),
        0x09 => BTreeMap::from([(
            HeadsetField::NoiseMode,
            reading(
                usize::from(first)
                    .checked_sub(1)
                    .and_then(|index| NOISE_READS.get(index))
                    .map(|&mode| FieldValue::text(mode)),
                raw,
            ),
        )]),
        _ => BTreeMap::from([(
            HeadsetField::Eq,
            reading(
                EQ_PRESETS
                    .get(usize::from(first))
                    .map(|&preset| FieldValue::text(preset)),
                raw,
            ),
        )]),
    }
}

fn decode_level(tag: u8, value: &[u8]) -> BTreeMap<HeadsetField, FieldReading> {
    let field = match tag {
        0x2c => HeadsetField::AncLevel,
        0x2d => HeadsetField::TransparencyLevel,
        _ => HeadsetField::PromptVolume,
    };
    let level = value
        .first()
        .filter(|&&byte| (1..=5).contains(&byte))
        .map(|&byte| FieldValue::Number(i32::from(byte)));
    BTreeMap::from([(field, reading(level, encode_hex(value)))])
}
