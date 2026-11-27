use openrose_headset::simulator::SimulatedHeadset;
use openrose_headset::{
    FieldValue, FirmwareVersion, HeadsetField, ModelProfile, decode_hex, encode_hex,
};

fn simulate(profile: ModelProfile) -> SimulatedHeadset {
    SimulatedHeadset::new(profile, FirmwareVersion::new(vec![1, 2, 5], "125"))
        .unwrap_or_else(|error| panic!("{profile:?}: {error}"))
}

fn buds_free() -> SimulatedHeadset {
    simulate(ModelProfile::BudsFreeIRsCommon)
}

fn cambrian() -> SimulatedHeadset {
    simulate(ModelProfile::CambrianRsCommon)
}

fn exchange(headset: &mut SimulatedHeadset, request: &str) -> Vec<String> {
    headset
        .receive(&decode_hex(request).expect("request hex"))
        .iter()
        .map(|frame| encode_hex(frame))
        .collect()
}

#[test]
fn the_info_query_is_answered_with_one_frame_per_record_the_model_reads_in_query_order() {
    let mut headset = buds_free();
    let query = "ff000ffa010708090c0d0e122a2b2c2d2e33aa";
    assert_eq!(
        vec![
            "ff00110101010203030200000000110112031302aa",
            "ff00020701aa",
            "ff00020901aa",
            "ff00040c6432ffaa",
            "ff00040d010205aa",
            "ff00020e00aa",
            "ff00022a01aa",
            "ff00022b00aa",
        ],
        exchange(&mut headset, query)
    );
}

#[test]
fn a_model_that_reads_other_records_answers_those_instead() {
    let mut headset = cambrian();
    assert_eq!(
        vec!["ff00022d03aa".to_string(), "ff00022e03aa".to_string(),],
        exchange(&mut headset, "ff0003fa2d2eaa")
    );
    assert_eq!(
        Vec::<String>::new(),
        exchange(&mut headset, "ff0003fa122caa")
    );
}

