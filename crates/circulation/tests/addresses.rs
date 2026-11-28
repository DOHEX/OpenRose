use openrose_circulation::{InterfaceAddress, select_interface_addresses};
use std::net::{IpAddr, Ipv4Addr};

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
