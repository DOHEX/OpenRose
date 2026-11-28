use crate::capabilities::HeadsetCapabilities;
use crate::error::ProtocolError;
use crate::field::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting, TouchBinding,
};
use crate::local_time::LocalDateTime;
use crate::hex::encode_hex;
use crate::model::{BatteryLayout, F8Protocol, ModelProfile, ProtocolFamily};
use crate::protocol::{
    ProtocolCodec, ProtocolQuery, ProtocolReply, ProtocolSetting, validated_touch_assignment,
};
use std::collections::{BTreeMap, BTreeSet};

pub(crate) const TAG_BATTERY: u8 = 0x01;
pub(crate) const TAG_FIRMWARE: u8 = 0x02;
pub(crate) const TAG_EQ: u8 = 0x04;
pub(crate) const TAG_TOUCH: u8 = 0x05;
pub(crate) const TAG_GAME_MODE: u8 = 0x08;
pub(crate) const TAG_EAR_DETECTION: u8 = 0x09;
pub(crate) const TAG_PROMPT_LANGUAGE: u8 = 0x0a;
pub(crate) const TAG_AUDIO: u8 = 0x0b;
pub(crate) const TAG_NOISE_MODE: u8 = 0x0c;
pub(crate) const TAG_ANC_LEVEL: u8 = 0x11;
pub(crate) const TAG_TRANSPARENCY_LEVEL: u8 = 0x12;
pub(crate) const TAG_PROMPT_VOLUME: u8 = 0x13;
pub(crate) const TAG_TOUCH_ENABLED: u8 = 0x16;

pub(crate) const COMMAND_EQ: u8 = 0x20;
pub(crate) const COMMAND_TOUCH: u8 = 0x22;
pub(crate) const COMMAND_GAME_MODE: u8 = 0x25;
pub(crate) const COMMAND_EAR_DETECTION: u8 = 0x26;
pub(crate) const COMMAND_QUERY: u8 = 0x27;
pub(crate) const COMMAND_PROMPT_LANGUAGE: u8 = 0x29;
pub(crate) const COMMAND_AUDIO: u8 = 0x2b;
pub(crate) const COMMAND_NOISE_MODE: u8 = 0x2c;
pub(crate) const COMMAND_ANC_LEVEL: u8 = 0x30;
pub(crate) const COMMAND_TRANSPARENCY_LEVEL: u8 = 0x31;
pub(crate) const COMMAND_PROMPT_VOLUME: u8 = 0x32;
pub(crate) const COMMAND_TOUCH_ENABLED: u8 = 0x33;
const COMMAND_REPLY: u8 = 0x28;

pub(crate) const HEADER_LENGTH: usize = 5;

const FIELD_TAGS: &[(u8, &[HeadsetField])] = &[
    (
        TAG_BATTERY,
        &[
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
            HeadsetField::LeftCharging,
            HeadsetField::RightCharging,
        ],
    ),
    (TAG_FIRMWARE, &[HeadsetField::Firmware]),
    (TAG_EQ, &[HeadsetField::Eq]),
    (TAG_TOUCH, &[HeadsetField::TouchKeys]),
    (TAG_GAME_MODE, &[HeadsetField::GameMode]),
    (TAG_EAR_DETECTION, &[HeadsetField::EarDetection]),
    (TAG_PROMPT_LANGUAGE, &[HeadsetField::PromptLanguage]),
    (
        TAG_AUDIO,
        &[HeadsetField::AudioMode, HeadsetField::Multipoint],
    ),
    (TAG_NOISE_MODE, &[HeadsetField::NoiseMode]),
    (TAG_ANC_LEVEL, &[HeadsetField::AncLevel]),
    (TAG_TRANSPARENCY_LEVEL, &[HeadsetField::TransparencyLevel]),
    (TAG_PROMPT_VOLUME, &[HeadsetField::PromptVolume]),
    (TAG_TOUCH_ENABLED, &[HeadsetField::TouchEnabled]),
];

