use crate::snapshot::SessionReference;
use openrose_headset::{HeadsetCommand, HeadsetId};
use serde::{Deserialize, Serialize};
use serde::de::DeserializeOwned;
use std::fmt;

pub const WIRE_FORMAT_VERSION: u8 = 1;

const WIRE_MAGIC: [u8; 3] = *b"ORC";
const HEADER_LEN: usize = 4;

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Error))]
pub enum CodecError {
    Malformed { detail: String },
    UnsupportedVersion { expected: u8, actual: u8 },
}

impl fmt::Display for CodecError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Malformed { detail } => write!(f, "无法解码核心数据: {detail}"),
            Self::UnsupportedVersion { expected, actual } => {
                write!(f, "核心编码版本不匹配: 期望 {expected}，实际 {actual}")
            }
        }
    }
}

impl std::error::Error for CodecError {}

pub fn encode_framed<T: Serialize>(value: &T) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(256);
    bytes.extend_from_slice(&WIRE_MAGIC);
    bytes.push(WIRE_FORMAT_VERSION);
    serde_json::to_writer(&mut bytes, value).expect("control wire value always serializes");
    bytes
}

pub fn decode_framed<T: DeserializeOwned>(bytes: &[u8]) -> Result<T, CodecError> {
    if bytes.len() < HEADER_LEN || bytes[..3] != WIRE_MAGIC {
        return Err(CodecError::Malformed {
            detail: "无效的核心编码帧头".to_string(),
        });
    }
    let version = bytes[3];
    if version != WIRE_FORMAT_VERSION {
        return Err(CodecError::UnsupportedVersion {
            expected: WIRE_FORMAT_VERSION,
            actual: version,
        });
    }
    serde_json::from_slice(&bytes[HEADER_LEN..]).map_err(|error| CodecError::Malformed {
        detail: error.to_string(),
    })
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum ControlCommand {
    RefreshBondedHeadsets,
    StartScan,
    StopScan,
    SelectCurrentHeadset {
        headset_id: HeadsetId,
    },
    Connect {
        headset_id: HeadsetId,
    },
    ConnectPinned {
        headset_id: HeadsetId,
    },
    ConnectForAddHeadset {
        headset_id: HeadsetId,
    },
    Disconnect,
    Set {
        reference: Option<SessionReference>,
        command: HeadsetCommand,
    },
    ClaimPrompt {
        reference: SessionReference,
        prompt_id: String,
    },
}

impl ControlCommand {
    pub fn encode(&self) -> Vec<u8> {
        encode_framed(self)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, CodecError> {
        decode_framed(bytes)
    }
}
