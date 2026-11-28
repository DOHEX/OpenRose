use crate::contract::{
    DEFAULT_PORT, HandoffStatus, LocalAudio, NearbyPeer, PairingOffer, Peer, PeerDiscovery, PeerId,
    PullOutcome, PullStage, RemoteCommand, RemoteError, RemoteReading, RemoteSessionReference,
    RouteDialer, RouteStream, YieldRefusal, YieldResponse, same_address,
};
use crate::endpoint::{HeadsetEndpoint, RemoteHandler, yield_via_local_audio};
use crate::identity::{PeerIdentity, cert_fingerprint};
use crate::pairing::{
    PairingRequest, PairingWindow, initiate_pairing, parse_qr_payload, respond_pairing,
};
use crate::trust::PeerTrust;
use crate::wire::{WireBody, recv_frame, send_frame};
use async_trait::async_trait;
use openrose_control::ControlRuntime;
use openrose_headset::HeadsetCommand;
use rustls::pki_types::ServerName;
use std::collections::{BTreeMap, BTreeSet};
use std::io::ErrorKind;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{Mutex as AsyncMutex, watch};
use tokio::task::JoinHandle;
use tokio::time::timeout;
use tokio_rustls::{TlsAcceptor, TlsConnector};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const RESPONSE_TIMEOUT: Duration = Duration::from_secs(18);

#[derive(Debug, Clone)]
pub struct CirculationConfig {
    pub storage_dir: PathBuf,
    pub name: String,
    pub bind_ip: IpAddr,
    pub listen_port: Option<u16>,
    pub response_timeout: Duration,
}

impl CirculationConfig {
    pub fn new(storage_dir: impl Into<PathBuf>, name: impl Into<String>) -> Self {
        Self {
            storage_dir: storage_dir.into(),
            name: name.into(),
            bind_ip: IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            listen_port: None,
            response_timeout: RESPONSE_TIMEOUT,
        }
    }

    pub fn loopback(storage_dir: impl Into<PathBuf>, name: impl Into<String>) -> Self {
        Self {
            storage_dir: storage_dir.into(),
            name: name.into(),
            bind_ip: IpAddr::V4(Ipv4Addr::LOCALHOST),
            listen_port: Some(0),
            response_timeout: RESPONSE_TIMEOUT,
        }
    }

    pub fn with_response_timeout(mut self, timeout: Duration) -> Self {
        self.response_timeout = timeout;
        self
    }
}

struct RunningState {
    bound_addr: SocketAddr,
    shutdown_tx: watch::Sender<bool>,
    accept_task: JoinHandle<()>,
    watch_task: JoinHandle<()>,
}

struct NodeInner {
    name: String,
    bind_ip: IpAddr,
    listen_port: Option<u16>,
    identity: PeerIdentity,
    trust: PeerTrust,
    pairing: PairingWindow,
    discovery: Arc<dyn PeerDiscovery>,
    dialer: Arc<dyn RouteDialer>,
    handler: Option<Arc<dyn RemoteHandler>>,
    local_audio: Option<Arc<dyn LocalAudio>>,
    nearby_tx: watch::Sender<Vec<NearbyPeer>>,
    _nearby_rx: watch::Receiver<Vec<NearbyPeer>>,
    readings_tx: watch::Sender<BTreeMap<PeerId, RemoteReading>>,
    _readings_rx: watch::Receiver<BTreeMap<PeerId, RemoteReading>>,
    pull_stage: Mutex<Option<PullStage>>,
    running: AsyncMutex<Option<RunningState>>,
    bound_addr_sync: Mutex<Option<SocketAddr>>,
    pull_lock: AsyncMutex<()>,
    yield_lock: AsyncMutex<()>,
    response_timeout: Duration,
}

#[derive(Clone)]
pub struct CirculationNode {
    inner: Arc<NodeInner>,
}

impl CirculationNode {
    pub fn new(
        config: CirculationConfig,
        discovery: Arc<dyn PeerDiscovery>,
    ) -> Result<Self, RemoteError> {
        Self::build(config, discovery, None, None, None)
    }

