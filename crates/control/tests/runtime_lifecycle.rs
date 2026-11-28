mod support;

use openrose_control::snapshot::{ConnectionState, SettingStage};
use openrose_headset::{HeadsetCommand, HeadsetField, NoiseMode};
use std::sync::Arc;
use std::time::Duration;
use support::{Harness, Options, settle};

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn concurrent_callers_on_a_multi_thread_runtime_finish_without_deadlock() {
    let harness = Arc::new(Harness::ready(Options::i5()).await);
    let work = async {
        let mut callers = Vec::new();
        for caller in 0..8 {
            let harness = Arc::clone(&harness);
            callers.push(tokio::spawn(async move {
                let mode = [NoiseMode::Anc, NoiseMode::Normal, NoiseMode::Transparency][caller % 3];
                for _ in 0..5 {
                    harness
                        .runtime
                        .set(HeadsetCommand::NoiseMode(mode), None)
                        .await
                        .expect("serialized and confirmed");
                    harness
                        .headset
                        .change(
                            HeadsetField::LeftBattery,
                            openrose_headset::FieldValue::Number(40 + caller as i32),
                        )
                        .expect("battery change");
                }
            }));
        }
        for caller in callers {
            caller.await.expect("caller");
        }
        assert!(harness.runtime.disconnect().await);
    };
    tokio::time::timeout(Duration::from_secs(20), work)
        .await
        .expect("no deadlock");
    let operations = harness.snapshot().setting_operations;
    assert_eq!(40, operations.len());
    assert!(
        operations
            .iter()
            .all(|operation| operation.stage == SettingStage::Confirmed)
    );
    assert_eq!(ConnectionState::Disconnected, harness.connection());
}

#[tokio::test(start_paused = true)]
async fn dropping_the_runtime_releases_the_control_connection() {
    let mut harness = Harness::ready(Options::i5()).await;
    assert!(harness.headset.link_open());
    let runtime = std::mem::replace(
        &mut harness.runtime,
        Arc::new(openrose_control::ControlRuntime::new(
            harness.transport.clone(),
            harness.recorder.clone(),
            openrose_control::ControlConfig::new(support::available(), harness.clock.clone()),
        )),
    );
    drop(runtime);
    settle().await;
    assert!(!harness.headset.link_open());
}
