use openrose_headset::{ModelIdentification, ModelProfile, decode_hex};
use std::collections::BTreeMap;

fn manufacturer_data(entries: &[(u16, &str)]) -> BTreeMap<u16, Vec<u8>> {
    entries
        .iter()
        .map(|&(company, payload)| (company, decode_hex(payload).expect("valid hex")))
        .collect()
}

#[test]
fn catalog_passes_prefix_and_name_invariants() {
    assert_eq!(Ok(()), ModelIdentification::validate_catalog());
}

#[test]
fn every_model_is_identified_by_each_of_its_own_advertisement_prefixes() {
    for &profile in ModelProfile::ALL {
        for &prefix in profile.advertisement_prefixes() {
            let mut full = prefix.to_string();
            full.push_str("99");
            let bytes = decode_hex(&full).expect("valid prefix hex");
            let company = u16::from_le_bytes([bytes[0], bytes[1]]);
            let mut data = BTreeMap::new();
            data.insert(company, bytes[2..].to_vec());
            assert_eq!(
                Some(profile),
                ModelIdentification::identify(&data),
                "{profile:?} {prefix}"
            );
        }
    }
}

#[test]
fn manufacturer_evidence_identifies_configuration_without_name_and_sorts_company_ids() {
    assert_eq!(
        Some(ModelProfile::I5Zt),
        ModelIdentification::identify(&manufacturer_data(&[(0x5352, "0001000199")]))
    );
    assert_eq!(
        Some(ModelProfile::HeadFeelZt),
        ModelIdentification::identify(&manufacturer_data(&[(0x5352, "0002000299")]))
    );
    assert_eq!(
        Some(ModelProfile::I3Zt),
        ModelIdentification::identify(&manufacturer_data(&[(0x02f1, "0399")]))
    );
    assert_eq!(
        Some(ModelProfile::I3Zt),
        ModelIdentification::identify(&manufacturer_data(&[(0x0201, "0399")]))
    );
    assert_eq!(
        None,
        ModelIdentification::identify(&manufacturer_data(&[(0x5352, "00010002")]))
    );
    assert_eq!(
        "01000200bbaa",
        ModelIdentification::normalized_hex(&manufacturer_data(&[(2, "aa"), (1, "bb")]))
    );
}

#[test]
fn zt_model_names_follow_adr_0032_rules() {
    for &profile in ModelProfile::ALL {
        let spec = profile.spec();
        let name = format!(
            "{}-{}",
            spec.name_brand.to_uppercase(),
            spec.name_model_number
        );
        let same_name: Vec<ModelProfile> = ModelProfile::ALL
            .iter()
            .copied()
            .filter(|p| {
                p.spec().name_brand == spec.name_brand
                    && p.spec().name_model_number == spec.name_model_number
            })
            .collect();
        let expected = if same_name.len() == 1 {
            Some(same_name[0])
        } else {
            None
        };
        assert_eq!(expected, ModelProfile::match_name(Some(&name)), "{name}");
    }

    for (name, expected) in [
        ("ROSE i5", Some(ModelProfile::I5Zt)),
        ("EarFree i5", Some(ModelProfile::I5Zt)),
        ("EarFree-i5", Some(ModelProfile::I5Zt)),
        ("EarFreei5", Some(ModelProfile::I5Zt)),
        ("ROSESELSAEarFreei5", Some(ModelProfile::I5Zt)),
        ("EarFree", Some(ModelProfile::I5Zt)),
        ("EarFree i3", Some(ModelProfile::I3Zt)),
        ("ROSE EarFree-i3", Some(ModelProfile::I3Zt)),
        ("ROSE i3", None),
        ("HeadFeel", Some(ModelProfile::HeadFeelZt)),
        ("HeadFree", Some(ModelProfile::HeadFeelZt)),
        ("ROSE HeadFeel", Some(ModelProfile::HeadFeelZt)),
        ("EarFree i8", None),
        ("iPhone 15", None),
        ("ThinkPad i7", None),
        ("Sony WH-1000XM4", None),
        ("AirPods Pro", None),
        ("", None),
    ] {
        assert_eq!(expected, ModelProfile::match_name(Some(name)), "{name}");
    }
    assert_eq!(None, ModelProfile::match_name(None));

    let pattern = ModelProfile::name_pattern();
    assert!(pattern.starts_with("(?is)rose.*|.*(?<![\\p{L}\\p{N}])(?:"));
    assert!(pattern.contains("e[\\s_-]*a[\\s_-]*r[\\s_-]*f[\\s_-]*r[\\s_-]*e[\\s_-]*e"));
    assert!(pattern.contains("h[\\s_-]*e[\\s_-]*a[\\s_-]*d[\\s_-]*f[\\s_-]*r[\\s_-]*e[\\s_-]*e"));
}

#[test]
fn model_display_names_and_preset_labels_come_from_model_spec() {
    assert_eq!("EarFree i5", ModelProfile::I5Zt.display_name());
    assert_eq!("i5", ModelProfile::I5Zt.short_label());
    assert_eq!(None, ModelProfile::I5Zt.version_name());
    assert_eq!(
        Some("弱水经典"),
        ModelProfile::I5Zt.equalizer_preset_label("pop")
    );
    assert_eq!(
        Some("日系柔情"),
        ModelProfile::I5Zt.equalizer_preset_label("hifi")
    );
    assert_eq!(
        Some("乐器大师"),
        ModelProfile::I5Zt.equalizer_preset_label("rock")
    );
    assert_eq!(
        Some("清新空灵"),
        ModelProfile::I5Zt.equalizer_preset_label("light")
    );
    assert_eq!(None, ModelProfile::I5Zt.equalizer_preset_label("unknown"));
    assert_eq!(None, ModelProfile::I3Zt.equalizer_preset_label("pop"));
    assert_eq!("EarFree-i3", ModelProfile::I3Zt.display_name());
    assert_eq!("HeadFeel", ModelProfile::HeadFeelZt.display_name());
    assert_eq!(Some(ModelProfile::I5Zt), ModelProfile::from_id("I5_ZT"));
    assert_eq!(Some(ModelProfile::I3Zt), ModelProfile::from_id("I3_ZT"));
    assert_eq!(
        Some(ModelProfile::HeadFeelZt),
        ModelProfile::from_id("HEADFEEL_ZT")
    );
    assert_eq!(None, ModelProfile::from_id("UNKNOWN"));
}
