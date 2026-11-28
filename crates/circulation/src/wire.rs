use crate::contract::{RemoteCommand, RemoteError, RemoteReading, WIRE_VERSION, YieldResponse};
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

pub const MAX_FRAME_BYTES: usize = 64 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WireFrame {
    pub version: u32,
    pub body: WireBody,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum WireBody {
    PairStart { name: String, spake_msg: Vec<u8> },
    PairChallenge { spake_msg: Vec<u8>, confirm: Vec<u8> },
    PairProof { confirm: Vec<u8> },
    PairGrant { name: String, grant: String },
    PairAck,
    PairCommitted,
    Request { grant: String, command: RemoteCommand },
    Reading(RemoteReading),
    Forget { grant: String },
    Ack,
    Error(RemoteError),
    Yield { grant: String, address: String },
    YieldReply(YieldResponse),
}

#[derive(Deserialize)]
struct VersionHeader {
    version: u32,
}

pub async fn send_frame<S: AsyncWrite + Unpin>(
    stream: &mut S,
    body: WireBody,
) -> Result<(), RemoteError> {
    let frame = WireFrame {
        version: WIRE_VERSION,
        body,
    };
    send_raw_frame(stream, &frame).await
}

pub async fn send_raw_frame<S: AsyncWrite + Unpin>(
    stream: &mut S,
    frame: &WireFrame,
) -> Result<(), RemoteError> {
    let bytes = serde_json::to_vec(frame).map_err(|_| RemoteError::Protocol)?;
    if bytes.is_empty() || bytes.len() > MAX_FRAME_BYTES {
        return Err(RemoteError::Protocol);
    }
    let len = u32::try_from(bytes.len()).map_err(|_| RemoteError::Protocol)?;
    stream
        .write_all(&len.to_be_bytes())
        .await
        .map_err(|_| RemoteError::Protocol)?;
    stream
        .write_all(&bytes)
        .await
        .map_err(|_| RemoteError::Protocol)?;
    stream.flush().await.map_err(|_| RemoteError::Protocol)?;
    Ok(())
}

pub async fn recv_frame<S: AsyncRead + AsyncWrite + Unpin>(
    stream: &mut S,
) -> Result<WireBody, RemoteError> {
    let mut len_buf = [0u8; 4];
    stream
        .read_exact(&mut len_buf)
        .await
        .map_err(|_| RemoteError::Protocol)?;
    let len = u32::from_be_bytes(len_buf) as usize;
    if len == 0 || len > MAX_FRAME_BYTES {
        return Err(RemoteError::Protocol);
    }
    let mut buf = vec![0u8; len];
    stream
        .read_exact(&mut buf)
        .await
        .map_err(|_| RemoteError::Protocol)?;
    let header: VersionHeader =
        serde_json::from_slice(&buf).map_err(|_| RemoteError::Protocol)?;
    if header.version != WIRE_VERSION {
        let _ = send_frame(stream, WireBody::Error(RemoteError::UnsupportedVersion)).await;
        return Err(RemoteError::UnsupportedVersion);
    }
    let frame: WireFrame = serde_json::from_slice(&buf).map_err(|_| RemoteError::Protocol)?;
    Ok(frame.body)
}