    pub fn with_dialer(
        config: CirculationConfig,
        discovery: Arc<dyn PeerDiscovery>,
        dialer: Arc<dyn RouteDialer>,
    ) -> Result<Self, RemoteError> {
        Self::build(config, discovery, None, None, Some(dialer))
    }

    pub fn with_control(
        config: CirculationConfig,
        discovery: Arc<dyn PeerDiscovery>,
        control: Arc<ControlRuntime>,
    ) -> Result<Self, RemoteError> {
        let identity = PeerIdentity::load_or_create(&config.storage_dir)?;
        let handler: Arc<dyn RemoteHandler> = Arc::new(HeadsetEndpoint::with_owner_active(
            identity.id().clone(),
            control,
            Arc::new(|| true),
        ));
        Self::build_with_identity(config, identity, discovery, Some(handler), None, None)
    }

    pub fn with_audio(
        config: CirculationConfig,
        discovery: Arc<dyn PeerDiscovery>,
        audio: Arc<dyn LocalAudio>,
    ) -> Result<Self, RemoteError> {
        Self::build(config, discovery, None, Some(audio), None)
    }

    pub fn with_control_and_audio(
        config: CirculationConfig,
        discovery: Arc<dyn PeerDiscovery>,
        control: Arc<ControlRuntime>,
        audio: Arc<dyn LocalAudio>,
    ) -> Result<Self, RemoteError> {
        Self::with_control_and_audio_for_owner(
            config,
            discovery,
            control,
            audio,
            Arc::new(|| true),
            Arc::new(TcpRouteDialer),
        )
    }

    pub fn with_control_and_audio_for_owner(
        config: CirculationConfig,
        discovery: Arc<dyn PeerDiscovery>,
        control: Arc<ControlRuntime>,
        audio: Arc<dyn LocalAudio>,
        owner_active: Arc<dyn Fn() -> bool + Send + Sync>,
        dialer: Arc<dyn RouteDialer>,
    ) -> Result<Self, RemoteError> {
        let identity = PeerIdentity::load_or_create(&config.storage_dir)?;
        let handler: Arc<dyn RemoteHandler> = Arc::new(
            HeadsetEndpoint::with_owner_active(identity.id().clone(), control, owner_active)
                .with_local_audio(Arc::clone(&audio)),
        );
        Self::build_with_identity(
            config,
            identity,
            discovery,
            Some(handler),
            Some(audio),
            Some(dialer),
        )
    }

    fn build(
        config: CirculationConfig,
        discovery: Arc<dyn PeerDiscovery>,
        handler: Option<Arc<dyn RemoteHandler>>,
        local_audio: Option<Arc<dyn LocalAudio>>,
        dialer: Option<Arc<dyn RouteDialer>>,
    ) -> Result<Self, RemoteError> {
        let identity = PeerIdentity::load_or_create(&config.storage_dir)?;
        Self::build_with_identity(config, identity, discovery, handler, local_audio, dialer)
    }

    fn build_with_identity(
        config: CirculationConfig,
        identity: PeerIdentity,
        discovery: Arc<dyn PeerDiscovery>,
        handler: Option<Arc<dyn RemoteHandler>>,
        local_audio: Option<Arc<dyn LocalAudio>>,
        dialer: Option<Arc<dyn RouteDialer>>,
    ) -> Result<Self, RemoteError> {
        let trust = PeerTrust::load(&config.storage_dir)?;
        let (nearby_tx, nearby_rx) = watch::channel(Vec::new());
        let initial_readings = trust
            .list()
            .into_iter()
            .map(|peer| (peer.id, RemoteReading::unavailable()))
            .collect();
        let (readings_tx, readings_rx) = watch::channel(initial_readings);
        Ok(Self {
            inner: Arc::new(NodeInner {
                name: config.name,
                bind_ip: config.bind_ip,
                listen_port: config.listen_port,
                identity,
                trust,
                pairing: PairingWindow::new(),
                discovery,
                dialer: dialer.unwrap_or_else(|| Arc::new(TcpRouteDialer)),
                handler,
                local_audio,
                nearby_tx,
                _nearby_rx: nearby_rx,
                readings_tx,
                _readings_rx: readings_rx,
                running: AsyncMutex::new(None),
                bound_addr_sync: Mutex::new(None),
                pull_lock: AsyncMutex::new(()),
                pull_stage: Mutex::new(None),
                yield_lock: AsyncMutex::new(()),
                response_timeout: config.response_timeout,
            }),
        })
    }

