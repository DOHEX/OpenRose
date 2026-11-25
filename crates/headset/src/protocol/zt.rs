use crate::capabilities::basic_noise_modes;
use crate::error::ProtocolError;
use crate::field::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting, TouchBinding,
};
use crate::local_time::LocalDateTime;
use crate::hex::{decode_hex, encode_hex};
use crate::model::{BatteryLayout, ModelProfile, ProtocolFamily, ZtProtocol};
use crate::protocol::{
    ProtocolCodec, ProtocolQuery, ProtocolReply, ProtocolSetting, custom_curve_bytes,
    validated_noise_cycle, validated_touch_assignment,
};
use std::collections::{BTreeMap, BTreeSet};

const DEFAULT_INIT_KEYS: &[&str] = &[
    "0101", "0602", "0201", "0603", "0801", "0d01", "0401", "0a01", "0605", "0c01", "0607", "0604",
    "0e03",
];

const ZT_NOISE_LEVELS: [i32; 3] = [1, 3, 5];
const ZT_CYCLE_ORDER: [&str; 4] = ["normal", "transparency", "wind", "anc"];
const ZT_GESTURES: [&str; 4] = ["single", "double", "triple", "long"];
const ZT_ACTIONS: [&str; 8] = [
    "volume_up",
    "volume_down",
    "previous_track",
    "next_track",
    "voice_assistant",
    "play_pause",
    "none",
    "noise_cycle",
];

pub struct ZtCodec {
    profile: ModelProfile,
    protocol: ZtProtocol,
}

impl ZtCodec {
    pub fn new(profile: ModelProfile, protocol: ZtProtocol) -> Self {
        Self { profile, protocol }
    }

    fn custom_equalizer(
        &self,
        value: &FieldValue,
        firmware: Option<&FirmwareVersion>,
    ) -> Result<ProtocolSetting, ProtocolError> {
        let caps = self.profile.capabilities(firmware, None);
        let payload = custom_curve_bytes(
            value,
            caps.equalizer.as_ref().and_then(|eq| eq.custom.as_ref()),
        )?;
        let presets = eq_presets(self.protocol);
        let Some(slot) = presets.iter().position(|&preset| preset == "custom") else {
            return Err(ProtocolError::InvalidArgument(
                "该型号的自定义均衡器没有可选的预设槽位".to_string(),
            ));
        };
        Ok(ProtocolSetting {
            write_frames: vec![frame("0281", &[slot as u8]), frame("0282", &payload)],
            readback: self.query("0201"),
            expected_values: BTreeMap::from([
                (HeadsetField::Eq, FieldValue::text("custom")),
                (HeadsetField::CustomEq, value.clone()),
            ]),
            additional_readbacks: vec![self.query("0202")],
        })
    }

    fn query(&self, key: &str) -> ProtocolQuery {
        let fields = if key == "0101" && self.profile.battery_layout() == BatteryLayout::SingleUnit
        {
            BTreeSet::new()
        } else if key == "0801" && self.protocol.independent_multipoint {
            BTreeSet::from([HeadsetField::AudioMode])
        } else {
            command_fields(key)
        };
        let response_keys =
            if key == "0101" && self.profile.battery_layout() == BatteryLayout::SingleUnit {
                BTreeSet::new()
            } else {
                BTreeSet::from([key.to_string()])
            };
        ProtocolQuery {
            key: key.to_string(),
            bytes: frame(key, &[]),
            response_keys,
            expected_fields: fields,
            delay_before_millis: 0,
        }
    }
}

impl ProtocolCodec for ZtCodec {
    fn profile(&self) -> ModelProfile {
        self.profile
    }

    fn init_queries(
        &mut self,
        max_write_size: usize,
        _now: LocalDateTime,
    ) -> Result<Vec<ProtocolQuery>, ProtocolError> {
        if max_write_size < 10 {
            return Err(ProtocolError::InvalidArgument(
                "ZT queries require a 10-byte write capacity".to_string(),
            ));
        }
        let keys = if self.protocol.init_keys.is_empty() {
            DEFAULT_INIT_KEYS
        } else {
            self.protocol.init_keys
        };
        Ok(keys.iter().map(|&key| self.query(key)).collect())
    }

