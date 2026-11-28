use crate::ControlRuntime;
use crate::drivers::DriverError;
use crate::runtime::with_runtime;
use async_trait::async_trait;
use openrose_circulation::codec::{
    CIRCULATION_WIRE_FORMAT_VERSION, CirculationCommand, CirculationReply,
};
use openrose_circulation::node::{CirculationConfig, CirculationNode as CoreCirculationNode};
use openrose_circulation::{
    DiscoveryContract, HandoffStatus, LocalAudio as CoreLocalAudio, NearbyPeer, NearbyPeerRecord,
    PairingOffer, Peer, PeerDiscovery as CorePeerDiscovery, PeerId, PullOutcome, RemoteError,
    RemoteReading, RemoteSessionReference, RouteDialer as CoreRouteDialer, RouteStream,
    WIRE_VERSION, YieldReceipt, YieldRefusal, service_instance_name,
};
use openrose_control::codec::CodecError;
use openrose_headset::HeadsetCommand;
use std::fmt;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::sync::watch;

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum CirculationFailure {
    Failed { error: RemoteError },
}

impl fmt::Display for CirculationFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Failed { error } => write!(f, "{error}"),
        }
    }
}

impl std::error::Error for CirculationFailure {}

impl From<RemoteError> for CirculationFailure {
    fn from(error: RemoteError) -> Self {
        Self::Failed { error }
    }
}

#[derive(uniffi::Object)]
pub struct NearbySink {
    tx: watch::Sender<Vec<NearbyPeer>>,
}

#[uniffi::export]
impl NearbySink {
    pub fn publish(&self, peers: Vec<NearbyPeerRecord>) {
        let converted = peers
            .into_iter()
            .map(|peer| NearbyPeer {
                id: peer.id,
                name: peer.name,
                addresses: peer
                    .addresses
                    .into_iter()
                    .filter_map(|addr| addr.parse::<IpAddr>().ok())
                    .collect(),
                port: peer.port,
            })
            .collect();
        self.tx.send_replace(converted);
    }
}

#[uniffi::export(foreign)]
#[async_trait]
pub trait PeerDiscovery: Send + Sync + 'static {
    async fn start(
        &self,
        peer_id: PeerId,
        name: String,
        port: u16,
        wire_version: u32,
        sink: Arc<NearbySink>,
    ) -> Result<(), DriverError>;

    async fn stop(&self);
}

#[uniffi::export(foreign)]
#[async_trait]
pub trait LocalAudio: Send + Sync + 'static {
    async fn is_connected(&self, address: String) -> Result<bool, DriverError>;

    async fn is_media_connected(&self, address: String) -> Result<bool, DriverError>;

    async fn yield_audio(&self, address: String) -> Result<(), DriverError>;

    async fn acquire(&self, address: String) -> Result<(), DriverError>;
}

#[uniffi::export(foreign)]
#[async_trait]
pub trait PeerRoute: Send + Sync + 'static {
    async fn connect(&self) -> Result<(), DriverError>;

    async fn read(&self) -> Result<Vec<u8>, DriverError>;

    async fn write(&self, value: Vec<u8>) -> Result<(), DriverError>;

    async fn close_link(&self) -> Result<(), DriverError>;
}

#[uniffi::export(foreign)]
pub trait PeerRouteDialer: Send + Sync + 'static {
    fn open_route(&self, address: String, port: u16) -> Result<Arc<dyn PeerRoute>, DriverError>;
}

#[uniffi::export(foreign)]
pub trait OwnerActive: Send + Sync + 'static {
    fn is_active(&self) -> bool;
}

struct PeerDiscoveryAdapter {
    foreign: Arc<dyn PeerDiscovery>,
    sink: Arc<NearbySink>,
}

impl PeerDiscoveryAdapter {
    fn new(foreign: Arc<dyn PeerDiscovery>) -> Self {
        let (tx, _) = watch::channel(Vec::new());
        Self {
            foreign,
            sink: Arc::new(NearbySink { tx }),
        }
    }
}

#[async_trait]
impl CorePeerDiscovery for PeerDiscoveryAdapter {
    async fn start(&self, id: &PeerId, name: &str, port: u16) -> Result<(), RemoteError> {
        self.foreign
            .start(
                id.clone(),
                name.to_string(),
                port,
                WIRE_VERSION,
                Arc::clone(&self.sink),
            )
            .await
            .map_err(|_| RemoteError::DiscoveryError)
    }