    pub fn id(&self) -> &PeerId {
        self.inner.identity.id()
    }

    pub fn name(&self) -> &str {
        &self.inner.name
    }

    pub fn bound_port(&self) -> Option<u16> {
        self.inner
            .bound_addr_sync
            .lock()
            .expect("bound addr lock")
            .map(|addr| addr.port())
    }

    pub fn peers(&self) -> Vec<Peer> {
        self.inner.trust.list()
    }

    pub fn watch_peers(&self) -> watch::Receiver<Vec<Peer>> {
        self.inner.trust.watch()
    }

    pub fn nearby(&self) -> Vec<NearbyPeer> {
        self.inner.nearby_tx.borrow().clone()
    }

    pub fn watch_nearby(&self) -> watch::Receiver<Vec<NearbyPeer>> {
        self.inner.nearby_tx.subscribe()
    }

    pub fn peer_readings(&self) -> BTreeMap<PeerId, RemoteReading> {
        self.inner.readings_tx.borrow().clone()
    }

    pub fn peer_reading(&self, peer_id: &PeerId) -> RemoteReading {
        self.inner
            .readings_tx
            .borrow()
            .get(peer_id)
            .cloned()
            .unwrap_or_else(RemoteReading::unavailable)
    }

    pub fn watch_peer_readings(&self) -> watch::Receiver<BTreeMap<PeerId, RemoteReading>> {
        self.inner.readings_tx.subscribe()
    }

    pub async fn start(&self) -> Result<u16, RemoteError> {
        let mut running_guard = self.inner.running.lock().await;
        if let Some(state) = running_guard.as_ref() {
            return Ok(state.bound_addr.port());
        }

        let listener = bind_listener(self.inner.bind_ip, self.inner.listen_port).await?;
        let bound_addr = listener
            .local_addr()
            .map_err(|_| RemoteError::ServiceClosed)?;
        let server_config = self.inner.identity.server_config()?;
        let acceptor = TlsAcceptor::from(server_config);

        if let Err(err) = self
            .inner
            .discovery
            .start(
                self.inner.identity.id(),
                &self.inner.name,
                bound_addr.port(),
            )
            .await
        {
            drop(listener);
            return Err(err);
        }

        *self.inner.bound_addr_sync.lock().expect("bound addr lock") = Some(bound_addr);

        let (shutdown_tx, shutdown_rx) = watch::channel(false);
        let accept_task = tokio::spawn(accept_loop(
            Arc::clone(&self.inner),
            listener,
            acceptor,
            shutdown_rx.clone(),
        ));
        let watch_task = tokio::spawn(watch_loop(
            self.clone(),
            self.inner.discovery.watch(),
            self.inner.trust.watch(),
            shutdown_rx,
        ));

        *running_guard = Some(RunningState {
            bound_addr,
            shutdown_tx,
            accept_task,
            watch_task,
        });
        Ok(bound_addr.port())
    }

    pub async fn close(&self) {
        let state = {
            let mut running_guard = self.inner.running.lock().await;
            running_guard.take()
        };
        *self.inner.bound_addr_sync.lock().expect("bound addr lock") = None;

        if let Some(state) = state {
            let _ = state.shutdown_tx.send(true);
            state.accept_task.abort();
            state.watch_task.abort();
            let _ = state.accept_task.await;
            let _ = state.watch_task.await;
        }
        self.inner.discovery.close().await;
        self.inner.nearby_tx.send_replace(Vec::new());
        self.reset_readings_to_unavailable();
    }

    fn reset_readings_to_unavailable(&self) {
        let peers = self.inner.trust.list();
        let next = peers
            .into_iter()
            .map(|p| (p.id, RemoteReading::unavailable()))
            .collect();
        self.inner.readings_tx.send_replace(next);
    }

    fn require_bound_addr(&self) -> Result<SocketAddr, RemoteError> {
        self.inner
            .bound_addr_sync
            .lock()
            .expect("bound addr lock")
            .ok_or(RemoteError::ServiceClosed)
    }

