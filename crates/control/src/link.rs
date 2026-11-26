use crate::confirmation::ReadProgress;
use crate::core::{Attempt, AttemptOutcome, Core, Interrupt, LinkInner};
use crate::error::{ControlError, ControlErrorCode};
use crate::recovery::recoverable_reason;
use crate::selection::TransportSelection;
use crate::snapshot::{ConnectionPhase, ConnectionState, ControlSnapshot, HeadsetCandidate};
use crate::targeting::{Origin, superseded_error};
use crate::transport::{
    ControlConnection, OpenFailureKind, TransportError, TransportRecoveryReason,
};
use openrose_headset::{FieldReading, HeadsetField, ModelProfile, ProtocolQuery, ProtocolReply};
use std::collections::BTreeSet;
use std::sync::Arc;
use std::time::Duration;
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;

const RECENT_NOTIFICATIONS: usize = 128;

pub(crate) struct AttemptRequest<'a> {
    pub(crate) candidate: HeadsetCandidate,
    pub(crate) profile: ModelProfile,
    pub(crate) origin: Origin,
    pub(crate) superseded: &'a (dyn Fn() -> bool + Send + Sync),
}

fn restrict(reply: &ProtocolReply, readable: &BTreeSet<HeadsetField>) -> ProtocolReply {
    let readings = reply.for_readable_fields(readable);
    if readings.len() == reply.readings.len() {
        return reply.clone();
    }
    let keys = if readings.is_empty() {
        BTreeSet::new()
    } else {
        reply.response_keys.clone()
    };
    ProtocolReply::new(
        reply.raw_hex.clone(),
        keys,
        readings,
        reply.warnings.clone(),
    )
}

fn append_recent(list: &mut Vec<String>, values: impl IntoIterator<Item = String>) {
    list.extend(values);
    if list.len() > RECENT_NOTIFICATIONS {
        list.drain(..list.len() - RECENT_NOTIFICATIONS);
    }
}

pub(crate) async fn guarded<T>(
    cancel: &CancellationToken,
    operation: impl Future<Output = T>,
) -> Result<T, Interrupt> {
    tokio::select! {
        biased;
        _ = cancel.cancelled() => Err(Interrupt::Cancelled),
        value = operation => Ok(value),
    }
}

pub(crate) async fn within<T>(
    cancel: &CancellationToken,
    limit: Duration,
    operation: impl Future<Output = Result<T, TransportError>>,
) -> Result<T, Interrupt> {
    match guarded(cancel, tokio::time::timeout(limit, operation)).await? {
        Err(_) => Err(Interrupt::Timeout),
        Ok(Err(error)) => Err(Interrupt::Transport(error)),
        Ok(Ok(value)) => Ok(value),
    }
}

impl Core {
    pub(crate) fn update_attempt(
        &self,
        attempt: &Attempt,
        epoch: Option<u64>,
        change: impl FnOnce(&mut ControlSnapshot),
    ) {
        self.state.update(|snapshot| {
            let live =
                epoch.is_none_or(|epoch| attempt.epoch() == epoch) && attempt.matches(snapshot);
            if live {
                change(snapshot);
            }
        });
    }

    pub(crate) fn phase(&self, attempt: &Attempt, epoch: u64, phase: ConnectionPhase) {
        self.update_attempt(attempt, Some(epoch), |snapshot| {
            if let ConnectionState::Connecting { phase: current, .. } = &mut snapshot.connection
                && phase != ConnectionPhase::Ready
                && phase >= *current
            {
                *current = phase;
            }
        });
    }