    fn watch(&self) -> watch::Receiver<Vec<NearbyPeer>> {
        self.sink.tx.subscribe()
    }

    async fn close(&self) {
        self.foreign.stop().await;
        self.sink.tx.send_replace(Vec::new());
    }
}

struct LocalAudioAdapter(Arc<dyn LocalAudio>);

#[async_trait]
impl CoreLocalAudio for LocalAudioAdapter {
    async fn is_connected(&self, address: &str) -> Result<bool, YieldRefusal> {
        self.0
            .is_connected(address.to_string())
            .await
            .map_err(|error| YieldRefusal::AudioStateUnreadable {
                detail: error.to_string(),
            })
    }

    async fn is_media_connected(&self, address: &str) -> bool {
        self.0
            .is_media_connected(address.to_string())
            .await
            .unwrap_or(false)
    }

    async fn yield_audio(&self, address: &str) -> Result<YieldReceipt, YieldRefusal> {
        self.0
            .yield_audio(address.to_string())
            .await
            .map(|()| YieldReceipt {
                address: address.to_string(),
            })
            .map_err(YieldRefusal::from)
    }

    async fn acquire(&self, address: &str) -> Result<(), YieldRefusal> {
        self.0
            .acquire(address.to_string())
            .await
            .map_err(YieldRefusal::from)
    }
}

struct RouteDialerAdapter(Arc<dyn PeerRouteDialer>);

#[async_trait]
impl CoreRouteDialer for RouteDialerAdapter {
    async fn connect(&self, addr: SocketAddr) -> Result<Box<dyn RouteStream>, RemoteError> {
        let route = self
            .0
            .open_route(addr.ip().to_string(), addr.port())
            .map_err(|_| RemoteError::PeerOffline)?;
        route
            .connect()
            .await
            .map_err(|_| RemoteError::PeerOffline)?;
        Ok(Box::new(PeerRouteStream::new(route)))
    }
}

type ReadFuture = Pin<Box<dyn Future<Output = Result<Vec<u8>, DriverError>> + Send>>;
type WriteFuture = Pin<Box<dyn Future<Output = (usize, Result<(), DriverError>)> + Send>>;
type ShutdownFuture = Pin<Box<dyn Future<Output = Result<(), DriverError>> + Send>>;

struct PeerRouteStream {
    route: Arc<dyn PeerRoute>,
    read_buf: Vec<u8>,
    read_pos: usize,
    read_fut: Option<ReadFuture>,
    write_fut: Option<WriteFuture>,
    shutdown_fut: Option<ShutdownFuture>,
    closed: bool,
}

impl PeerRouteStream {
    fn new(route: Arc<dyn PeerRoute>) -> Self {
        Self {
            route,
            read_buf: Vec::new(),
            read_pos: 0,
            read_fut: None,
            write_fut: None,
            shutdown_fut: None,
            closed: false,
        }
    }

    fn drain_buffered(&mut self, buf: &mut ReadBuf<'_>) -> bool {
        if self.read_pos >= self.read_buf.len() {
            return false;
        }
        let available = self.read_buf.len() - self.read_pos;
        let to_copy = available.min(buf.remaining());
        buf.put_slice(&self.read_buf[self.read_pos..self.read_pos + to_copy]);
        self.read_pos += to_copy;
        if self.read_pos == self.read_buf.len() {
            self.read_buf.clear();
            self.read_pos = 0;
        }
        true
    }
}

impl Drop for PeerRouteStream {
    fn drop(&mut self) {
        if !self.closed
            && let Ok(handle) = tokio::runtime::Handle::try_current()
        {
            let route = Arc::clone(&self.route);
            handle.spawn(async move {
                let _ = route.close_link().await;
            });
        }
    }
}

impl AsyncRead for PeerRouteStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if buf.remaining() == 0 || this.drain_buffered(buf) {
            return Poll::Ready(Ok(()));
        }
        if this.read_fut.is_none() {
            let route = Arc::clone(&this.route);
            this.read_fut = Some(Box::pin(async move { route.read().await }));
        }
        match this
            .read_fut
            .as_mut()
            .expect("read future")
            .as_mut()
            .poll(cx)
        {
            Poll::Pending => Poll::Pending,
            Poll::Ready(res) => {
                this.read_fut = None;
                match res {
                    Ok(data) => {
                        if data.is_empty() {
                            return Poll::Ready(Ok(()));
                        }
                        this.read_buf = data;
                        this.read_pos = 0;
                        let _ = this.drain_buffered(buf);
                        Poll::Ready(Ok(()))
                    }
                    Err(err) => Poll::Ready(Err(io::Error::other(err.to_string()))),
                }
            }
        }
    }
}

