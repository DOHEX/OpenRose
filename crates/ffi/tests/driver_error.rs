use openrose_circulation::YieldRefusal;
use openrose_control::gatt::{GattError, GattFailureKind};
use openrose_control::spp::{SppError, SppFailureKind};
use openrose_control::transport::TransportError;
use openrose_ffi::{DriverError, YieldRefusalKind};

#[test]
fn a_gatt_failure_keeps_its_kind_and_detail() {
    let error = GattError::from(DriverError::Gatt {
        kind: GattFailureKind::Transient,
        detail: "link busy".to_string(),
    });

    assert_eq!(
        GattError::new(GattFailureKind::Transient, "link busy"),
        error
    );
}

#[test]
fn a_spp_failure_keeps_its_kind_and_detail() {
    let error = SppError::from(DriverError::Spp {
        kind: SppFailureKind::Permission,
        detail: "BLUETOOTH_CONNECT was revoked".to_string(),
    });

    assert_eq!(
        SppError::new(SppFailureKind::Permission, "BLUETOOTH_CONNECT was revoked"),
        error
    );
}

#[test]
fn a_failure_of_another_driver_kind_is_unknown_with_its_detail() {
    let gatt = GattError::from(DriverError::Spp {
        kind: SppFailureKind::Permission,
        detail: "wrong driver".to_string(),
    });
    let spp = SppError::from(DriverError::Terminal {
        detail: "callback crashed".to_string(),
    });

    assert_eq!(
        GattError::new(GattFailureKind::Unknown, "wrong driver"),
        gatt
    );
    assert_eq!(
        SppError::new(SppFailureKind::Unknown, "callback crashed"),
        spp
    );
}

#[test]
fn a_transport_failure_keeps_its_variant_and_detail() {
    let cases = [
        (
            DriverError::PermissionDenied {
                detail: "revoked".to_string(),
            },
            TransportError::PermissionDenied("revoked".to_string()),
        ),
        (
            DriverError::Unsupported {
                detail: "no radio".to_string(),
            },
            TransportError::Unsupported("no radio".to_string()),
        ),
        (
            DriverError::InvalidArgument {
                detail: "bad address".to_string(),
            },
            TransportError::InvalidArgument("bad address".to_string()),
        ),
        (
            DriverError::Terminal {
                detail: "adapter reset".to_string(),
            },
            TransportError::Terminal("adapter reset".to_string()),
        ),
        (
            DriverError::Gatt {
                kind: GattFailureKind::Permission,
                detail: "gatt call".to_string(),
            },
            TransportError::Terminal("gatt call".to_string()),
        ),
    ];

    for (failure, expected) in cases {
        assert_eq!(expected, TransportError::from(failure));
    }
}

#[test]
fn a_driver_error_displays_its_detail() {
    let error = DriverError::PermissionDenied {
        detail: "BLUETOOTH_CONNECT was revoked".to_string(),
    };

    assert_eq!("BLUETOOTH_CONNECT was revoked", error.to_string());
}

#[test]
fn a_yield_refusal_keeps_its_kind_and_detail() {
    assert_eq!(
        YieldRefusal::InCall,
        YieldRefusal::from(DriverError::YieldRefused {
            kind: YieldRefusalKind::InCall,
            detail: "in call".to_string(),
        })
    );
    assert_eq!(
        YieldRefusal::MicInUse,
        YieldRefusal::from(DriverError::YieldRefused {
            kind: YieldRefusalKind::MicInUse,
            detail: "mic busy".to_string(),
        })
    );
    assert_eq!(
        YieldRefusal::Unavailable("audio_changing".to_string()),
        YieldRefusal::from(DriverError::YieldRefused {
            kind: YieldRefusalKind::Unavailable,
            detail: "audio_changing".to_string(),
        })
    );
    assert_eq!(
        YieldRefusal::Unavailable("link lost".to_string()),
        YieldRefusal::from(DriverError::Terminal {
            detail: "link lost".to_string(),
        })
    );
}
