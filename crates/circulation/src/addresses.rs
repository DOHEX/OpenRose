use if_addrs::IfAddr;
use std::collections::BTreeMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

pub trait HasIp {
    fn ip(&self) -> IpAddr;
}

impl HasIp for IpAddr {
    fn ip(&self) -> IpAddr {
        *self
    }
}

impl HasIp for SocketAddr {
    fn ip(&self) -> IpAddr {
        SocketAddr::ip(self)
    }
}

pub fn sort_ipv4_first<T: HasIp + Ord>(addrs: &mut Vec<T>) {
    addrs.sort_by(|left, right| {
        (!left.ip().is_ipv4())
            .cmp(&!right.ip().is_ipv4())
            .then_with(|| left.cmp(right))
    });
    addrs.dedup();
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InterfaceAddress {
    pub interface_key: String,
    pub is_up: bool,
    pub is_p2p: bool,
    pub is_loopback: bool,
    pub is_link_local: bool,
    pub addr: IpAddr,
}

pub fn is_link_local(addr: IpAddr) -> bool {
    match addr {
        IpAddr::V4(v4) => v4.is_link_local(),
        IpAddr::V6(v6) => (v6.segments()[0] & 0xffc0) == 0xfe80,
    }
}

pub fn is_usable_unicast(addr: IpAddr) -> bool {
    !addr.is_unspecified() && !addr.is_multicast() && !is_link_local(addr)
}

pub fn select_interface_addresses(candidates: &[InterfaceAddress]) -> Vec<IpAddr> {
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
    sort_ipv4_first(&mut addrs);
    addrs
}

pub fn filter_resolved_addresses(addrs: impl IntoIterator<Item = IpAddr>) -> Vec<IpAddr> {
    let mut selected: Vec<IpAddr> = addrs
        .into_iter()
        .filter(|addr| is_usable_unicast(*addr))
        .collect();
    sort_ipv4_first(&mut selected);
    selected
}

pub fn advertised_addresses() -> Vec<IpAddr> {
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

