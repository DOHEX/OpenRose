mod support;

use async_trait::async_trait;
use openrose_desktop_platform::bluetooth::linux::bus::{
    AUDIO_SINK_UUID, AudioEndpointOps, BluezAudioBus, BluezError, BluezInterfaces, CLASSIC_BEARER,
    LE_BEARER, ProxyBluezAudioBus,
};
use std::sync::{Arc, Mutex};
use support::*;

const PATH: &str = "/org/bluez/hci0/dev_00_11_22_33_44_55";
const OWNER: &str = ":1.42";

#[derive(Clone, Default)]
struct RecordingOps {
    calls: Arc<Mutex<Vec<String>>>,
    gone: Option<&'static str>,
    busy_profile: bool,
}

impl RecordingOps {
    fn record(&self, label: &str, owner: &str, path: &str, call: &str) -> Result<(), BluezError> {
        assert_eq!(path, PATH);
        assert_eq!(owner, OWNER);
        self.calls.lock().expect("calls").push(format!("{label}.{call}"));
        if self.gone == Some(label) {
            return Err(BluezError::NotConnected("bearer already down".into()));
        }
        Ok(())
    }

    fn calls(&self) -> Vec<String> {
        self.calls.lock().expect("calls").clone()
    }
}

#[async_trait]
impl AudioEndpointOps for RecordingOps {
    async fn connect_profile(&self, owner: &str, path: &str, uuid: &str) -> Result<(), BluezError> {
        assert_eq!(owner, "org.bluez");
        assert_eq!(path, PATH);
        assert_eq!(uuid, AUDIO_SINK_UUID);
        if self.busy_profile {
            return Err(BluezError::InProgress("browsing".into()));
        }
        Ok(())
    }

    async fn disconnect_device(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        self.record("device", owner, path, "Disconnect")
    }

    async fn disconnect_classic_bearer(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        self.record("classic", owner, path, "disconnect")
    }

    async fn disconnect_le_bearer(&self, owner: &str, path: &str) -> Result<(), BluezError> {
        self.record("le", owner, path, "disconnect")
    }
}

fn bearers(classic: bool, le: bool) -> BluezInterfaces {
    interfaces(&[
        (CLASSIC_BEARER, props(&[("Connected", flag(classic))])),
        (LE_BEARER, props(&[("Connected", flag(le))])),
    ])
}

#[tokio::test]
async fn connect_profile_exposes_the_bluez_busy_reply_to_the_acquisition_loop() {
    let ops = RecordingOps {
        busy_profile: true,
        ..RecordingOps::default()
    };
    let error = ProxyBluezAudioBus::new(ops)
        .connect_audio(PATH)
        .await
        .expect_err("busy");
    assert!(matches!(error, BluezError::InProgress(_)));
}

#[tokio::test]
async fn dual_bearer_release_targets_only_the_initially_connected_bearers_in_classic_then_le_order() {
    let cases = [
        (bearers(true, true), vec!["classic.disconnect", "le.disconnect"]),
        (bearers(true, false), vec!["classic.disconnect"]),
        (bearers(false, true), vec!["le.disconnect"]),
        (bearers(false, false), vec![]),
    ];
    for (interfaces, expected) in cases {
        let ops = RecordingOps::default();
        ProxyBluezAudioBus::new(ops.clone())
            .disconnect_audio(PATH, OWNER, &interfaces)
            .await
            .expect("disconnect");
        assert_eq!(ops.calls(), expected);
    }
}

#[tokio::test]
async fn a_bearer_that_has_already_dropped_does_not_stop_the_release_of_the_other_bearer() {
    for gone in ["classic", "le"] {
        let ops = RecordingOps {
            gone: Some(gone),
            ..RecordingOps::default()
        };
        ProxyBluezAudioBus::new(ops.clone())
            .disconnect_audio(PATH, OWNER, &bearers(true, true))
            .await
            .expect("disconnect");
        assert_eq!(ops.calls(), vec!["classic.disconnect", "le.disconnect"]);
    }
}

#[tokio::test]
async fn a_missing_bearer_reading_on_either_side_only_disconnects_device1() {
    let cases = [
        BluezInterfaces::new(),
        interfaces(&[(CLASSIC_BEARER, props(&[("Connected", flag(true))]))]),
        interfaces(&[(LE_BEARER, props(&[("Connected", flag(true))]))]),
    ];
    for interfaces in cases {
        let ops = RecordingOps::default();
        ProxyBluezAudioBus::new(ops.clone())
            .disconnect_audio(PATH, OWNER, &interfaces)
            .await
            .expect("disconnect");
        assert_eq!(ops.calls(), vec!["device.Disconnect"]);
    }
}
