use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HexError {
    pub message: String,
}

impl fmt::Display for HexError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for HexError {}

pub fn encode_hex(bytes: &[u8]) -> String {
    const TABLE: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &byte in bytes {
        out.push(TABLE[(byte >> 4) as usize] as char);
        out.push(TABLE[(byte & 0x0f) as usize] as char);
    }
    out
}

pub fn decode_hex(hex: &str) -> Result<Vec<u8>, HexError> {
    if !hex.len().is_multiple_of(2) {
        return Err(HexError {
            message: format!("hex string must have even length, got {}", hex.len()),
        });
    }
    let bytes = hex.as_bytes();
    let mut out = Vec::with_capacity(bytes.len() / 2);
    for &[high, low] in bytes.as_chunks::<2>().0 {
        let hi = decode_nibble(high)?;
        let lo = decode_nibble(low)?;
        out.push((hi << 4) | lo);
    }
    Ok(out)
}

pub fn is_lower_hex(value: &str) -> bool {
    value
        .bytes()
        .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn decode_nibble(byte: u8) -> Result<u8, HexError> {
    match byte {
        b'0'..=b'9' => Ok(byte - b'0'),
        b'a'..=b'f' => Ok(byte - b'a' + 10),
        b'A'..=b'F' => Ok(byte - b'A' + 10),
        other => Err(HexError {
            message: format!("invalid hex byte: 0x{other:02x}"),
        }),
    }
}
