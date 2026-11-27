mod support;

use openrose_control::scan::Advertisement;
use openrose_control::snapshot::ConnectionState;
use openrose_headset::{
    BatteryLayout, FieldReading, FirmwareVersion, HeadsetCommand, HeadsetField, HeadsetId,
    ModelProfile, ProtocolFamily, decode_hex,
};
use support::scripted::simulated;
use support::{available, settle};

fn firmware(profile: ModelProfile) -> FirmwareVersion {
    match profile.family() {
        ProtocolFamily::F8 => FirmwareVersion::new(vec![125], "125"),
        ProtocolFamily::Zt | ProtocolFamily::RsCommonV2 | ProtocolFamily::RsCommon => {
            FirmwareVersion::new(vec![1, 2, 5], "125")
        }
    }
}

fn catalog_advertisement(profile: ModelProfile, id: &HeadsetId) -> Advertisement {
    let prefix = decode_hex(profile.advertisement_prefixes()[0]).expect("advertisement prefix");
    let company = u16::from(prefix[0]) | (u16::from(prefix[1]) << 8);
    let name = match profile {
        ModelProfile::I5Zt => "i5",
        ModelProfile::I7RsV2 => "i7",
        _ => profile.display_name(),
    };
    Advertisement {
        name: Some(name.to_string()),
        manufacturer_data: [(company, prefix[2..].to_vec())].into(),
        ..Advertisement::new(id.clone())
    }
}

#[tokio::test(start_paused = true)]
async fn catalog_advertisements_reach_a_ready_control_session_with_the_documented_battery_layout() {
    assert_eq!(52, ModelProfile::ALL.len());
    for profile in ModelProfile::ALL.iter().copied() {
        let id = HeadsetId::new(profile.id());
        let (transport, runtime, _) = simulated(available());
        let headset = transport
            .add_headset(id.clone(), profile, firmware(profile))
            .unwrap_or_else(|error| panic!("{}: {error}", profile.id()));
        transport.audio().unpair(id.as_str());
        runtime
            .start_scan()
            .await
            .unwrap_or_else(|error| panic!("{}: {error:?}", profile.id()));
        transport
            .scan()
            .advertise(catalog_advertisement(profile, &id));
        settle().await;
        assert!(
            runtime.snapshot().bonded_headsets.is_empty(),
            "{}",
            profile.id()
        );

        runtime
            .connect_for_add_headset(id.clone())
            .await
            .unwrap_or_else(|error| panic!("{}: {error:?}", profile.id()));

        let snapshot = runtime.snapshot();
        assert!(
            matches!(snapshot.connection, ConnectionState::Connected { .. }),
            "{}",
            profile.id()
        );
        let state = snapshot
            .headset
            .unwrap_or_else(|| panic!("{}: no headset state", profile.id()));
        assert_eq!(profile, state.readings.profile, "{}", profile.id());
        let battery = if profile.battery_layout() == BatteryLayout::SingleUnit {
            HeadsetField::CaseBattery
        } else {
            HeadsetField::LeftBattery
        };
        assert!(
            matches!(
                state.readings.fields.get(&battery),
                Some(FieldReading::Known { .. })
            ),
            "{} {battery:?}: {:?}",
            profile.id(),
            state.readings.fields.get(&battery)
        );

        if profile.family() == ProtocolFamily::RsCommonV2 {
            runtime
                .set(HeadsetCommand::GameMode(false), None)
                .await
                .unwrap_or_else(|error| panic!("{}: {error:?}", profile.id()));
            let written: Vec<String> = headset
                .written_hex()
                .iter()
                .map(|frame| frame[6..10].to_string())
                .collect();
            let expected: &[&str] = if matches!(
                profile,
                ModelProfile::FirstAngelRsV2 | ModelProfile::MokaRsV2
            ) {
                &["0e00", "fa0e", "fa2a"]
            } else {
                &["0e00", "fa0e"]
            };
            assert_eq!(
                expected,
                &written[written.len() - expected.len()..],
                "{}",
                profile.id()
            );
        }

        assert!(runtime.release().await, "{}", profile.id());
    }
}
