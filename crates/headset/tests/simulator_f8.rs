use openrose_headset::simulator::{SimulatedHeadset, SimulatorError};
use openrose_headset::{
    FieldValue, FirmwareVersion, HeadsetField, ModelProfile, decode_hex, encode_hex,
};

fn simulate(profile: ModelProfile) -> SimulatedHeadset {
    SimulatedHeadset::new(profile, FirmwareVersion::new(vec![125], "125"))
        .unwrap_or_else(|error| panic!("{profile:?}: {error}"))
}

fn ceramics_x() -> SimulatedHeadset {
    simulate(ModelProfile::CeramicsXF8)
}

fn agate() -> SimulatedHeadset {
    simulate(ModelProfile::AgateF8)
}

fn hex(literal: &str) -> String {
    literal.split_whitespace().collect()
}

fn exchange(headset: &mut SimulatedHeadset, request: &str) -> Vec<String> {
    headset
        .receive(&decode_hex(&hex(request)).expect("request hex"))
        .iter()
        .map(|frame| encode_hex(frame))
        .collect()
}

#[test]
fn an_extended_model_answers_each_query_with_the_record_of_its_tag() {
    let mut headset = ceramics_x();
    let cases = [
        ("00 27 01 00 01 01", "00 27 01 00 05 01 00 64 62 50"),
        ("00 27 01 00 01 0c", "00 27 01 00 03 0c 00 01"),
        ("00 27 01 00 01 04", "00 27 01 00 03 04 00 01"),
        ("00 27 01 00 01 08", "00 27 01 00 03 08 00 00"),
        ("00 27 01 00 01 0b", "00 27 01 00 03 0b 00 02"),
        ("00 27 01 00 01 02", "00 27 01 00 06 02 00 00 00 00 7d"),
        (
            "02 27 01 00 01 05",
            "00 27 01 00 1a 05 00 00 00 07 00 00 07 00 00 04 00 00 04 00 00 03 00 00 03 00 00 00 00 00 00",
        ),
        ("00 27 01 00 01 09", "00 27 01 00 03 09 00 00"),
        ("00 27 01 00 01 13", "00 27 01 00 03 13 00 03"),
        ("00 27 01 00 01 11", "00 27 01 00 03 11 00 01"),
        ("00 27 01 00 01 16", "00 27 01 00 03 16 00 01"),
        ("00 27 01 00 01 0a", "00 27 01 00 03 0a 00 00"),
    ];
    for (query, reply) in cases {
        assert_eq!(vec![hex(reply)], exchange(&mut headset, query), "{query}");
    }
}

#[test]
fn a_basic_model_answers_the_same_tags_with_its_own_battery_and_touch_layout() {
    let mut headset = agate();
    assert_eq!(
        vec![hex("00 27 01 00 05 01 00 64 62 00")],
        exchange(&mut headset, "00 27 01 00 01 01")
    );
    assert_eq!(
        vec![hex("00 27 01 00 08 05 00 07 07 04 04 03 03")],
        exchange(&mut headset, "02 27 01 00 01 05")
    );
}

#[test]
fn writes_change_device_state_that_only_the_readback_reveals() {
    let cases = [
        (
            "00 20 01 00 02 00 02",
            "00 27 01 00 01 04",
            "00 27 01 00 03 04 00 02",
        ),
        (
            "00 2c 01 00 01 02",
            "00 27 01 00 01 0c",
            "00 27 01 00 03 0c 00 02",
        ),
        (
            "00 2c 01 00 01 03",
            "00 27 01 00 01 0c",
            "00 27 01 00 03 0c 00 06",
        ),
        (
            "00 2c 01 00 01 00",
            "00 27 01 00 01 0c",
            "00 27 01 00 03 0c 00 00",
        ),
        (
            "00 25 01 00 01 01",
            "00 27 01 00 01 08",
            "00 27 01 00 03 08 00 01",
        ),
        (
            "00 26 01 00 01 01",
            "00 27 01 00 01 09",
            "00 27 01 00 03 09 00 01",
        ),
        (
            "00 29 01 00 01 01",
            "00 27 01 00 01 0a",
            "00 27 01 00 03 0a 00 01",
        ),
        (
            "00 2b 01 00 01 01",
            "00 27 01 00 01 0b",
            "00 27 01 00 03 0b 00 01",
        ),
        (
            "00 2b 01 00 01 00",
            "00 27 01 00 01 0b",
            "00 27 01 00 03 0b 00 00",
        ),
        (
            "00 32 01 00 01 04",
            "00 27 01 00 01 13",
            "00 27 01 00 03 13 00 04",
        ),
        (
            "00 33 01 00 01 00",
            "00 27 01 00 01 16",
            "00 27 01 00 03 16 00 00",
        ),
        (
            "00 30 01 00 01 02",
            "00 27 01 00 01 11",
            "00 27 01 00 03 11 00 02",
        ),
        (
            "00 31 01 00 01 00",
            "00 27 01 00 01 12",
            "00 27 01 00 03 12 00 00",
        ),
    ];
    for (write, query, reply) in cases {
        let mut headset = ceramics_x();
        assert_eq!(
            Vec::<String>::new(),
            exchange(&mut headset, write),
            "{write}"
        );
        assert_eq!(
            vec![hex(reply)],
            exchange(&mut headset, query),
            "{write} then {query}"
        );
    }
}

