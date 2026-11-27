use openrose_ffi_simulator::SimulatedHeadset;
use openrose_headset::{FieldValue, FirmwareVersion, HeadsetField, ModelProfile, decode_hex};

const NOISE_READBACK_QUERY: &str = "08ee00000006020a0008";

#[test]
fn a_simulated_headset_answers_a_query_and_exposes_its_readings() {
    let simulated = SimulatedHeadset::new(
        ModelProfile::I5Zt,
        FirmwareVersion::new(vec![1, 2, 5], "125"),
    )
    .expect("i5 simulator");

    let replies = simulated.receive(decode_hex(NOISE_READBACK_QUERY).expect("query hex"));

    assert!(!replies.is_empty());
    assert_eq!(
        Some(&FieldValue::text("wind")),
        simulated.readings().get(&HeadsetField::NoiseMode)
    );
}