    fn advertised_addresses(&self, bound_addr: SocketAddr) -> Vec<IpAddr> {
        if !bound_addr.ip().is_unspecified() {
            return vec![bound_addr.ip()];
        }
        detect_local_ip()
            .map(|ip| vec![ip])
            .unwrap_or_else(|| vec![IpAddr::V4(Ipv4Addr::LOCALHOST)])
    }

    pub fn offer_pairing(&self) -> Result<PairingOffer, RemoteError> {
        let bound_addr = self.require_bound_addr()?;
        let addresses = self.advertised_addresses(bound_addr);
        self.inner
            .pairing
            .open(self.inner.identity.id(), &addresses, bound_addr.port())
    }

    pub fn current_pairing_offer(&self) -> Option<PairingOffer> {
        let bound_addr = self.require_bound_addr().ok()?;
        let addresses = self.advertised_addresses(bound_addr);
        self.inner
            .pairing
            .current(self.inner.identity.id(), &addresses, bound_addr.port())
    }

    pub async fn pair_qr(&self, qr: &str) -> Result<Peer, RemoteError> {
        let payload = parse_qr_payload(qr)?;
        let addrs: Vec<SocketAddr> = payload
            .addresses
            .iter()
            .map(|&ip| SocketAddr::new(ip, payload.port))
            .collect();
        self.pair_over_addrs(&payload.peer_id, &payload.code, &addrs)
            .await
    }

    pub async fn pair(&self, peer_id: &PeerId, code: &str) -> Result<Peer, RemoteError> {
        let addrs = self.addresses_for(peer_id)?;
        self.pair_over_addrs(peer_id, code, &addrs).await
    }

    async fn pair_over_addrs(
        &self,
        peer_id: &PeerId,
        code: &str,
        addrs: &[SocketAddr],
    ) -> Result<Peer, RemoteError> {
        self.require_bound_addr()?;
        if peer_id == self.inner.identity.id() || !peer_id.is_valid() {
            return Err(RemoteError::InvalidArgument);
        }
        let mut tls = self.open_route(peer_id, addrs).await?;
        let grant = timeout(
            RESPONSE_TIMEOUT,
            initiate_pairing(
                &mut tls,
                self.inner.identity.id(),
                &self.inner.name,
                peer_id,
                code,
                &self.inner.trust,
            ),
        )
        .await
        .map_err(|_| RemoteError::Timeout)??;
        Ok(grant.peer)
    }

    pub async fn forget(&self, peer_id: &PeerId) -> Result<(), RemoteError> {
        let Some(grant) = self.inner.trust.remove(peer_id)? else {
            return Ok(());
        };
        self.inner.readings_tx.send_modify(|map| {
            map.remove(peer_id);
        });
        if let Ok(addrs) = self.addresses_for(peer_id)
            && let Ok(mut tls) = self.open_route(peer_id, &addrs).await
        {
            let _ = timeout(
                RESPONSE_TIMEOUT,
                send_frame(&mut tls, WireBody::Forget { grant: grant.token }),
            )
            .await;
            let _ = timeout(RESPONSE_TIMEOUT, recv_frame(&mut tls)).await;
        }
        Ok(())
    }

    pub async fn remote_read(&self, peer_id: &PeerId) -> Result<RemoteReading, RemoteError> {
        let result = self.send_request(peer_id, RemoteCommand::Read).await;
        match &result {
            Ok(reading) => {
                self.set_peer_reading(peer_id, reading.clone());
            }
            Err(RemoteError::PeerOffline) => {
                self.set_peer_reading(peer_id, RemoteReading::unavailable());
            }
            _ => {}
        }
        result
    }

    pub async fn remote_set(
        &self,
        peer_id: &PeerId,
        session: RemoteSessionReference,
        command: HeadsetCommand,
    ) -> Result<RemoteReading, RemoteError> {
        let result = self
            .send_request(peer_id, RemoteCommand::Set { session, command })
            .await;
        match &result {
            Ok(reading) => {
                self.set_peer_reading(peer_id, reading.clone());
            }
            Err(RemoteError::PeerOffline) => {
                self.set_peer_reading(peer_id, RemoteReading::unavailable());
            }
            _ => {}
        }
        result
    }

