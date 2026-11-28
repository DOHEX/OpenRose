use crate::audio::paired_headsets;
use crate::core::{Core, platform_error};
use crate::error::{ControlError, ControlErrorCode, ControlResult};
use crate::scan::{Advertisement, ScanDriver};
use crate::snapshot::{HeadsetCandidate, ScanState};
use crate::transport::TransportError;
use openrose_headset::{HeadsetId, ModelIdentification};
use std::sync::Arc;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

#[derive(Default)]
pub(crate) struct ScanSlot {
    active: Option<ActiveScan>,
}

struct ActiveScan {
    cancel: CancellationToken,
    task: JoinHandle<()>,
}

fn candidate_of(advertisement: Advertisement) -> HeadsetCandidate {
    let manufacturer_data_hex =
        ModelIdentification::normalized_hex(&advertisement.manufacturer_data);
    let profile = ModelIdentification::identify(&advertisement.manufacturer_data);
    HeadsetCandidate {
        id: advertisement.headset_id,
        name: advertisement.name,
        rssi: advertisement.rssi,
        profile,
        classic_address: ModelIdentification::classic_address(&manufacturer_data_hex, profile),
        manufacturer_data_hex,
    }
}

impl Core {
    pub(crate) async fn refresh_bonded_headsets(&self) {
        let Ok(raw) = self.audio.bonded_devices().await else {
            return;
        };
        let bonded = paired_headsets(&raw);
        self.state
            .update(|snapshot| snapshot.apply_bonded_headsets(bonded));
    }

    pub(crate) async fn find_candidate(&self, id: &HeadsetId) -> Option<HeadsetCandidate> {
        let cached = self.state.read(|snapshot| snapshot.known_candidate(id));
        if cached
            .as_ref()
            .is_some_and(|candidate| candidate.profile.is_some())
        {
            return cached;
        }
        self.refresh_bonded_headsets().await;
        self.state.read(|snapshot| snapshot.known_candidate(id))
    }

    pub(crate) async fn start_scan(self: &Arc<Self>) -> ControlResult {
        let mut slot = self.scan.lock().await;
        let denied = self.state.read(|snapshot| {
            if snapshot.platform.scan_permission.missing() {
                Some(ControlError::new(
                    ControlErrorCode::PermissionRequired,
                    "需要扫描权限才能搜索附近耳机",
                ))
            } else {
                platform_error(snapshot)
            }
        });
        if let Some(error) = denied {
            return self.reject(error);
        }
        if slot
            .active
            .as_ref()
            .is_some_and(|active| !active.task.is_finished())
        {
            return Ok(());
        }
        let driver = match self.transport.start_scan().await {
            Ok(driver) => driver,
            Err(error) => {
                let error = ControlError::new(ControlErrorCode::Failed, error.message());
                self.state.update(|snapshot| {
                    snapshot.scan = ScanState::Failed(error.clone());
                    snapshot.last_error = Some(error.clone());
                });
                return Err(error);
            }
        };
        self.state.update(|snapshot| {
            snapshot.scan = ScanState::Scanning;
            snapshot.candidates.clear();
            snapshot.last_error = None;
        });
        let cancel = self.shutdown.child_token();
        let task = tokio::spawn(Arc::clone(self).pump_scan(driver, cancel.clone()));
        slot.active = Some(ActiveScan { cancel, task });
        Ok(())
    }

    pub(crate) async fn stop_scan(&self) {
        let mut slot = self.scan.lock().await;
        if let Some(active) = slot.active.take() {
            active.cancel.cancel();
            let _ = active.task.await;
        }
        self.state
            .update(|snapshot| snapshot.scan = ScanState::Idle);
    }

    async fn pump_scan(self: Arc<Self>, driver: Box<dyn ScanDriver>, cancel: CancellationToken) {
        let ended: Option<Result<(), TransportError>> = loop {
            let next = tokio::select! {
                biased;
                _ = cancel.cancelled() => break None,
                next = driver.next_advertisement() => next,
            };
            match next {
                Ok(Some(advertisement)) => self.observe_advertisement(advertisement, &cancel),
                Ok(None) => break Some(Ok(())),
                Err(error) => break Some(Err(error)),
            }
        };
        driver.stop().await;
        let Some(ended) = ended else {
            return;
        };
        self.state.update(|snapshot| match ended {
            Ok(()) => snapshot.scan = ScanState::Idle,
            Err(error) => {
                let error = ControlError::new(ControlErrorCode::Failed, error.message());
                snapshot.scan = ScanState::Failed(error.clone());
                snapshot.last_error = Some(error);
            }
        });
    }

    fn observe_advertisement(&self, advertisement: Advertisement, cancel: &CancellationToken) {
        let candidate = candidate_of(advertisement);
        let id = candidate.id.clone();
        self.state.update(|snapshot| {
            if cancel.is_cancelled() {
                return;
            }
            let identified = candidate.with_evidence(snapshot.known_candidate(&id).as_ref());
            match snapshot
                .candidates
                .iter_mut()
                .find(|existing| existing.id == identified.id)
            {
                Some(existing) => *existing = identified,
                None => snapshot.candidates.push(identified),
            }
        });
    }
}