    pub(crate) async fn begin_attempt(
        self: &Arc<Self>,
        request: AttemptRequest<'_>,
    ) -> Result<Arc<Attempt>, ControlError> {
        let mut inner = self.lifecycle.lock().await;
        if inner.released {
            return Err(ControlError::new(
                ControlErrorCode::NotReady,
                "控制所有者已释放",
            ));
        }
        if (request.superseded)() {
            return Err(superseded_error());
        }
        let candidate = request.candidate;
        let shared = inner.active.clone().filter(|active| {
            active.candidate.id == candidate.id
                && self
                    .state
                    .read(|s| !matches!(s.connection, ConnectionState::Disconnected))
        });
        if let Some(active) = shared {
            return Ok(active);
        }
        if !self.clear_active(&mut inner).await {
            return Err(ControlError::new(
                ControlErrorCode::NotReady,
                "之前的控制连接尚未确认释放",
            ));
        }
        inner.generation += 1;
        let audio_bound = request.origin == Origin::Audio
            || self
                .state
                .read(|s| candidate.matches_audio(s.audio_headset_id.as_ref()));
        let attempt = Arc::new(Attempt::new(
            inner.generation,
            candidate.clone(),
            request.profile,
            request.origin,
            audio_bound,
            self.shutdown.child_token(),
            self.state.read(|s| s.settings.force_ble),
        ));
        self.state.update(|s| {
            s.connection = ConnectionState::Connecting {
                headset_id: candidate.id.clone(),
                session_id: attempt.id,
                phase: ConnectionPhase::Connecting,
            };
            s.headset = Some(attempt.loading_headset());
            s.last_error = None;
            s.connection_recovery = None;
        });
        inner.active = Some(Arc::clone(&attempt));
        tokio::spawn(Arc::clone(self).run_attempt(Arc::clone(&attempt)));
        Ok(attempt)
    }

    pub(crate) async fn disconnect(self: &Arc<Self>, retire: bool) -> bool {
        let mut inner = self.lifecycle.lock().await;
        self.disconnect_locked(&mut inner, retire).await
    }

    pub(crate) async fn disconnect_locked(&self, inner: &mut LinkInner, retire: bool) -> bool {
        let closed = self.clear_active(inner).await;
        if retire && closed {
            inner.released = true;
        }
        self.state.update(|s| {
            s.connection = ConnectionState::Disconnected;
            s.headset = None;
            s.last_error = None;
            s.connection_recovery = None;
        });
        closed
    }

    pub(crate) async fn clear_active(&self, inner: &mut LinkInner) -> bool {
        let previous = inner.active.take();
        inner.generation += 1;
        if let Some(previous) = &previous {
            previous.bump_epoch();
        }
        self.state.update(|s| {
            s.connection = ConnectionState::Disconnected;
            s.headset = None;
        });
        if let Some(previous) = &previous {
            self.end_settings(previous);
            previous.cancel.cancel();
        }
        self.connections.close_all().await
    }

    async fn run_attempt(self: Arc<Self>, attempt: Arc<Attempt>) {
        struct UnwindGuard {
            core: Arc<Core>,
            attempt: Arc<Attempt>,
            finished: bool,
        }
        impl Drop for UnwindGuard {
            fn drop(&mut self) {
                if self.finished {
                    return;
                }
                self.core.update_attempt(&self.attempt, None, |s| {
                    s.connection = ConnectionState::Disconnected;
                    s.headset = None;
                });
                self.attempt
                    .outcome
                    .send_replace(Some(AttemptOutcome::Panicked));
            }
        }
        let mut guard = UnwindGuard {
            core: Arc::clone(&self),
            attempt: Arc::clone(&attempt),
            finished: false,
        };
        let outcome = match self.open_and_read(&attempt).await {
            Ok(()) => Ok(()),
            Err(Interrupt::Cancelled) => {
                self.update_attempt(&attempt, None, |s| {
                    s.connection = ConnectionState::Disconnected;
                    s.headset = None;
                    s.last_error = Some(ControlError::new(
                        ControlErrorCode::Disconnected,
                        "控制会话已结束",
                    ));
                });
                Err(ControlError::new(
                    ControlErrorCode::Disconnected,
                    "连接会话已结束",
                ))
            }
            Err(other) => {
                let error = other.into_connection_error();
                self.update_attempt(&attempt, None, |s| {
                    s.connection = ConnectionState::Disconnected;
                    s.headset = None;
                    s.last_error = Some(error.clone());
                });
                Err(error)
            }
        };
        if !attempt.is_ready() {
            self.close_attempt_link(&attempt).await;
            let mut inner = self.lifecycle.lock().await;
            if inner.is_active(&attempt) {
                inner.active = None;
            }
        }
        guard.finished = true;
        attempt
            .outcome
            .send_replace(Some(AttemptOutcome::Finished(outcome)));
    }