    pub async fn handoff_status(&self, address: Option<&str>) -> HandoffStatus {
        let paired = self.inner.trust.list();
        let offline = {
            let nearby = self.inner.nearby_tx.borrow();
            !paired.is_empty()
                && paired
                    .iter()
                    .all(|peer| !nearby.iter().any(|n| n.id == peer.id))
        };
        let media_ready = match (&self.inner.local_audio, address) {
            (Some(audio), Some(addr)) => {
                let trimmed = addr.trim();
                !trimmed.is_empty() && audio.is_media_connected(trimmed).await
            }
            _ => false,
        };
        let progress = *self.inner.pull_stage.lock().expect("pull stage lock");
        HandoffStatus {
            available: self.inner.local_audio.is_some() && !paired.is_empty(),
            media_ready,
            offline,
            progress,
        }
    }

    pub async fn pull(&self, address: &str) -> Result<PullOutcome, RemoteError> {
        let Ok(_guard) = self.inner.pull_lock.try_lock() else {
            return Err(RemoteError::PullInProgress);
        };
        self.require_bound_addr()?;
        let Some(local_audio) = &self.inner.local_audio else {
            return Err(RemoteError::UnsupportedOperation);
        };
        let address = address.trim();
        if address.is_empty() {
            return Err(RemoteError::InvalidArgument);
        }
        let peers = self.inner.trust.list();
        if peers.is_empty() {
            return Err(RemoteError::NotPaired);
        }
        if local_audio.is_media_connected(address).await {
            return Ok(PullOutcome::AlreadyConnected);
        }

        struct PullStageGuard<'a>(&'a Mutex<Option<PullStage>>);
        impl Drop for PullStageGuard<'_> {
            fn drop(&mut self) {
                *self.0.lock().expect("pull stage lock") = None;
            }
        }
        *self.inner.pull_stage.lock().expect("pull stage lock") = Some(PullStage::ContactingPeers);
        let _stage_guard = PullStageGuard(&self.inner.pull_stage);

        let mut reachable = 0usize;
        for peer in &peers {
            match self.send_yield(&peer.id, address).await {
                Ok(YieldResponse::Yielded(receipt)) => {
                    if !same_address(&receipt.address, address) {
                        return Err(RemoteError::Protocol);
                    }
                    reachable += 1;
                }
                Ok(YieldResponse::NotHolding) => {
                    reachable += 1;
                }
                Ok(YieldResponse::Refused(refusal)) => {
                    return Err(RemoteError::YieldRefused(refusal));
                }
                Err(RemoteError::PeerOffline) => {}
                Err(err) => return Err(err),
            }
        }

        *self.inner.pull_stage.lock().expect("pull stage lock") = Some(if reachable == 0 {
            PullStage::AcquiringOffline
        } else {
            PullStage::Acquiring
        });
        local_audio
            .acquire(address)
            .await
            .map_err(RemoteError::AcquireFailed)?;
        if !local_audio.is_media_connected(address).await {
            return Err(RemoteError::AcquireFailed(YieldRefusal::MediaNotConnected));
        }

        if reachable == 0 {
            Ok(PullOutcome::AcquiredWithPeersUnreachable)
        } else {
            Ok(PullOutcome::Acquired)
        }
    }

    fn set_peer_reading(&self, peer_id: &PeerId, reading: RemoteReading) {
        if self.inner.trust.grant(peer_id).is_none() {
            return;
        }
        self.inner.readings_tx.send_modify(|map| {
            map.insert(peer_id.clone(), reading);
        });
    }

    async fn send_request(
        &self,
        peer_id: &PeerId,
        command: RemoteCommand,
    ) -> Result<RemoteReading, RemoteError> {
        match self
            .send_peer_frame(peer_id, |grant| WireBody::Request { grant, command })
            .await?
        {
            WireBody::Reading(reading) => Ok(reading),
            _ => Err(RemoteError::Protocol),
        }
    }

    async fn send_yield(
        &self,
        peer_id: &PeerId,
        address: &str,
    ) -> Result<YieldResponse, RemoteError> {
        match self
            .send_peer_frame(peer_id, |grant| WireBody::Yield {
                grant,
                address: address.to_string(),
            })
            .await?
        {
            WireBody::YieldReply(reply) => Ok(reply),
            _ => Err(RemoteError::Protocol),
        }
    }

    async fn send_peer_frame(
        &self,
        peer_id: &PeerId,
        build: impl FnOnce(String) -> WireBody,
    ) -> Result<WireBody, RemoteError> {
        self.require_bound_addr()?;
        let grant = self
            .inner
            .trust
            .grant(peer_id)
            .ok_or(RemoteError::NotPaired)?;
        let addrs = self.addresses_for(peer_id)?;
        let mut tls = self.open_route(peer_id, &addrs).await?;
        let frame = build(grant.token.clone());
        let response = timeout(self.inner.response_timeout, async {
            send_frame(&mut tls, frame).await?;
            recv_frame(&mut tls).await
        })
        .await
        .map_err(|_| RemoteError::Timeout)??;

        if self.inner.trust.grant(peer_id).as_ref() != Some(&grant) {
            return Err(RemoteError::NotPaired);
        }

        match response {
            WireBody::Error(RemoteError::NotPaired) => {
                let _ = self.inner.trust.remove(peer_id);
                self.inner.readings_tx.send_modify(|map| {
                    map.remove(peer_id);
                });
                Err(RemoteError::NotPaired)
            }
            WireBody::Error(err) => Err(err),
            other => Ok(other),
        }
    }

    fn addresses_for(&self, peer_id: &PeerId) -> Result<Vec<SocketAddr>, RemoteError> {
        let nearby = self.inner.nearby_tx.borrow();
        let mut addrs: Vec<SocketAddr> = nearby
            .iter()
            .filter(|peer| &peer.id == peer_id)
            .flat_map(|peer| {
                peer.addresses
                    .iter()
                    .map(|&ip| SocketAddr::new(ip, peer.port))
            })
            .collect();
        if addrs.is_empty() {
            return Err(RemoteError::PeerOffline);
        }
        sort_socket_addrs(&mut addrs);
        Ok(addrs)
    }

    async fn open_route(
        &self,
        peer_id: &PeerId,
        addrs: &[SocketAddr],
    ) -> Result<tokio_rustls::client::TlsStream<Box<dyn RouteStream>>, RemoteError> {
        self.require_bound_addr()?;
        let mut sorted = addrs.to_vec();
        sort_socket_addrs(&mut sorted);
        if sorted.is_empty() {
            return Err(RemoteError::PeerOffline);
        }
        let client_config = self.inner.identity.client_config(peer_id.clone())?;
        let connector = TlsConnector::from(client_config);
        let server_name =
            ServerName::try_from("openrose.peer").map_err(|_| RemoteError::IdentityError)?;
        for addr in sorted {
            let connect_limit = CONNECT_TIMEOUT.min(self.inner.response_timeout);
            let Ok(Ok(stream)) = timeout(connect_limit, self.inner.dialer.connect(addr)).await
            else {
                continue;
            };
            let Ok(Ok(tls)) = timeout(
                self.inner.response_timeout,
                connector.connect(server_name.clone(), stream),
            )
            .await
            else {
                continue;
            };
            self.require_bound_addr()?;
            return Ok(tls);
        }
        Err(RemoteError::PeerOffline)
    }
}

