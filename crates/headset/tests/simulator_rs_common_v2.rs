use openrose_headset::simulator::{SimulatedHeadset, SimulatorError};
use openrose_headset::{
    FieldReading, FieldValue, FirmwareVersion, HeadsetField, ModelProfile, ProtocolFamily,
    create_protocol_codec, decode_hex, encode_hex,
};
use std::collections::BTreeSet;

fn i7() -> SimulatedHeadset {
    SimulatedHeadset::new(
        ModelProfile::I7RsV2,
        FirmwareVersion::new(vec![1, 2, 5], "125"),
    )
    .expect("i7 simulator")
}

fn exchange(headset: &mut SimulatedHeadset, request: &str) -> Vec<String> {
    headset
        .receive(&decode_hex(request).expect("request hex"))
        .iter()
        .map(|frame| encode_hex(frame))
        .collect()
}

#[test]
fn i7_answers_the_batched_initial_query_with_one_frame_of_records_in_query_order() {
    let mut headset = i7();
    let query = "ff0012fa0d0c092c2d2a45460e322b01072f082e3649aa";
    let reply = "dd00040d010205040ce43250020901022c03022d03022a01024500024600020e00023200022b00110101010203030204081101120313021407020700022f04020800022e0305360100010142aa";
    assert_eq!(vec![reply.to_string()], exchange(&mut headset, query));
}

#[test]
fn writes_change_device_state_that_only_the_readback_reveals() {
    let cases = [
        (vec!["ff01020e0111aa"], "ff0202fa0e0baa", "dd00020e01eeaa"),
        (vec!["ff010209020daa"], "ff0202fa0906aa", "dd00020902eaaa"),
        (vec!["ff01022a002caa"], "ff0202fa2a27aa", "dd00022a0009aa"),
        (
            vec!["ff01032a050335aa"],
            "ff0202fa2a27aa",
            "dd00032a050312aa",
        ),
        (vec!["ff01022c0533aa"], "ff0202fa2c29aa", "dd00022c0510aa"),
        (vec!["ff0102320135aa"], "ff0202fa322faa", "dd0002320112aa"),
        (vec!["ff01022e0535aa"], "ff0202fa2e2baa", "dd00022e0512aa"),
        (vec!["ff010207010aaa"], "ff0202fa0704aa", "dd00020701e7aa"),
        (
            vec!["ff010536010101013faa"],
            "ff0202fa3633aa",
            "dd000536010101011caa",
        ),
        (vec!["ff0102450249aa"], "ff0202fa4542aa", "dd0002450226aa"),
        (
            vec!["ff010301010308aa"],
            "ff0202fa01feaa",
            "dd0011010103020303020408110112031302140760aa",
        ),
        (
            vec!["ff010301140119aa"],
            "ff0202fa01feaa",
            "dd0011010101020303020408110112031302140158aa",
        ),
        (
            vec!["ff01022a0430aa", "ff020b3e00000000000000000081cbaa"],
            "ff0303fa2a3e67aa",
            "dd00022a040b3e00000000000000000081d7aa",
        ),
    ];
    for (writes, query, reply) in cases {
        let mut headset = i7();
        for write in &writes {
            assert_eq!(
                Vec::<String>::new(),
                exchange(&mut headset, write),
                "{write}"
            );
        }
        assert_eq!(
            vec![reply.to_string()],
            exchange(&mut headset, query),
            "{writes:?}"
        );
    }
}

#[test]
fn writes_are_acknowledged_with_the_record_only_when_enabled() {
    let mut headset = i7();
    headset.acknowledge_writes(true);
    assert_eq!(
        vec!["dd00020e01eeaa".to_string()],
        exchange(&mut headset, "ff01020e0111aa")
    );
    headset.acknowledge_writes(false);
    assert_eq!(
        Vec::<String>::new(),
        exchange(&mut headset, "ff02020e0011aa")
    );
}

