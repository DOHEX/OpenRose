use crate::contract::{
    HandoffStatus, NearbyPeerRecord, PairingOffer, Peer, PeerId, PullOutcome, RemoteError,
    RemoteReading, RemoteSessionReference,
};
use openrose_control::codec::{CodecError, FrameHeader, decode_with_header, encode_with_header};
use openrose_headset::HeadsetCommand;
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};

pub const CIRCULATION_WIRE_FORMAT_VERSION: u8 = 1;

const CIRCULATION_FRAME: FrameHeader = FrameHeader {
    magic: *b"ORL",
    version: CIRCULATION_WIRE_FORMAT_VERSION,
};

fn encode_framed<T: Serialize>(value: &T) -> Vec<u8> {
    encode_with_header(CIRCULATION_FRAME, value)
}

fn decode_framed<T: DeserializeOwned>(bytes: &[u8]) -> Result<T, CodecError> {
    decode_with_header(CIRCULATION_FRAME, bytes)
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
#[allow(clippy::large_enum_variant)]
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
