mod support;

use openrose_control::ControlErrorCode;
use openrose_control::scan::Advertisement;
use openrose_control::snapshot::{
    ConnectionState, PermissionState, PlatformState, ScanState, TransportAvailability,
};
use openrose_control::transport::TransportError;
use openrose_headset::{FirmwareVersion, HeadsetId, ModelProfile};
use support::scripted::simulated;
use support::{available, settle};

const BONDED: &str = "AA:BB:CC:00:00:05";

fn error_code<T: std::fmt::Debug>(
    result: Result<T, openrose_control::ControlError>,
) -> ControlErrorCode {
    result.expect_err("expected a failure").code
}

fn i5_advertisement(id: &str) -> Advertisement {
    Advertisement {
        manufacturer_data: [(0x5352, vec![0, 1, 0, 1])].into(),
        ..Advertisement::new(HeadsetId::new(id))
    }
}

fn firmware() -> FirmwareVersion {
    FirmwareVersion::new(vec![1, 2, 5], "125")
}

#[tokio::test(start_paused = true)]
async fn the_bluetooth_permission_is_required_before_scanning() {
    let denied = PlatformState::new(PermissionState::Denied, TransportAvailability::Available);
    let (transport, runtime, _) = simulated(denied);
    assert_eq!(
        ControlErrorCode::PermissionRequired,
        error_code(runtime.start_scan().await)
    );
    assert_eq!(0, transport.scan().scans_started());
    assert_eq!(ScanState::Idle, runtime.snapshot().scan);
    assert_eq!(
        Some(ControlErrorCode::PermissionRequired),
        runtime.snapshot().last_error.map(|error| error.code)
    );
}

#[tokio::test(start_paused = true)]
async fn the_scan_permission_blocks_scanning_but_never_paired_control() {
    let platform = available().with_scan_permission(PermissionState::Denied);
    let (transport, runtime, _) = simulated(platform);
    transport
        .add_headset(HeadsetId::new(BONDED), ModelProfile::I5Zt, firmware())
        .unwrap();
    settle().await;

    assert_eq!(
        ControlErrorCode::PermissionRequired,
        error_code(runtime.start_scan().await)
    );
    assert_eq!(0, transport.scan().scans_started());
    runtime
        .connect(HeadsetId::new(BONDED))
        .await
        .expect("paired control");
    assert!(matches!(
        runtime.snapshot().connection,
        ConnectionState::Connected { .. }
    ));
}

#[tokio::test(start_paused = true)]
async fn scanning_uses_manufacturer_evidence_and_leaves_same_name_variants_unsupported() {
    let (transport, runtime, _) = simulated(available());
    transport
        .add_headset(HeadsetId::new("known"), ModelProfile::I5Zt, firmware())
        .unwrap();
    runtime.start_scan().await.expect("scan");
    settle().await;
    transport.scan().advertise(Advertisement {
        name: Some("renamed".to_string()),
        rssi: Some(-40),
        ..i5_advertisement("known")
    });
    transport.scan().advertise(Advertisement {
        name: Some("EarFeel i5".to_string()),
        rssi: Some(-50),
        manufacturer_data: [(0x5352, vec![0, 2])].into(),
        ..Advertisement::new(HeadsetId::new("unknown"))
    });
    settle().await;

    let snapshot = runtime.snapshot();
    assert_eq!(ScanState::Scanning, snapshot.scan);
    assert_eq!(Some(ModelProfile::I5Zt), snapshot.candidates[0].profile);
    assert_eq!(None, snapshot.candidates[1].profile);
    assert_eq!(
        ControlErrorCode::Unsupported,
        error_code(runtime.connect(HeadsetId::new("unknown")).await)
    );
    assert_eq!(1, transport.scan().active_scans());
    runtime.stop_scan().await;
    assert_eq!(ScanState::Idle, runtime.snapshot().scan);
    assert_eq!(0, transport.scan().active_scans());
}

#[tokio::test(start_paused = true)]
async fn simultaneous_scan_entrypoints_share_one_scanner_and_a_stopped_scan_stays_stopped() {
    let (transport, runtime, _) = simulated(available());
    let (first, second) = tokio::join!(runtime.start_scan(), runtime.start_scan());
    first.expect("scan");
    second.expect("shared scan");
    assert_eq!(1, transport.scan().scans_started());

    runtime.stop_scan().await;
    transport.scan().advertise(i5_advertisement("stale"));
    settle().await;
    assert!(runtime.snapshot().candidates.is_empty());

    runtime.start_scan().await.expect("second scan");
    assert_eq!(2, transport.scan().scans_started());
    transport.scan().advertise(i5_advertisement("fresh"));
    settle().await;
    let ids: Vec<HeadsetId> = runtime
        .snapshot()
        .candidates
        .into_iter()
        .map(|candidate| candidate.id)
        .collect();
    assert_eq!(vec![HeadsetId::new("fresh")], ids);
    runtime.stop_scan().await;
}

