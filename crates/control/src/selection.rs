use crate::transport::{AudioProfileEvidence, TransportFacts};
use openrose_headset::ModelProfile;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransportEndpoint {
    BleGatt {
        service_uuid: String,
        write_uuid: String,
        notify_uuid: String,
    },
    SppRfcomm {
        address: String,
        service_uuid: String,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransportSelectionReason {
    ModelBleOnly,
    ForcedBle,
    SppUnavailable,
    ClassicAddressUnavailable,
    AudioProfileUnknown,
    LeAudio,
    ClassicAudio,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransportSelection {
    pub endpoint: TransportEndpoint,
    pub reason: TransportSelectionReason,
}

pub(crate) struct SppRequest {
    ble: TransportEndpoint,
    address: String,
    service_uuid: String,
}

pub(crate) enum Preselection {
    Fixed(TransportSelection),
    NeedsFacts(SppRequest),
}

pub(crate) fn preselect(
    profile: ModelProfile,
    classic_address: Option<&str>,
    force_ble: bool,
) -> Preselection {
    let gatt = profile.gatt();
    let ble = TransportEndpoint::BleGatt {
        service_uuid: gatt.service_uuid,
        write_uuid: gatt.write_uuid,
        notify_uuid: gatt.notify_uuid,
    };
    let fixed = |ble: TransportEndpoint, reason| {
        Preselection::Fixed(TransportSelection {
            endpoint: ble,
            reason,
        })
    };
    let Some(service_uuid) = profile.spp_control_uuid() else {
        return fixed(ble, TransportSelectionReason::ModelBleOnly);
    };
    if force_ble {
        return fixed(ble, TransportSelectionReason::ForcedBle);
    }
    let Some(address) = classic_address else {
        return fixed(ble, TransportSelectionReason::ClassicAddressUnavailable);
    };
    Preselection::NeedsFacts(SppRequest {
        ble,
        address: address.to_string(),
        service_uuid: service_uuid.to_string(),
    })
}

impl SppRequest {
    pub(crate) fn address(&self) -> &str {
        &self.address
    }

    pub(crate) fn decide(self, facts: TransportFacts) -> TransportSelection {
        let Self {
            ble,
            address,
            service_uuid,
        } = self;
        let (endpoint, reason) = if !facts.spp_available {
            (ble, TransportSelectionReason::SppUnavailable)
        } else {
            match facts.audio_profile {
                AudioProfileEvidence::Unknown => {
                    (ble, TransportSelectionReason::AudioProfileUnknown)
                }
                AudioProfileEvidence::LeAudio => (ble, TransportSelectionReason::LeAudio),
                AudioProfileEvidence::Classic => (
                    TransportEndpoint::SppRfcomm {
                        address,
                        service_uuid,
                    },
                    TransportSelectionReason::ClassicAudio,
                ),
            }
        };
        TransportSelection { endpoint, reason }
    }
}