#[test]
fn touch_writes_address_the_slot_of_the_gesture_and_side() {
    let touch_query = "02 27 01 00 01 05";
    let mut extended = ceramics_x();
    assert!(exchange(&mut extended, "00 22 01 00 03 03 01 05").is_empty());
    assert!(exchange(&mut extended, "00 22 01 00 03 08 01 06").is_empty());
    assert_eq!(
        vec![hex(
            "00 27 01 00 1a 05 00 00 00 07 00 00 07 00 00 05 00 00 04 00 00 03 00 00 03 00 00 00 00 00 06"
        )],
        exchange(&mut extended, touch_query)
    );

    let mut basic = agate();
    assert!(exchange(&mut basic, "00 22 01 00 03 03 01 05").is_empty());
    assert!(exchange(&mut basic, "00 22 01 00 03 02 01 06").is_empty());
    assert!(exchange(&mut basic, "00 22 01 00 03 07 01 02").is_empty());
    assert!(exchange(&mut basic, "00 22 01 00 03 05 01 02").is_empty());
    assert_eq!(
        vec![hex("00 27 01 00 08 05 00 07 06 05 04 02 03")],
        exchange(&mut basic, touch_query)
    );
}

#[test]
fn writes_are_acknowledged_with_the_record_only_when_enabled() {
    let mut headset = ceramics_x();
    headset.acknowledge_writes(true);
    assert_eq!(
        vec![hex("00 27 01 00 03 04 00 02")],
        exchange(&mut headset, "00 20 01 00 02 00 02")
    );
    headset.acknowledge_writes(false);
    assert!(exchange(&mut headset, "00 20 01 00 02 00 01").is_empty());
}

#[test]
fn frames_that_are_not_valid_requests_change_nothing_and_get_no_reply() {
    let mut headset = ceramics_x();
    let before = headset.readings();
    for frame in [
        "00 27 01 00 02 04",
        "00 27 01 00 01",
        "00 27 02 00 01 04",
        "00 27 01 01 01 04",
        "00 27 01 00 01 77",
        "00 27 01 00 02 04 08",
        "00 77 01 00 01 04",
        "00",
        "",
    ] {
        assert_eq!(
            Vec::<String>::new(),
            exchange(&mut headset, frame),
            "{frame}"
        );
    }
    assert!(exchange(&mut headset, "00 20 01 00 01 02").is_empty());
    assert_eq!(before, headset.readings());
}