    async fn close_attempt_link(&self, attempt: &Arc<Attempt>) {
        let (connection, observer) = {
            let _inner = self.lifecycle.lock().await;
            attempt.bump_epoch();
            self.end_settings(attempt);
            let mut link = attempt.link.lock().expect("link lock");
            (link.connection.take(), link.observer.take())
        };
        if let Some((cancel, _)) = &observer {
            cancel.cancel();
        }
        if let Some(connection) = connection {
            self.connections.close(&connection).await;
        }
        if let Some((_, handle)) = observer {
            let _ = handle.await;
        }
    }

    async fn open_and_read(self: &Arc<Self>, attempt: &Arc<Attempt>) -> Result<(), Interrupt> {
        let epoch = self.begin_link(attempt).await?;
        let selection = self.select_transport(attempt, epoch).await?;
        let Err(failure) = self.open_and_read_link(attempt, epoch, &selection).await else {
            return Ok(());
        };
        match recoverable_reason(&failure) {
            Some(reason) if self.can_retry(attempt, &selection, reason) => {
                self.prepare_recovery(attempt, &selection, epoch, reason)
                    .await?;
                let epoch = self.begin_link(attempt).await?;
                self.open_and_read_link(attempt, epoch, &selection).await
            }
            _ => Err(failure),
        }
    }

    async fn open_and_read_link(
        self: &Arc<Self>,
        attempt: &Arc<Attempt>,
        epoch: u64,
        selection: &TransportSelection,
    ) -> Result<(), Interrupt> {
        let opened = self.open_link(attempt, epoch, selection).await?;
        {
            let inner = self.lifecycle.lock().await;
            if !inner.is_active(attempt) || attempt.epoch() != epoch {
                return Err(Interrupt::Cancelled);
            }
        }
        self.observe(attempt, Arc::clone(&opened), epoch);
        self.phase(attempt, epoch, ConnectionPhase::Subscribing);
        within(
            &attempt.cancel,
            self.timing.connect_timeout,
            opened.subscribe(),
        )
        .await?;
        self.throw_progress_failure(attempt)?;
        self.phase(attempt, epoch, ConnectionPhase::Initializing);
        self.read_initial_queries(attempt, epoch, &opened, selection)
            .await?;
        let live = self.state.read(|s| attempt.matches(s));
        if !live {
            return Err(Interrupt::Ended);
        }
        if !attempt.progress.borrow().meaningful {
            return Err(Interrupt::Control(ControlError::new(
                ControlErrorCode::Timeout,
                "没有收到可用的设备状态",
            )));
        }
        self.commit_ready(attempt, epoch).await?;
        self.start_settings(attempt);
        Ok(())
    }

    async fn open_link(
        self: &Arc<Self>,
        attempt: &Arc<Attempt>,
        epoch: u64,
        selection: &TransportSelection,
    ) -> Result<Arc<dyn ControlConnection>, Interrupt> {
        let open_cancel = attempt.cancel.child_token();
        let create = || async {
            let connection = self
                .open_endpoint(attempt, epoch, &selection.endpoint, &open_cancel)
                .await?;
            attempt.link.lock().expect("link lock").connection = Some(Arc::clone(&connection));
            Ok(connection)
        };
        let open = self.connections.open(create);
        tokio::pin!(open);
        let mut timed_out = false;
        let result = tokio::select! {
            result = &mut open => result,
            _ = tokio::time::sleep(self.timing.connect_timeout) => {
                timed_out = true;
                open_cancel.cancel();
                (&mut open).await
            }
        };
        match result {
            Ok(connection) => Ok(connection),
            Err(failure) => Err(match failure.kind {
                OpenFailureKind::Cancelled if timed_out => Interrupt::Timeout,
                OpenFailureKind::Cancelled => Interrupt::Cancelled,
                OpenFailureKind::Transport(error) => Interrupt::Transport(error),
            }),
        }
    }

