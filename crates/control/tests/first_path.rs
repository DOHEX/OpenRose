mod support;

use openrose_control::snapshot::{ConnectionPhase, ConnectionState, SettingStage};
use openrose_headset::{FieldReading, FieldValue, HeadsetCommand, HeadsetField, ModelProfile, NoiseMode};
use support::Harness;

#[tokio::test(start_paused = true)]
async fn the_simulated_i5_connects_reads_its_state_and_confirms_a_noise_mode_change() {
    let harness = Harness::i5();
    harness.runtime.refresh_bonded_headsets().await;
    harness.runtime.connect(harness.id()).await.expect("connect");

    let ConnectionState::Connected { profile, .. } = harness.connection() else {
        panic!("expected a ready control session");
    };
    assert_eq!(ModelProfile::I5Zt, profile);
    assert_eq!(Some(FieldValue::Number(100)), harness.value(HeadsetField::LeftBattery));
    assert!(matches!(
        harness.reading(HeadsetField::CaseBattery),
        Some(FieldReading::NotProvided { .. })
    ));
    assert_eq!(Some(FieldValue::text("wind")), harness.value(HeadsetField::NoiseMode));

    harness
        .runtime
        .set(HeadsetCommand::NoiseMode(NoiseMode::Normal), None)
        .await
        .expect("the headset confirms the mode");

    assert_eq!(Some(FieldValue::text("normal")), harness.value(HeadsetField::NoiseMode));
    assert_eq!(vec![SettingStage::Confirmed], harness.stages());
    assert_eq!(
        Some(&FieldValue::text("normal")),
        harness.headset.readings().get(&HeadsetField::NoiseMode)
    );

    let phases: Vec<ConnectionPhase> = harness
        .recorder
        .all()
        .iter()
        .filter_map(|snapshot| match snapshot.connection {
            ConnectionState::Connecting { phase, .. } => Some(phase),
            _ => None,
        })
        .collect();
    let mut sorted = phases.clone();
    sorted.sort();
    sorted.dedup();
    assert_eq!(
        vec![
            ConnectionPhase::Connecting,
            ConnectionPhase::Discovering,
            ConnectionPhase::Subscribing,
            ConnectionPhase::Initializing
        ],
        sorted
    );
    assert!(harness.runtime.disconnect().await);
}