    fn prepare_setting(
        &mut self,
        setting: HeadsetSetting,
        value: &FieldValue,
        firmware: Option<&FirmwareVersion>,
        _current_fields: &BTreeMap<HeadsetField, FieldReading>,
    ) -> Result<ProtocolSetting, ProtocolError> {
        if setting == HeadsetSetting::CustomEq {
            return self.custom_equalizer(value, firmware);
        }
        let write = encode_setting(self.protocol, self.profile, setting, value, firmware)?;
        let readback = self.query(write.read_key);
        let expected_values = if write.read_key == "0801" && !self.protocol.independent_multipoint {
            let multipoint = write.payload.first().copied() == Some(0);
            BTreeMap::from([
                (
                    HeadsetField::AudioMode,
                    FieldValue::text(if multipoint { "aac_sbc" } else { "ldac" }),
                ),
                (HeadsetField::Multipoint, FieldValue::Toggle(multipoint)),
            ])
        } else {
            let field = *readback
                .expected_fields
                .iter()
                .next()
                .expect("ZT readback must map to a field");
            BTreeMap::from([(field, value.clone())])
        };
        Ok(ProtocolSetting {
            write_frames: vec![frame(write.write_key, &write.payload)],
            readback,
            expected_values,
            additional_readbacks: Vec::new(),
        })
    }

    fn feed_notification(&mut self, bytes: &[u8]) -> Vec<ProtocolReply> {
        vec![decode_reply(bytes, self.profile, self.protocol)]
    }

    fn reset(&mut self) {}
}

struct ZtWrite {
    write_key: &'static str,
    read_key: &'static str,
    payload: Vec<u8>,
}

fn eq_presets(protocol: ZtProtocol) -> &'static [&'static str] {
    if protocol.independent_multipoint {
        &["pop", "hifi", "rock", "custom"]
    } else {
        &["pop", "hifi", "rock", "light"]
    }
}

fn audio_modes(protocol: ZtProtocol) -> &'static [&'static str] {
    if protocol.independent_multipoint {
        &["aac_sbc", "ldac", "lhdc", "ldac_lhdc"]
    } else {
        &["aac_sbc", "ldac"]
    }
}

fn frame(key: &str, payload: &[u8]) -> Vec<u8> {
    let key_bytes = decode_hex(key).expect("ZT command key must be valid hex");
    debug_assert_eq!(2, key_bytes.len());
    let length = (payload.len() + 10) as u16;
    let [len_lo, len_hi] = length.to_le_bytes();
    let mut out = Vec::with_capacity(payload.len() + 10);
    out.extend_from_slice(&[
        0x08,
        0xee,
        0x00,
        0x00,
        0x00,
        key_bytes[0],
        key_bytes[1],
        len_lo,
        len_hi,
    ]);
    out.extend_from_slice(payload);
    let checksum = out.iter().fold(0u8, |acc, &byte| acc.wrapping_add(byte));
    out.push(checksum);
    out
}

fn encode_setting(
    protocol: ZtProtocol,
    profile: ModelProfile,
    setting: HeadsetSetting,
    value: &FieldValue,
    firmware: Option<&FirmwareVersion>,
) -> Result<ZtWrite, ProtocolError> {
    match setting {
        HeadsetSetting::NoiseMode => Ok(ZtWrite {
            write_key: "0682",
            read_key: "0602",
            payload: noise_mode_bytes(protocol, value)?,
        }),
        HeadsetSetting::AncLevel => Ok(ZtWrite {
            write_key: "0687",
            read_key: "0607",
            payload: noise_level_byte(protocol, value)?,
        }),
        HeadsetSetting::TransparencyLevel => Ok(ZtWrite {
            write_key: "0684",
            read_key: "0604",
            payload: noise_level_byte(protocol, value)?,
        }),
        HeadsetSetting::NoiseCycle => Ok(ZtWrite {
            write_key: "0685",
            read_key: "0605",
            payload: noise_cycle_bytes(value)?,
        }),
        HeadsetSetting::Eq => Ok(ZtWrite {
            write_key: "0281",
            read_key: "0201",
            payload: preset_byte(protocol, profile, firmware, value)?,
        }),
        HeadsetSetting::Gain => Ok(ZtWrite {
            write_key: "0283",
            read_key: "0203",
            payload: gain_byte(profile, firmware, value)?,
        }),
        HeadsetSetting::PromptVolume => prompt_volume_frame(protocol, value),
        HeadsetSetting::AudioMode => Ok(ZtWrite {
            write_key: "0881",
            read_key: "0801",
            payload: audio_mode_byte(protocol, value)?,
        }),
        HeadsetSetting::Multipoint => multipoint_frame(protocol, value),
        HeadsetSetting::TouchBinding => Ok(ZtWrite {
            write_key: "0481",
            read_key: "0401",
            payload: touch_bytes(profile, value)?,
        }),
        HeadsetSetting::GameMode => Ok(ZtWrite {
            write_key: "0683",
            read_key: "0603",
            payload: inverted_toggle(value, "游戏模式需要明确开关")?,
        }),
        HeadsetSetting::TouchEnabled => Ok(ZtWrite {
            write_key: "0a81",
            read_key: "0a01",
            payload: inverted_toggle(value, "触控和检测功能需要明确开关")?,
        }),
        HeadsetSetting::EarDetection => Ok(ZtWrite {
            write_key: "0c81",
            read_key: "0c01",
            payload: inverted_toggle(value, "触控和检测功能需要明确开关")?,
        }),
        _ => Err(ProtocolError::Unsupported("该型号不支持此设置".to_string())),
    }
}