impl AsyncWrite for PeerRouteStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if this.write_fut.is_none() {
            if buf.is_empty() {
                return Poll::Ready(Ok(0));
            }
            let route = Arc::clone(&this.route);
            let chunk = buf.to_vec();
            let len = chunk.len();
            this.write_fut = Some(Box::pin(async move { (len, route.write(chunk).await) }));
        }
        match this
            .write_fut
            .as_mut()
            .expect("write future")
            .as_mut()
            .poll(cx)
        {
            Poll::Pending => Poll::Pending,
            Poll::Ready((len, res)) => {
                this.write_fut = None;
                Poll::Ready(
                    res.map(|()| len)
                        .map_err(|e| io::Error::other(e.to_string())),
                )
            }
        }
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if let Some(fut) = this.write_fut.as_mut() {
            match fut.as_mut().poll(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready((_, res)) => {
                    this.write_fut = None;
                    if let Err(err) = res {
                        return Poll::Ready(Err(io::Error::other(err.to_string())));
                    }
                }
            }
        }
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if let Some(fut) = this.write_fut.as_mut() {
            match fut.as_mut().poll(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready((_, res)) => {
                    this.write_fut = None;
                    if let Err(err) = res {
                        return Poll::Ready(Err(io::Error::other(err.to_string())));
                    }
                }
            }
        }
        if this.shutdown_fut.is_none() {
            let route = Arc::clone(&this.route);
            this.shutdown_fut = Some(Box::pin(async move { route.close_link().await }));
        }
        match this
            .shutdown_fut
            .as_mut()
            .expect("shutdown future")
            .as_mut()
            .poll(cx)
        {
            Poll::Pending => Poll::Pending,
            Poll::Ready(res) => {
                this.shutdown_fut = None;
                this.closed = true;
                Poll::Ready(res.map_err(|e| io::Error::other(e.to_string())))
            }
        }
    }
}

#[derive(uniffi::Object)]
pub struct CirculationNode {
    inner: CoreCirculationNode,
}