const BASIC_INIT_TAGS: [u8; 11] = [
    TAG_BATTERY,
    TAG_NOISE_MODE,
    TAG_EQ,
    TAG_GAME_MODE,
    TAG_AUDIO,
    TAG_FIRMWARE,
    TAG_TOUCH,
    TAG_EAR_DETECTION,
    TAG_PROMPT_VOLUME,
    TAG_ANC_LEVEL,
    TAG_TOUCH_ENABLED,
];

pub(crate) const TOUCH_GESTURES: [&str; 4] = ["single", "double", "triple", "long"];
pub(crate) const BASIC_TOUCH_GESTURES: [&str; 3] = ["single", "double", "long"];
pub(crate) const BASIC_TOUCH_OFFSETS: [usize; 6] = [7, 9, 11, 8, 10, 12];
pub(crate) const EXTENDED_TOUCH_OFFSETS: [usize; 8] = [9, 15, 21, 27, 12, 18, 24, 30];
pub(crate) const NOISE_LEVEL_BY_WIRE: [i32; 3] = [5, 3, 1];

pub(crate) const TOUCH_ACTIONS: [(u8, &str); 8] = [
    (0, "none"),
    (2, "voice_assistant"),
    (3, "previous_track"),
    (4, "next_track"),
    (5, "volume_up"),
    (6, "volume_down"),
    (7, "play_pause"),
    (9, "noise_cycle"),
];

pub struct F8Codec {
    profile: ModelProfile,
    protocol: F8Protocol,
    capabilities: HeadsetCapabilities,
    buffer: Vec<u8>,
}

impl F8Codec {
    pub fn new(profile: ModelProfile, protocol: F8Protocol) -> Self {
        Self {
            profile,
            protocol,
            capabilities: profile.capabilities(None, None),
            buffer: Vec::new(),
        }
    }

    fn setting_write(
        &self,
        setting: HeadsetSetting,
        value: &FieldValue,
        capabilities: &HeadsetCapabilities,
    ) -> Result<F8Write, ProtocolError> {
        match setting {
            HeadsetSetting::Eq => eq_write(value, capabilities),
            HeadsetSetting::GameMode => toggle_write(COMMAND_GAME_MODE, TAG_GAME_MODE, value),
            HeadsetSetting::EarDetection => {
                toggle_write(COMMAND_EAR_DETECTION, TAG_EAR_DETECTION, value)
            }
            HeadsetSetting::TouchEnabled => {
                toggle_write(COMMAND_TOUCH_ENABLED, TAG_TOUCH_ENABLED, value)
            }
            HeadsetSetting::NoiseMode => self.noise_mode_write(value, capabilities),
            HeadsetSetting::AncLevel | HeadsetSetting::TransparencyLevel => {
                noise_level_write(setting, value, capabilities)
            }
            HeadsetSetting::PromptLanguage => language_write(value, capabilities),
            HeadsetSetting::AudioMode | HeadsetSetting::Multipoint => {
                audio_write(setting, value, capabilities)
            }
            HeadsetSetting::TouchBinding => touch_write(value, capabilities),
            HeadsetSetting::PromptVolume => prompt_volume_write(value, capabilities),
            _ => Err(unsupported_setting()),
        }
    }

    fn noise_mode_write(
        &self,
        value: &FieldValue,
        capabilities: &HeadsetCapabilities,
    ) -> Result<F8Write, ProtocolError> {
        let modes = capabilities
            .noise_control
            .as_ref()
            .map(|noise| &noise.modes)
            .ok_or_else(unsupported_setting)?;
        let FieldValue::Text(mode) = value else {
            return Err(ProtocolError::InvalidArgument(
                "降噪模式需要该型号支持的模式名称".to_string(),
            ));
        };
        if !modes.contains(mode) {
            return Err(ProtocolError::InvalidArgument(
                "降噪模式需要该型号支持的模式名称".to_string(),
            ));
        }
        let wire = match mode.as_str() {
            "anc" => 1,
            "transparency" => 2,
            "wind" => 0,
            "normal" => self.protocol.noise_normal_wire,
            _ => {
                return Err(ProtocolError::Unsupported(
                    "该型号不支持该降噪模式".to_string(),
                ));
            }
        };
        Ok(F8Write {
            command: COMMAND_NOISE_MODE,
            tag: TAG_NOISE_MODE,
            payload: vec![wire],
        })
    }