    fn throw_progress_failure(&self, attempt: &Attempt) -> Result<(), Interrupt> {
        let progress = attempt.progress.borrow();
        if !progress.ended {
            return Ok(());
        }
        Err(match progress.failure.clone() {
            Some(error) => Interrupt::Transport(error),
            None => Interrupt::Ended,
        })
    }

    pub(crate) fn ensure_active(&self, attempt: &Attempt) -> Result<(), Interrupt> {
        let live = self.state.read(|s| attempt.matches(s));
        if live && !attempt.progress.borrow().ended {
            Ok(())
        } else {
            Err(Interrupt::Ended)
        }
    }

    pub(crate) async fn write_paced(
        &self,
        attempt: &Attempt,
        connection: &Arc<dyn ControlConnection>,
        bytes: &[u8],
        cancel: &CancellationToken,
    ) -> Result<ReadProgress, Interrupt> {
        if bytes.len() > connection.max_write_size() {
            return Err(Interrupt::Control(ControlError::new(
                ControlErrorCode::Failed,
                "完整协议报文超出传输写入容量",
            )));
        }
        let interval = self
            .timing
            .write_interval
            .unwrap_or_else(|| Duration::from_millis(attempt.profile.write_interval_millis()));
        let wait = {
            let link = attempt.link.lock().expect("link lock");
            link.has_written.then_some(link.next_write_at)
        };
        if let Some(next_write_at) = wait {
            guarded(cancel, async {
                match next_write_at {
                    Some(at) => tokio::time::sleep_until(at).await,
                    None => tokio::time::sleep(interval).await,
                }
            })
            .await?;
        }
        self.ensure_active(attempt)?;
        let baseline = attempt.progress.borrow().clone();
        {
            let mut link = attempt.link.lock().expect("link lock");
            link.has_written = true;
            link.next_write_at = None;
        }
        within(cancel, self.timing.write_timeout, connection.write(bytes)).await?;
        let early_noise_slot = attempt.profile == ModelProfile::I5Zt
            && self
                .state
                .read(|s| s.platform.host_platform.is_desktop() && s.headset_has_system_audio());
        if early_noise_slot {
            attempt.link.lock().expect("link lock").next_write_at = Some(Instant::now() + interval);
        }
        Ok(baseline)
    }

    async fn commit_ready(&self, attempt: &Arc<Attempt>, epoch: u64) -> Result<(), Interrupt> {
        let inner = self.lifecycle.lock().await;
        let live = inner.is_active(attempt)
            && attempt.epoch() == epoch
            && self.state.read(|s| attempt.matches(s));
        let ended = attempt.progress.borrow().ended;
        if !live || ended {
            return Err(match attempt.progress.borrow().failure.clone() {
                Some(error) => Interrupt::Transport(error),
                None => Interrupt::Ended,
            });
        }
        self.state.update(|s| {
            s.connection = ConnectionState::Connected {
                headset_id: attempt.candidate.id.clone(),
                session_id: attempt.id,
                profile: attempt.profile,
            };
        });
        let ready = self.state.read(|s| {
            matches!(&s.connection, ConnectionState::Connected { session_id, .. } if *session_id == attempt.id)
        });
        attempt.link.lock().expect("link lock").ready = ready;
        drop(inner);
        if ready { Ok(()) } else { Err(Interrupt::Ended) }
    }