#[test]
fn frames_that_are_not_valid_requests_change_nothing_and_get_no_reply() {
    let mut headset = i7();
    let before = headset.readings();
    for frame in [
        "ff0002fa0effaa",
        "ff0002fa0e09ab",
        "fe0002fa0e09aa",
        "ff0003fa0e0caa",
        "dd00020e00edaa",
        "ff0202fa990baa",
        "",
        "ff",
    ] {
        assert_eq!(
            Vec::<String>::new(),
            exchange(&mut headset, frame),
            "{frame}"
        );
    }
    assert_eq!(
        Vec::<String>::new(),
        exchange(&mut headset, "ff01034900014daa")
    );
    assert_eq!(
        Vec::<String>::new(),
        exchange(&mut headset, "ff0202fa4946aa")
    );
    assert_eq!(before, headset.readings());
}

#[test]
fn readings_are_the_device_truth_decoded_from_its_records() {
    let mut headset = i7();
    exchange(&mut headset, "ff01020e0111aa");
    let readings = headset.readings();
    let number = |value| FieldValue::Number(value);
    assert_eq!(Some(&number(100)), readings.get(&HeadsetField::LeftBattery));
    assert_eq!(Some(&number(50)), readings.get(&HeadsetField::RightBattery));
    assert_eq!(Some(&number(80)), readings.get(&HeadsetField::CaseBattery));
    assert_eq!(
        Some(&FieldValue::Toggle(true)),
        readings.get(&HeadsetField::GameMode)
    );
    assert_eq!(
        Some(&FieldValue::text("anc")),
        readings.get(&HeadsetField::NoiseMode)
    );
    assert_eq!(
        Some(&FieldValue::Firmware(FirmwareVersion::from_parts(vec![
            1, 2, 5
        ]))),
        readings.get(&HeadsetField::Firmware)
    );
}

fn device_change(
    headset: &mut SimulatedHeadset,
    field: HeadsetField,
    value: FieldValue,
) -> Vec<String> {
    headset
        .set(field, value)
        .expect("the device accepts the change")
        .iter()
        .map(|frame| encode_hex(frame))
        .collect()
}

#[test]
fn device_initiated_changes_become_unsolicited_record_frames_and_device_truth() {
    let cases = [
        (
            HeadsetField::LeftBattery,
            FieldValue::Number(20),
            "dd00040c94325003aa",
        ),
        (
            HeadsetField::RightBattery,
            FieldValue::Number(40),
            "dd00040ce4285049aa",
        ),
        (
            HeadsetField::CaseBattery,
            FieldValue::Number(60),
            "dd00040ce4323c3faa",
        ),
        (
            HeadsetField::RightInCase,
            FieldValue::Toggle(true),
            "dd00040ce4b250d3aa",
        ),
        (
            HeadsetField::LeftInCase,
            FieldValue::Toggle(false),
            "dd00040c643250d3aa",
        ),
        (
            HeadsetField::NoiseMode,
            FieldValue::text("transparency"),
            "dd00020903ebaa",
        ),
        (
            HeadsetField::GameMode,
            FieldValue::Toggle(true),
            "dd00020e01eeaa",
        ),
        (
            HeadsetField::AncLevel,
            FieldValue::Number(1),
            "dd00022c010caa",
        ),
        (
            HeadsetField::TransparencyLevel,
            FieldValue::Number(5),
            "dd00022d0511aa",
        ),
        (
            HeadsetField::FindState,
            FieldValue::text("start_both"),
            "dd00022f0311aa",
        ),
        (
            HeadsetField::TouchEnabled,
            FieldValue::Toggle(false),
            "dd00020701e7aa",
        ),
        (
            HeadsetField::EarDetection,
            FieldValue::Toggle(false),
            "dd00020801e8aa",
        ),
        (
            HeadsetField::Multipoint,
            FieldValue::Toggle(true),
            "dd0002320112aa",
        ),
        (
            HeadsetField::PromptVolume,
            FieldValue::Number(5),
            "dd00022e0512aa",
        ),
    ];
    for (field, value, frame) in cases {
        let mut headset = i7();
        assert_eq!(
            vec![frame.to_string()],
            device_change(&mut headset, field, value.clone()),
            "{field:?}"
        );
        assert_eq!(Some(&value), headset.readings().get(&field), "{field:?}");
    }
}