    fn decode(&self, raw: &[u8]) -> ProtocolReply {
        let hex = encode_hex(raw);
        if raw.len() < HEADER_LENGTH + 1 {
            return ProtocolReply::new(hex, BTreeSet::new(), BTreeMap::new(), Vec::new());
        }
        let tag = raw[5];
        if tag_fields(tag).is_none() {
            return ProtocolReply::new(hex, BTreeSet::new(), BTreeMap::new(), Vec::new());
        }
        let key = tag_key(tag);
        if raw.len() <= 7 {
            let warning = format!("{key} 字段被截断");
            return ProtocolReply::new(hex, BTreeSet::from([key]), BTreeMap::new(), vec![warning]);
        }
        let mut readings = BTreeMap::new();
        self.decode_basic(tag, raw, &mut readings);
        if self.protocol.extended_init {
            self.decode_extended(tag, raw, &mut readings);
        }
        ProtocolReply::new(hex, BTreeSet::from([key]), readings, Vec::new())
    }

    fn decode_basic(
        &self,
        tag: u8,
        raw: &[u8],
        readings: &mut BTreeMap<HeadsetField, FieldReading>,
    ) {
        let value = raw[7];
        match tag {
            TAG_BATTERY => self.decode_battery(raw, readings),
            TAG_FIRMWARE => {
                if raw.len() >= 11 {
                    let version = i32::from(raw[9]) * 256 + i32::from(raw[10]);
                    put_reading(
                        readings,
                        raw,
                        HeadsetField::Firmware,
                        Some(FieldValue::Firmware(FirmwareVersion::new(
                            vec![version],
                            version.to_string(),
                        ))),
                        9,
                    );
                }
            }
            TAG_EQ => {
                let preset = match value {
                    0 => Some("hifi"),
                    1 => Some("pop"),
                    2 => self.protocol.eq_at_two,
                    _ => None,
                };
                put_reading(
                    readings,
                    raw,
                    HeadsetField::Eq,
                    preset.map(FieldValue::text),
                    7,
                );
            }
            TAG_TOUCH => self.decode_touch(raw, readings),
            TAG_GAME_MODE => put_reading(
                readings,
                raw,
                HeadsetField::GameMode,
                toggle_value(value),
                7,
            ),
            _ => {}
        }
    }

    fn decode_battery(&self, raw: &[u8], readings: &mut BTreeMap<HeadsetField, FieldReading>) {
        if raw.len() < 10 {
            return;
        }
        let has_case = self.profile.battery_layout() == BatteryLayout::EarbudsAndCase;
        for (index, field) in [
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
        ]
        .into_iter()
        .enumerate()
        {
            if index != 2 || has_case {
                let level = raw[7 + index] & 0x7f;
                let value = (level <= 100).then(|| FieldValue::Number(i32::from(level)));
                put_reading(readings, raw, field, value, 7 + index);
            }
        }
        put_reading(
            readings,
            raw,
            HeadsetField::LeftCharging,
            Some(FieldValue::Toggle(raw[7] & 0x80 != 0)),
            7,
        );
        put_reading(
            readings,
            raw,
            HeadsetField::RightCharging,
            Some(FieldValue::Toggle(raw[8] & 0x80 != 0)),
            8,
        );
    }

    fn decode_touch(&self, raw: &[u8], readings: &mut BTreeMap<HeadsetField, FieldReading>) {
        let (offsets, gestures): (&[usize], &[&str]) = if self.protocol.extended_init {
            (&EXTENDED_TOUCH_OFFSETS, &TOUCH_GESTURES)
        } else {
            (&BASIC_TOUCH_OFFSETS, &BASIC_TOUCH_GESTURES)
        };
        let last_offset = offsets.iter().copied().max().unwrap_or_default();
        if raw.len() <= last_offset {
            return;
        }
        let bindings = offsets
            .iter()
            .enumerate()
            .map(|(index, &offset)| {
                let code = raw[offset] & 0x0f;
                TouchBinding {
                    side: if index < gestures.len() {
                        "left"
                    } else {
                        "right"
                    }
                    .to_string(),
                    gesture: gestures[index % gestures.len()].to_string(),
                    action: touch_action_name(code).map(str::to_string),
                    raw_action: i32::from(code),
                }
            })
            .collect();
        put_reading(
            readings,
            raw,
            HeadsetField::TouchKeys,
            Some(FieldValue::TouchKeys(bindings)),
            7,
        );
    }