    fn observe(
        self: &Arc<Self>,
        attempt: &Arc<Attempt>,
        connection: Arc<dyn ControlConnection>,
        epoch: u64,
    ) {
        let cancel = attempt.cancel.child_token();
        let handle = tokio::spawn(Arc::clone(self).run_observer(
            Arc::clone(attempt),
            connection,
            epoch,
            cancel.clone(),
        ));
        attempt.link.lock().expect("link lock").observer = Some((cancel, handle));
    }

    async fn run_observer(
        self: Arc<Self>,
        attempt: Arc<Attempt>,
        connection: Arc<dyn ControlConnection>,
        epoch: u64,
        cancel: CancellationToken,
    ) {
        let mut failure: Option<TransportError> = None;
        loop {
            let next = tokio::select! {
                biased;
                _ = cancel.cancelled() => break,
                next = connection.next_notification() => next,
            };
            match next {
                Ok(bytes) => {
                    let inner = self.lifecycle.lock().await;
                    if inner.is_active(&attempt) && attempt.epoch() == epoch {
                        self.receive_notification(&attempt, &bytes);
                    }
                }
                Err(error) => {
                    failure = Some(error);
                    break;
                }
            }
        }
        let error = match &failure {
            Some(
                TransportError::Unsupported(message) | TransportError::InvalidArgument(message),
            ) => ControlError::new(ControlErrorCode::Unsupported, message.clone()),
            Some(TransportError::PermissionDenied(message)) => {
                ControlError::new(ControlErrorCode::PermissionRequired, message.clone())
            }
            Some(other) => ControlError::new(ControlErrorCode::Disconnected, other.message()),
            None => ControlError::new(ControlErrorCode::Disconnected, "设备已断开"),
        };
        {
            let mut inner = self.lifecycle.lock().await;
            attempt.progress.send_modify(|progress| {
                progress.ended = true;
                progress.failure = failure.clone();
            });
            let setup_recovery =
                matches!(failure, Some(TransportError::Recoverable { .. })) && !attempt.is_ready();
            if inner.is_active(&attempt) && attempt.epoch() == epoch && !setup_recovery {
                self.end_settings(&attempt);
                inner.active = None;
                self.update_attempt(&attempt, Some(epoch), |s| {
                    s.connection = ConnectionState::Disconnected;
                    s.headset = None;
                    s.last_error = Some(error);
                });
            }
        }
        self.connections.close(&connection).await;
    }

    fn receive_notification(&self, attempt: &Attempt, bytes: &[u8]) {
        let replies = attempt
            .link
            .lock()
            .expect("link lock")
            .codec
            .feed_notification(bytes);
        let mut control_replies = Vec::new();
        let mut applied = false;
        self.state.update(|snapshot| {
            if !attempt.matches(snapshot) {
                return;
            }
            let Some(headset) = snapshot.headset.as_mut() else {
                return;
            };
            applied = true;
            for reply in &replies {
                headset.readings = headset.readings.with_readings(&reply.readings);
                control_replies.push(restrict(
                    reply,
                    &headset.readings.capabilities.readable_fields,
                ));
            }
            let diagnostics = &mut headset.diagnostics;
            append_recent(
                &mut diagnostics.raw_notifications,
                [openrose_headset::encode_hex(bytes)],
            );
            append_recent(
                &mut diagnostics.unknown_notifications,
                control_replies
                    .iter()
                    .filter(|reply| !reply.accepted)
                    .map(|reply| reply.raw_hex.clone()),
            );
            append_recent(
                &mut diagnostics.warnings,
                replies.iter().flat_map(|reply| reply.warnings.clone()),
            );
        });
        if applied {
            attempt
                .progress
                .send_modify(|progress| progress.received(&control_replies));
        }
    }

