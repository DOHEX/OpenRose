#[cfg(feature = "uniffi")]
uniffi::setup_scaffolding!();

pub mod codec;
pub mod contract;
pub mod endpoint;
#[cfg(feature = "uniffi")]
mod ffi;
pub mod identity;
pub mod node;
pub mod pairing;
pub mod trust;
pub mod wire;

pub use contract::{
    BatterySlots, DEFAULT_PORT, HandoffStatus, LocalAudio, MAX_ADVERTISED_NAME_LEN,
    MDNS_SERVICE_TYPE, NearbyPeer, NearbyPeerRecord, PairingOffer, Peer, PeerDiscovery, PeerId,
    PullOutcome, PullStage, REMOTE_CONTROL_SETTINGS, RemoteCommand, RemoteControlStatus,
    RemoteError, RemoteReading, RemoteSessionReference, RouteDialer, RouteStream, WIRE_VERSION,
    YieldReceipt, YieldRefusal, YieldResponse, is_remote_control_setting, supports_circulation,
};
pub use endpoint::{HeadsetEndpoint, RemoteHandler};
pub use node::{CirculationConfig, CirculationNode};