fn noise_mode_bytes(protocol: ZtProtocol, value: &FieldValue) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Text(mode) = value else {
        return Err(ProtocolError::InvalidArgument(
            "降噪模式需要模式名称".to_string(),
        ));
    };
    let bytes: Option<&[u8]> = if protocol.three_byte_noise {
        match mode.as_str() {
            "anc" => Some(&[0x01, 0x00, 0x00]),
            "normal" => Some(&[0x00, 0x00, 0x01]),
            "transparency" => Some(&[0x00, 0x01, 0x00]),
            _ => None,
        }
    } else {
        match mode.as_str() {
            "anc" => Some(&[0x01, 0x00, 0x00, 0x00]),
            "normal" => Some(&[0x00, 0x01, 0x00, 0x00]),
            "wind" => Some(&[0x00, 0x00, 0x01, 0x00]),
            "transparency" => Some(&[0x00, 0x00, 0x00, 0x01]),
            _ => None,
        }
    };
    bytes
        .map(|slice| slice.to_vec())
        .ok_or_else(|| ProtocolError::Unsupported("该型号不支持该降噪模式".to_string()))
}

fn noise_level_byte(protocol: ZtProtocol, value: &FieldValue) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Number(level) = value else {
        return Err(ProtocolError::InvalidArgument("档位需要整数".to_string()));
    };
    let Some(index) = ZT_NOISE_LEVELS.iter().position(|&item| item == *level) else {
        return Err(ProtocolError::InvalidArgument(
            "ZT 档位仅支持 1、3、5".to_string(),
        ));
    };
    Ok(vec![(index as i32 + protocol.level_write_offset) as u8])
}

fn noise_cycle_bytes(value: &FieldValue) -> Result<Vec<u8>, ProtocolError> {
    let flags = validated_noise_cycle(value, &basic_noise_modes())?;
    Ok(ZT_CYCLE_ORDER
        .iter()
        .map(|&mode| u8::from(*flags.get(mode).unwrap_or(&false)))
        .collect())
}

fn preset_byte(
    protocol: ZtProtocol,
    profile: ModelProfile,
    firmware: Option<&FirmwareVersion>,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Text(preset) = value else {
        return Err(ProtocolError::InvalidArgument(
            "均衡器需要预设名称".to_string(),
        ));
    };
    let presets = eq_presets(protocol);
    let supported = profile
        .capabilities(firmware, None)
        .equalizer
        .is_some_and(|eq| eq.presets.contains(preset));
    let Some(index) = presets.iter().position(|&item| item == preset.as_str()) else {
        return Err(ProtocolError::Unsupported(
            "该型号不支持该均衡器预设".to_string(),
        ));
    };
    if !supported {
        return Err(ProtocolError::Unsupported(
            "该型号不支持该均衡器预设".to_string(),
        ));
    }
    Ok(vec![index as u8])
}

fn gain_byte(
    profile: ModelProfile,
    firmware: Option<&FirmwareVersion>,
    value: &FieldValue,
) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Number(level) = value else {
        return Err(ProtocolError::InvalidArgument(
            "该型号不支持此增益档位".to_string(),
        ));
    };
    if !profile
        .capabilities(firmware, None)
        .gain_levels
        .contains(level)
    {
        return Err(ProtocolError::InvalidArgument(
            "该型号不支持此增益档位".to_string(),
        ));
    }
    Ok(vec![(*level + 1) as u8])
}

