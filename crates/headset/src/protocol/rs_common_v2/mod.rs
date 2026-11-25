mod readings;
pub(crate) mod wire;
mod write;

pub(crate) use readings::{read_record, record_fields, record_ids, rs_common_v2_readable_fields};

use self::wire::{DEFAULT_INFO_IDS, key_hex};
use self::write::rs_v2_setting;
use crate::error::ProtocolError;
use crate::field::{FieldReading, FieldValue, FirmwareVersion, HeadsetField, HeadsetSetting};
use crate::hex::{decode_hex, encode_hex};
use crate::local_time::LocalDateTime;
use crate::model::{CapabilityFacts, ModelProfile, RsCommonV2Protocol};
use crate::protocol::{
    ProtocolCodec, ProtocolQuery, ProtocolReply, ProtocolSetting, custom_curve_bytes,
};
use std::collections::{BTreeMap, BTreeSet};

const MAX_RECEIVE_BUFFER: usize = 8192;
const REQUEST_HEADER: u8 = 0xff;
const RESPONSE_HEADER: u8 = 0xdd;
const FRAME_TRAILER: u8 = 0xaa;
const INFO_REQUEST: u8 = 0xfa;
const EQUALIZER_ID: u8 = 0x2a;
const CUSTOM_CURVE_ID: u8 = 0x3e;
const CLOCK_ID: u8 = 0x35;
const EAR_ADAPTIVE_ID: u8 = 0x3a;
const SPATIAL_MODE_ID: u8 = 0x38;

pub struct RsCommonV2Codec {
    profile: ModelProfile,
    protocol: RsCommonV2Protocol,
    info_types: Vec<u8>,
    sequence: u8,
    buffer: Vec<u8>,
}

impl RsCommonV2Codec {
    pub fn new(profile: ModelProfile, protocol: RsCommonV2Protocol) -> Self {
        let info_types = decode_hex(protocol.info_ids.unwrap_or(DEFAULT_INFO_IDS))
            .expect("RS Common V2 info ids must be valid hex");
        Self {
            profile,
            protocol,
            info_types,
            sequence: 0,
            buffer: Vec::new(),
        }
    }

    fn frame(&mut self, body: &[u8]) -> Vec<u8> {
        debug_assert!(body.len() <= usize::from(u8::MAX));
        let mut out = Vec::with_capacity(body.len() + 5);
        out.extend_from_slice(&[REQUEST_HEADER, self.sequence, body.len() as u8]);
        out.extend_from_slice(body);
        self.sequence = self.sequence.wrapping_add(1);
        out.push(checksum(&out));
        out.push(FRAME_TRAILER);
        out
    }

    fn query(
        &mut self,
        ids: &[u8],
        expected_fields: Option<BTreeSet<HeadsetField>>,
    ) -> ProtocolQuery {
        let fields = match &expected_fields {
            Some(fields) => fields.clone(),
            None => self.profile.capabilities(None, None).readable_fields,
        };
        let known_ids: Vec<u8> = ids
            .iter()
            .copied()
            .filter(|&id| record_fields(id).iter().any(|field| fields.contains(field)))
            .collect();
        let response_keys = known_ids.iter().map(|&id| key_hex(id)).collect();
        let expected_fields = expected_fields.unwrap_or_else(|| {
            known_ids
                .iter()
                .flat_map(|&id| record_fields(id).iter().copied())
                .filter(|field| fields.contains(field))
                .collect()
        });
        let mut body = Vec::with_capacity(ids.len() + 1);
        body.push(INFO_REQUEST);
        body.extend_from_slice(ids);
        ProtocolQuery {
            key: format!("fa{}", encode_hex(ids)),
            bytes: self.frame(&body),
            response_keys,
            expected_fields,
            delay_before_millis: 0,
        }
    }

    fn query_curve(&mut self) -> ProtocolQuery {
        self.query(
            &[CUSTOM_CURVE_ID],
            Some(BTreeSet::from([HeadsetField::CustomEq])),
        )
    }

