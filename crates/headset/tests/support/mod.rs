use openrose_headset::{FirmwareVersion, ModelProfile, ProtocolFamily};

pub fn firmwares(profile: ModelProfile) -> Vec<FirmwareVersion> {
    match profile.family() {
        ProtocolFamily::F8 => vec![
            FirmwareVersion::new(vec![125], "125"),
            FirmwareVersion::new(vec![999], "999"),
        ],
        ProtocolFamily::Zt | ProtocolFamily::RsCommonV2 | ProtocolFamily::RsCommon => vec![
            FirmwareVersion::new(vec![1, 2, 5], "125"),
            FirmwareVersion::new(vec![9, 9, 9], "999"),
        ],
    }
}