fn prompt_volume_frame(protocol: ZtProtocol, value: &FieldValue) -> Result<ZtWrite, ProtocolError> {
    let FieldValue::Number(level) = value else {
        return Err(ProtocolError::InvalidArgument(
            "ZT 提示音仅支持 1～5 档".to_string(),
        ));
    };
    if !ProtocolFamily::Zt.prompt_volume_levels().contains(level) {
        return Err(ProtocolError::InvalidArgument(
            "ZT 提示音仅支持 1～5 档".to_string(),
        ));
    }
    if protocol.prompt_volume_direct {
        Ok(ZtWrite {
            write_key: "0e03",
            read_key: "0e03",
            payload: vec![*level as u8],
        })
    } else {
        Ok(ZtWrite {
            write_key: "0e83",
            read_key: "0e03",
            payload: vec![(*level - 1) as u8],
        })
    }
}

fn audio_mode_byte(protocol: ZtProtocol, value: &FieldValue) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Text(mode) = value else {
        return Err(ProtocolError::InvalidArgument(
            "音频模式需要配置名称".to_string(),
        ));
    };
    let modes = audio_modes(protocol);
    let Some(index) = modes.iter().position(|&item| item == mode.as_str()) else {
        return Err(ProtocolError::Unsupported(
            "该型号不支持该音频配置".to_string(),
        ));
    };
    Ok(vec![index as u8])
}

fn multipoint_frame(protocol: ZtProtocol, value: &FieldValue) -> Result<ZtWrite, ProtocolError> {
    let FieldValue::Toggle(enabled) = value else {
        return Err(ProtocolError::InvalidArgument(
            "多点连接需要明确开关".to_string(),
        ));
    };
    if protocol.independent_multipoint {
        Ok(ZtWrite {
            write_key: "0882",
            read_key: "0802",
            payload: vec![u8::from(*enabled)],
        })
    } else {
        Ok(ZtWrite {
            write_key: "0881",
            read_key: "0801",
            payload: vec![u8::from(!*enabled)],
        })
    }
}

fn touch_bytes(profile: ModelProfile, value: &FieldValue) -> Result<Vec<u8>, ProtocolError> {
    let caps = profile.capabilities(None, None);
    let (side, gesture, action) = validated_touch_assignment(value, caps.touch_control.as_ref())?;
    let side_byte = u8::from(side != "left");
    let gesture_byte = ZT_GESTURES
        .iter()
        .position(|&g| g == gesture)
        .expect("validated ZT touch gesture") as u8;
    let action_byte = ZT_ACTIONS
        .iter()
        .position(|&a| a == action)
        .expect("validated ZT touch action") as u8;
    Ok(vec![side_byte, gesture_byte, action_byte])
}

fn inverted_toggle(value: &FieldValue, requirement: &str) -> Result<Vec<u8>, ProtocolError> {
    let FieldValue::Toggle(enabled) = value else {
        return Err(ProtocolError::InvalidArgument(requirement.to_string()));
    };
    Ok(vec![u8::from(!*enabled)])
}

fn command_fields(key: &str) -> BTreeSet<HeadsetField> {
    match key {
        "0101" => BTreeSet::from([
            HeadsetField::LeftBattery,
            HeadsetField::RightBattery,
            HeadsetField::CaseBattery,
            HeadsetField::LeftCharging,
            HeadsetField::RightCharging,
        ]),
        "0602" => BTreeSet::from([HeadsetField::NoiseMode]),
        "0201" => BTreeSet::from([HeadsetField::Eq]),
        "0603" => BTreeSet::from([HeadsetField::GameMode]),
        "0801" => BTreeSet::from([HeadsetField::AudioMode, HeadsetField::Multipoint]),
        "0d01" => BTreeSet::from([HeadsetField::Firmware]),
        "0401" => BTreeSet::from([HeadsetField::TouchKeys]),
        "0a01" => BTreeSet::from([HeadsetField::TouchEnabled]),
        "0605" => BTreeSet::from([HeadsetField::NoiseCycle]),
        "0c01" => BTreeSet::from([HeadsetField::EarDetection]),
        "0607" => BTreeSet::from([HeadsetField::AncLevel]),
        "0604" => BTreeSet::from([HeadsetField::TransparencyLevel]),
        "0e03" => BTreeSet::from([HeadsetField::PromptVolume]),
        "0103" => BTreeSet::from([HeadsetField::CaseBattery]),
        "0202" => BTreeSet::from([HeadsetField::CustomEq]),
        "0203" => BTreeSet::from([HeadsetField::Gain]),
        "0802" => BTreeSet::from([HeadsetField::Multipoint]),
        "0803" => BTreeSet::new(),
        "0b01" => BTreeSet::from([HeadsetField::HideSearch]),
        _ => BTreeSet::new(),
    }
}