    fn custom_equalizer(
        &mut self,
        value: &FieldValue,
        capabilities: &crate::capabilities::HeadsetCapabilities,
    ) -> Result<ProtocolSetting, ProtocolError> {
        let payload = custom_curve_bytes(
            value,
            capabilities
                .equalizer
                .as_ref()
                .and_then(|eq| eq.custom.as_ref()),
        )?;
        let mut curve_body = vec![CUSTOM_CURVE_ID];
        curve_body.extend(payload);
        let write_frames = vec![self.frame(&[EQUALIZER_ID, 4]), self.frame(&curve_body)];
        let readback = self.query(&[EQUALIZER_ID], None);
        let curve_readback = self.query_curve();
        Ok(ProtocolSetting {
            write_frames,
            readback,
            expected_values: BTreeMap::from([
                (HeadsetField::Eq, FieldValue::text("custom")),
                (HeadsetField::CustomEq, value.clone()),
            ]),
            additional_readbacks: vec![curve_readback],
        })
    }

    fn spatial_follow_up(
        &self,
        setting: HeadsetSetting,
        value: &FieldValue,
        current_fields: &BTreeMap<HeadsetField, FieldReading>,
    ) -> Option<FollowUp> {
        if !self.protocol.spatial_needs_ear_adaptive {
            return None;
        }
        let confirmed = |field: HeadsetField| current_fields.get(&field)?.known_value();
        let on = FieldValue::Toggle(true);
        let off = FieldValue::text("off");
        if setting == HeadsetSetting::SpatialMode
            && *value != off
            && confirmed(HeadsetField::EarAdaptive) != Some(&on)
        {
            return Some(FollowUp {
                body: [EAR_ADAPTIVE_ID, 1],
                field: HeadsetField::EarAdaptive,
                expected: on,
            });
        }
        if setting == HeadsetSetting::EarAdaptive
            && *value == FieldValue::Toggle(false)
            && confirmed(HeadsetField::SpatialMode) != Some(&off)
        {
            return Some(FollowUp {
                body: [SPATIAL_MODE_ID, 0],
                field: HeadsetField::SpatialMode,
                expected: off,
            });
        }
        None
    }

    fn find_complete(&self) -> Option<(usize, ProtocolReply)> {
        (5..self.buffer.len())
            .filter(|&end| self.buffer[end] == FRAME_TRAILER)
            .find_map(|end| {
                parse_frame(&self.buffer[..=end], self.profile).map(|reply| (end + 1, reply))
            })
    }
}

struct FollowUp {
    body: [u8; 2],
    field: HeadsetField,
    expected: FieldValue,
}

impl ProtocolCodec for RsCommonV2Codec {
    fn profile(&self) -> ModelProfile {
        self.profile
    }

    fn init_queries(
        &mut self,
        max_write_size: usize,
        now: LocalDateTime,
    ) -> Result<Vec<ProtocolQuery>, ProtocolError> {
        if max_write_size < 7 {
            return Err(ProtocolError::InvalidArgument(
                "RS Common V2 queries require at least a 7-byte write capacity".to_string(),
            ));
        }
        let clock = self.protocol.synchronize_clock_on_connect.then_some(now);
        let info_types = self.info_types.clone();
        let mut queries: Vec<ProtocolQuery> = info_types
            .chunks(max_write_size - 6)
            .map(|group| self.query(group, None))
            .collect();
        if let Some(now) = clock {
            let body = [
                CLOCK_ID,
                (now.year() / 100) as u8,
                (now.year() % 100) as u8,
                now.month(),
                now.day(),
                now.hour(),
                now.minute(),
                now.second(),
            ];
            queries.push(ProtocolQuery {
                key: "35".to_string(),
                bytes: self.frame(&body),
                response_keys: BTreeSet::new(),
                expected_fields: BTreeSet::new(),
                delay_before_millis: 0,
            });
        }
        if has_custom_curve(&self.profile.capabilities(None, None)) {
            queries.push(self.query_curve());
        }
        Ok(queries)
    }

    fn firmware_init_queries(&mut self, firmware: Option<&FirmwareVersion>) -> Vec<ProtocolQuery> {
        let unlocked = !has_custom_curve(&self.profile.capabilities(None, None))
            && has_custom_curve(&self.profile.capabilities(firmware, None));
        if unlocked {
            vec![self.query_curve()]
        } else {
            Vec::new()
        }
    }

