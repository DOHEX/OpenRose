use async_trait::async_trait;
use if_addrs::IfAddr;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo, TxtProperties};
use openrose_circulation::{
    MAX_ADVERTISED_NAME_LEN, MDNS_SERVICE_TYPE, NearbyPeer, PeerDiscovery, PeerId, RemoteError,
    WIRE_VERSION,
};
use std::collections::BTreeMap;
use std::fmt;
use std::net::{IpAddr, Ipv4Addr};
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct InterfaceAddress {
    pub interface_key: String,
    pub is_up: bool,
    pub is_p2p: bool,
    pub is_loopback: bool,
    pub is_link_local: bool,
    pub addr: IpAddr,
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
        ("id".to_string(), id.as_str().to_string()),
        ("name".to_string(), truncate_advertised_name(name)),
        ("v".to_string(), version.to_string()),
    ]
}

pub(crate) fn decode_txt_properties(props: &TxtProperties) -> Option<AdvertisedPeer> {
    let id = props
        .get_property_val_str("id")
        .map(str::trim)
        .filter(|s| !s.is_empty())?;
    let name = props.get_property_val_str("name")?;
    let version = props.get_property_val_str("v")?.trim().parse::<u32>().ok()?;
    Some(AdvertisedPeer {
        id: PeerId::new(id),
        name: truncate_advertised_name(name),
        version,
    })
}

pub(crate) fn is_link_local(addr: IpAddr) -> bool {
    match addr {
        IpAddr::V4(v4) => v4.is_link_local(),
        IpAddr::V6(v6) => (v6.segments()[0] & 0xffc0) == 0xfe80,
    }
}

pub(crate) fn is_usable_unicast(addr: IpAddr) -> bool {
    !addr.is_unspecified() && !addr.is_multicast() && !is_link_local(addr)
}

pub(crate) fn select_interface_addresses(candidates: &[InterfaceAddress]) -> Vec<IpAddr> {
    let mut by_interface: BTreeMap<&str, IpAddr> = BTreeMap::new();
    for candidate in candidates {
        if !candidate.is_up
            || candidate.is_p2p
            || candidate.is_loopback
            || candidate.is_link_local
            || !is_usable_unicast(candidate.addr)
        {
            continue;
        }
        by_interface
            .entry(candidate.interface_key.as_str())
            .and_modify(|current| {
                if (!candidate.addr.is_ipv4(), candidate.addr) < (!current.is_ipv4(), *current) {
                    *current = candidate.addr;
                }
            })
            .or_insert(candidate.addr);
    }
    let mut addrs: Vec<IpAddr> = by_interface.into_values().collect();
    addrs.sort_by_key(|addr| (!addr.is_ipv4(), *addr));
    addrs.dedup();
    addrs
}

pub(crate) fn filter_resolved_addresses(addrs: impl IntoIterator<Item = IpAddr>) -> Vec<IpAddr> {
    let mut selected: Vec<IpAddr> = addrs
        .into_iter()
        .filter(|addr| is_usable_unicast(*addr))
        .collect();
    selected.sort_by_key(|addr| (!addr.is_ipv4(), *addr));
    selected.dedup();
    selected
}

fn discover_advertised_addresses() -> Vec<IpAddr> {
    let interfaces = if_addrs::get_if_addrs().unwrap_or_default();
    let candidates: Vec<InterfaceAddress> = interfaces
        .into_iter()
        .map(|iface| {
            let interface_key = match iface.index {
                Some(idx) => format!("{idx}:{}", iface.name),
                None => format!("name:{}", iface.name),
            };
            let is_up = iface.is_oper_up();
            let is_p2p = iface.is_p2p();
            let is_loopback = iface.is_loopback();
            let is_link_local = iface.is_link_local() || is_link_local(iface.ip());
            let addr = match iface.addr {
                IfAddr::V4(v4) => IpAddr::V4(v4.ip),
                IfAddr::V6(v6) => IpAddr::V6(v6.ip),
            };
            InterfaceAddress {
                interface_key,
                is_up,
                is_p2p,
                is_loopback,
                is_link_local,
                addr,
            }
        })
        .collect();
    let selected = select_interface_addresses(&candidates);
    if selected.is_empty() {
        vec![IpAddr::V4(Ipv4Addr::LOCALHOST)]
    } else {
        selected
    }
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

fn instance_name_for(id: &PeerId) -> String {
    let prefix: String = id.as_str().chars().take(20).collect();
    if prefix.is_empty() {
        "OpenRose".to_string()
    } else {
        format!("OpenRose-{prefix}")
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

        let addresses = discover_advertised_addresses();
        let props = encode_txt_properties(id, name, WIRE_VERSION);
        let service_info = ServiceInfo::new(
            MDNS_SERVICE_TYPE,
            &instance_name_for(id),
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
    use std::net::Ipv6Addr;

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
    fn interface_selection_picks_one_non_link_local_address_per_interface_with_ipv4_preferred() {
        let wifi4: IpAddr = "192.0.2.4".parse().unwrap();
        let wifi6: IpAddr = "2001:db8:1::4".parse().unwrap();
        let wifi_ll6: IpAddr = "fe80::1".parse().unwrap();
        let eth6: IpAddr = "2001:db8:2::4".parse().unwrap();
        let eth_ll4: IpAddr = "169.254.10.1".parse().unwrap();
        let lo4: IpAddr = Ipv4Addr::LOCALHOST.into();
        let down4: IpAddr = "192.0.2.99".parse().unwrap();
        let p2p4: IpAddr = "10.0.0.2".parse().unwrap();

        let candidates = vec![
            InterfaceAddress {
                interface_key: "wlan0".to_string(),
                is_up: true,
                is_p2p: false,
                is_loopback: false,
                is_link_local: false,
                addr: wifi6,
            },
            InterfaceAddress {
                interface_key: "wlan0".to_string(),
                is_up: true,
                is_p2p: false,
                is_loopback: false,
                is_link_local: true,
                addr: wifi_ll6,
            },
            InterfaceAddress {
                interface_key: "wlan0".to_string(),
                is_up: true,
                is_p2p: false,
                is_loopback: false,
                is_link_local: false,
                addr: wifi4,
            },
            InterfaceAddress {
                interface_key: "eth0".to_string(),
                is_up: true,
                is_p2p: false,
                is_loopback: false,
                is_link_local: false,
                addr: eth_ll4,
            },
            InterfaceAddress {
                interface_key: "eth0".to_string(),
                is_up: true,
                is_p2p: false,
                is_loopback: false,
                is_link_local: false,
                addr: eth6,
            },
            InterfaceAddress {
                interface_key: "lo".to_string(),
                is_up: true,
                is_p2p: false,
                is_loopback: true,
                is_link_local: false,
                addr: lo4,
            },
            InterfaceAddress {
                interface_key: "eth1".to_string(),
                is_up: false,
                is_p2p: false,
                is_loopback: false,
                is_link_local: false,
                addr: down4,
            },
            InterfaceAddress {
                interface_key: "tun0".to_string(),
                is_up: true,
                is_p2p: true,
                is_loopback: false,
                is_link_local: false,
                addr: p2p4,
            },
        ];

        assert_eq!(select_interface_addresses(&candidates), vec![wifi4, eth6]);
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