pub(crate) fn zt_readable_fields() -> BTreeSet<HeadsetField> {
    let mut fields = BTreeSet::new();
    for key in [
        "0101", "0602", "0201", "0603", "0801", "0d01", "0401", "0a01", "0605", "0c01", "0607",
        "0604", "0e03", "0103", "0202", "0203", "0802", "0803", "0b01",
    ] {
        fields.extend(command_fields(key));
    }
    fields
}

fn minimum_reply_size(command: &str) -> Option<usize> {
    match command {
        "0101" => Some(16),
        "0602" => Some(14),
        "0201" | "0603" | "0801" | "0a01" | "0c01" | "0607" | "0604" | "0e03" | "0103" | "0203"
        | "0802" | "0803" | "0b01" => Some(10),
        "0d01" | "0605" => Some(13),
        "0401" => Some(17),
        "0202" => Some(19),
        _ => None,
    }
}

fn decode_reply(bytes: &[u8], profile: ModelProfile, protocol: ZtProtocol) -> ProtocolReply {
    let hex = encode_hex(bytes);
    if bytes.len() < 7 {
        return ProtocolReply::warning(hex, "通知短于命令字段");
    }
    let command = encode_hex(&bytes[5..7]);
    let Some(min_size) = minimum_reply_size(&command) else {
        return ProtocolReply::warning(hex, format!("未知字段 {command}"));
    };
    if bytes.len() < min_size {
        return ProtocolReply::warning(hex, format!("{command} 字段被截断"));
    }
    let readings = decode_command_readings(&command, bytes, profile, protocol);
    ProtocolReply::new(hex, BTreeSet::from([command]), readings, Vec::new())
}

