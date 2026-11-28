use async_trait::async_trait;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo, TxtProperties};
use openrose_circulation::{
    MAX_ADVERTISED_NAME_LEN, MDNS_SERVICE_TYPE, NearbyPeer, PeerDiscovery, PeerId, RemoteError,
    TXT_KEY_ID, TXT_KEY_NAME, TXT_KEY_VERSION, WIRE_VERSION, advertised_addresses,
    filter_resolved_addresses, service_instance_name,
};
use std::collections::BTreeMap;
use std::fmt;
use std::net::IpAddr;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{Mutex as AsyncMutex, watch};
use tokio::task::JoinHandle;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveryError {
    message: String,
}

impl DiscoveryError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl fmt::Display for DiscoveryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for DiscoveryError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct AdvertisedPeer {
    pub id: PeerId,
    pub name: String,
    pub version: u32,
}

pub(crate) fn truncate_advertised_name(name: &str) -> String {
    name.chars().take(MAX_ADVERTISED_NAME_LEN).collect()
}

pub(crate) fn encode_txt_properties(
    id: &PeerId,
    name: &str,
    version: u32,
) -> Vec<(String, String)> {
    vec![
        (TXT_KEY_ID.to_string(), id.as_str().to_string()),
        (TXT_KEY_NAME.to_string(), truncate_advertised_name(name)),
        (TXT_KEY_VERSION.to_string(), version.to_string()),
    ]
}

pub(crate) fn decode_txt_properties(props: &TxtProperties) -> Option<AdvertisedPeer> {
    let id = props
        .get_property_val_str(TXT_KEY_ID)
        .map(str::trim)
        .filter(|s| !s.is_empty())?;
    let name = props.get_property_val_str(TXT_KEY_NAME)?;
    let version = props.get_property_val_str(TXT_KEY_VERSION)?.trim().parse::<u32>().ok()?;
    Some(AdvertisedPeer {
        id: PeerId::new(id),
        name: truncate_advertised_name(name),
        version,
    })
}

#[derive(Default)]
pub(crate) struct DiscoveryCatalog {
    own_id: Option<PeerId>,
    by_fullname: BTreeMap<String, NearbyPeer>,
}

impl DiscoveryCatalog {
    pub(crate) fn reset(&mut self, own_id: PeerId) {
        self.own_id = Some(own_id);
        self.by_fullname.clear();
    }

    pub(crate) fn clear(&mut self) {
        self.own_id = None;
        self.by_fullname.clear();
    }

    pub(crate) fn apply_resolved(
        &mut self,
        fullname: &str,
        props: &TxtProperties,
        port: u16,
        addresses: impl IntoIterator<Item = IpAddr>,
    ) -> bool {
        let key = fullname.to_lowercase();
        let Some(advertised) = decode_txt_properties(props) else {
            return self.by_fullname.remove(&key).is_some();
        };
        if advertised.version != WIRE_VERSION {
            return self.by_fullname.remove(&key).is_some();
        }
        if self.own_id.as_ref() == Some(&advertised.id) {
            return self.by_fullname.remove(&key).is_some();
        }
        if port == 0 {
            return self.by_fullname.remove(&key).is_some();
        }
        let filtered_addrs = filter_resolved_addresses(addresses);
        if filtered_addrs.is_empty() {
            return self.by_fullname.remove(&key).is_some();
        }
        let peer = NearbyPeer {
            id: advertised.id,
            name: advertised.name,
            addresses: filtered_addrs,
            port,
        };
        if self.by_fullname.get(&key) == Some(&peer) {
            return false;
        }
        self.by_fullname.insert(key, peer);
        true
    }

    pub(crate) fn apply_removed(&mut self, fullname: &str) -> bool {
        self.by_fullname.remove(&fullname.to_lowercase()).is_some()
    }

