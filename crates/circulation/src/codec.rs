use crate::contract::{
    HandoffStatus, NearbyPeerRecord, PairingOffer, Peer, PeerId, PullOutcome, RemoteError,
    RemoteReading, RemoteSessionReference,
};
use openrose_control::codec::CodecError;
use openrose_headset::HeadsetCommand;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

pub const CIRCULATION_WIRE_FORMAT_VERSION: u8 = 1;

const CIRCULATION_MAGIC: [u8; 3] = *b"ORL";
const HEADER_LEN: usize = 4;

fn encode_framed<T: Serialize>(value: &T) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(256);
    bytes.extend_from_slice(&CIRCULATION_MAGIC);
    bytes.push(CIRCULATION_WIRE_FORMAT_VERSION);
    serde_json::to_writer(&mut bytes, value).expect("circulation wire value always serializes");
    bytes
}

fn decode_framed<T: DeserializeOwned>(bytes: &[u8]) -> Result<T, CodecError> {
    if bytes.len() < HEADER_LEN || bytes[..3] != CIRCULATION_MAGIC {
        return Err(CodecError::Malformed {
            detail: "无效的互联编码帧头".to_string(),
        });
    }
    let version = bytes[3];
    if version != CIRCULATION_WIRE_FORMAT_VERSION {
        return Err(CodecError::UnsupportedVersion {
            expected: CIRCULATION_WIRE_FORMAT_VERSION,
            actual: version,
        });
    }
    serde_json::from_slice(&bytes[HEADER_LEN..]).map_err(|error| CodecError::Malformed {
        detail: error.to_string(),
    })
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum CirculationCommand {
    Directory,
    Offer,
    Pair {
        peer_id: PeerId,
        code: String,
    },
    PairQr {
        qr: String,
    },
    Forget {
        peer_id: PeerId,
    },
    Read {
        peer_id: PeerId,
    },
    Set {
        session: RemoteSessionReference,
        command: HeadsetCommand,
    },
    Handoff {
        address: Option<String>,
    },
    Pull {
        address: String,
    },
}

impl CirculationCommand {
    pub fn encode(&self) -> Vec<u8> {
        encode_framed(self)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, CodecError> {
        decode_framed(bytes)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Enum))]
pub enum CirculationReply {
    Directory {
        paired: Vec<Peer>,
        nearby: Vec<NearbyPeerRecord>,
        offer: Option<PairingOffer>,
    },
    Offer {
        offer: PairingOffer,
    },
    Paired {
        peer: Peer,
    },
    Forgotten,
    Reading {
        reading: RemoteReading,
    },
    Handoff {
        status: HandoffStatus,
    },
    Pulled {
        outcome: PullOutcome,
    },
    Failed {
        error: RemoteError,
    },
}

impl CirculationReply {
    pub fn encode(&self) -> Vec<u8> {
        encode_framed(self)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self, CodecError> {
        decode_framed(bytes)
    }
}
