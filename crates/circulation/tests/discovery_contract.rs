use openrose_circulation::{DiscoveryContract, MAX_ADVERTISED_NAME_LEN, service_instance_name};

#[test]
fn the_instance_name_uses_the_first_twenty_id_characters_and_degrades_without_an_id() {
    let id = "0123456789abcdef0123456789abcdef";
    assert_eq!(service_instance_name(id), "OpenRose-0123456789abcdef0123");
    assert_eq!(service_instance_name(""), "OpenRose");
}

#[test]
fn the_contract_carries_the_values_every_platform_must_share() {
    let contract = DiscoveryContract::current();
    assert_eq!(contract.service_type, "_openrose._tcp.local.");
    assert_eq!(contract.max_name_len as usize, MAX_ADVERTISED_NAME_LEN);
    assert_eq!(
        (contract.txt_key_id, contract.txt_key_name, contract.txt_key_version),
        ("id".to_string(), "name".to_string(), "v".to_string())
    );
}