    fn decode_extended(
        &self,
        tag: u8,
        raw: &[u8],
        readings: &mut BTreeMap<HeadsetField, FieldReading>,
    ) {
        let value = raw[7];
        match tag {
            TAG_EAR_DETECTION => put_reading(
                readings,
                raw,
                HeadsetField::EarDetection,
                toggle_value(value),
                7,
            ),
            TAG_TOUCH_ENABLED => {
                if self
                    .capabilities
                    .readable_fields
                    .contains(&HeadsetField::TouchEnabled)
                {
                    put_reading(
                        readings,
                        raw,
                        HeadsetField::TouchEnabled,
                        toggle_value(value),
                        7,
                    );
                }
            }
            TAG_PROMPT_LANGUAGE => {
                let language = match value {
                    0 => Some("chinese"),
                    1 => Some("english"),
                    _ => None,
                };
                put_reading(
                    readings,
                    raw,
                    HeadsetField::PromptLanguage,
                    language.map(FieldValue::text),
                    7,
                );
            }
            TAG_AUDIO => decode_audio(raw, readings),
            TAG_NOISE_MODE => self.decode_noise_mode(raw, readings),
            TAG_ANC_LEVEL => self.decode_noise_level(HeadsetSetting::AncLevel, raw, readings),
            TAG_TRANSPARENCY_LEVEL => {
                self.decode_noise_level(HeadsetSetting::TransparencyLevel, raw, readings)
            }
            TAG_PROMPT_VOLUME if value <= 9 => put_reading(
                readings,
                raw,
                HeadsetField::PromptVolume,
                Some(FieldValue::Number(i32::from(value))),
                7,
            ),
            _ => {}
        }
    }

    fn decode_noise_mode(&self, raw: &[u8], readings: &mut BTreeMap<HeadsetField, FieldReading>) {
        let normal_wire = self.protocol.noise_normal_wire;
        let mode = match raw[7] {
            0 => Some(if normal_wire == 0 { "normal" } else { "wind" }),
            1 => Some("anc"),
            2 => Some("transparency"),
            6 if normal_wire == 3 => Some("normal"),
            _ => None,
        };
        put_reading(
            readings,
            raw,
            HeadsetField::NoiseMode,
            mode.map(FieldValue::text),
            7,
        );
    }

    fn decode_noise_level(
        &self,
        setting: HeadsetSetting,
        raw: &[u8],
        readings: &mut BTreeMap<HeadsetField, FieldReading>,
    ) {
        let field = if setting == HeadsetSetting::AncLevel {
            HeadsetField::AncLevel
        } else {
            HeadsetField::TransparencyLevel
        };
        let exposes_levels = self
            .capabilities
            .noise_control
            .as_ref()
            .and_then(|noise| noise.level_capabilities(setting))
            .is_some();
        let value = if exposes_levels {
            noise_level_value(raw[7])
        } else {
            None
        };
        put_reading(readings, raw, field, value, 7);
    }
}

impl ProtocolCodec for F8Codec {
    fn profile(&self) -> ModelProfile {
        self.profile
    }

    fn init_queries(
        &mut self,
        max_write_size: usize,
        _now: LocalDateTime,
    ) -> Result<Vec<ProtocolQuery>, ProtocolError> {
        if max_write_size < HEADER_LENGTH + 1 {
            return Err(ProtocolError::InvalidArgument(
                "F8 queries require a 6-byte write capacity".to_string(),
            ));
        }
        let mut tags = BASIC_INIT_TAGS.to_vec();
        if self.protocol.extended_init {
            tags.push(TAG_PROMPT_LANGUAGE);
        }
        Ok(tags.into_iter().map(query).collect())
    }