    fn prepare_setting(
        &mut self,
        setting: HeadsetSetting,
        value: &FieldValue,
        firmware: Option<&FirmwareVersion>,
        current_fields: &BTreeMap<HeadsetField, FieldReading>,
    ) -> Result<ProtocolSetting, ProtocolError> {
        let capabilities = CapabilityFacts {
            firmware: firmware.cloned(),
            game_mode: CapabilityFacts::from_readings(current_fields).game_mode,
        }
        .resolve(self.profile);
        if setting == HeadsetSetting::CustomEq {
            return self.custom_equalizer(value, &capabilities);
        }
        let write = rs_v2_setting(&capabilities, setting, value)?;
        let mut body = vec![write.type_id];
        body.extend_from_slice(&write.payload);
        let write_frame = self.frame(&body);
        let readback_fields: BTreeSet<HeadsetField> = write.expected.keys().copied().collect();
        if let Some(follow_up) = self.spatial_follow_up(setting, value, current_fields) {
            let follow_frame = self.frame(&follow_up.body);
            let readback = self.query(&[write.type_id], Some(readback_fields));
            let follow_readback = self.query(
                &[follow_up.body[0]],
                Some(BTreeSet::from([follow_up.field])),
            );
            let mut expected_values = write.expected;
            expected_values.insert(follow_up.field, follow_up.expected);
            return Ok(ProtocolSetting {
                write_frames: vec![write_frame, follow_frame],
                readback,
                expected_values,
                additional_readbacks: vec![follow_readback],
            });
        }
        let readback = self.query(&[write.type_id], Some(readback_fields));
        let refresh_equalizer =
            setting == HeadsetSetting::GameMode && self.protocol.refresh_equalizer_after_game_mode;
        let additional_readbacks = if refresh_equalizer {
            vec![self.query(&[EQUALIZER_ID], None)]
        } else {
            Vec::new()
        };
        Ok(ProtocolSetting {
            write_frames: vec![write_frame],
            readback,
            expected_values: write.expected,
            additional_readbacks,
        })
    }

    fn feed_notification(&mut self, bytes: &[u8]) -> Vec<ProtocolReply> {
        self.buffer.extend_from_slice(bytes);
        let mut replies = Vec::new();
        if self.buffer.len() > MAX_RECEIVE_BUFFER {
            replies.push(ProtocolReply::warning(
                encode_hex(&self.buffer),
                "接收缓冲区超过 8192 字节，已清空",
            ));
            self.buffer.clear();
            return replies;
        }
        while !self.buffer.is_empty() {
            let Some(start) = self.buffer.iter().position(|&byte| byte == RESPONSE_HEADER) else {
                replies.push(ProtocolReply::warning(
                    encode_hex(&self.buffer),
                    "没有 DD 接收帧头",
                ));
                self.buffer.clear();
                break;
            };
            if start > 0 {
                replies.push(ProtocolReply::warning(
                    encode_hex(&self.buffer[..start]),
                    "忽略帧前数据",
                ));
                self.buffer.drain(..start);
            }
            let Some((end, reply)) = self.find_complete() else {
                break;
            };
            replies.push(reply);
            self.buffer.drain(..end);
        }
        replies
    }

    fn reset(&mut self) {
        self.sequence = 0;
        self.buffer.clear();
    }
}

fn has_custom_curve(capabilities: &crate::capabilities::HeadsetCapabilities) -> bool {
    capabilities
        .equalizer
        .as_ref()
        .is_some_and(|eq| eq.custom.is_some())
}

fn checksum(bytes: &[u8]) -> u8 {
    bytes.iter().fold(0u8, |sum, &byte| sum.wrapping_add(byte))
}

fn parse_frame(raw: &[u8], profile: ModelProfile) -> Option<ProtocolReply> {
    let stop = raw.len() - 2;
    let expected_checksum = raw[stop];
    if checksum(&raw[..stop]) != expected_checksum && checksum(&raw[2..stop]) != expected_checksum {
        return None;
    }
    let mut readings = BTreeMap::new();
    let mut keys = BTreeSet::new();
    let mut warnings = Vec::new();
    let mut position = 2;
    while position + 1 < stop {
        let length = usize::from(raw[position]);
        let end = if length == 0 {
            stop
        } else {
            position + length + 1
        };
        if end > stop {
            return None;
        }
        let id = raw[position + 1];
        let value = &raw[position + 2..end];
        if length == 0 {
            warnings.push("L00 兼容字段；客户端仍验证校验字节".to_string());
        }
        let decoded = read_record(id, profile, value);
        if decoded.is_empty() {
            warnings.push(format!("未更新字段 {}={}", key_hex(id), encode_hex(value)));
        } else {
            keys.insert(key_hex(id));
            readings.extend(decoded);
        }
        position = end;
    }
    (position == stop).then(|| ProtocolReply::new(encode_hex(raw), keys, readings, warnings))
}