    pub(crate) fn snapshot(&self) -> Vec<NearbyPeer> {
        let mut by_id: BTreeMap<PeerId, NearbyPeer> = BTreeMap::new();
        for peer in self.by_fullname.values() {
            by_id
                .entry(peer.id.clone())
                .and_modify(|existing| {
                    let mut combined = existing.addresses.clone();
                    combined.extend(peer.addresses.iter().copied());
                    existing.addresses = filter_resolved_addresses(combined);
                    existing.name = peer.name.clone();
                    existing.port = peer.port;
                })
                .or_insert_with(|| peer.clone());
        }
        by_id.into_values().collect()
    }
}

struct ActiveDiscovery {
    fullname: String,
    browse_task: JoinHandle<()>,
}

pub struct MdnsDiscovery {
    daemon: ServiceDaemon,
    catalog: Arc<Mutex<DiscoveryCatalog>>,
    active: AsyncMutex<Option<ActiveDiscovery>>,
    nearby_tx: watch::Sender<Vec<NearbyPeer>>,
    _nearby_rx: watch::Receiver<Vec<NearbyPeer>>,
}

impl MdnsDiscovery {
    pub fn new() -> Result<Self, DiscoveryError> {
        let daemon = ServiceDaemon::new().map_err(|err| DiscoveryError::new(err.to_string()))?;
        let (nearby_tx, nearby_rx) = watch::channel(Vec::new());
        Ok(Self {
            daemon,
            catalog: Arc::new(Mutex::new(DiscoveryCatalog::default())),
            active: AsyncMutex::new(None),
            nearby_tx,
            _nearby_rx: nearby_rx,
        })
    }

    async fn stop_active(&self, active: ActiveDiscovery) {
        active.browse_task.abort();
        let _ = active.browse_task.await;
        let _ = self.daemon.stop_browse(MDNS_SERVICE_TYPE);
        if let Ok(rx) = self.daemon.unregister(&active.fullname) {
            let _ = tokio::time::timeout(Duration::from_millis(500), rx.recv_async()).await;
        }
    }
}

impl Drop for MdnsDiscovery {
    fn drop(&mut self) {
        if let Ok(mut guard) = self.active.try_lock()
            && let Some(active) = guard.take()
        {
            active.browse_task.abort();
        }
        let _ = self.daemon.shutdown();
    }
}

fn host_name_for(id: &PeerId) -> String {
    let sanitized: String = id
        .as_str()
        .chars()
        .map(|ch| if ch.is_ascii_alphanumeric() { ch } else { '-' })
        .take(20)
        .collect();
    let trimmed = sanitized.trim_matches('-');
    if trimmed.is_empty() {
        "openrose.local.".to_string()
    } else {
        format!("openrose-{trimmed}.local.")
    }
}

#[async_trait]
impl PeerDiscovery for MdnsDiscovery {
    async fn start(&self, id: &PeerId, name: &str, port: u16) -> Result<(), RemoteError> {
        let mut guard = self.active.lock().await;
        if let Some(previous) = guard.take() {
            self.stop_active(previous).await;
        }

        {
            let mut catalog = self.catalog.lock().expect("discovery catalog lock");
            catalog.reset(id.clone());
        }
        self.nearby_tx.send_replace(Vec::new());

        let addresses = advertised_addresses();
        let props = encode_txt_properties(id, name, WIRE_VERSION);
        let service_info = ServiceInfo::new(
            MDNS_SERVICE_TYPE,
            &service_instance_name(id.as_str()),
            &host_name_for(id),
            &addresses[..],
            port,
            &props[..],
        )
        .map_err(|_| RemoteError::DiscoveryError)?;
        let fullname = service_info.get_fullname().to_string();

        let browse_rx = self
            .daemon
            .browse(MDNS_SERVICE_TYPE)
            .map_err(|_| RemoteError::DiscoveryError)?;

        if self.daemon.register(service_info).is_err() {
            let _ = self.daemon.stop_browse(MDNS_SERVICE_TYPE);
            return Err(RemoteError::DiscoveryError);
        }

        let catalog = Arc::clone(&self.catalog);
        let nearby_tx = self.nearby_tx.clone();
        let browse_task = tokio::spawn(async move {
            while let Ok(event) = browse_rx.recv_async().await {
                let next_snapshot = match event {
                    ServiceEvent::ServiceResolved(resolved) => {
                        let mut guard = catalog.lock().expect("discovery catalog lock");
                        let addrs: Vec<IpAddr> = resolved
                            .get_addresses()
                            .iter()
                            .map(|scoped| scoped.to_ip_addr())
                            .collect();
                        if guard.apply_resolved(
                            resolved.get_fullname(),
                            resolved.get_properties(),
                            resolved.get_port(),
                            addrs,
                        ) {
                            Some(guard.snapshot())
                        } else {
                            None
                        }
                    }
                    ServiceEvent::ServiceRemoved(_, fullname) => {
                        let mut guard = catalog.lock().expect("discovery catalog lock");
                        if guard.apply_removed(&fullname) {
                            Some(guard.snapshot())
                        } else {
                            None
                        }
                    }
                    _ => None,
                };
                if let Some(snapshot) = next_snapshot
                    && *nearby_tx.borrow() != snapshot
                {
                    nearby_tx.send_replace(snapshot);
                }
            }
        });

        *guard = Some(ActiveDiscovery {
            fullname,
            browse_task,
        });
        Ok(())
    }