    fn prepare_setting(
        &mut self,
        setting: HeadsetSetting,
        value: &FieldValue,
        firmware: Option<&FirmwareVersion>,
        _current_fields: &BTreeMap<HeadsetField, FieldReading>,
    ) -> Result<ProtocolSetting, ProtocolError> {
        let capabilities = self.profile.capabilities(firmware, None);
        if !capabilities.writable_settings.contains(&setting) {
            return Err(unsupported_setting());
        }
        let write = self.setting_write(setting, value, &capabilities)?;
        let readback = query(write.tag);
        let expected_values = if matches!(
            setting,
            HeadsetSetting::AudioMode | HeadsetSetting::Multipoint
        ) {
            let wire = write.payload[0];
            BTreeMap::from([
                (
                    HeadsetField::AudioMode,
                    FieldValue::text(if wire == 1 { "ldac" } else { "aac_sbc" }),
                ),
                (HeadsetField::Multipoint, FieldValue::Toggle(wire == 0)),
            ])
        } else {
            let field = *readback
                .expected_fields
                .iter()
                .next()
                .expect("F8 readback must map to a field");
            BTreeMap::from([(field, value.clone())])
        };
        Ok(ProtocolSetting {
            write_frames: vec![frame(write.command, &write.payload)],
            readback,
            expected_values,
            additional_readbacks: Vec::new(),
        })
    }

    fn feed_notification(&mut self, bytes: &[u8]) -> Vec<ProtocolReply> {
        self.buffer.extend_from_slice(bytes);
        let mut replies = Vec::new();
        let mut offset = 0;
        while self.buffer.len() - offset >= HEADER_LENGTH {
            let head = &self.buffer[offset..];
            let is_header =
                matches!(head[1], COMMAND_QUERY | COMMAND_REPLY) && head[2] == 1 && head[3] == 0;
            if !is_header {
                offset += 1;
                continue;
            }
            let size = HEADER_LENGTH + usize::from(head[4]);
            if head.len() < size {
                break;
            }
            replies.push(self.decode(&head[..size]));
            offset += size;
        }
        self.buffer.drain(..offset);
        replies
    }

    fn reset(&mut self) {
        self.buffer.clear();
    }
}

struct F8Write {
    command: u8,
    tag: u8,
    payload: Vec<u8>,
}

fn unsupported_setting() -> ProtocolError {
    ProtocolError::Unsupported("该型号不支持此设置".to_string())
}

fn frame(command: u8, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(HEADER_LENGTH + payload.len());
    out.extend_from_slice(&[0, command, 1, 0, payload.len() as u8]);
    out.extend_from_slice(payload);
    out
}

fn tag_key(tag: u8) -> String {
    format!("{tag:02x}")
}

fn tag_fields(tag: u8) -> Option<&'static [HeadsetField]> {
    FIELD_TAGS
        .iter()
        .find(|&&(candidate, _)| candidate == tag)
        .map(|&(_, fields)| fields)
}

fn query(tag: u8) -> ProtocolQuery {
    let key = tag_key(tag);
    let mut bytes = frame(COMMAND_QUERY, &[tag]);
    if tag == TAG_TOUCH {
        bytes[0] = 2;
    }
    ProtocolQuery {
        response_keys: BTreeSet::from([key.clone()]),
        key,
        bytes,
        expected_fields: tag_fields(tag)
            .unwrap_or_default()
            .iter()
            .copied()
            .collect(),
        delay_before_millis: 0,
    }
}

pub(crate) fn f8_readable_fields() -> BTreeSet<HeadsetField> {
    FIELD_TAGS
        .iter()
        .flat_map(|&(_, fields)| fields.iter().copied())
        .collect()
}

pub(crate) fn record_tags() -> impl Iterator<Item = u8> {
    FIELD_TAGS.iter().map(|&(tag, _)| tag)
}