#[tokio::test(start_paused = true)]
async fn repeated_advertisements_update_in_place_without_changing_discovery_order() {
    let (transport, runtime, _) = simulated(available());
    runtime.start_scan().await.expect("scan");
    transport.scan().advertise(i5_advertisement("first"));
    transport.scan().advertise(i5_advertisement("second"));
    transport.scan().advertise(Advertisement {
        name: Some("Updated name".to_string()),
        rssi: Some(-25),
        ..i5_advertisement("first")
    });
    settle().await;

    let candidates = runtime.snapshot().candidates;
    let ids: Vec<&str> = candidates.iter().map(|c| c.id.as_str()).collect();
    assert_eq!(vec!["first", "second"], ids);
    assert_eq!(Some("Updated name".to_string()), candidates[0].name);
    assert_eq!(Some(-25), candidates[0].rssi);
    runtime.stop_scan().await;
}

#[tokio::test(start_paused = true)]
async fn a_scan_without_manufacturer_data_keeps_the_known_identity_and_new_evidence_wins() {
    let (transport, runtime, _) = simulated(available());
    transport
        .add_headset(HeadsetId::new(BONDED), ModelProfile::I5Zt, firmware())
        .unwrap();
    settle().await;
    let paired = runtime.snapshot().bonded_headsets[0].clone();
    assert_eq!(Some(BONDED.to_string()), paired.classic_address);

    runtime.start_scan().await.expect("scan");
    transport.scan().advertise(Advertisement {
        rssi: Some(-48),
        ..Advertisement::new(HeadsetId::new(BONDED))
    });
    settle().await;
    let expected = openrose_control::snapshot::HeadsetCandidate {
        rssi: Some(-48),
        ..paired
    };
    assert_eq!(vec![expected.clone()], runtime.snapshot().candidates);
    runtime
        .connect(HeadsetId::new(BONDED))
        .await
        .expect("connect");
    assert_eq!(Some(expected), runtime.snapshot().connection_target);
    runtime.disconnect().await;

    runtime.start_scan().await.expect("second scan");
    transport.scan().advertise(Advertisement {
        manufacturer_data: [(0x8418, vec![5, 5])].into(),
        ..Advertisement::new(HeadsetId::new(BONDED))
    });
    settle().await;
    transport.scan().advertise(Advertisement {
        rssi: Some(-52),
        ..Advertisement::new(HeadsetId::new(BONDED))
    });
    settle().await;
    let candidates = runtime.snapshot().candidates;
    assert_eq!(1, candidates.len());
    assert_eq!(Some(ModelProfile::AgateF8), candidates[0].profile);
    assert_eq!("18840505", candidates[0].manufacturer_data_hex);
    assert_eq!(Some(-52), candidates[0].rssi);
    assert_eq!(None, candidates[0].classic_address);
    runtime.stop_scan().await;
}

#[tokio::test(start_paused = true)]
async fn a_scanner_that_cannot_start_or_fails_later_reports_the_failure() {
    let (transport, runtime, _) = simulated(available());
    transport
        .scan()
        .fail_start(Some(TransportError::Terminal("扫描器不可用".to_string())));
    let error = runtime.start_scan().await.expect_err("scan start fails");
    assert_eq!(ControlErrorCode::Failed, error.code);
    assert_eq!(ScanState::Failed(error.clone()), runtime.snapshot().scan);
    assert_eq!(Some(error), runtime.snapshot().last_error);

    transport.scan().fail_start(None);
    runtime.start_scan().await.expect("scan again");
    assert_eq!(ScanState::Scanning, runtime.snapshot().scan);
    transport
        .scan()
        .fail_scans(TransportError::Terminal("适配器断开".to_string()));
    settle().await;
    let ScanState::Failed(error) = runtime.snapshot().scan else {
        panic!("the scan should have failed");
    };
    assert_eq!("适配器断开", error.message);
    assert_eq!(0, transport.scan().active_scans());
}

#[tokio::test(start_paused = true)]
async fn connecting_stops_the_scan() {
    let (transport, runtime, _) = simulated(available());
    transport
        .add_headset(HeadsetId::new(BONDED), ModelProfile::I5Zt, firmware())
        .unwrap();
    settle().await;
    runtime.start_scan().await.expect("scan");
    assert_eq!(1, transport.scan().active_scans());
    runtime
        .connect(HeadsetId::new(BONDED))
        .await
        .expect("connect");
    assert_eq!(0, transport.scan().active_scans());
    assert_eq!(ScanState::Idle, runtime.snapshot().scan);
}
