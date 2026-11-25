use crate::core::{Attempt, Core, Interrupt};
use crate::error::{ControlError, ControlErrorCode};
use crate::snapshot::{ConnectionPhase, ConnectionRecovery, ConnectionState};
use crate::transport::{TransportError, TransportRecoveryReason};
use std::sync::Arc;

pub(crate) fn recoverable_reason(interrupt: &Interrupt) -> Option<TransportRecoveryReason> {
    match interrupt {
        Interrupt::Transport(TransportError::Recoverable { reason, .. }) => Some(*reason),
        _ => None,
    }
}

impl Core {
    pub(crate) fn can_recover_first_query(&self, index: Option<usize>) -> bool {
        index == Some(0) && self.transport.supports_initialization_recovery()
    }

    pub(crate) fn can_retry(&self, attempt: &Attempt, reason: TransportRecoveryReason) -> bool {
        match reason {
            TransportRecoveryReason::WriteFailed | TransportRecoveryReason::NoInitialResponse => {
                self.can_recover_first_query(attempt.query_index())
            }
            TransportRecoveryReason::ServiceDiscovery
            | TransportRecoveryReason::ServicesUnavailable
            | TransportRecoveryReason::Subscription
            | TransportRecoveryReason::ServicesChanged => true,
        }
    }

    pub(crate) async fn begin_link(&self, attempt: &Arc<Attempt>) -> Result<u64, Interrupt> {
        let inner = self.lifecycle.lock().await;
        if !inner.is_active(attempt) {
            return Err(Interrupt::Cancelled);
        }
        let epoch = attempt.bump_epoch();
        attempt.reset_link();
        Ok(epoch)
    }

    pub(crate) async fn prepare_recovery(
        &self,
        attempt: &Arc<Attempt>,
        failed_epoch: u64,
        reason: TransportRecoveryReason,
    ) -> Result<(), Interrupt> {
        let (connection, observer) = {
            let inner = self.lifecycle.lock().await;
            if !inner.is_active(attempt) || attempt.epoch() != failed_epoch {
                return Err(Interrupt::Cancelled);
            }
            attempt.bump_epoch();
            self.end_settings(attempt);
            let mut link = attempt.link.lock().expect("link lock");
            (link.connection.take(), link.observer.take())
        };
        if let Some((cancel, _)) = &observer {
            cancel.cancel();
        }
        let released = match &connection {
            Some(connection) => self.connections.close(connection).await,
            None => true,
        };
        if let Some((_, handle)) = observer {
            let _ = handle.await;
        }
        if !released {
            return Err(Interrupt::Control(ControlError::new(
                ControlErrorCode::Disconnected,
                "旧控制连接尚未确认释放",
            )));
        }
        let inner = self.lifecycle.lock().await;
        if !inner.is_active(attempt) {
            return Err(Interrupt::Cancelled);
        }
        self.state.update(|snapshot| {
            if !attempt.matches(snapshot) {
                return;
            }
            snapshot.connection = ConnectionState::Connecting {
                headset_id: attempt.candidate.id.clone(),
                session_id: attempt.id,
                phase: ConnectionPhase::Connecting,
            };
            snapshot.headset = Some(attempt.loading_headset());
            snapshot.last_error = None;
            snapshot.connection_recovery = Some(ConnectionRecovery {
                session_id: attempt.id,
                reason,
            });
        });
        Ok(())
    }
}