pub(crate) fn read_frame(
    profile: ModelProfile,
    protocol: F8Protocol,
    raw: &[u8],
) -> BTreeMap<HeadsetField, FieldReading> {
    F8Codec::new(profile, protocol).decode(raw).readings
}

fn touch_action_name(code: u8) -> Option<&'static str> {
    TOUCH_ACTIONS
        .iter()
        .find(|&&(candidate, _)| candidate == code)
        .map(|&(_, name)| name)
}

fn touch_action_code(name: &str) -> Option<u8> {
    TOUCH_ACTIONS
        .iter()
        .find(|&&(_, candidate)| candidate == name)
        .map(|&(code, _)| code)
}

fn toggle_value(value: u8) -> Option<FieldValue> {
    (value <= 1).then_some(FieldValue::Toggle(value == 1))
}

fn noise_level_value(wire: u8) -> Option<FieldValue> {
    NOISE_LEVEL_BY_WIRE
        .get(usize::from(wire))
        .map(|&level| FieldValue::Number(level))
}

fn put_reading(
    readings: &mut BTreeMap<HeadsetField, FieldReading>,
    raw: &[u8],
    field: HeadsetField,
    value: Option<FieldValue>,
    index: usize,
) {
    let source = encode_hex(&raw[index..=index]);
    let reading = match value {
        Some(value) => FieldReading::known(value, source),
        None => FieldReading::unknown(source),
    };
    readings.insert(field, reading);
}

fn decode_audio(raw: &[u8], readings: &mut BTreeMap<HeadsetField, FieldReading>) {
    let value = raw[7];
    let mode = match value {
        0 | 2 => Some("aac_sbc"),
        1 => Some("ldac"),
        _ => None,
    };
    put_reading(
        readings,
        raw,
        HeadsetField::AudioMode,
        mode.map(FieldValue::text),
        7,
    );
    put_reading(
        readings,
        raw,
        HeadsetField::Multipoint,
        (value <= 2).then_some(FieldValue::Toggle(value == 0)),
        7,
    );
}

fn eq_write(
    value: &FieldValue,
    capabilities: &HeadsetCapabilities,
) -> Result<F8Write, ProtocolError> {
    let presets = capabilities
        .equalizer
        .as_ref()
        .map(|equalizer| &equalizer.presets)
        .ok_or_else(unsupported_setting)?;
    let preset = match value {
        FieldValue::Text(preset) if presets.contains(preset) => preset.as_str(),
        _ => {
            return Err(ProtocolError::InvalidArgument(
                "均衡器需要该型号支持的预设名称".to_string(),
            ));
        }
    };
    let wire = match preset {
        "hifi" => 0,
        "pop" => 1,
        "rock" => 2,
        _ => {
            return Err(ProtocolError::Unsupported(
                "该型号不支持该均衡器预设".to_string(),
            ));
        }
    };
    Ok(F8Write {
        command: COMMAND_EQ,
        tag: TAG_EQ,
        payload: vec![0, wire],
    })
}

fn language_write(
    value: &FieldValue,
    capabilities: &HeadsetCapabilities,
) -> Result<F8Write, ProtocolError> {
    let language = match value {
        FieldValue::Text(language) if capabilities.prompt_languages.contains(language) => {
            language.as_str()
        }
        _ => {
            return Err(ProtocolError::InvalidArgument(
                "提示音语言需要该型号支持的语言名称".to_string(),
            ));
        }
    };
    Ok(F8Write {
        command: COMMAND_PROMPT_LANGUAGE,
        tag: TAG_PROMPT_LANGUAGE,
        payload: vec![if language == "chinese" { 0 } else { 1 }],
    })
}

