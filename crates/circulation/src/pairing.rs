use crate::contract::{PairingOffer, Peer, PeerId, RemoteError};
use crate::identity::constant_time_eq;
use crate::trust::{PeerGrant, PeerTrust};
use crate::wire::{WireBody, recv_frame, send_frame};
use openrose_headset::encode_hex;
use ring::rand::{SecureRandom, SystemRandom};
use sha2::{Digest, Sha256};
use spake2::{Ed25519Group, Identity, Password, Spake2};
use std::net::IpAddr;
use std::sync::Mutex;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::time::Instant;

pub const PAIRING_WINDOW_TTL: Duration = Duration::from_secs(120);
pub const MAX_PAIRING_ATTEMPTS: u32 = 5;
const MAX_PAIRING_NAME_LEN: usize = 128;

struct WindowState {
    code: String,
    expires_at: Instant,
    attempts: u32,
}

pub struct PairingWindow {
    state: Mutex<Option<WindowState>>,
}

impl Default for PairingWindow {
    fn default() -> Self {
        Self::new()
    }
}

impl PairingWindow {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(None),
        }
    }

    pub fn open(
        &self,
        id: &PeerId,
        addresses: &[IpAddr],
        port: u16,
    ) -> Result<PairingOffer, RemoteError> {
        let mut raw = [0u8; 4];
        SystemRandom::new()
            .fill(&mut raw)
            .map_err(|_| RemoteError::IdentityError)?;
        let value = u32::from_be_bytes(raw) % 1_000_000;
        let code = format!("{value:06}");
        let expires_at = Instant::now() + PAIRING_WINDOW_TTL;
        let mut guard = self.state.lock().expect("pairing window lock");
        *guard = Some(WindowState {
            code: code.clone(),
            expires_at,
            attempts: 0,
        });
        Ok(PairingOffer {
            qr: encode_qr_payload(id, &code, addresses, port),
            code,
            valid_for_millis: PAIRING_WINDOW_TTL.as_millis() as u64,
        })
    }

    pub fn current(
        &self,
        id: &PeerId,
        addresses: &[IpAddr],
        port: u16,
    ) -> Option<PairingOffer> {
        let guard = self.state.lock().expect("pairing window lock");
        let state = guard.as_ref()?;
        let now = Instant::now();
        if now >= state.expires_at || state.attempts >= MAX_PAIRING_ATTEMPTS {
            return None;
        }
        let remaining = state.expires_at.saturating_duration_since(now);
        Some(PairingOffer {
            qr: encode_qr_payload(id, &state.code, addresses, port),
            code: state.code.clone(),
            valid_for_millis: remaining.as_millis() as u64,
        })
    }

    pub fn try_pairing(&self) -> Result<String, RemoteError> {
        let mut guard = self.state.lock().expect("pairing window lock");
        let Some(state) = guard.as_mut() else {
            return Err(RemoteError::PairingNotOpen);
        };
        if Instant::now() >= state.expires_at {
            return Err(RemoteError::PairingExpired);
        }
        state.attempts = state.attempts.saturating_add(1);
        if state.attempts > MAX_PAIRING_ATTEMPTS {
            return Err(RemoteError::PairingAttemptsExceeded);
        }
        Ok(state.code.clone())
    }

    pub fn finish(&self, code: &str) -> Result<(), RemoteError> {
        let mut guard = self.state.lock().expect("pairing window lock");
        let Some(state) = guard.as_ref() else {
            return Err(RemoteError::PairingNotOpen);
        };
        if Instant::now() >= state.expires_at {
            *guard = None;
            return Err(RemoteError::PairingExpired);
        }
        if !constant_time_eq(state.code.as_bytes(), code.as_bytes()) {
            return Err(RemoteError::PairingRejected);
        }
        *guard = None;
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QrPayload {
    pub peer_id: PeerId,
    pub code: String,
    pub addresses: Vec<IpAddr>,
    pub port: u16,
}

pub fn is_valid_pairing_code(code: &str) -> bool {
    code.len() == 6 && code.bytes().all(|b| b.is_ascii_digit())
}

pub fn encode_qr_payload(
    id: &PeerId,
    code: &str,
    addresses: &[IpAddr],
    port: u16,
) -> String {
    let mut out = format!("openrose://pair?v=1&id={}&code={code}&port={port}", id.as_str());
    for addr in addresses {
        out.push_str("&addr=");
        out.push_str(&addr.to_string());
    }
    out
}

pub fn parse_qr_payload(qr: &str) -> Result<QrPayload, RemoteError> {
    let query = qr
        .strip_prefix("openrose://pair?")
        .ok_or(RemoteError::InvalidQrPayload)?;
    let mut version: Option<&str> = None;
    let mut peer_id: Option<PeerId> = None;
    let mut code: Option<String> = None;
    let mut port: Option<u16> = None;
    let mut addresses = Vec::new();
    for pair in query.split('&') {
        let (key, value) = pair.split_once('=').ok_or(RemoteError::InvalidQrPayload)?;
        match key {
            "v" => version = Some(value),
            "id" => {
                let candidate = PeerId::new(value);
                if !candidate.is_valid() {
                    return Err(RemoteError::InvalidQrPayload);
                }
                peer_id = Some(candidate);
            }
            "code" => {
                if !is_valid_pairing_code(value) {
                    return Err(RemoteError::InvalidQrPayload);
                }
                code = Some(value.to_string());
            }
            "port" => {
                let parsed: u16 = value.parse().map_err(|_| RemoteError::InvalidQrPayload)?;
                if parsed == 0 {
                    return Err(RemoteError::InvalidQrPayload);
                }
                port = Some(parsed);
            }
            "addr" => {
                let ip: IpAddr = value.parse().map_err(|_| RemoteError::InvalidQrPayload)?;
                addresses.push(ip);
            }
            _ => {}
        }
    }
    if version != Some("1") || addresses.is_empty() {
        return Err(RemoteError::InvalidQrPayload);
    }
    Ok(QrPayload {
        peer_id: peer_id.ok_or(RemoteError::InvalidQrPayload)?,
        code: code.ok_or(RemoteError::InvalidQrPayload)?,
        addresses,
        port: port.ok_or(RemoteError::InvalidQrPayload)?,
    })
}

fn transcript_confirmations(
    initiator_fp: &PeerId,
    responder_fp: &PeerId,
    msg_a: &[u8],
    msg_b: &[u8],
    shared_key: &[u8],
) -> ([u8; 32], [u8; 32]) {
    let mut h = Sha256::new();
    h.update(b"openrose-spake2-v1");
    h.update(initiator_fp.as_str().as_bytes());
    h.update(responder_fp.as_str().as_bytes());
    h.update((msg_a.len() as u32).to_be_bytes());
    h.update(msg_a);
    h.update((msg_b.len() as u32).to_be_bytes());
    h.update(msg_b);
    h.update((shared_key.len() as u32).to_be_bytes());
    h.update(shared_key);
    let transcript = h.finalize();

    let mut ha = Sha256::new();
    ha.update(b"initiator");
    ha.update(transcript);
    let confirm_a: [u8; 32] = ha.finalize().into();

    let mut hb = Sha256::new();
    hb.update(b"responder");
    hb.update(transcript);
    let confirm_b: [u8; 32] = hb.finalize().into();

    (confirm_a, confirm_b)
}

fn truncate_name(name: &str) -> String {
    name.chars().take(MAX_PAIRING_NAME_LEN).collect()
}

pub async fn initiate_pairing<S: AsyncRead + AsyncWrite + Unpin>(
    stream: &mut S,
    local_id: &PeerId,
    local_name: &str,
    remote_id: &PeerId,
    code: &str,
    trust: &PeerTrust,
) -> Result<PeerGrant, RemoteError> {
    if !is_valid_pairing_code(code) {
        return Err(RemoteError::InvalidPairingCode);
    }
    let (state, msg_a) = Spake2::<Ed25519Group>::start_a(
        &Password::new(code.as_bytes()),
        &Identity::new(local_id.as_str().as_bytes()),
        &Identity::new(remote_id.as_str().as_bytes()),
    );
    send_frame(
        stream,
        WireBody::PairStart {
            name: truncate_name(local_name),
            spake_msg: msg_a.clone(),
        },
    )
    .await?;

    let (msg_b, confirm_b) = match recv_frame(stream).await? {
        WireBody::PairChallenge { spake_msg, confirm } => (spake_msg, confirm),
        WireBody::Error(err) => return Err(err),
        _ => return Err(RemoteError::Protocol),
    };

    let shared_key = state
        .finish(&msg_b)
        .map_err(|_| RemoteError::PairingRejected)?;
    let (confirm_a, expected_b) =
        transcript_confirmations(local_id, remote_id, &msg_a, &msg_b, &shared_key);
    if !constant_time_eq(&confirm_b, &expected_b) {
        return Err(RemoteError::PairingRejected);
    }

    send_frame(
        stream,
        WireBody::PairProof {
            confirm: confirm_a.to_vec(),
        },
    )
    .await?;

    let (remote_name, token) = match recv_frame(stream).await? {
        WireBody::PairGrant { name, grant } => (name, grant),
        WireBody::Error(err) => return Err(err),
        _ => return Err(RemoteError::Protocol),
    };

    send_frame(stream, WireBody::PairAck).await?;

    match recv_frame(stream).await? {
        WireBody::PairCommitted => {}
        WireBody::Error(err) => return Err(err),
        _ => return Err(RemoteError::Protocol),
    }

    let grant = PeerGrant {
        peer: Peer {
            id: remote_id.clone(),
            name: truncate_name(&remote_name),
        },
        token,
    };
    trust.add(grant.clone())?;
    Ok(grant)
}

pub struct PairingRequest {
    pub peer_id: PeerId,
    pub name: String,
    pub spake_msg: Vec<u8>,
}

pub async fn respond_pairing<S: AsyncRead + AsyncWrite + Unpin>(
    stream: &mut S,
    local_id: &PeerId,
    local_name: &str,
    request: PairingRequest,
    window: &PairingWindow,
    trust: &PeerTrust,
) -> Result<PeerGrant, RemoteError> {
    let code = match window.try_pairing() {
        Ok(code) => code,
        Err(err) => {
            let _ = send_frame(stream, WireBody::Error(err.clone())).await;
            return Err(err);
        }
    };

    let (state, msg_b) = Spake2::<Ed25519Group>::start_b(
        &Password::new(code.as_bytes()),
        &Identity::new(request.peer_id.as_str().as_bytes()),
        &Identity::new(local_id.as_str().as_bytes()),
    );
    let shared_key = match state.finish(&request.spake_msg) {
        Ok(key) => key,
        Err(_) => {
            let _ = send_frame(stream, WireBody::Error(RemoteError::PairingRejected)).await;
            return Err(RemoteError::PairingRejected);
        }
    };
    let (expected_a, confirm_b) =
        transcript_confirmations(&request.peer_id, local_id, &request.spake_msg, &msg_b, &shared_key);

    send_frame(
        stream,
        WireBody::PairChallenge {
            spake_msg: msg_b,
            confirm: confirm_b.to_vec(),
        },
    )
    .await?;

    let confirm_a = match recv_frame(stream).await? {
        WireBody::PairProof { confirm } => confirm,
        WireBody::Error(err) => return Err(err),
        _ => return Err(RemoteError::Protocol),
    };
    if !constant_time_eq(&confirm_a, &expected_a) {
        let _ = send_frame(stream, WireBody::Error(RemoteError::PairingRejected)).await;
        return Err(RemoteError::PairingRejected);
    }

    let mut token_bytes = [0u8; 32];
    SystemRandom::new()
        .fill(&mut token_bytes)
        .map_err(|_| RemoteError::IdentityError)?;
    let pending = PeerGrant {
        peer: Peer {
            id: request.peer_id,
            name: truncate_name(&request.name),
        },
        token: encode_hex(&token_bytes),
    };

    send_frame(
        stream,
        WireBody::PairGrant {
            name: truncate_name(local_name),
            grant: pending.token.clone(),
        },
    )
    .await?;

    match recv_frame(stream).await? {
        WireBody::PairAck => {}
        WireBody::Error(err) => return Err(err),
        _ => return Err(RemoteError::Protocol),
    }

    if let Err(err) = window.finish(&code) {
        let _ = send_frame(stream, WireBody::Error(err.clone())).await;
        return Err(err);
    }
    if let Err(err) = trust.add(pending.clone()) {
        let _ = send_frame(stream, WireBody::Error(err.clone())).await;
        return Err(err);
    }
    send_frame(stream, WireBody::PairCommitted).await?;
    Ok(pending)
}