    async fn read_initial_queries(
        &self,
        attempt: &Arc<Attempt>,
        epoch: u64,
        connection: &Arc<dyn ControlConnection>,
        selection: &TransportSelection,
    ) -> Result<(), Interrupt> {
        let now = self.clock.now();
        let queries = attempt
            .link
            .lock()
            .expect("link lock")
            .codec
            .init_queries(connection.max_write_size(), now)
            .map_err(|error| {
                Interrupt::Control(ControlError::new(
                    ControlErrorCode::Unsupported,
                    error.to_string(),
                ))
            })?;
        let mut index = 0;
        for query in &queries {
            self.run_initial_query(attempt, epoch, connection, selection, query, index)
                .await?;
            index += 1;
        }
        let firmware = self.state.read(|s| {
            s.headset
                .as_ref()
                .and_then(|h| h.readings.confirmed_firmware().cloned())
        });
        let firmware_queries = attempt
            .link
            .lock()
            .expect("link lock")
            .codec
            .firmware_init_queries(firmware.as_ref());
        for query in &firmware_queries {
            self.run_initial_query(attempt, epoch, connection, selection, query, index)
                .await?;
            index += 1;
        }
        Ok(())
    }

    async fn run_initial_query(
        &self,
        attempt: &Arc<Attempt>,
        epoch: u64,
        connection: &Arc<dyn ControlConnection>,
        selection: &TransportSelection,
        query: &ProtocolQuery,
        index: usize,
    ) -> Result<(), Interrupt> {
        attempt.link.lock().expect("link lock").query_index = Some(index);
        let recoverable = self.can_recover_first_query(selection, Some(index));
        self.throw_progress_failure(attempt)?;
        let next_write_at = attempt.link.lock().expect("link lock").next_write_at;
        if let Some(at) = next_write_at {
            guarded(&attempt.cancel, tokio::time::sleep_until(at)).await?;
        }
        self.run_next_during_initialization(attempt).await?;
        guarded(
            &attempt.cancel,
            tokio::time::sleep(Duration::from_millis(query.delay_before_millis)),
        )
        .await?;
        self.throw_progress_failure(attempt)?;
        match self
            .write_paced(attempt, connection, &query.bytes, &attempt.cancel)
            .await
        {
            Err(Interrupt::Timeout) if recoverable => {
                return Err(Interrupt::Transport(TransportError::Recoverable {
                    reason: TransportRecoveryReason::WriteFailed,
                    message: "首条初始化读取写入超时".to_string(),
                }));
            }
            written => written?,
        };
        let wait_for_meaningful = recoverable && !query.expected_fields.is_empty();
        let mut progress = attempt.progress.subscribe();
        let observed = guarded(
            &attempt.cancel,
            tokio::time::timeout(
                self.timing.query_timeout,
                progress.wait_for(|progress| {
                    progress.ended
                        || (query
                            .response_keys
                            .iter()
                            .all(|key| progress.keys.contains(key))
                            && (!wait_for_meaningful || progress.meaningful))
                }),
            ),
        )
        .await?;
        match observed {
            Ok(Ok(observed)) if observed.ended => Err(match observed.failure.clone() {
                Some(error) => Interrupt::Transport(error),
                None => Interrupt::Ended,
            }),
            Ok(Ok(_)) => Ok(()),
            Ok(Err(_)) => Err(Interrupt::Ended),
            Err(_) => {
                if recoverable && !attempt.progress.borrow().meaningful {
                    return Err(Interrupt::Transport(TransportError::Recoverable {
                        reason: TransportRecoveryReason::NoInitialResponse,
                        message: "首条初始化读取没有收到有效设备状态".to_string(),
                    }));
                }
                let timeout = ControlError::new(
                    ControlErrorCode::Timeout,
                    format!("{} 部分字段读取超时", query.key),
                );
                self.update_attempt(attempt, Some(epoch), |snapshot| {
                    if let Some(headset) = snapshot.headset.as_mut() {
                        for field in &query.expected_fields {
                            if headset.readings.fields.get(field) == Some(&FieldReading::Loading) {
                                headset
                                    .diagnostics
                                    .field_errors
                                    .insert(*field, timeout.clone());
                            }
                        }
                    }
                    snapshot.last_error = Some(timeout.clone());
                });
                Ok(())
            }
        }
    }
}