fn touch_write(
    value: &FieldValue,
    capabilities: &HeadsetCapabilities,
) -> Result<F8Write, ProtocolError> {
    let (side, gesture, action) =
        validated_touch_assignment(value, capabilities.touch_control.as_ref())?;
    let gesture_index = TOUCH_GESTURES
        .iter()
        .position(|&candidate| candidate == gesture)
        .ok_or_else(|| ProtocolError::InvalidArgument("该触控槽位无效".to_string()))?;
    let slot = gesture_index as u8 * 2 + if side == "left" { 1 } else { 2 };
    let action_code = touch_action_code(action)
        .ok_or_else(|| ProtocolError::Unsupported("该型号不支持此触控动作".to_string()))?;
    Ok(F8Write {
        command: COMMAND_TOUCH,
        tag: TAG_TOUCH,
        payload: vec![slot, 1, action_code],
    })
}

fn toggle_write(command: u8, tag: u8, value: &FieldValue) -> Result<F8Write, ProtocolError> {
    let FieldValue::Toggle(enabled) = value else {
        return Err(ProtocolError::InvalidArgument(
            "该设置需要明确开关".to_string(),
        ));
    };
    Ok(F8Write {
        command,
        tag,
        payload: vec![u8::from(*enabled)],
    })
}

fn prompt_volume_write(
    value: &FieldValue,
    capabilities: &HeadsetCapabilities,
) -> Result<F8Write, ProtocolError> {
    let family_levels = ProtocolFamily::F8.prompt_volume_levels();
    let level = match value {
        FieldValue::Number(level) if family_levels.contains(level) => *level,
        _ => {
            return Err(ProtocolError::InvalidArgument(
                "F8 提示音仅支持 0～9 档".to_string(),
            ));
        }
    };
    if !capabilities.prompt_volume_levels.contains(&level) {
        return Err(ProtocolError::Unsupported(
            "该型号不支持此提示音档位".to_string(),
        ));
    }
    Ok(F8Write {
        command: COMMAND_PROMPT_VOLUME,
        tag: TAG_PROMPT_VOLUME,
        payload: vec![level as u8],
    })
}

fn noise_level_write(
    setting: HeadsetSetting,
    value: &FieldValue,
    capabilities: &HeadsetCapabilities,
) -> Result<F8Write, ProtocolError> {
    let levels = capabilities
        .noise_control
        .as_ref()
        .and_then(|noise| noise.level_capabilities(setting))
        .map(|capability| &capability.levels)
        .ok_or_else(unsupported_setting)?;
    let level = match value {
        FieldValue::Number(level) if levels.contains(level) => *level,
        _ => {
            return Err(ProtocolError::InvalidArgument(
                "档位需要该型号支持的整数档位".to_string(),
            ));
        }
    };
    let wire = NOISE_LEVEL_BY_WIRE
        .iter()
        .position(|&candidate| candidate == level)
        .ok_or_else(|| ProtocolError::InvalidArgument("F8 档位仅支持 1、3、5".to_string()))?
        as u8;
    let (command, tag) = if setting == HeadsetSetting::AncLevel {
        (COMMAND_ANC_LEVEL, TAG_ANC_LEVEL)
    } else {
        (COMMAND_TRANSPARENCY_LEVEL, TAG_TRANSPARENCY_LEVEL)
    };
    Ok(F8Write {
        command,
        tag,
        payload: vec![wire],
    })
}

fn audio_write(
    setting: HeadsetSetting,
    value: &FieldValue,
    capabilities: &HeadsetCapabilities,
) -> Result<F8Write, ProtocolError> {
    let wire = if setting == HeadsetSetting::Multipoint {
        let FieldValue::Toggle(enabled) = value else {
            return Err(ProtocolError::InvalidArgument(
                "多点连接需要明确开关".to_string(),
            ));
        };
        if *enabled { 0 } else { 2 }
    } else {
        let modes = capabilities
            .audio_control
            .as_ref()
            .map(|audio| &audio.modes)
            .ok_or_else(unsupported_setting)?;
        match value {
            FieldValue::Text(mode) if modes.contains(mode) => {
                if mode == "ldac" {
                    1
                } else {
                    2
                }
            }
            _ => {
                return Err(ProtocolError::InvalidArgument(
                    "音频模式需要该型号支持的配置名称".to_string(),
                ));
            }
        }
    };
    Ok(F8Write {
        command: COMMAND_AUDIO,
        tag: TAG_AUDIO,
        payload: vec![wire],
    })
}