#[test]
fn device_initiated_changes_outside_what_the_model_can_report_are_refused() {
    let mut headset = i7();
    let refused = [
        (HeadsetField::LeftBattery, FieldValue::Number(0)),
        (HeadsetField::NoiseMode, FieldValue::text("deep")),
        (HeadsetField::NoiseMode, FieldValue::text("unknown")),
        (HeadsetField::AncLevel, FieldValue::Number(2)),
        (HeadsetField::GameMode, FieldValue::Number(1)),
        (HeadsetField::PromptVolume, FieldValue::Number(6)),
        (HeadsetField::FindState, FieldValue::text("start_none")),
        (HeadsetField::SpatialMode, FieldValue::text("fixed")),
    ];
    let before = headset.readings();
    for (field, value) in refused {
        assert!(
            headset.set(field, value.clone()).is_err(),
            "{field:?} {value:?}"
        );
    }
    assert_eq!(before, headset.readings());
}

#[test]
fn every_rs_common_v2_model_answers_all_the_queries_its_own_codec_sends() {
    let now = openrose_headset::LocalDateTime::new(2026, 11, 20, 17, 38, 9).expect("valid time");
    let models: Vec<ModelProfile> = ModelProfile::ALL
        .iter()
        .copied()
        .filter(|profile| profile.family() == ProtocolFamily::RsCommonV2)
        .collect();
    assert_eq!(32, models.len());
    for profile in models {
        let mut headset =
            SimulatedHeadset::new(profile, FirmwareVersion::new(vec![1, 2, 5], "125"))
                .unwrap_or_else(|error| panic!("{profile:?}: {error}"));
        let mut codec = create_protocol_codec(profile);
        let mut queries = codec.init_queries(512, now).expect("init queries");
        let mut firmware: Option<FirmwareVersion> = None;
        let mut index = 0;
        while index < queries.len() {
            let query = queries[index].clone();
            let mut answered = BTreeSet::new();
            for frame in headset.receive(&query.bytes) {
                for reply in codec.feed_notification(&frame) {
                    answered.extend(reply.response_keys.clone());
                    if let Some(FieldReading::Known {
                        value: FieldValue::Firmware(version),
                        ..
                    }) = reply.readings.get(&HeadsetField::Firmware)
                    {
                        firmware = Some(version.clone());
                    }
                }
            }
            assert!(
                query.response_keys.is_subset(&answered),
                "{profile:?} {}: expected {:?}, answered {answered:?}",
                query.key,
                query.response_keys
            );
            index += 1;
            if index == queries.len() && firmware.is_some() {
                queries.extend(codec.firmware_init_queries(firmware.take().as_ref()));
            }
        }
    }
}

#[test]
fn families_without_a_simulated_device_are_refused_explicitly() {
    let firmware = || FirmwareVersion::new(vec![1, 2, 5], "125");
    for (profile, family) in [
        (ModelProfile::ExplorerF8, ProtocolFamily::F8),
        (ModelProfile::CambrianRsCommon, ProtocolFamily::RsCommon),
    ] {
        assert_eq!(
            Some(SimulatorError::UnsupportedFamily(family)),
            SimulatedHeadset::new(profile, firmware()).err(),
            "{profile:?}"
        );
    }
}

#[test]
fn firmware_must_be_three_bytes() {
    for parts in [
        vec![1, 2],
        vec![1, 2, 3, 4],
        vec![1, 2, 256],
        vec![1, -1, 3],
    ] {
        assert!(
            matches!(
                SimulatedHeadset::new(
                    ModelProfile::I7RsV2,
                    FirmwareVersion::new(parts.clone(), "x")
                )
                .err(),
                Some(SimulatorError::InvalidFirmware(_))
            ),
            "{parts:?}"
        );
    }
}
