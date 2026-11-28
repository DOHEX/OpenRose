use openrose_headset::simulator::SimulatedHeadset;
use openrose_headset::{FirmwareVersion, ModelProfile, decode_hex, encode_hex};

fn i5() -> SimulatedHeadset {
    SimulatedHeadset::new(
        ModelProfile::I5Zt,
        FirmwareVersion::new(vec![1, 2, 5], "125"),
    )
    .expect("i5 simulator")
}

fn exchange(headset: &mut SimulatedHeadset, request: &str) -> Vec<String> {
    headset
        .receive(&decode_hex(request).expect("request hex"))
        .iter()
        .map(|frame| encode_hex(frame))
        .collect()
}

#[test]
fn i5_answers_every_initial_query_with_the_actual_capture_bytes() {
    let mut headset = i5();
    let captured = [
        ("08ee00000001010a0002", "09ff00000101011100000164620000ffe2"),
        ("08ee00000006020a0008", "09ff00000106020e000000010020"),
        ("08ee00000002010a0003", "09ff00000102010b000118"),
        ("08ee00000006030a0009", "09ff00000106030b00011e"),
        ("08ee00000008010a0009", "09ff00000108010b00011e"),
        ("08ee0000000d010a000e", "09ff0000010d010e00010205002d"),
        (
            "08ee00000004010a0005",
            "09ff00000104011200060502040605030746",
        ),
        ("08ee0000000a010a000b", "09ff0000010a010b00001f"),
        ("08ee00000006050a000b", "09ff00000106050e000101010126"),
        ("08ee0000000c010a000d", "09ff0000010c010b000122"),
        ("08ee00000006070a000d", "09ff00000106070b000223"),
        ("08ee00000006040a000a", "09ff00000106040b00001e"),
        ("08ee0000000e030a0011", "09ff0000010e030b000126"),
    ];
    for (query, reply) in captured {
        assert_eq!(
            vec![reply.to_string()],
            exchange(&mut headset, query),
            "{query}"
        );
    }
}

#[test]
fn writes_change_device_state_that_only_the_readback_reveals() {
    let mut headset = i5();
    let cases = [
        (
            "08ee00000006820e00000100008d",
            "08ee00000006020a0008",
            "09ff00000106020e000001000020",
        ),
        (
            "08ee00000006870b00018f",
            "08ee00000006070a000d",
            "09ff00000106070b000122",
        ),
        (
            "08ee00000006840b00018c",
            "08ee00000006040a000a",
            "09ff00000106040b00011f",
        ),
        (
            "08ee00000006850e000100000191",
            "08ee00000006050a000b",
            "09ff00000106050e000100000124",
        ),
    ];
    for (write, readback, reply) in cases {
        assert_eq!(
            Vec::<String>::new(),
            exchange(&mut headset, write),
            "{write}"
        );
        assert_eq!(
            vec![reply.to_string()],
            exchange(&mut headset, readback),
            "{readback}"
        );
    }
}

#[test]
fn a_device_that_acknowledges_writes_echoes_the_setter_key_without_the_state_reply() {
    let mut headset = i5();
    headset.acknowledge_writes(true);
    assert_eq!(
        vec!["09ff00000106820e0000010000a0".to_string()],
        exchange(&mut headset, "08ee00000006820e00000100008d")
    );
    assert_eq!(
        vec!["09ff00000106020e000001000020".to_string()],
        exchange(&mut headset, "08ee00000006020a0008")
    );
}

#[test]
fn frames_with_a_bad_checksum_length_or_unknown_key_get_no_reply() {
    let mut headset = i5();
    assert!(exchange(&mut headset, "08ee00000006020a0009").is_empty());
    assert!(exchange(&mut headset, "08ee00000006020b0008").is_empty());
    assert!(exchange(&mut headset, "08ee00000063ff0a0063").is_empty());
    assert!(exchange(&mut headset, "08ee0000").is_empty());
}

#[test]
fn device_initiated_changes_emit_the_notification_of_the_changed_field() {
    use openrose_headset::{FieldValue, HeadsetField};
    let mut headset = i5();
    let frames = headset
        .set(HeadsetField::NoiseMode, FieldValue::text("anc"))
        .expect("anc is a ZT mode");
    assert_eq!(
        vec!["09ff00000106020e000100000020".to_string()],
        frames.iter().map(|f| encode_hex(f)).collect::<Vec<_>>()
    );
    assert_eq!(
        Some(&FieldValue::text("anc")),
        headset.readings().get(&HeadsetField::NoiseMode)
    );
    assert!(
        headset
            .set(HeadsetField::NoiseMode, FieldValue::text("dynamic"))
            .is_err()
    );
    assert_eq!(
        Some(&FieldValue::text("anc")),
        headset.readings().get(&HeadsetField::NoiseMode)
    );
    let battery = headset
        .set(HeadsetField::LeftBattery, FieldValue::Number(41))
        .expect("battery");
    assert_eq!(
        "09ff00000101011100000129620000ffa7",
        encode_hex(&battery[0])
    );
}

#[test]
fn firmware_must_be_three_hex_digits() {
    use openrose_headset::simulator::SimulatorError;
    assert!(matches!(
        SimulatedHeadset::new(ModelProfile::I5Zt, FirmwareVersion::new(vec![1, 2], "12")),
        Err(SimulatorError::InvalidFirmware(_))
    ));
    assert!(matches!(
        SimulatedHeadset::new(
            ModelProfile::I5Zt,
            FirmwareVersion::new(vec![1, 16, 0], "1160")
        ),
        Err(SimulatorError::InvalidFirmware(_))
    ));
}