struct TcpRouteDialer;

#[async_trait]
impl RouteDialer for TcpRouteDialer {
    async fn connect(&self, addr: SocketAddr) -> Result<Box<dyn RouteStream>, RemoteError> {
        let stream = TcpStream::connect(addr)
            .await
            .map_err(|_| RemoteError::PeerOffline)?;
        Ok(Box::new(stream))
    }
}

async fn bind_listener(
    bind_ip: IpAddr,
    listen_port: Option<u16>,
) -> Result<TcpListener, RemoteError> {
    let requested = listen_port.unwrap_or(DEFAULT_PORT);
    match TcpListener::bind(SocketAddr::new(bind_ip, requested)).await {
        Ok(listener) => Ok(listener),
        Err(err) if err.kind() == ErrorKind::AddrInUse && listen_port.is_none() => {
            TcpListener::bind(SocketAddr::new(bind_ip, 0))
                .await
                .map_err(|_| RemoteError::ServiceClosed)
        }
        Err(_) => Err(RemoteError::ServiceClosed),
    }
}

fn detect_local_ip() -> Option<IpAddr> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    socket.connect("192.0.2.1:80").ok()?;
    let ip = socket.local_addr().ok()?.ip();
    if ip.is_unspecified() { None } else { Some(ip) }
}

