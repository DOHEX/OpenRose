use crate::core::{Attempt, Core, Interrupt};
use crate::gatt::GattConnection;
use crate::link::guarded;
use crate::selection::{Preselection, TransportEndpoint, TransportSelection, preselect};
use crate::spp::SppConnection;
use crate::transport::{ControlConnection, OpenFailure, OpenFailureKind};
use std::sync::Arc;
use tokio_util::sync::CancellationToken;

fn refused(error: crate::transport::TransportError) -> OpenFailure {
    OpenFailure {
        kind: OpenFailureKind::Transport(error),
        unreleased: None,
    }
}

impl Core {
    pub(crate) async fn select_transport(
        &self,
        attempt: &Attempt,
        epoch: u64,
    ) -> Result<TransportSelection, Interrupt> {
        let selection = match preselect(
            attempt.profile,
            attempt.candidate.classic_address.as_deref(),
            attempt.force_ble,
        ) {
            Preselection::Fixed(selection) => selection,
            Preselection::NeedsFacts(request) => {
                let lookup = self
                    .transport
                    .connection_facts(&attempt.candidate.id, request.address());
                let facts = guarded(
                    &attempt.cancel,
                    tokio::time::timeout(self.timing.connect_timeout, lookup),
                )
                .await?
                .map_err(|_| Interrupt::Timeout)?;
                request.decide(facts.unwrap_or_default())
            }
        };
        self.update_attempt(attempt, Some(epoch), |snapshot| {
            if let Some(headset) = snapshot.headset.as_mut() {
                headset.transport_selection = Some(selection.clone());
            }
        });
        Ok(selection)
    }

    pub(crate) async fn open_endpoint(
        &self,
        attempt: &Attempt,
        epoch: u64,
        endpoint: &TransportEndpoint,
        cancel: &CancellationToken,
    ) -> Result<Arc<dyn ControlConnection>, OpenFailure> {
        if cancel.is_cancelled() {
            return Err(OpenFailure {
                kind: OpenFailureKind::Cancelled,
                unreleased: None,
            });
        }
        let on_phase = |phase| self.phase(attempt, epoch, phase);
        match endpoint {
            TransportEndpoint::BleGatt { .. } => {
                let driver = self
                    .transport
                    .open_gatt(&attempt.candidate.id)
                    .await
                    .map_err(refused)?;
                let connection: Arc<dyn ControlConnection> =
                    GattConnection::open(driver, attempt.profile.gatt(), &on_phase, cancel).await?;
                Ok(connection)
            }
            TransportEndpoint::SppRfcomm {
                address,
                service_uuid,
            } => {
                let driver = self
                    .transport
                    .open_spp(&attempt.candidate.id, address, service_uuid)
                    .await
                    .map_err(refused)?;
                let connection: Arc<dyn ControlConnection> =
                    SppConnection::open(driver, &on_phase, cancel).await?;
                Ok(connection)
            }
        }
    }
}
