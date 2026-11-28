use crate::contract::{PeerId, RemoteError};
use openrose_headset::{decode_hex, encode_hex};
use rcgen::{CertificateParams, DistinguishedName, DnType, KeyPair, PKCS_ECDSA_P256_SHA256, SanType};
use ring::rand::{SecureRandom, SystemRandom};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{
    CryptoProvider, WebPkiSupportedAlgorithms, verify_tls12_signature, verify_tls13_signature,
};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{CertificateError, ClientConfig, DigitallySignedStruct, DistinguishedName as RustlsDn, Error as TlsError, ServerConfig, SignatureScheme};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Arc;

const IDENTITY_FILE: &str = "identity.json";

#[derive(Serialize, Deserialize)]
struct StoredIdentity {
    cert_hex: String,
    key_hex: String,
}

pub(crate) fn replace_file_secure(path: &Path, contents: &[u8]) -> Result<(), RemoteError> {
    let parent = path.parent().ok_or(RemoteError::StorageError)?;
    fs::create_dir_all(parent).map_err(|_| RemoteError::StorageError)?;
    let mut suffix = [0u8; 8];
    SystemRandom::new()
        .fill(&mut suffix)
        .map_err(|_| RemoteError::StorageError)?;
    let temp_path = parent.join(format!(".openrose-{}.tmp", encode_hex(&suffix)));
    let write_result = (|| -> std::io::Result<()> {
        let mut options = fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options.open(&temp_path)?;
        file.write_all(contents)?;
        file.sync_all()?;
        fs::rename(&temp_path, path)?;
        Ok(())
    })();
    if write_result.is_err() {
        let _ = fs::remove_file(&temp_path);
        return Err(RemoteError::StorageError);
    }
    Ok(())
}

pub fn cert_fingerprint(cert: &CertificateDer<'_>) -> PeerId {
    let digest = Sha256::digest(cert.as_ref());
    PeerId(encode_hex(&digest))
}

pub struct PeerIdentity {
    id: PeerId,
    cert: CertificateDer<'static>,
    key_der: Vec<u8>,
    provider: Arc<CryptoProvider>,
}

impl PeerIdentity {
    pub fn load_or_create(directory: &Path) -> Result<Self, RemoteError> {
        fs::create_dir_all(directory).map_err(|_| RemoteError::StorageError)?;
        let path: PathBuf = directory.join(IDENTITY_FILE);
        let (cert, key_der) = if path.exists() {
            let bytes = fs::read(&path).map_err(|_| RemoteError::StorageError)?;
            let stored: StoredIdentity =
                serde_json::from_slice(&bytes).map_err(|_| RemoteError::IdentityError)?;
            let cert_bytes =
                decode_hex(&stored.cert_hex).map_err(|_| RemoteError::IdentityError)?;
            let key_bytes =
                decode_hex(&stored.key_hex).map_err(|_| RemoteError::IdentityError)?;
            (CertificateDer::from(cert_bytes), key_bytes)
        } else {
            let key_pair = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)
                .map_err(|_| RemoteError::IdentityError)?;
            let mut params = CertificateParams::default();
            let mut dn = DistinguishedName::new();
            dn.push(DnType::CommonName, "OpenRose");
            params.distinguished_name = dn;
            if let Ok(dns) = "openrose.peer".try_into() {
                params.subject_alt_names = vec![SanType::DnsName(dns)];
            }
            let cert = params
                .self_signed(&key_pair)
                .map_err(|_| RemoteError::IdentityError)?;
            let cert_der = cert.der().to_vec();
            let key_der = key_pair.serialize_der();
            let stored = StoredIdentity {
                cert_hex: encode_hex(&cert_der),
                key_hex: encode_hex(&key_der),
            };
            let encoded =
                serde_json::to_vec(&stored).map_err(|_| RemoteError::IdentityError)?;
            replace_file_secure(&path, &encoded)?;
            (CertificateDer::from(cert_der), key_der)
        };
        let id = cert_fingerprint(&cert);
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        Ok(Self {
            id,
            cert,
            key_der,
            provider,
        })
    }

    pub fn id(&self) -> &PeerId {
        &self.id
    }

    pub fn cert_der(&self) -> &CertificateDer<'static> {
        &self.cert
    }

    fn private_key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key_der.clone()))
    }

    pub fn server_config(&self) -> Result<Arc<ServerConfig>, RemoteError> {
        let verifier = Arc::new(PeerClientCertVerifier {
            algorithms: self.provider.signature_verification_algorithms,
        });
        let config = ServerConfig::builder_with_provider(Arc::clone(&self.provider))
            .with_safe_default_protocol_versions()
            .map_err(|_| RemoteError::IdentityError)?
            .with_client_cert_verifier(verifier)
            .with_single_cert(vec![self.cert.clone()], self.private_key())
            .map_err(|_| RemoteError::IdentityError)?;
        Ok(Arc::new(config))
    }

    pub fn client_config(&self, expected_peer: PeerId) -> Result<Arc<ClientConfig>, RemoteError> {
        let verifier = Arc::new(PinnedServerCertVerifier {
            expected_peer,
            algorithms: self.provider.signature_verification_algorithms,
        });
        let config = ClientConfig::builder_with_provider(Arc::clone(&self.provider))
            .with_safe_default_protocol_versions()
            .map_err(|_| RemoteError::IdentityError)?
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_client_auth_cert(vec![self.cert.clone()], self.private_key())
            .map_err(|_| RemoteError::IdentityError)?;
        Ok(Arc::new(config))
    }
}

#[derive(Debug)]
struct PinnedServerCertVerifier {
    expected_peer: PeerId,
    algorithms: WebPkiSupportedAlgorithms,
}

impl ServerCertVerifier for PinnedServerCertVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        if end_entity.is_empty() || !intermediates.is_empty() {
            return Err(TlsError::InvalidCertificate(CertificateError::UnknownIssuer));
        }
        let actual = cert_fingerprint(end_entity);
        if !constant_time_eq(actual.as_str().as_bytes(), self.expected_peer.as_str().as_bytes()) {
            return Err(TlsError::InvalidCertificate(
                CertificateError::ApplicationVerificationFailure,
            ));
        }
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}

#[derive(Debug)]
struct PeerClientCertVerifier {
    algorithms: WebPkiSupportedAlgorithms,
}

impl ClientCertVerifier for PeerClientCertVerifier {
    fn root_hint_subjects(&self) -> &[RustlsDn] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, TlsError> {
        if end_entity.is_empty() || !intermediates.is_empty() {
            return Err(TlsError::InvalidCertificate(CertificateError::UnknownIssuer));
        }
        Ok(ClientCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}

pub(crate) fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for (&x, &y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    std::hint::black_box(diff) == 0
}
