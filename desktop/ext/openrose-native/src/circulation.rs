use crate::{OpenRoseClient, SHARED_RUNTIME, SystemClock, catalog_advertisement};
use async_trait::async_trait;
use nsh_core::JsonValue;
use nsh_core::prelude::*;
use openrose_circulation::{
    CirculationConfig, CirculationNode, NearbyPeer, PairingOffer, Peer, PeerDiscovery, PeerId,
    RemoteError, RemoteReading, RemoteSessionReference,
};
use openrose_control::simulator::{SimulatedHeadsetHandle, SimulatedTransport};
use openrose_control::snapshot::{
    ConnectionState, ControlSnapshot, PermissionState, PlatformState, TransportAvailability,
};
use openrose_control::{ControlConfig, ControlObserver, ControlRuntime, SessionTiming};
use openrose_headset::{HeadsetCommand, HeadsetId, ModelProfile, NoiseMode};
use qrcode::{Color, QrCode};
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::future::Future;
use std::net::{IpAddr, Ipv4Addr};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot, watch};
use tokio::task::JoinHandle;

const DEFAULT_DEV_PEER_LATENCY_MS: u64 = 40;
const APP_ID: &str = "io.github.dohex.openrose";

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct LocalPeerView {
    id: String,
    name: String,
    started: bool,
    bound_port: Option<u16>,
    start_error: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PairingOfferView {
    code: String,
    qr: String,
    valid_for_millis: u64,
    qr_size: usize,
    qr_modules: String,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct NearbyPeerView {
    id: String,
    name: String,
    addresses: Vec<String>,
    port: u16,
    paired: bool,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct PairedPeerView {
    id: String,
    name: String,
    available: bool,
    reading: RemoteReading,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct CirculationSnapshot {
    local_peer: LocalPeerView,
    paired_peers: Vec<PairedPeerView>,
    nearby_peers: Vec<NearbyPeerView>,
    pairing_offer: Option<PairingOfferView>,
    remote_readings: BTreeMap<String, RemoteReading>,
    pending_noise_writes: BTreeMap<String, String>,
    last_error: Option<String>,
}

#[derive(Clone, Default)]
struct LocalMeta {
    started: bool,
    bound_port: Option<u16>,
    start_error: Option<String>,
    last_error: Option<String>,
    pending_noise_writes: BTreeMap<String, (u64, String)>,
    revision: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DevPeerStatus {
    peer_id: String,
    name: String,
    online: bool,
    pairing_code: Option<String>,
    headset_name: String,
    noise_mode: Option<String>,
    reply_latency_ms: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DevPeerPairingInfo {
    peer_id: String,
    name: String,
    code: String,
    qr: String,
    valid_for_millis: u64,
}

struct LoopbackDiscoveryHub {
    state: Mutex<BTreeMap<PeerId, NearbyPeer>>,
    tx: watch::Sender<Vec<NearbyPeer>>,
    _rx: watch::Receiver<Vec<NearbyPeer>>,
}

impl LoopbackDiscoveryHub {
    fn new() -> Arc<Self> {
        let (tx, rx) = watch::channel(Vec::new());
        Arc::new(Self {
            state: Mutex::new(BTreeMap::new()),
            tx,
            _rx: rx,
        })
    }

    fn client(self: &Arc<Self>) -> Arc<LoopbackDiscovery> {
        Arc::new(LoopbackDiscovery {
            hub: Arc::clone(self),
            own_id: Mutex::new(None),
        })
    }

    fn publish(&self, peer: NearbyPeer) {
        let mut map = self.state.lock().expect("loopback discovery lock");
        map.insert(peer.id.clone(), peer);
        let list: Vec<NearbyPeer> = map.values().cloned().collect();
        self.tx.send_replace(list);
    }

    fn remove(&self, id: &PeerId) {
        let mut map = self.state.lock().expect("loopback discovery lock");
        if map.remove(id).is_some() {
            let list: Vec<NearbyPeer> = map.values().cloned().collect();
            self.tx.send_replace(list);
        }
    }
}

struct LoopbackDiscovery {
    hub: Arc<LoopbackDiscoveryHub>,
    own_id: Mutex<Option<PeerId>>,
}

#[async_trait]
impl PeerDiscovery for LoopbackDiscovery {
    async fn start(&self, id: &PeerId, name: &str, port: u16) -> Result<(), RemoteError> {
        *self.own_id.lock().expect("loopback own id lock") = Some(id.clone());
        self.hub.publish(NearbyPeer {
            id: id.clone(),
            name: name.to_string(),
            addresses: vec![IpAddr::V4(Ipv4Addr::LOCALHOST)],
            port,
        });
        Ok(())
    }

    fn watch(&self) -> watch::Receiver<Vec<NearbyPeer>> {
        self.hub.tx.subscribe()
    }

    async fn close(&self) {
        if let Some(id) = self.own_id.lock().expect("loopback own id lock").take() {
            self.hub.remove(&id);
        }
    }
}

struct UnavailableDiscovery {
    tx: watch::Sender<Vec<NearbyPeer>>,
    _rx: watch::Receiver<Vec<NearbyPeer>>,
}

impl UnavailableDiscovery {
    fn new() -> Arc<Self> {
        let (tx, rx) = watch::channel(Vec::new());
        Arc::new(Self { tx, _rx: rx })
    }
}

#[async_trait]
impl PeerDiscovery for UnavailableDiscovery {
    async fn start(&self, _id: &PeerId, _name: &str, _port: u16) -> Result<(), RemoteError> {
        Err(RemoteError::DiscoveryError)
    }

    fn watch(&self) -> watch::Receiver<Vec<NearbyPeer>> {
        self.tx.subscribe()
    }

    async fn close(&self) {}
}

struct NoopObserver;

impl ControlObserver for NoopObserver {
    fn state_changed(&self, _snapshot: &ControlSnapshot) {}
}

struct DevPeerState {
    node: CirculationNode,
    transport: Arc<SimulatedTransport>,
    runtime: Arc<ControlRuntime>,
    handle: SimulatedHeadsetHandle,
    headset_id: HeadsetId,
    online: AtomicBool,
    reply_latency_ms: AtomicU64,
}

impl DevPeerState {
    fn status(&self) -> DevPeerStatus {
        let online = self.online.load(Ordering::SeqCst);
        let pairing_code = self.node.current_pairing_offer().map(|offer| offer.code);
        let snap = self.runtime.snapshot();
        let noise_mode = snap
            .headset
            .as_ref()
            .and_then(|h| h.readings.confirmed_noise_mode())
            .map(|m| m.id().to_string());
        DevPeerStatus {
            peer_id: self.node.id().as_str().to_string(),
            name: self.node.name().to_string(),
            online,
            pairing_code,
            headset_name: "EarFree i5".to_string(),
            noise_mode,
            reply_latency_ms: self.reply_latency_ms.load(Ordering::SeqCst),
        }
    }
}

struct DevCirculationState {
    hub: Arc<LoopbackDiscoveryHub>,
    temp_root: PathBuf,
    seq: AtomicU64,
    peers: Mutex<BTreeMap<String, Arc<DevPeerState>>>,
    last_peer_id: Mutex<Option<String>>,
}

impl DevCirculationState {
    fn get_peer(&self, peer_id: Option<&str>) -> Result<Arc<DevPeerState>, String> {
        let peers = self.peers.lock().expect("dev peers lock");
        let target_id = match peer_id {
            Some(id) => id.to_string(),
            None => self
                .last_peer_id
                .lock()
                .expect("dev last peer lock")
                .clone()
                .ok_or_else(|| remote_error_code(&RemoteError::PeerOffline))?,
        };
        peers
            .get(&target_id)
            .cloned()
            .ok_or_else(|| remote_error_code(&RemoteError::PeerOffline))
    }

    fn all_peers(&self) -> Vec<Arc<DevPeerState>> {
        self.peers
            .lock()
            .expect("dev peers lock")
            .values()
            .cloned()
            .collect()
    }
}

#[derive(Default)]
struct CirculationSubscriberHub {
    next_id: AtomicU64,
    senders: Mutex<BTreeMap<u64, mpsc::UnboundedSender<CirculationSnapshot>>>,
}

impl CirculationSubscriberHub {
    fn subscribe(&self) -> (u64, mpsc::UnboundedReceiver<CirculationSnapshot>) {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        let (tx, rx) = mpsc::unbounded_channel();
        self.senders
            .lock()
            .expect("circulation subscriber lock")
            .insert(id, tx);
        (id, rx)
    }

    fn remove(&self, id: u64) {
        self.senders
            .lock()
            .expect("circulation subscriber lock")
            .remove(&id);
    }

    fn notify(&self, snapshot: &CirculationSnapshot) {
        let mut guard = self.senders.lock().expect("circulation subscriber lock");
        guard.retain(|_, tx| tx.send(snapshot.clone()).is_ok());
    }
}

struct PendingNoiseWriteGuard {
    inner: Arc<CirculationInner>,
    peer_id: String,
    write_id: u64,
    active: bool,
}

impl PendingNoiseWriteGuard {
    fn new(inner: Arc<CirculationInner>, peer_id: String, mode_id: String) -> Self {
        let write_id = inner.next_write_id.fetch_add(1, Ordering::Relaxed) + 1;
        let pid = peer_id.clone();
        inner.local_meta_tx.send_modify(|meta| {
            meta.pending_noise_writes
                .insert(pid, (write_id, mode_id));
            meta.revision = meta.revision.wrapping_add(1);
        });
        Self {
            inner,
            peer_id,
            write_id,
            active: true,
        }
    }

    fn finish(mut self) {
        self.clear();
    }

    fn clear(&mut self) {
        if !self.active {
            return;
        }
        self.active = false;
        let peer_id = &self.peer_id;
        let write_id = self.write_id;
        self.inner.local_meta_tx.send_modify(|meta| {
            let should_remove = meta
                .pending_noise_writes
                .get(peer_id)
                .is_some_and(|(id, _)| *id == write_id);
            if should_remove {
                meta.pending_noise_writes.remove(peer_id);
                meta.revision = meta.revision.wrapping_add(1);
            }
        });
    }
}

impl Drop for PendingNoiseWriteGuard {
    fn drop(&mut self) {
        self.clear();
    }
}

struct CirculationInner {
    node: CirculationNode,
    local_meta_tx: watch::Sender<LocalMeta>,
    _local_meta_rx: watch::Receiver<LocalMeta>,
    startup_rx: watch::Receiver<bool>,
    subscribers: Arc<CirculationSubscriberHub>,
    dev_state: Option<Arc<DevCirculationState>>,
    watcher_task: Mutex<Option<JoinHandle<()>>>,
    offer_expiry_task: Mutex<Option<JoinHandle<()>>>,
    next_write_id: AtomicU64,
}

impl CirculationInner {
    fn snapshot(&self) -> CirculationSnapshot {
        let meta = self.local_meta_tx.borrow().clone();
        let peers = self.node.peers();
        let nearby = self.node.nearby();
        let readings = self.node.peer_readings();
        let paired_ids: BTreeSet<&PeerId> = peers.iter().map(|peer| &peer.id).collect();
        let nearby_ids: BTreeSet<&PeerId> = nearby.iter().map(|peer| &peer.id).collect();

        let nearby_peers = nearby
            .iter()
            .map(|peer| NearbyPeerView {
                id: peer.id.as_str().to_string(),
                name: peer.name.clone(),
                addresses: peer.addresses.iter().map(ToString::to_string).collect(),
                port: peer.port,
                paired: paired_ids.contains(&peer.id),
            })
            .collect();

        let mut remote_readings = BTreeMap::new();
        let paired_peers = peers
            .iter()
            .map(|peer| {
                let reading = readings
                    .get(&peer.id)
                    .cloned()
                    .unwrap_or_else(RemoteReading::unavailable);
                remote_readings.insert(peer.id.as_str().to_string(), reading.clone());
                PairedPeerView {
                    id: peer.id.as_str().to_string(),
                    name: peer.name.clone(),
                    available: nearby_ids.contains(&peer.id),
                    reading,
                }
            })
            .collect();

        let pending_noise_writes = meta
            .pending_noise_writes
            .into_iter()
            .map(|(peer_id, (_, mode))| (peer_id, mode))
            .collect();

        let pairing_offer = self
            .node
            .current_pairing_offer()
            .as_ref()
            .map(pairing_offer_to_view);

        CirculationSnapshot {
            local_peer: LocalPeerView {
                id: self.node.id().as_str().to_string(),
                name: self.node.name().to_string(),
                started: meta.started,
                bound_port: meta.bound_port,
                start_error: meta.start_error,
            },
            paired_peers,
            nearby_peers,
            pairing_offer,
            remote_readings,
            pending_noise_writes,
            last_error: meta.last_error,
        }
    }

    async fn wait_started(&self) {
        let mut rx = self.startup_rx.clone();
        let _ = rx.wait_for(|ready| *ready).await;
    }

    fn set_last_error(&self, code: Option<String>) {
        self.local_meta_tx.send_modify(|meta| {
            meta.last_error = code;
            meta.revision = meta.revision.wrapping_add(1);
        });
    }

    fn bump_meta(&self) {
        self.local_meta_tx.send_modify(|meta| {
            meta.revision = meta.revision.wrapping_add(1);
        });
    }

    fn record_result<T>(&self, result: Result<T, RemoteError>) -> Result<T, String> {
        match result {
            Ok(value) => {
                self.set_last_error(None);
                Ok(value)
            }
            Err(err) => {
                let code = remote_error_code(&err);
                self.set_last_error(Some(code.clone()));
                Err(code)
            }
        }
    }

    fn schedule_offer_expiry(self: &Arc<Self>, valid_for_millis: u64) {
        let mut guard = self
            .offer_expiry_task
            .lock()
            .expect("offer expiry task lock");
        if let Some(handle) = guard.take() {
            handle.abort();
        }
        let weak = Arc::downgrade(self);
        let delay = Duration::from_millis(valid_for_millis.saturating_add(50));
        *guard = Some(SHARED_RUNTIME.spawn(async move {
            tokio::time::sleep(delay).await;
            if let Some(inner) = weak.upgrade() {
                inner.bump_meta();
            }
        }));
    }

    async fn offer_pairing(self: &Arc<Self>) -> Result<PairingOfferView, String> {
        self.wait_started().await;
        let offer = self.record_result(self.node.offer_pairing())?;
        let view = pairing_offer_to_view(&offer);
        self.schedule_offer_expiry(offer.valid_for_millis);
        self.bump_meta();
        Ok(view)
    }

    async fn pair(&self, peer_id: String, code: String) -> Result<Peer, String> {
        self.wait_started().await;
        let trimmed = code.trim();
        self.record_result(self.node.pair(&PeerId::new(peer_id), trimmed).await)
    }

    async fn pair_qr(&self, qr: String) -> Result<Peer, String> {
        self.wait_started().await;
        self.record_result(self.node.pair_qr(qr.trim()).await)
    }

    async fn forget(&self, peer_id: String) -> Result<(), String> {
        self.wait_started().await;
        self.record_result(self.node.forget(&PeerId::new(peer_id)).await)
    }

    async fn refresh_peer(&self, peer_id: String) -> Result<RemoteReading, String> {
        self.wait_started().await;
        self.record_result(self.node.remote_read(&PeerId::new(peer_id)).await)
    }

    async fn set_remote_noise_mode(
        self: &Arc<Self>,
        peer_id: String,
        session: JsonValue,
        mode: String,
    ) -> Result<RemoteReading, String> {
        self.wait_started().await;
        let session_ref: RemoteSessionReference = serde_json::from_value(session).map_err(|_| {
            let code = remote_error_code(&RemoteError::InvalidArgument);
            self.set_last_error(Some(code.clone()));
            code
        })?;
        let parsed = parse_noise_mode(&mode).ok_or_else(|| {
            let code = remote_error_code(&RemoteError::InvalidArgument);
            self.set_last_error(Some(code.clone()));
            code
        })?;
        let guard = PendingNoiseWriteGuard::new(
            Arc::clone(self),
            peer_id.clone(),
            parsed.id().to_string(),
        );
        let result = self
            .node
            .remote_set(
                &PeerId::new(peer_id),
                session_ref,
                HeadsetCommand::NoiseMode(parsed),
            )
            .await;
        guard.finish();
        self.record_result(result)
    }

    async fn close(&self) {
        if let Some(handle) = self
            .offer_expiry_task
            .lock()
            .expect("offer expiry task lock")
            .take()
        {
            handle.abort();
        }
        self.node.close().await;
        self.local_meta_tx.send_modify(|meta| {
            meta.started = false;
            meta.bound_port = None;
            meta.pending_noise_writes.clear();
            meta.revision = meta.revision.wrapping_add(1);
        });
        if let Some(dev) = &self.dev_state {
            for peer in dev.all_peers() {
                peer.node.close().await;
                peer.online.store(false, Ordering::SeqCst);
            }
        }
    }

    async fn dev_add_simulated_peer(&self, name: Option<String>) -> Result<DevPeerStatus, String> {
        self.wait_started().await;
        let dev = self
            .dev_state
            .as_ref()
            .ok_or_else(|| remote_error_code(&RemoteError::UnsupportedOperation))?;
        let seq = dev.seq.fetch_add(1, Ordering::Relaxed) + 1;
        let peer_dir = dev.temp_root.join(format!("peer-{seq}"));
        ensure_secure_dir(&peer_dir).map_err(|err| remote_error_code(&err))?;
        let headset_id = HeadsetId::new(format!("AA:BB:CC:DD:EE:{:02X}", (seq % 250) + 1));
        let (remote_transport, remote_runtime, handle) =
            create_dev_remote_runtime(headset_id.clone())?;
        let peer_name = name
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .unwrap_or_else(|| {
                if seq == 1 {
                    "模拟手机".to_string()
                } else {
                    format!("模拟手机 {seq}")
                }
            });

        let remote_node = CirculationNode::with_control(
            CirculationConfig::loopback(peer_dir, peer_name),
            dev.hub.client(),
            Arc::clone(&remote_runtime),
        )
        .map_err(|err| remote_error_code(&err))?;

        connect_dev_remote_headset(&remote_transport, &remote_runtime, &headset_id).await?;
        remote_node
            .start()
            .await
            .map_err(|err| remote_error_code(&err))?;

        let peer_state = Arc::new(DevPeerState {
            node: remote_node,
            transport: remote_transport,
            runtime: remote_runtime,
            handle,
            headset_id,
            online: AtomicBool::new(true),
            reply_latency_ms: AtomicU64::new(DEFAULT_DEV_PEER_LATENCY_MS),
        });
        let peer_id = peer_state.node.id().as_str().to_string();
        dev.peers
            .lock()
            .expect("dev peers lock")
            .insert(peer_id.clone(), Arc::clone(&peer_state));
        *dev.last_peer_id.lock().expect("dev last peer lock") = Some(peer_id);

        Ok(peer_state.status())
    }

    async fn dev_open_peer_pairing(
        &self,
        peer_id: Option<String>,
    ) -> Result<DevPeerPairingInfo, String> {
        self.wait_started().await;
        let dev = self
            .dev_state
            .as_ref()
            .ok_or_else(|| remote_error_code(&RemoteError::UnsupportedOperation))?;
        let peer = dev.get_peer(peer_id.as_deref())?;
        if !peer.online.load(Ordering::SeqCst) {
            peer.node
                .start()
                .await
                .map_err(|err| remote_error_code(&err))?;
            peer.online.store(true, Ordering::SeqCst);
        }
        let offer = peer
            .node
            .offer_pairing()
            .map_err(|err| remote_error_code(&err))?;
        Ok(DevPeerPairingInfo {
            peer_id: peer.node.id().as_str().to_string(),
            name: peer.node.name().to_string(),
            code: offer.code,
            qr: offer.qr,
            valid_for_millis: offer.valid_for_millis,
        })
    }

    async fn dev_set_peer_online(
        &self,
        online: bool,
        peer_id: Option<String>,
    ) -> Result<(), String> {
        self.wait_started().await;
        let dev = self
            .dev_state
            .as_ref()
            .ok_or_else(|| remote_error_code(&RemoteError::UnsupportedOperation))?;
        let peer = dev.get_peer(peer_id.as_deref())?;
        if online {
            if !peer.online.load(Ordering::SeqCst) {
                peer.node
                    .start()
                    .await
                    .map_err(|err| remote_error_code(&err))?;
                peer.online.store(true, Ordering::SeqCst);
            }
        } else if peer.online.load(Ordering::SeqCst) {
            peer.node.close().await;
            peer.online.store(false, Ordering::SeqCst);
        }
        Ok(())
    }
}

impl Drop for CirculationInner {
    fn drop(&mut self) {
        if let Some(task) = self.watcher_task.lock().expect("watcher task lock").take() {
            task.abort();
        }
        if let Some(task) = self
            .offer_expiry_task
            .lock()
            .expect("offer expiry task lock")
            .take()
        {
            task.abort();
        }
        let node = self.node.clone();
        let dev_state = self.dev_state.clone();
        SHARED_RUNTIME.spawn(async move {
            node.close().await;
            if let Some(dev) = dev_state {
                for peer in dev.all_peers() {
                    peer.node.close().await;
                    let _ = peer.runtime.disconnect().await;
                    let _ = &peer.transport;
                    let _ = &peer.headset_id;
                }
                let _ = fs::remove_dir_all(&dev.temp_root);
            }
        });
    }
}

fn run_circulation_core<T: Send + nsh_core::element::Ret + 'static>(
    work: impl Future<Output = Result<T, String>> + Send + 'static,
) -> Later<T> {
    Later::new(async move {
        let (tx, rx) = oneshot::channel();
        SHARED_RUNTIME.spawn(async move {
            let result = work.await;
            let _ = tx.send(result);
        });
        rx.await
            .map_err(|_| JsError::Error(remote_error_code(&RemoteError::ServiceClosed)))?
            .map_err(JsError::Error)
    })
}

fn remote_error_code(err: &RemoteError) -> String {
    match err {
        RemoteError::ControlFailed(code) => format!("ControlFailed:{code:?}"),
        other => format!("{other:?}"),
    }
}

fn parse_noise_mode(raw: &str) -> Option<NoiseMode> {
    let trimmed = raw.trim();
    NoiseMode::from_id(trimmed)
        .or_else(|| NoiseMode::from_id(&trimmed.to_ascii_lowercase()))
}

fn pairing_offer_to_view(offer: &PairingOffer) -> PairingOfferView {
    let (qr_size, qr_modules) = encode_qr_matrix(&offer.qr);
    PairingOfferView {
        code: offer.code.clone(),
        qr: offer.qr.clone(),
        valid_for_millis: offer.valid_for_millis,
        qr_size,
        qr_modules,
    }
}

fn encode_qr_matrix(payload: &str) -> (usize, String) {
    let Ok(code) = QrCode::new(payload.as_bytes()) else {
        return (0, String::new());
    };
    let size = code.width();
    let modules = code
        .into_colors()
        .into_iter()
        .map(|c| match c {
            Color::Dark => '1',
            Color::Light => '0',
        })
        .collect();
    (size, modules)
}

fn default_device_name() -> String {
    let raw = std::env::var("HOSTNAME")
        .ok()
        .or_else(|| std::env::var("COMPUTERNAME").ok())
        .or_else(|| fs::read_to_string("/etc/hostname").ok())
        .unwrap_or_else(|| "OpenRose".to_string());
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        "OpenRose".to_string()
    } else {
        trimmed.chars().take(48).collect()
    }
}

fn fallback_app_data_dir(
    xdg_data_home: Option<&str>,
    home: Option<&str>,
    temp_dir: &Path,
) -> PathBuf {
    if let Some(xdg) = xdg_data_home.map(str::trim).filter(|s| !s.is_empty()) {
        return PathBuf::from(xdg).join(APP_ID);
    }
    if let Some(home_dir) = home.map(str::trim).filter(|s| !s.is_empty()) {
        return PathBuf::from(home_dir).join(".local/share").join(APP_ID);
    }
    temp_dir.join(APP_ID)
}

fn production_storage_dir() -> PathBuf {
    let base = data_dir().unwrap_or_else(|| {
        let xdg = std::env::var("XDG_DATA_HOME").ok();
        let home = std::env::var("HOME").ok();
        fallback_app_data_dir(xdg.as_deref(), home.as_deref(), &std::env::temp_dir())
    });
    base.join("circulation")
}

fn ensure_secure_dir(dir: &Path) -> Result<(), RemoteError> {
    fs::create_dir_all(dir).map_err(|_| RemoteError::StorageError)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(dir, fs::Permissions::from_mode(0o700))
            .map_err(|_| RemoteError::StorageError)?;
    }
    Ok(())
}

fn create_production_discovery() -> Result<Arc<dyn PeerDiscovery>, RemoteError> {
    let discovery = openrose_desktop_platform::discovery::MdnsDiscovery::new()
        .map_err(|_| RemoteError::DiscoveryError)?;
    Ok(Arc::new(discovery))
}

fn create_dev_remote_runtime(
    headset_id: HeadsetId,
) -> Result<(Arc<SimulatedTransport>, Arc<ControlRuntime>, SimulatedHeadsetHandle), String> {
    let transport = Arc::new(SimulatedTransport::new());
    let profile = ModelProfile::I5Zt;
    let handle = transport
        .add_headset(
            headset_id.clone(),
            profile,
            profile.family().default_firmware(),
        )
        .map_err(|_| remote_error_code(&RemoteError::IdentityError))?;
    handle.reply_latency(Duration::from_millis(DEFAULT_DEV_PEER_LATENCY_MS));
    transport.set_audio_connected(Some(headset_id));
    let mut config = ControlConfig::new(
        PlatformState::new(PermissionState::Granted, TransportAvailability::Available),
        Arc::new(SystemClock),
    );
    config.timing = SessionTiming {
        write_interval: Some(Duration::ZERO),
        ..SessionTiming::default()
    };
    let runtime = {
        let _guard = SHARED_RUNTIME.enter();
        Arc::new(ControlRuntime::new(
            transport.clone(),
            Arc::new(NoopObserver),
            config,
        ))
    };
    Ok((transport, runtime, handle))
}

async fn connect_dev_remote_headset(
    transport: &SimulatedTransport,
    runtime: &ControlRuntime,
    headset_id: &HeadsetId,
) -> Result<(), String> {
    let profile = ModelProfile::I5Zt;
    runtime.refresh_bonded_headsets().await;
    let ready = tokio::time::timeout(Duration::from_secs(10), async {
        loop {
            let snapshot = runtime.snapshot();
            if snapshot.ready_session().is_some() {
                return;
            }
            let observed = snapshot
                .candidates
                .iter()
                .any(|candidate| candidate.id == *headset_id && candidate.profile.is_some());
            if !observed {
                let _ = runtime.start_scan().await;
                transport
                    .scan()
                    .advertise(catalog_advertisement(profile, headset_id));
            } else if matches!(snapshot.connection, ConnectionState::Disconnected) {
                let _ = runtime.connect(headset_id.clone()).await;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await;
    runtime.stop_scan().await;
    ready.map_err(|_| remote_error_code(&RemoteError::SessionNotReady))
}

/// Circulation pairing and remote headset control node tied to the desktop app lifecycle.
#[class]
pub struct OpenRoseCirculation {
    inner: Arc<CirculationInner>,
}

impl OpenRoseCirculation {
    fn create_for_client(client: &OpenRoseClient) -> Result<Self, JsError> {
        let control = Arc::clone(&client.runtime);
        if client.dev.is_some() {
            Self::create_dev(control)
        } else {
            Self::create_production(control, production_storage_dir(), default_device_name())
        }
    }

    fn create_dev(control: Arc<ControlRuntime>) -> Result<Self, JsError> {
        static DEV_SEQ: AtomicU64 = AtomicU64::new(1);
        let seq = DEV_SEQ.fetch_add(1, Ordering::Relaxed);
        let temp_root = std::env::temp_dir().join(format!(
            "openrose-dev-circ-{}-{seq}",
            std::process::id()
        ));
        let local_dir = temp_root.join("local");
        ensure_secure_dir(&local_dir)
            .map_err(|err| JsError::Error(remote_error_code(&err)))?;

        let hub = LoopbackDiscoveryHub::new();
        let node = CirculationNode::with_control(
            CirculationConfig::loopback(local_dir, "本机桌面"),
            hub.client(),
            control,
        )
        .map_err(|err| JsError::Error(remote_error_code(&err)))?;

        let dev_state = Arc::new(DevCirculationState {
            hub,
            temp_root,
            seq: AtomicU64::new(0),
            peers: Mutex::new(BTreeMap::new()),
            last_peer_id: Mutex::new(None),
        });

        Ok(Self::spawn_inner(node, None, Some(dev_state)))
    }

    fn create_production(
        control: Arc<ControlRuntime>,
        storage_dir: PathBuf,
        name: String,
    ) -> Result<Self, JsError> {
        ensure_secure_dir(&storage_dir)
            .map_err(|err| JsError::Error(remote_error_code(&err)))?;
        let (discovery, initial_error) = match create_production_discovery() {
            Ok(d) => (d, None),
            Err(err) => (
                UnavailableDiscovery::new() as Arc<dyn PeerDiscovery>,
                Some(remote_error_code(&err)),
            ),
        };
        let node = CirculationNode::with_control(
            CirculationConfig::new(storage_dir, name),
            discovery,
            control,
        )
        .map_err(|err| JsError::Error(remote_error_code(&err)))?;

        Ok(Self::spawn_inner(node, initial_error, None))
    }

    fn spawn_inner(
        node: CirculationNode,
        initial_error: Option<String>,
        dev_state: Option<Arc<DevCirculationState>>,
    ) -> Self {
        let initial_meta = LocalMeta {
            start_error: initial_error.clone(),
            last_error: initial_error.clone(),
            ..LocalMeta::default()
        };
        let (local_meta_tx, local_meta_rx) = watch::channel(initial_meta);
        let (startup_tx, startup_rx) = watch::channel(false);
        let subscribers = Arc::new(CirculationSubscriberHub::default());
        let inner = Arc::new(CirculationInner {
            node: node.clone(),
            local_meta_tx,
            _local_meta_rx: local_meta_rx,
            startup_rx,
            subscribers,
            dev_state,
            watcher_task: Mutex::new(None),
            offer_expiry_task: Mutex::new(None),
            next_write_id: AtomicU64::new(0),
        });

        let weak_watcher = Arc::downgrade(&inner);
        let mut peers_rx = node.watch_peers();
        let mut nearby_rx = node.watch_nearby();
        let mut readings_rx = node.watch_peer_readings();
        let mut meta_rx = inner.local_meta_tx.subscribe();
        let watcher = SHARED_RUNTIME.spawn(async move {
            loop {
                tokio::select! {
                    res = peers_rx.changed() => {
                        if res.is_err() { break; }
                    }
                    res = nearby_rx.changed() => {
                        if res.is_err() { break; }
                    }
                    res = readings_rx.changed() => {
                        if res.is_err() { break; }
                    }
                    res = meta_rx.changed() => {
                        if res.is_err() { break; }
                    }
                }
                let Some(inner) = weak_watcher.upgrade() else {
                    break;
                };
                let snap = inner.snapshot();
                inner.subscribers.notify(&snap);
            }
        });
        *inner.watcher_task.lock().expect("watcher task lock") = Some(watcher);

        let weak_start = Arc::downgrade(&inner);
        SHARED_RUNTIME.spawn(async move {
            if let Some(code) = initial_error {
                if let Some(inner) = weak_start.upgrade() {
                    inner.local_meta_tx.send_modify(|meta| {
                        meta.start_error = Some(code.clone());
                        meta.last_error = Some(code);
                        meta.revision = meta.revision.wrapping_add(1);
                    });
                }
            } else {
                match node.start().await {
                    Ok(port) => {
                        if let Some(inner) = weak_start.upgrade() {
                            inner.local_meta_tx.send_modify(|meta| {
                                meta.started = true;
                                meta.bound_port = Some(port);
                                meta.start_error = None;
                                meta.revision = meta.revision.wrapping_add(1);
                            });
                        }
                    }
                    Err(err) => {
                        if let Some(inner) = weak_start.upgrade() {
                            let code = remote_error_code(&err);
                            inner.local_meta_tx.send_modify(|meta| {
                                meta.start_error = Some(code.clone());
                                meta.last_error = Some(code);
                                meta.revision = meta.revision.wrapping_add(1);
                            });
                        }
                    }
                }
            }
            let _ = startup_tx.send(true);
        });

        Self { inner }
    }
}

#[class]
impl OpenRoseCirculation {
    /// Creates a circulation node whose identity and trust live in the app data directory (or an isolated loopback peer in dev mode).
    pub fn new(client: Instance<OpenRoseClient>) -> Result<Self, JsError> {
        if client.is_disposed() {
            return Err(JsError::Error(remote_error_code(&RemoteError::ServiceClosed)));
        }
        client.with(Self::create_for_client)
    }

    /// Reads the latest circulation snapshot (local peer, paired peers with availability, nearby peers, pairing offer, remote readings, pending noise writes, and last error code).
    pub fn snapshot(&self) -> JsonValue {
        serde_json::to_value(self.inner.snapshot()).expect("circulation snapshot serializes")
    }

    /// Subscribes to circulation state updates and forwards each snapshot to JS.
    pub fn subscribe(&self, cx: CallCx<'_>, on_snapshot: Callback<JsonValue>) -> Stop {
        let (sub_id, mut rx) = self.inner.subscribers.subscribe();
        let initial =
            serde_json::to_value(self.inner.snapshot()).expect("circulation snapshot serializes");
        on_snapshot.post(initial);
        cx.start(async move {
            while let Some(snapshot) = rx.recv().await {
                let json = serde_json::to_value(&snapshot)
                    .expect("circulation snapshot serializes");
                on_snapshot.post(json);
            }
        });
        let subscribers = Arc::clone(&self.inner.subscribers);
        Stop::new(move || {
            subscribers.remove(sub_id);
        })
    }

    /// Opens a fresh pairing window on this device and returns the 6-digit code, QR payload, and QR matrix.
    pub fn offer_pairing(&self) -> Later<JsonValue> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move {
            let view = inner.offer_pairing().await?;
            Ok(serde_json::to_value(view).expect("pairing offer serializes"))
        })
    }

    /// Pairs with a nearby discovered peer by entering its 6-digit pairing code.
    pub fn pair(&self, peer_id: String, code: String) -> Later<JsonValue> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move {
            let peer = inner.pair(peer_id, code).await?;
            Ok(serde_json::to_value(peer).expect("peer serializes"))
        })
    }

    /// Pairs with a peer using a scanned QR payload string.
    pub fn pair_qr(&self, qr: String) -> Later<JsonValue> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move {
            let peer = inner.pair_qr(qr).await?;
            Ok(serde_json::to_value(peer).expect("peer serializes"))
        })
    }

    /// Unpairs a paired peer immediately and notifies it best-effort.
    pub fn forget(&self, peer_id: String) -> Later<()> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move { inner.forget(peer_id).await })
    }

    /// Refreshes the current headset reading from the given paired peer.
    pub fn refresh_peer(&self, peer_id: String) -> Later<JsonValue> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move {
            let reading = inner.refresh_peer(peer_id).await?;
            Ok(serde_json::to_value(reading).expect("remote reading serializes"))
        })
    }

    /// Switches the noise mode on the peer's held headset and resolves after the holder confirms.
    pub fn set_remote_noise_mode(
        &self,
        peer_id: String,
        session: JsonValue,
        mode: String,
    ) -> Later<JsonValue> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move {
            let reading = inner
                .set_remote_noise_mode(peer_id, session, mode)
                .await?;
            Ok(serde_json::to_value(reading).expect("remote reading serializes"))
        })
    }

    /// Clears the last recorded error code in the circulation snapshot.
    pub fn clear_error(&self) {
        self.inner.set_last_error(None);
    }

    /// Closes the circulation node and stops discovery advertisement.
    pub fn close(&self) -> Later<()> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move {
            inner.close().await;
            Ok(())
        })
    }

    /// Adds and starts a dev-mode simulated peer node holding a simulated EarFree i5 on loopback discovery.
    pub fn dev_add_simulated_peer(&self, name: Option<String>) -> Later<JsonValue> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move {
            let status = inner.dev_add_simulated_peer(name).await?;
            Ok(serde_json::to_value(status).expect("dev peer status serializes"))
        })
    }

    /// Opens a pairing window on a dev-mode simulated peer and returns its peer id, name, code, and QR payload.
    pub fn dev_open_peer_pairing(&self, peer_id: Option<String>) -> Later<JsonValue> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move {
            let info = inner.dev_open_peer_pairing(peer_id).await?;
            Ok(serde_json::to_value(info).expect("dev peer pairing info serializes"))
        })
    }

    /// Toggles a dev-mode simulated peer online or offline to exercise availability and re-read on return.
    pub fn dev_set_peer_online(&self, online: bool, peer_id: Option<String>) -> Later<()> {
        let inner = Arc::clone(&self.inner);
        run_circulation_core(async move { inner.dev_set_peer_online(online, peer_id).await })
    }

    /// Updates a dev-mode simulated peer headset reply latency in milliseconds.
    pub fn dev_set_peer_latency_ms(
        &self,
        millis: u32,
        peer_id: Option<String>,
    ) -> Result<(), JsError> {
        let dev = self
            .inner
            .dev_state
            .as_ref()
            .ok_or_else(|| JsError::Error(remote_error_code(&RemoteError::UnsupportedOperation)))?;
        let peer = dev
            .get_peer(peer_id.as_deref())
            .map_err(JsError::Error)?;
        peer.reply_latency_ms
            .store(u64::from(millis), Ordering::SeqCst);
        peer.handle
            .reply_latency(Duration::from_millis(u64::from(millis)));
        Ok(())
    }

    /// Returns a dev-mode simulated peer status, or null when unavailable or outside dev mode.
    pub fn dev_peer_status(&self, peer_id: Option<String>) -> Option<JsonValue> {
        let dev = self.inner.dev_state.as_ref()?;
        let peer = dev.get_peer(peer_id.as_deref()).ok()?;
        Some(serde_json::to_value(peer.status()).expect("dev peer status serializes"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use openrose_circulation::RemoteControlStatus;

    struct TempDir(PathBuf);

    impl TempDir {
        fn new(label: &str) -> Self {
            static SEQ: AtomicU64 = AtomicU64::new(1);
            let id = SEQ.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "openrose-native-circ-test-{label}-{}-{id}",
                std::process::id()
            ));
            let _ = fs::remove_dir_all(&path);
            fs::create_dir_all(&path).expect("create temp dir");
            Self(path)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    async fn wait_until(label: &str, mut pred: impl FnMut() -> bool) {
        for _ in 0..150 {
            if pred() {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        panic!("condition not satisfied before timeout: {label}");
    }

    #[test]
    fn dev_mode_pairs_by_code_reads_sets_noise_mode_and_tracks_availability() {
        let client = OpenRoseClient::create_dev(20);
        let circ = OpenRoseCirculation::create_for_client(&client).expect("create dev circulation");
        let inner = Arc::clone(&circ.inner);

        SHARED_RUNTIME.block_on(async move {
            inner.wait_started().await;
            let init_snap = inner.snapshot();
            assert!(init_snap.local_peer.started);
            assert!(init_snap.local_peer.bound_port.is_some());
            assert!(init_snap.nearby_peers.is_empty());
            assert!(init_snap.paired_peers.is_empty());
            assert!(init_snap.last_error.is_none());

            let peer_status = inner
                .dev_add_simulated_peer(None)
                .await
                .expect("add simulated peer");
            let dev_id = peer_status.peer_id.clone();

            wait_until("nearby", || {
                inner
                    .snapshot()
                    .nearby_peers
                    .iter()
                    .any(|p| p.id == dev_id && !p.paired)
            })
            .await;

            let local_offer = inner.offer_pairing().await.expect("local offer");
            assert!(!local_offer.qr_modules.is_empty());
            assert!(inner.snapshot().pairing_offer.is_some());

            let peer_offer = inner
                .dev_open_peer_pairing(Some(dev_id.clone()))
                .await
                .expect("peer offer");

            let wrong_code = if peer_offer.code == "000000" {
                "111111".to_string()
            } else {
                "000000".to_string()
            };
            let wrong_err = inner
                .pair(dev_id.clone(), wrong_code)
                .await
                .expect_err("wrong code should fail");
            assert_eq!(wrong_err, "PairingRejected");
            assert_eq!(
                inner.snapshot().last_error.as_deref(),
                Some("PairingRejected")
            );

            let paired = inner
                .pair(dev_id.clone(), peer_offer.code)
                .await
                .expect("pair with dev peer");
            assert_eq!(paired.id.as_str(), dev_id);
            assert!(inner.snapshot().last_error.is_none());

            wait_until("paired ready", || {
                let snap = inner.snapshot();
                snap.paired_peers.iter().any(|p| {
                    p.id == dev_id && p.available && p.reading.status == RemoteControlStatus::Ready
                }) && snap
                    .nearby_peers
                    .iter()
                    .any(|p| p.id == dev_id && p.paired)
            })
            .await;

            let reading = inner.refresh_peer(dev_id.clone()).await.expect("remote read");
            assert_eq!(reading.noise_mode, Some(NoiseMode::Wind));
            assert_eq!(
                inner
                    .snapshot()
                    .remote_readings
                    .get(&dev_id)
                    .and_then(|r| r.noise_mode),
                Some(NoiseMode::Wind)
            );

            let session = serde_json::to_value(reading.session.expect("session"))
                .expect("session json");
            circ.dev_set_peer_latency_ms(120, Some(dev_id.clone()))
                .expect("set latency");

            let inner_for_write = Arc::clone(&inner);
            let dev_id_for_write = dev_id.clone();
            let session_for_write = session.clone();
            let write_task = tokio::spawn(async move {
                inner_for_write
                    .set_remote_noise_mode(dev_id_for_write, session_for_write, "anc".to_string())
                    .await
            });

            wait_until("pending noise write observed", || {
                inner
                    .snapshot()
                    .pending_noise_writes
                    .get(&dev_id)
                    .map(String::as_str)
                    == Some("anc")
            })
            .await;

            let updated = write_task
                .await
                .expect("write task")
                .expect("remote set noise");
            assert_eq!(updated.noise_mode, Some(NoiseMode::Anc));
            assert!(inner.snapshot().pending_noise_writes.is_empty());
            assert_eq!(
                circ.dev_peer_status(Some(dev_id.clone()))
                    .and_then(|v| v.get("noiseMode").and_then(|m| m.as_str()).map(str::to_string))
                    .as_deref(),
                Some("anc")
            );

            inner
                .dev_set_peer_online(false, Some(dev_id.clone()))
                .await
                .expect("set peer offline");
            wait_until("offline unavailable", || {
                inner.snapshot().paired_peers.iter().any(|p| {
                    p.id == dev_id
                        && !p.available
                        && p.reading.status == RemoteControlStatus::Unavailable
                })
            })
            .await;

            inner
                .dev_set_peer_online(true, Some(dev_id.clone()))
                .await
                .expect("set peer back online");
            wait_until("back ready", || {
                inner.snapshot().paired_peers.iter().any(|p| {
                    p.id == dev_id
                        && p.available
                        && p.reading.status == RemoteControlStatus::Ready
                        && p.reading.noise_mode == Some(NoiseMode::Anc)
                })
            })
            .await;

            inner.forget(dev_id.clone()).await.expect("forget");
            let after_forget = inner.snapshot();
            assert!(after_forget.paired_peers.is_empty());
            assert!(!after_forget.remote_readings.contains_key(&dev_id));
        });
    }

    #[test]
    fn dev_mode_pairs_by_qr_and_clears_offer_on_responder() {
        let client = OpenRoseClient::create_dev(20);
        let circ = OpenRoseCirculation::create_for_client(&client).expect("create dev circulation");
        let inner = Arc::clone(&circ.inner);

        SHARED_RUNTIME.block_on(async move {
            inner.wait_started().await;
            let status = inner
                .dev_add_simulated_peer(Some("手机 B".to_string()))
                .await
                .expect("add simulated peer");
            let dev_id = status.peer_id;
            let peer_offer = inner
                .dev_open_peer_pairing(Some(dev_id.clone()))
                .await
                .expect("open peer pairing");
            let paired = inner.pair_qr(peer_offer.qr).await.expect("pair by qr");
            assert_eq!(paired.id.as_str(), dev_id);
            assert_eq!(paired.name, "手机 B");

            let bad_session = inner
                .set_remote_noise_mode(
                    dev_id,
                    serde_json::json!({ "peer_id": "invalid", "headset_id": "none", "token": "bad" }),
                    "normal".to_string(),
                )
                .await
                .expect_err("stale session should fail");
            assert_eq!(bad_session, "SessionExpired");
            assert_eq!(
                inner.snapshot().last_error.as_deref(),
                Some("SessionExpired")
            );

            circ.clear_error();
            assert!(inner.snapshot().last_error.is_none());
        });
    }

    #[test]
    fn subscriber_hub_supports_multiple_subscribers_independently() {
        let hub = CirculationSubscriberHub::default();
        let (id1, mut rx1) = hub.subscribe();
        let (id2, mut rx2) = hub.subscribe();
        let client = OpenRoseClient::create_dev(10);
        let circ = OpenRoseCirculation::create_for_client(&client).expect("create dev circulation");
        let snap = circ.inner.snapshot();
        hub.notify(&snap);
        assert!(rx1.try_recv().is_ok());
        assert!(rx2.try_recv().is_ok());

        hub.remove(id1);
        hub.notify(&snap);
        assert!(rx1.try_recv().is_err());
        assert!(rx2.try_recv().is_ok());
        hub.remove(id2);
    }

    #[test]
    fn identity_and_trust_persist_with_secure_permissions() {
        let dir = TempDir::new("persist");
        let client = OpenRoseClient::create_dev(10);
        let circ1 = OpenRoseCirculation::create_production(
            Arc::clone(&client.runtime),
            dir.0.clone(),
            "Desktop 1".to_string(),
        )
        .expect("create first");
        let id1 = circ1.inner.node.id().clone();
        drop(circ1);

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let dir_mode = fs::metadata(&dir.0)
                .expect("dir metadata")
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(dir_mode, 0o700);
            let identity_mode = fs::metadata(dir.0.join("identity.json"))
                .expect("identity metadata")
                .permissions()
                .mode()
                & 0o777;
            assert_eq!(identity_mode, 0o600);
        }

        let circ2 = OpenRoseCirculation::create_production(
            Arc::clone(&client.runtime),
            dir.0.clone(),
            "Desktop 1".to_string(),
        )
        .expect("create second");
        let id2 = circ2.inner.node.id().clone();
        assert_eq!(id1, id2);
    }

    #[test]
    fn fallback_app_data_dir_uses_xdg_then_home_then_temp() {
        let temp = PathBuf::from("/tmp");
        assert_eq!(
            fallback_app_data_dir(Some("/var/data"), Some("/home/u"), &temp),
            PathBuf::from("/var/data/io.github.dohex.openrose")
        );
        assert_eq!(
            fallback_app_data_dir(Some("  "), Some("/home/u"), &temp),
            PathBuf::from("/home/u/.local/share/io.github.dohex.openrose")
        );
        assert_eq!(
            fallback_app_data_dir(None, None, &temp),
            PathBuf::from("/tmp/io.github.dohex.openrose")
        );
    }
}