fn sort_socket_addrs(addrs: &mut Vec<SocketAddr>) {
    addrs.sort_by_key(|addr| (!addr.is_ipv4(), addr.to_string()));
    addrs.dedup();
}

async fn accept_loop(
    inner: Arc<NodeInner>,
    listener: TcpListener,
    acceptor: TlsAcceptor,
    mut shutdown: watch::Receiver<bool>,
) {
    loop {
        let accepted = tokio::select! {
            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    break;
                }
                continue;
            }
            res = listener.accept() => res,
        };
        let Ok((tcp, _)) = accepted else {
            break;
        };
        let inner = Arc::clone(&inner);
        let acceptor = acceptor.clone();
        tokio::spawn(async move {
            let _ = handle_connection(inner, acceptor, tcp).await;
        });
    }
}

async fn handle_connection(
    inner: Arc<NodeInner>,
    acceptor: TlsAcceptor,
    tcp: TcpStream,
) -> Result<(), RemoteError> {
    let mut tls = timeout(RESPONSE_TIMEOUT, acceptor.accept(tcp))
        .await
        .map_err(|_| RemoteError::Timeout)?
        .map_err(|_| RemoteError::IdentityError)?;

    let remote_id = {
        let (_, session) = tls.get_ref();
        let cert = session
            .peer_certificates()
            .and_then(|certs| certs.first())
            .ok_or(RemoteError::IdentityError)?;
        cert_fingerprint(cert)
    };

    let frame = timeout(RESPONSE_TIMEOUT, recv_frame(&mut tls))
        .await
        .map_err(|_| RemoteError::Timeout)??;

    match frame {
        WireBody::PairStart { name, spake_msg } => {
            let _ = timeout(
                RESPONSE_TIMEOUT,
                respond_pairing(
                    &mut tls,
                    inner.identity.id(),
                    &inner.name,
                    PairingRequest {
                        peer_id: remote_id.clone(),
                        name,
                        spake_msg,
                    },
                    &inner.pairing,
                    &inner.trust,
                ),
            )
            .await;
        }
        WireBody::Request { grant, command } => {
            if inner.trust.verify(&remote_id, &grant).is_none() {
                let _ = send_frame(&mut tls, WireBody::Error(RemoteError::NotPaired)).await;
                return Ok(());
            }
            let reply = match &inner.handler {
                Some(handler) => match handler.handle(command).await {
                    Ok(reading) => WireBody::Reading(reading),
                    Err(err) => WireBody::Error(err),
                },
                None => WireBody::Error(RemoteError::UnsupportedOperation),
            };
            let _ = timeout(inner.response_timeout, send_frame(&mut tls, reply)).await;
        }
        WireBody::Yield { grant, address } => {
            if inner.trust.verify(&remote_id, &grant).is_none() {
                let _ = send_frame(&mut tls, WireBody::Error(RemoteError::NotPaired)).await;
                return Ok(());
            }
            let reply = if let Some(handler) = &inner.handler {
                match handler.yield_audio(&address).await {
                    Ok(resp) => WireBody::YieldReply(resp),
                    Err(err) => WireBody::Error(err),
                }
            } else if let Some(audio) = &inner.local_audio {
                let _guard = inner.yield_lock.lock().await;
                match yield_via_local_audio(audio.as_ref(), None, &address).await {
                    Ok(resp) => WireBody::YieldReply(resp),
                    Err(err) => WireBody::Error(err),
                }
            } else {
                WireBody::Error(RemoteError::UnsupportedOperation)
            };
            let _ = timeout(inner.response_timeout, send_frame(&mut tls, reply)).await;
        }
        WireBody::Forget { grant } => {
            if inner.trust.verify(&remote_id, &grant).is_none() {
                let _ = send_frame(&mut tls, WireBody::Error(RemoteError::NotPaired)).await;
                return Ok(());
            }
            let _ = inner.trust.remove(&remote_id);
            inner.readings_tx.send_modify(|map| {
                map.remove(&remote_id);
            });
            let _ = timeout(inner.response_timeout, send_frame(&mut tls, WireBody::Ack)).await;
        }
        _ => {
            let _ = send_frame(&mut tls, WireBody::Error(RemoteError::UnsupportedOperation)).await;
        }
    }
    Ok(())
}