fn decode_command_readings(
    command: &str,
    bytes: &[u8],
    profile: ModelProfile,
    protocol: ZtProtocol,
) -> BTreeMap<HeadsetField, FieldReading> {
    let first = bytes[9];
    let nibble = first & 0x0f;
    let first_hex = encode_hex(&[first]);

    let scalar = |value: Option<FieldValue>| match value {
        Some(v) => FieldReading::known(v, first_hex.clone()),
        None => FieldReading::unknown(first_hex.clone()),
    };
    let toggle = |on: u8| {
        scalar(if nibble <= 1 {
            Some(FieldValue::Toggle(nibble == on))
        } else {
            None
        })
    };
    let choice = |options: &[&str]| {
        scalar(
            options
                .get(nibble as usize)
                .map(|&name| FieldValue::text(name)),
        )
    };

    match command {
        "0101" => {
            if profile.battery_layout() == BatteryLayout::SingleUnit {
                return BTreeMap::new();
            }
            let left_charging = bytes[13] != 0;
            let right_charging = bytes[14] != 0;
            BTreeMap::from([
                (HeadsetField::LeftBattery, decode_battery(bytes[11], true)),
                (HeadsetField::RightBattery, decode_battery(bytes[12], true)),
                (
                    HeadsetField::CaseBattery,
                    decode_battery(bytes[15], left_charging || right_charging),
                ),
                (
                    HeadsetField::LeftCharging,
                    FieldReading::known(
                        FieldValue::Toggle(left_charging),
                        encode_hex(&[bytes[13]]),
                    ),
                ),
                (
                    HeadsetField::RightCharging,
                    FieldReading::known(
                        FieldValue::Toggle(right_charging),
                        encode_hex(&[bytes[14]]),
                    ),
                ),
            ])
        }
        "0602" => {
            let tail = encode_hex(&bytes[9..]);
            let reading = match tail.as_str() {
                "0100000020" => FieldReading::known(FieldValue::text("anc"), tail),
                "0001000020" => FieldReading::known(FieldValue::text("normal"), tail),
                "0000010020" => FieldReading::known(FieldValue::text("wind"), tail),
                "0000000120" => FieldReading::known(FieldValue::text("transparency"), tail),
                "0200000021" => FieldReading::known(FieldValue::text("auto"), tail),
                _ => FieldReading::unknown(tail),
            };
            BTreeMap::from([(HeadsetField::NoiseMode, reading)])
        }
        "0201" => BTreeMap::from([(HeadsetField::Eq, choice(eq_presets(protocol)))]),
        "0603" => BTreeMap::from([(HeadsetField::GameMode, toggle(0))]),
        "0801" => {
            let mut map =
                BTreeMap::from([(HeadsetField::AudioMode, choice(audio_modes(protocol)))]);
            if !protocol.independent_multipoint {
                map.insert(HeadsetField::Multipoint, toggle(0));
            }
            map
        }
        "0d01" => {
            let parts = vec![
                i32::from(bytes[9] & 0x0f),
                i32::from(bytes[10] & 0x0f),
                i32::from(bytes[11] & 0x0f),
            ];
            let firmware = FirmwareVersion::from_parts(parts);
            BTreeMap::from([(
                HeadsetField::Firmware,
                FieldReading::known(FieldValue::Firmware(firmware), encode_hex(&bytes[9..12])),
            )])
        }
        "0401" => {
            let bindings = (0..8)
                .map(|idx| {
                    let action_code = bytes[9 + idx] & 0x0f;
                    TouchBinding {
                        side: if idx < 4 { "left" } else { "right" }.to_string(),
                        gesture: ZT_GESTURES[idx % 4].to_string(),
                        action: ZT_ACTIONS
                            .get(action_code as usize)
                            .map(|&name| name.to_string()),
                        raw_action: i32::from(action_code),
                    }
                })
                .collect();
            BTreeMap::from([(
                HeadsetField::TouchKeys,
                FieldReading::known(FieldValue::TouchKeys(bindings), encode_hex(&bytes[9..17])),
            )])
        }
        "0a01" => BTreeMap::from([(HeadsetField::TouchEnabled, toggle(0))]),
        "0605" => {
            let flags = ZT_CYCLE_ORDER
                .iter()
                .enumerate()
                .map(|(idx, &name)| {
                    let flag = bytes[9 + idx] & 0x0f;
                    let value = if flag <= 1 { Some(flag == 1) } else { None };
                    (name.to_string(), value)
                })
                .collect();
            BTreeMap::from([(
                HeadsetField::NoiseCycle,
                FieldReading::known(FieldValue::NoiseCycle(flags), encode_hex(&bytes[9..13])),
            )])
        }
        "0c01" => BTreeMap::from([(HeadsetField::EarDetection, toggle(0))]),
        "0607" => BTreeMap::from([(
            HeadsetField::AncLevel,
            scalar(
                ZT_NOISE_LEVELS
                    .get(first as usize)
                    .copied()
                    .map(FieldValue::Number),
            ),
        )]),
        "0604" => BTreeMap::from([(
            HeadsetField::TransparencyLevel,
            scalar(
                ZT_NOISE_LEVELS
                    .get(first as usize)
                    .copied()
                    .map(FieldValue::Number),
            ),
        )]),
        "0e03" => {
            let level = if protocol.prompt_volume_direct {
                i32::from(first)
            } else {
                i32::from(nibble) + 1
            };
            let value = if (1..=5).contains(&level) {
                Some(FieldValue::Number(level))
            } else {
                None
            };
            BTreeMap::from([(HeadsetField::PromptVolume, scalar(value))])
        }
        "0103" => {
            if profile.battery_layout() == BatteryLayout::SingleUnit {
                BTreeMap::from([(HeadsetField::CaseBattery, decode_battery(first, true))])
            } else {
                BTreeMap::new()
            }
        }
        "0202" => {
            let gains = (9..19)
                .map(|offset| {
                    let encoded = bytes[offset];
                    if encoded & 0x80 != 0 {
                        -i32::from(encoded & 0x7f)
                    } else {
                        i32::from(encoded)
                    }
                })
                .collect();
            BTreeMap::from([(
                HeadsetField::CustomEq,
                FieldReading::known(FieldValue::IntegerList(gains), encode_hex(&bytes[9..19])),
            )])
        }
        "0203" => {
            let level = i32::from(first) - 1;
            let value = if (0..=2).contains(&level) {
                Some(FieldValue::Number(level))
            } else {
                None
            };
            BTreeMap::from([(HeadsetField::Gain, scalar(value))])
        }
        "0802" => BTreeMap::from([(HeadsetField::Multipoint, toggle(1))]),
        "0803" => BTreeMap::new(),
        "0b01" => BTreeMap::from([(HeadsetField::HideSearch, scalar(None))]),
        _ => BTreeMap::new(),
    }
}

fn decode_battery(value: u8, available: bool) -> FieldReading {
    let raw = encode_hex(&[value]);
    if value == 0xff {
        FieldReading::not_provided(raw)
    } else if available && (1..=100).contains(&value) {
        FieldReading::known(FieldValue::Number(i32::from(value)), raw)
    } else {
        FieldReading::unknown(raw)
    }
}