#[test]
fn writes_change_device_state_that_only_the_readback_reveals() {
    let cases = [
        (vec!["ff00021206aa"], "ff0002fa09aa", "ff00020903aa"),
        (vec!["ff00022a02aa"], "ff0002fa2aaa", "ff00022a02aa"),
        (vec!["ff00022b01aa"], "ff0002fa2baa", "ff00022b01aa"),
        (vec!["ff00022b04aa"], "ff0002fa2baa", "ff00022b04aa"),
        (vec!["ff00020e01aa"], "ff0002fa0eaa", "ff00020e01aa"),
        (vec!["ff00020e02aa"], "ff0002fa07aa", "ff00020700aa"),
        (
            vec!["ff00020e02aa", "ff00020e00aa"],
            "ff0002fa07aa",
            "ff00020701aa",
        ),
        (
            vec!["ff0003010205aa"],
            "ff0002fa01aa",
            "ff00110101010205030200000000110112031302aa",
        ),
        (
            vec!["ff0003011105aa"],
            "ff0002fa01aa",
            "ff00110101010203030200000000110512031302aa",
        ),
    ];
    for (writes, query, reply) in cases {
        let mut headset = buds_free();
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
fn level_and_spatial_writes_reach_the_records_of_the_models_that_read_them() {
    let mut headset = cambrian();
    for (write, query, reply) in [
        ("ff00025a02aa", "ff0002fa2daa", "ff00022d02aa"),
        ("ff00025c02aa", "ff0002fa2eaa", "ff00022e02aa"),
    ] {
        assert_eq!(Vec::<String>::new(), exchange(&mut headset, write));
        assert_eq!(vec![reply.to_string()], exchange(&mut headset, query));
    }
}

#[test]
fn writes_to_records_the_model_does_not_read_are_ignored() {
    let mut headset = buds_free();
    let before = headset.readings();
    for write in ["ff00025805aa", "ff00025c02aa", "ff00023301aa"] {
        assert_eq!(
            Vec::<String>::new(),
            exchange(&mut headset, write),
            "{write}"
        );
    }
    assert_eq!(before, headset.readings());
}

#[test]
fn writes_are_acknowledged_with_the_records_they_touch_only_when_enabled() {
    let mut headset = buds_free();
    headset.acknowledge_writes(true);
    assert_eq!(
        vec!["ff00020903aa".to_string()],
        exchange(&mut headset, "ff00021206aa")
    );
    assert_eq!(
        vec!["ff00020e00aa".to_string(), "ff00020700aa".to_string()],
        exchange(&mut headset, "ff00020e02aa")
    );
    headset.acknowledge_writes(false);
    assert_eq!(Vec::<String>::new(), exchange(&mut headset, "ff00021204aa"));
}

#[test]
fn frames_that_are_not_valid_requests_change_nothing_and_get_no_reply() {
    let mut headset = buds_free();
    let before = headset.readings();
    for frame in [
        "fe0002fa09aa",
        "ff0002fa09ab",
        "ff0003fa09aa",
        "ff0002fa09",
        "ff0000aa",
        "ff0002fa99aa",
        "dd0002fa09aa",
        "",
        "ff",
    ] {
        assert_eq!(
            Vec::<String>::new(),
            exchange(&mut headset, frame),
            "{frame}"
        );
    }
    assert_eq!(before, headset.readings());
}

#[test]
fn readings_are_the_device_truth_decoded_from_its_records() {
    let mut headset = buds_free();
    exchange(&mut headset, "ff00020e01aa");
    exchange(&mut headset, "ff00021206aa");
    let readings = headset.readings();
    let number = |value| FieldValue::Number(value);
    assert_eq!(Some(&number(100)), readings.get(&HeadsetField::LeftBattery));
    assert_eq!(Some(&number(50)), readings.get(&HeadsetField::RightBattery));
    assert_eq!(None, readings.get(&HeadsetField::CaseBattery));
    assert_eq!(
        Some(&FieldValue::Toggle(true)),
        readings.get(&HeadsetField::GameMode)
    );
    assert_eq!(
        Some(&FieldValue::text("transparency")),
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
            ModelProfile::BudsFreeIRsCommon,
            HeadsetField::LeftBattery,
            FieldValue::Number(20),
            "ff00040c1432ffaa",
        ),
        (
            ModelProfile::BudsFreeIRsCommon,
            HeadsetField::RightBattery,
            FieldValue::Number(40),
            "ff00040c6428ffaa",
        ),
        (
            ModelProfile::BudsFreeIRsCommon,
            HeadsetField::LeftInCase,
            FieldValue::Toggle(true),
            "ff00040ce432ffaa",
        ),
        (
            ModelProfile::BudsFreeIRsCommon,
            HeadsetField::NoiseMode,
            FieldValue::text("transparency"),
            "ff00020903aa",
        ),
        (
            ModelProfile::BudsFreeIRsCommon,
            HeadsetField::GameMode,
            FieldValue::Toggle(true),
            "ff00020e01aa",
        ),
        (
            ModelProfile::BudsFreeIRsCommon,
            HeadsetField::TouchEnabled,
            FieldValue::Toggle(false),
            "ff00020700aa",
        ),
        (
            ModelProfile::BudsFreeIRsCommon,
            HeadsetField::AudioMode,
            FieldValue::text("ldac"),
            "ff00022b01aa",
        ),
        (
            ModelProfile::BudsFreeIRsCommon,
            HeadsetField::Eq,
            FieldValue::text("rock"),
            "ff00022a02aa",
        ),
        (
            ModelProfile::CambrianRsCommon,
            HeadsetField::CaseBattery,
            FieldValue::Number(60),
            "ff00040cffff3caa",
        ),
        (
            ModelProfile::CambrianRsCommon,
            HeadsetField::Multipoint,
            FieldValue::Toggle(true),
            "ff00022b04aa",
        ),
        (
            ModelProfile::CambrianRsCommon,
            HeadsetField::TransparencyLevel,
            FieldValue::Number(2),
            "ff00022d02aa",
        ),
        (
            ModelProfile::CambrianRsCommon,
            HeadsetField::PromptVolume,
            FieldValue::Number(4),
            "ff00022e04aa",
        ),
    ];
    for (profile, field, value, frame) in cases {
        let mut headset = simulate(profile);
        assert_eq!(
            vec![frame.to_string()],
            device_change(&mut headset, field, value.clone()),
            "{profile:?} {field:?}"
        );
        assert_eq!(
            Some(&value),
            headset.readings().get(&field),
            "{profile:?} {field:?}"
        );
    }
}

#[test]
fn device_initiated_changes_outside_what_the_model_can_report_are_refused() {
    let mut headset = buds_free();
    let refused = [
        (HeadsetField::CaseBattery, FieldValue::Number(50)),
        (HeadsetField::LeftBattery, FieldValue::Number(0)),
        (HeadsetField::NoiseMode, FieldValue::text("wind")),
        (HeadsetField::AncLevel, FieldValue::Number(3)),
        (HeadsetField::PromptVolume, FieldValue::Number(3)),
        (HeadsetField::GameMode, FieldValue::Number(1)),
        (HeadsetField::AudioMode, FieldValue::text("lhdc")),
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
fn a_model_refuses_levels_its_configuration_rules_out() {
    let mut headset = cambrian();
    assert!(
        headset
            .set(HeadsetField::TransparencyLevel, FieldValue::Number(5))
            .is_err()
    );
    assert!(
        headset
            .set(HeadsetField::PromptVolume, FieldValue::Number(6))
            .is_err()
    );
    assert!(
        headset
            .set(HeadsetField::AncLevel, FieldValue::Number(3))
            .is_err()
    );
    assert!(
        headset
            .set(HeadsetField::TransparencyLevel, FieldValue::Number(3))
            .is_ok()
    );
}

#[test]
fn firmware_must_fit_the_three_bytes_the_family_reports() {
    use openrose_headset::simulator::SimulatorError;
    for parts in [
        vec![1, 2],
        vec![1, 2, 3, 4],
        vec![16, 2, 5],
        vec![1, 256, 5],
        vec![1, 2, -1],
    ] {
        assert!(
            matches!(
                SimulatedHeadset::new(
                    ModelProfile::CambrianRsCommon,
                    FirmwareVersion::new(parts.clone(), "x")
                )
                .err(),
                Some(SimulatorError::InvalidFirmware(_))
            ),
            "{parts:?}"
        );
    }
}
