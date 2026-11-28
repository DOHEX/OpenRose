use crate::transport::TransportError;
use async_trait::async_trait;
use openrose_headset::HeadsetId;
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "uniffi", derive(uniffi::Record))]
pub struct Advertisement {
    pub headset_id: HeadsetId,
    pub name: Option<String>,
    pub rssi: Option<i16>,
    pub manufacturer_data: BTreeMap<u16, Vec<u8>>,
}

impl Advertisement {
    pub fn new(headset_id: HeadsetId) -> Self {
        Self {
            headset_id,
            name: None,
            rssi: None,
            manufacturer_data: BTreeMap::new(),
        }
    }
}

#[async_trait]
pub trait ScanDriver: Send + Sync {
    async fn next_advertisement(&self) -> Result<Option<Advertisement>, TransportError>;

    async fn stop(&self);
}