    fn watch(&self) -> watch::Receiver<Vec<NearbyPeer>> {
        self.nearby_tx.subscribe()
    }

    async fn close(&self) {
        let previous = {
            let mut guard = self.active.lock().await;
            guard.take()
        };
        if let Some(active) = previous {
            self.stop_active(active).await;
        }
        {
            let mut catalog = self.catalog.lock().expect("discovery catalog lock");
            catalog.clear();
        }
        if !self.nearby_tx.borrow().is_empty() {
            self.nearby_tx.send_replace(Vec::new());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mdns_sd::IntoTxtProperties;
    use std::net::{Ipv4Addr, Ipv6Addr};

    fn peer_id(ch: char) -> PeerId {
        PeerId::new(std::iter::repeat_n(ch, 64).collect::<String>())
    }

    #[test]
    fn txt_encoding_truncates_name_on_char_boundary_and_roundtrips() {
        let id = peer_id('a');
        let long_name = "玫瑰音频设备".repeat(15);
        let props = encode_txt_properties(&id, &long_name, WIRE_VERSION);
        let txt = (&props[..]).into_txt_properties();
        let decoded = decode_txt_properties(&txt).expect("valid TXT properties");
        assert_eq!(decoded.id, id);
        assert_eq!(decoded.version, WIRE_VERSION);
        assert_eq!(decoded.name.chars().count(), MAX_ADVERTISED_NAME_LEN);
        assert_eq!(decoded.name, "玫瑰音频设备".repeat(10));

        let mixed = format!("{}🎧tail", "a".repeat(59));
        let mixed_truncated = truncate_advertised_name(&mixed);
        assert_eq!(mixed_truncated.chars().count(), MAX_ADVERTISED_NAME_LEN);
        assert_eq!(mixed_truncated, format!("{}🎧", "a".repeat(59)));
    }

    #[test]
    fn txt_decoding_rejects_missing_or_invalid_fields() {
        let id = peer_id('b');
        let cases: &[&[(&str, &str)]] = &[
            &[("id", id.as_str()), ("name", "Laptop")],
            &[("id", id.as_str()), ("name", "Laptop"), ("v", "nope")],
            &[("id", "   "), ("name", "Laptop"), ("v", "1")],
            &[("id", id.as_str()), ("v", "1")],
        ];
        for props in cases {
            assert_eq!(decode_txt_properties(&(*props).into_txt_properties()), None);
        }
    }

    #[test]
    fn catalog_filters_self_version_mismatch_and_removed_services() {
        let own_id = peer_id('a');
        let other_id = peer_id('b');
        let wrong_v_id = peer_id('c');
        let mut catalog = DiscoveryCatalog::default();
        catalog.reset(own_id.clone());

        let own_props = (&encode_txt_properties(&own_id, "Self", WIRE_VERSION)[..]).into_txt_properties();
        assert!(!catalog.apply_resolved(
            "OpenRose-self._openrose._tcp.local.",
            &own_props,
            21871,
            [IpAddr::V4(Ipv4Addr::new(192, 0, 2, 10))],
        ));
        assert!(catalog.snapshot().is_empty());

        let wrong_v_props =
            (&encode_txt_properties(&wrong_v_id, "OldPeer", WIRE_VERSION + 1)[..]).into_txt_properties();
        assert!(!catalog.apply_resolved(
            "OpenRose-old._openrose._tcp.local.",
            &wrong_v_props,
            21871,
            [IpAddr::V4(Ipv4Addr::new(192, 0, 2, 11))],
        ));
        assert!(catalog.snapshot().is_empty());

        let peer_props =
            (&encode_txt_properties(&other_id, "Phone", WIRE_VERSION)[..]).into_txt_properties();
        assert!(catalog.apply_resolved(
            "OpenRose-peer._openrose._tcp.local.",
            &peer_props,
            21872,
            [
                IpAddr::V6("fe80::1".parse::<Ipv6Addr>().unwrap()),
                IpAddr::V6("2001:db8::20".parse::<Ipv6Addr>().unwrap()),
                IpAddr::V4(Ipv4Addr::new(192, 0, 2, 20)),
            ],
        ));
        assert_eq!(
            catalog.snapshot(),
            vec![NearbyPeer {
                id: other_id,
                name: "Phone".to_string(),
                addresses: vec![
                    IpAddr::V4(Ipv4Addr::new(192, 0, 2, 20)),
                    IpAddr::V6("2001:db8::20".parse::<Ipv6Addr>().unwrap()),
                ],
                port: 21872,
            }]
        );

        assert!(catalog.apply_removed("OPENROSE-PEER._openrose._tcp.local."));
        assert!(catalog.snapshot().is_empty());
    }

    #[tokio::test]
    async fn two_mdns_instances_discover_and_unregister_each_other() {
        let id_a = peer_id('1');
        let id_b = peer_id('2');
        let disc_a = MdnsDiscovery::new().expect("create first mdns discovery");
        let disc_b = MdnsDiscovery::new().expect("create second mdns discovery");
        let mut rx_a = disc_a.watch();
        let mut rx_b = disc_b.watch();

        disc_a
            .start(&id_a, "Desktop A", 21871)
            .await
            .expect("start disc_a");
        disc_b
            .start(&id_b, "Desktop B", 21872)
            .await
            .expect("start disc_b");

        tokio::time::timeout(Duration::from_secs(10), async {
            loop {
                let peers_a = rx_a.borrow().clone();
                let peers_b = rx_b.borrow().clone();
                let a_sees_b = peers_a.iter().any(|p| p.id == id_b && p.port == 21872 && p.name == "Desktop B" && !p.addresses.is_empty())
                    && peers_a.iter().all(|p| p.id != id_a);
                let b_sees_a = peers_b.iter().any(|p| p.id == id_a && p.port == 21871 && p.name == "Desktop A" && !p.addresses.is_empty())
                    && peers_b.iter().all(|p| p.id != id_b);
                if a_sees_b && b_sees_a {
                    break;
                }
                tokio::select! {
                    res = rx_a.changed() => { res.expect("rx_a open"); }
                    res = rx_b.changed() => { res.expect("rx_b open"); }
                }
            }
        })
        .await
        .expect("both instances should discover each other over mDNS");

        disc_a.close().await;

        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                if rx_b.borrow().iter().all(|p| p.id != id_a) {
                    break;
                }
                rx_b.changed().await.expect("rx_b open");
            }
        })
        .await
        .expect("disc_b should drop disc_a after close");

        disc_b.close().await;
    }
}