#[uniffi::export]
impl CirculationNode {
    #[uniffi::constructor]
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        storage_dir: String,
        name: String,
        listen_port: Option<u16>,
        control: Arc<ControlRuntime>,
        discovery: Arc<dyn PeerDiscovery>,
        audio: Arc<dyn LocalAudio>,
        dialer: Arc<dyn PeerRouteDialer>,
        owner_active: Arc<dyn OwnerActive>,
    ) -> Result<Arc<Self>, CirculationFailure> {
        let mut config = CirculationConfig::new(PathBuf::from(storage_dir), name);
        config.listen_port = listen_port;
        let inner = with_runtime(|| {
            CoreCirculationNode::with_control_and_audio_for_owner(
                config,
                Arc::new(PeerDiscoveryAdapter::new(discovery)),
                control.core(),
                Arc::new(LocalAudioAdapter(audio)),
                Arc::new(move || owner_active.is_active()),
                Arc::new(RouteDialerAdapter(dialer)),
            )
        })
        .map_err(CirculationFailure::from)?;
        Ok(Arc::new(Self { inner }))
    }

    pub fn id(&self) -> PeerId {
        self.inner.id().clone()
    }

    pub fn name(&self) -> String {
        self.inner.name().to_string()
    }

    pub fn bound_port(&self) -> Option<u16> {
        self.inner.bound_port()
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl CirculationNode {
    pub async fn start(&self) -> Result<u16, CirculationFailure> {
        self.inner.start().await.map_err(CirculationFailure::from)
    }

    pub async fn close_node(&self) {
        self.inner.close().await;
    }

    pub async fn execute(&self, command: CirculationCommand) -> CirculationReply {
        match self.dispatch(command).await {
            Ok(reply) => reply,
            Err(CirculationFailure::Failed { error }) => CirculationReply::Failed { error },
        }
    }
}

impl CirculationNode {
    pub fn peers(&self) -> Vec<Peer> {
        self.inner.peers()
    }

    pub fn nearby(&self) -> Vec<NearbyPeerRecord> {
        self.inner
            .nearby()
            .iter()
            .map(NearbyPeerRecord::from)
            .collect()
    }

    pub fn offer_pairing(&self) -> Result<PairingOffer, CirculationFailure> {
        self.inner.offer_pairing().map_err(CirculationFailure::from)
    }

    pub fn current_pairing_offer(&self) -> Option<PairingOffer> {
        self.inner.current_pairing_offer()
    }

    pub async fn pair(&self, peer_id: PeerId, code: String) -> Result<Peer, CirculationFailure> {
        self.inner
            .pair(&peer_id, &code)
            .await
            .map_err(CirculationFailure::from)
    }

    pub async fn pair_qr(&self, qr: String) -> Result<Peer, CirculationFailure> {
        self.inner
            .pair_qr(&qr)
            .await
            .map_err(CirculationFailure::from)
    }

    pub async fn forget(&self, peer_id: PeerId) -> Result<(), CirculationFailure> {
        self.inner
            .forget(&peer_id)
            .await
            .map_err(CirculationFailure::from)
    }

    pub async fn remote_read(&self, peer_id: PeerId) -> Result<RemoteReading, CirculationFailure> {
        self.inner
            .remote_read(&peer_id)
            .await
            .map_err(CirculationFailure::from)
    }

    pub async fn remote_set(
        &self,
        peer_id: PeerId,
        session: RemoteSessionReference,
        command: HeadsetCommand,
    ) -> Result<RemoteReading, CirculationFailure> {
        self.inner
            .remote_set(&peer_id, session, command)
            .await
            .map_err(CirculationFailure::from)
    }

    pub async fn handoff_status(&self, address: Option<String>) -> HandoffStatus {
        self.inner.handoff_status(address.as_deref()).await
    }

    pub async fn pull(&self, address: String) -> Result<PullOutcome, CirculationFailure> {
        self.inner
            .pull(&address)
            .await
            .map_err(CirculationFailure::from)
    }

    async fn dispatch(
        &self,
        command: CirculationCommand,
    ) -> Result<CirculationReply, CirculationFailure> {
        Ok(match command {
            CirculationCommand::Directory => CirculationReply::Directory {
                paired: self.peers(),
                nearby: self.nearby(),
                offer: self.current_pairing_offer(),
            },
            CirculationCommand::Offer => CirculationReply::Offer {
                offer: self.offer_pairing()?,
            },
            CirculationCommand::Pair { peer_id, code } => CirculationReply::Paired {
                peer: self.pair(peer_id, code).await?,
            },
            CirculationCommand::PairQr { qr } => CirculationReply::Paired {
                peer: self.pair_qr(qr).await?,
            },
            CirculationCommand::Forget { peer_id } => {
                self.forget(peer_id).await?;
                CirculationReply::Forgotten
            }
            CirculationCommand::Read { peer_id } => CirculationReply::Reading {
                reading: self.remote_read(peer_id).await?,
            },
            CirculationCommand::Set { session, command } => CirculationReply::Reading {
                reading: self
                    .remote_set(session.peer_id.clone(), session, command)
                    .await?,
            },
            CirculationCommand::Handoff { address } => CirculationReply::Handoff {
                status: self.handoff_status(address).await,
            },
            CirculationCommand::Pull { address } => CirculationReply::Pulled {
                outcome: self.pull(address).await?,
            },
        })
    }
}

#[uniffi::export]
pub fn circulation_discovery_contract() -> DiscoveryContract {
    DiscoveryContract::current()
}

#[uniffi::export]
pub fn circulation_service_instance_name(peer_id: String) -> String {
    service_instance_name(&peer_id)
}

#[uniffi::export]
pub fn circulation_wire_format_version() -> u8 {
    CIRCULATION_WIRE_FORMAT_VERSION
}

#[uniffi::export]
pub fn encode_circulation_command(command: CirculationCommand) -> Vec<u8> {
    command.encode()
}

#[uniffi::export]
pub fn decode_circulation_command(bytes: Vec<u8>) -> Result<CirculationCommand, CodecError> {
    CirculationCommand::decode(&bytes)
}

#[uniffi::export]
pub fn encode_circulation_reply(reply: CirculationReply) -> Vec<u8> {
    reply.encode()
}

#[uniffi::export]
pub fn decode_circulation_reply(bytes: Vec<u8>) -> Result<CirculationReply, CodecError> {
    CirculationReply::decode(&bytes)
}