#[test]
fn readings_are_the_device_truth_decoded_from_its_records() {
    let mut headset = ceramics_x();
    exchange(&mut headset, "00 25 01 00 01 01");
    exchange(&mut headset, "00 2c 01 00 01 03");
    let readings = headset.readings();
    let number = |value| FieldValue::Number(value);
    assert_eq!(Some(&number(100)), readings.get(&HeadsetField::LeftBattery));
    assert_eq!(Some(&number(98)), readings.get(&HeadsetField::RightBattery));
    assert_eq!(Some(&number(80)), readings.get(&HeadsetField::CaseBattery));
    assert_eq!(
        Some(&FieldValue::Toggle(true)),
        readings.get(&HeadsetField::GameMode)
    );
    assert_eq!(
        Some(&FieldValue::text("normal")),
        readings.get(&HeadsetField::NoiseMode)
    );
    assert_eq!(
        Some(&FieldValue::Firmware(FirmwareVersion::new(
            vec![125],
            "125"
        ))),
        readings.get(&HeadsetField::Firmware)
    );
    assert_eq!(None, agate().readings().get(&HeadsetField::NoiseMode));
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
            ModelProfile::CeramicsXF8,
            HeadsetField::LeftBattery,
            FieldValue::Number(20),
            "00 27 01 00 05 01 00 14 62 50",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::CaseBattery,
            FieldValue::Number(60),
            "00 27 01 00 05 01 00 64 62 3c",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::RightCharging,
            FieldValue::Toggle(true),
            "00 27 01 00 05 01 00 64 e2 50",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::Eq,
            FieldValue::text("hifi"),
            "00 27 01 00 03 04 00 00",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::NoiseMode,
            FieldValue::text("wind"),
            "00 27 01 00 03 0c 00 00",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::NoiseMode,
            FieldValue::text("normal"),
            "00 27 01 00 03 0c 00 06",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::PromptLanguage,
            FieldValue::text("english"),
            "00 27 01 00 03 0a 00 01",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::AudioMode,
            FieldValue::text("ldac"),
            "00 27 01 00 03 0b 00 01",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::Multipoint,
            FieldValue::Toggle(true),
            "00 27 01 00 03 0b 00 00",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::GameMode,
            FieldValue::Toggle(true),
            "00 27 01 00 03 08 00 01",
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::EarDetection,
            FieldValue::Toggle(true),
            "00 27 01 00 03 09 00 01",
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetField::AncLevel,
            FieldValue::Number(5),
            "00 27 01 00 03 11 00 00",
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetField::TransparencyLevel,
            FieldValue::Number(1),
            "00 27 01 00 03 12 00 02",
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetField::PromptVolume,
            FieldValue::Number(5),
            "00 27 01 00 03 13 00 05",
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetField::NoiseMode,
            FieldValue::text("normal"),
            "00 27 01 00 03 0c 00 00",
        ),
        (
            ModelProfile::AgateF8,
            HeadsetField::RightBattery,
            FieldValue::Number(41),
            "00 27 01 00 05 01 00 64 29 00",
        ),
    ];
    for (profile, field, value, frame) in cases {
        let mut headset = simulate(profile);
        assert_eq!(
            vec![hex(frame)],
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
    let refused = [
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::LeftBattery,
            FieldValue::Number(0),
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::AncLevel,
            FieldValue::Number(3),
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::NoiseMode,
            FieldValue::text("dynamic"),
        ),
        (
            ModelProfile::CeramicsXF8,
            HeadsetField::Eq,
            FieldValue::text("light"),
        ),
        (
            ModelProfile::AgateF8,
            HeadsetField::CaseBattery,
            FieldValue::Number(50),
        ),
        (
            ModelProfile::AgateF8,
            HeadsetField::NoiseMode,
            FieldValue::text("anc"),
        ),
        (
            ModelProfile::AgateF8,
            HeadsetField::Eq,
            FieldValue::text("rock"),
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetField::NoiseMode,
            FieldValue::text("wind"),
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetField::AncLevel,
            FieldValue::Number(2),
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetField::PromptVolume,
            FieldValue::Number(0),
        ),
        (
            ModelProfile::ExplorerF8,
            HeadsetField::GameMode,
            FieldValue::Number(1),
        ),
    ];
    for (profile, field, value) in refused {
        let mut headset = simulate(profile);
        let before = headset.readings();
        assert!(
            headset.set(field, value.clone()).is_err(),
            "{profile:?} {field:?} {value:?}"
        );
        assert_eq!(before, headset.readings(), "{profile:?} {field:?}");
    }
}

#[test]
fn firmware_is_one_number_that_fits_the_two_bytes_the_family_reports() {
    for parts in [vec![], vec![1, 2, 5], vec![-1], vec![65536]] {
        assert!(
            matches!(
                SimulatedHeadset::new(
                    ModelProfile::ExplorerF8,
                    FirmwareVersion::new(parts.clone(), "x")
                )
                .err(),
                Some(SimulatorError::InvalidFirmware(_))
            ),
            "{parts:?}"
        );
    }
    let mut headset = SimulatedHeadset::new(
        ModelProfile::ExplorerF8,
        FirmwareVersion::new(vec![515], "515"),
    )
    .expect("two-byte firmware");
    assert_eq!(
        vec![hex("00 27 01 00 06 02 00 00 00 02 03")],
        exchange(&mut headset, "00 27 01 00 01 02")
    );
}