async fn watch_loop(
    node: CirculationNode,
    mut discovery_rx: watch::Receiver<Vec<NearbyPeer>>,
    mut peers_rx: watch::Receiver<Vec<Peer>>,
    mut shutdown: watch::Receiver<bool>,
) {
    let mut online_peers: BTreeSet<PeerId> = BTreeSet::new();
    let mut paired_ids: BTreeSet<PeerId> = peers_rx.borrow().iter().map(|p| p.id.clone()).collect();

    sync_nearby(&node, &discovery_rx.borrow());
    refresh_availability(
        &node,
        &paired_ids,
        &mut online_peers,
        &node.inner.nearby_tx.borrow().clone(),
    );

    loop {
        tokio::select! {
            changed = shutdown.changed() => {
                if changed.is_err() || *shutdown.borrow() {
                    break;
                }
            }
            res = discovery_rx.changed() => {
                if res.is_err() {
                    break;
                }
                let raw = discovery_rx.borrow_and_update().clone();
                sync_nearby(&node, &raw);
                let filtered = node.inner.nearby_tx.borrow().clone();
                refresh_availability(&node, &paired_ids, &mut online_peers, &filtered);
            }
            res = peers_rx.changed() => {
                if res.is_err() {
                    break;
                }
                let next_peers: BTreeSet<PeerId> = peers_rx
                    .borrow_and_update()
                    .iter()
                    .map(|p| p.id.clone())
                    .collect();
                let removed: Vec<PeerId> = paired_ids.difference(&next_peers).cloned().collect();
                for id in &removed {
                    online_peers.remove(id);
                }
                if !removed.is_empty() {
                    node.inner.readings_tx.send_modify(|map| {
                        for id in &removed {
                            map.remove(id);
                        }
                    });
                }
                paired_ids = next_peers;
                let filtered = node.inner.nearby_tx.borrow().clone();
                refresh_availability(&node, &paired_ids, &mut online_peers, &filtered);
            }
        }
    }
}

fn sync_nearby(node: &CirculationNode, raw: &[NearbyPeer]) {
    let self_id = node.inner.identity.id();
    let mut seen = BTreeSet::new();
    let mut filtered = Vec::new();
    for peer in raw {
        if &peer.id != self_id
            && peer.id.is_valid()
            && !peer.addresses.is_empty()
            && peer.port > 0
            && seen.insert(peer.id.clone())
        {
            filtered.push(peer.clone());
        }
    }
    node.inner.nearby_tx.send_replace(filtered);
}

fn refresh_availability(
    node: &CirculationNode,
    paired_ids: &BTreeSet<PeerId>,
    online_peers: &mut BTreeSet<PeerId>,
    nearby: &[NearbyPeer],
) {
    let now_online: BTreeSet<PeerId> = nearby
        .iter()
        .map(|p| p.id.clone())
        .filter(|id| paired_ids.contains(id))
        .collect();

    let went_offline: Vec<PeerId> = online_peers.difference(&now_online).cloned().collect();
    if !went_offline.is_empty() {
        node.inner.readings_tx.send_modify(|map| {
            for id in &went_offline {
                map.insert(id.clone(), RemoteReading::unavailable());
            }
        });
    }

    let returned: Vec<PeerId> = now_online.difference(online_peers).cloned().collect();
    *online_peers = now_online;

    for id in returned {
        let node_clone = node.clone();
        tokio::spawn(async move {
            let _ = node_clone.remote_read(&id).await;
        });
    }
}
