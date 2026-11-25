use openrose_headset::{ModelProfile, ReconnectGuidance};

#[test]
fn only_models_with_a_charging_case_ask_the_user_to_open_it() {
    assert_eq!(
        ReconnectGuidance::OpenCaseAutoConnect,
        ModelProfile::I5Zt.reconnect_guidance()
    );
    assert_eq!(
        ReconnectGuidance::WaitSystemBluetooth,
        ModelProfile::HeadFeelZt.reconnect_guidance()
    );
}
