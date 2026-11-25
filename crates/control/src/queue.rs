use crate::confirmation::SettingConfirmation;
use crate::core::{Attempt, Core, Interrupt, platform_error};
use crate::error::{ControlError, ControlErrorCode, ControlResult};
use crate::link::guarded;
use crate::snapshot::{SettingOperation, SettingStage};
use crate::transport::{ControlConnection, TransportError};
use openrose_headset::{
    HeadsetCommand, HeadsetField, ProtocolError, ProtocolSetting, ValidationError,
    ValidationErrorKind,
};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::{Arc, Mutex};
use tokio::sync::{mpsc, oneshot};
use tokio_util::sync::CancellationToken;

const QUEUE_CAPACITY: usize = 32;

pub(crate) struct Request {
    command: HeadsetCommand,
    operation_id: u64,
    result: Mutex<Option<oneshot::Sender<ControlResult>>>,
}

impl Request {
    fn complete(&self, result: ControlResult) {
        if let Some(sender) = self.result.lock().expect("result lock").take() {
            let _ = sender.send(result);
        }
    }

    fn is_completed(&self) -> bool {
        self.result.lock().expect("result lock").is_none()
    }
}

pub(crate) struct Pending {
    sender: Mutex<Option<mpsc::Sender<Arc<Request>>>>,
    receiver: tokio::sync::Mutex<mpsc::Receiver<Arc<Request>>>,
    requests: Mutex<BTreeMap<u64, Arc<Request>>>,
    pub(crate) cancel: CancellationToken,
}

impl Pending {
    pub(crate) fn new(cancel: CancellationToken) -> Self {
        let (sender, receiver) = mpsc::channel(QUEUE_CAPACITY);
        Self {
            sender: Mutex::new(Some(sender)),
            receiver: tokio::sync::Mutex::new(receiver),
            requests: Mutex::new(BTreeMap::new()),
            cancel,
        }
    }
}

fn validation_error(error: ValidationError) -> ControlError {
    let code = match error.kind {
        ValidationErrorKind::NotReady => ControlErrorCode::NotReady,
        ValidationErrorKind::Unsupported => ControlErrorCode::Unsupported,
        ValidationErrorKind::InvalidArgument => ControlErrorCode::InvalidArgument,
    };
    ControlError::new(code, error.message)
}

fn protocol_failure(error: ProtocolError) -> Interrupt {
    let code = match error {
        ProtocolError::InvalidArgument(_) => ControlErrorCode::InvalidArgument,
        ProtocolError::Unsupported(_) => ControlErrorCode::Unsupported,
    };
    Interrupt::Control(ControlError::new(code, error.to_string()))
}

impl Core {
    pub(crate) async fn set(
        self: &Arc<Self>,
        command: HeadsetCommand,
        expected_session: Option<u64>,
    ) -> ControlResult {
        let receiver = {
            let inner = self.lifecycle.lock().await;
            let attempt = inner.active.clone().filter(|attempt| {
                attempt.connection().is_some() && !attempt.progress.borrow().ended
            });
            let snapshot = self.state.snapshot();
            let session = snapshot.session_for(command.setting());
            if let Some(expected) = expected_session {
                let current = attempt
                    .as_ref()
                    .is_some_and(|attempt| attempt.id == expected)
                    && session
                        .as_ref()
                        .is_some_and(|session| session.session_id == expected);
                if !current {
                    return Err(ControlError::new(
                        ControlErrorCode::Disconnected,
                        "请求的设备会话已结束或已被替换",
                    ));
                }
            }
            if let Some(error) = platform_error(&snapshot) {
                return self.reject(error);
            }
            let (Some(attempt), Some(session)) = (attempt, session) else {
                return self.reject(ControlError::new(
                    ControlErrorCode::NotReady,
                    "设备尚未就绪",
                ));
            };
            if session.session_id != attempt.id {
                return self.reject(ControlError::new(
                    ControlErrorCode::NotReady,
                    "设备尚未就绪",
                ));
            }
            let headset = snapshot
                .headset
                .as_ref()
                .expect("a session implies a headset");
            if let Err(error) = headset.readings.validate(&command) {
                return self.reject(validation_error(error));
            }
            let mut requests = attempt.queue.requests.lock().expect("requests lock");
            if requests.len() >= QUEUE_CAPACITY {
                drop(requests);
                return self.reject(ControlError::new(
                    ControlErrorCode::NotReady,
                    "设置队列已满",
                ));
            }
            let operation = SettingOperation {
                id: self
                    .operation_ids
                    .fetch_add(1, std::sync::atomic::Ordering::SeqCst)
                    + 1,
                session_id: attempt.id,
                setting: command.setting(),
                requested_value: command.value(),
                stage: SettingStage::Submitted,
                error: None,
                written_frames: 0,
                total_frames: 0,
                confirmed_fields: BTreeSet::new(),
            };
            let (result, receiver) = oneshot::channel();
            let request = Arc::new(Request {
                command,
                operation_id: operation.id,
                result: Mutex::new(Some(result)),
            });
            let sent = attempt
                .queue
                .sender
                .lock()
                .expect("sender lock")
                .as_ref()
                .is_some_and(|sender| sender.try_send(Arc::clone(&request)).is_ok());
            if !sent {
                drop(requests);
                return self.reject(ControlError::new(
                    ControlErrorCode::Disconnected,
                    "连接会话已结束",
                ));
            }
            requests.insert(operation.id, request);
            drop(requests);
            self.state.update(|s| {
                s.with_setting_operation(operation);
                s.last_error = None;
            });
            receiver
        };
        receiver.await.unwrap_or_else(|_| {
            Err(ControlError::new(
                ControlErrorCode::Disconnected,
                "连接会话已结束",
            ))
        })
    }

    pub(crate) fn reject(&self, error: ControlError) -> ControlResult {
        self.state.update(|s| s.last_error = Some(error.clone()));
        Err(error)
    }

    pub(crate) fn start_settings(self: &Arc<Self>, attempt: &Arc<Attempt>) {
        let core = Arc::clone(self);
        let attempt = Arc::clone(attempt);
        tokio::spawn(async move {
            let mut receiver = attempt.queue.receiver.lock().await;
            loop {
                let request = tokio::select! {
                    biased;
                    _ = attempt.queue.cancel.cancelled() => break,
                    request = receiver.recv() => request,
                };
                let Some(request) = request else { break };
                core.execute(&attempt, &request, &attempt.queue.cancel)
                    .await;
            }
        });
    }

    pub(crate) async fn run_next_during_initialization(
        &self,
        attempt: &Arc<Attempt>,
    ) -> Result<(), Interrupt> {
        let request = match attempt.queue.receiver.try_lock() {
            Ok(mut receiver) => receiver.try_recv().ok(),
            Err(_) => None,
        };
        if let Some(request) = request {
            self.execute(attempt, &request, &attempt.cancel).await;
        }
        Ok(())
    }

    async fn execute(
        &self,
        attempt: &Arc<Attempt>,
        request: &Arc<Request>,
        cancel: &CancellationToken,
    ) {
        if request.is_completed() {
            return;
        }
        let outcome = self.validate_and_apply(attempt, request, cancel).await;
        let result = match outcome {
            Ok(result) => result,
            Err(Interrupt::Cancelled) => return,
            Err(Interrupt::Timeout) => self.setting_failure(
                attempt,
                request,
                ControlError::new(ControlErrorCode::Timeout, "设置或设备回读超时"),
                SettingStage::TimedOut,
            ),
            Err(Interrupt::Ended) => self.setting_failure(
                attempt,
                request,
                ControlError::new(ControlErrorCode::Disconnected, "设备连接已结束"),
                SettingStage::Disconnected,
            ),
            Err(Interrupt::Control(error)) => {
                let stage = if error.code == ControlErrorCode::Disconnected {
                    SettingStage::Disconnected
                } else {
                    SettingStage::Failed
                };
                self.setting_failure(attempt, request, error, stage)
            }
            Err(Interrupt::Transport(error)) => {
                let code = match &error {
                    TransportError::Unsupported(_) => ControlErrorCode::Unsupported,
                    TransportError::InvalidArgument(_) => ControlErrorCode::InvalidArgument,
                    _ => ControlErrorCode::Failed,
                };
                self.setting_failure(
                    attempt,
                    request,
                    ControlError::new(code, error.message()),
                    SettingStage::Failed,
                )
            }
        };
        let mut inner = self.lifecycle.lock().await;
        attempt
            .queue
            .requests
            .lock()
            .expect("requests lock")
            .remove(&request.operation_id);
        request.complete(result.clone());
        if let Err(error) = &result
            && inner.is_active(attempt)
            && matches!(
                error.code,
                ControlErrorCode::Timeout | ControlErrorCode::Failed
            )
        {
            self.clear_active(&mut inner).await;
            let error = error.clone();
            self.state.update(|s| s.last_error = Some(error));
        }
    }

    async fn validate_and_apply(
        &self,
        attempt: &Arc<Attempt>,
        request: &Arc<Request>,
        cancel: &CancellationToken,
    ) -> Result<ControlResult, Interrupt> {
        let snapshot = self.state.snapshot();
        let Some(headset) = snapshot
            .headset
            .as_ref()
            .filter(|headset| headset.session_id == attempt.id)
        else {
            return Err(Interrupt::Ended);
        };
        let session_matches = snapshot
            .session_for(request.command.setting())
            .is_some_and(|session| session.session_id == attempt.id);
        let rejection = if session_matches {
            headset
                .readings
                .validate(&request.command)
                .err()
                .map(validation_error)
        } else {
            Some(ControlError::new(
                ControlErrorCode::NotReady,
                "设备尚未就绪",
            ))
        };
        match rejection {
            None => self.apply_setting(attempt, request, snapshot, cancel).await,
            Some(error) => Ok(self.setting_failure(attempt, request, error, SettingStage::Failed)),
        }
    }

    async fn apply_setting(
        &self,
        attempt: &Arc<Attempt>,
        request: &Arc<Request>,
        snapshot: crate::snapshot::ControlSnapshot,
        cancel: &CancellationToken,
    ) -> Result<ControlResult, Interrupt> {
        let connection = attempt.connection().ok_or(Interrupt::Ended)?;
        let headset = snapshot.headset.as_ref().ok_or(Interrupt::Ended)?;
        let firmware = headset.readings.confirmed_firmware().cloned();
        let prepared = attempt
            .link
            .lock()
            .expect("link lock")
            .codec
            .prepare_setting(
                request.command.setting(),
                &request.command.value(),
                firmware.as_ref(),
                &headset.readings.fields,
            )
            .map_err(protocol_failure)?;
        self.setting_stage(
            attempt,
            request,
            SettingStage::Submitted,
            None,
            None,
            Some(prepared.write_frames.len() as u32),
            None,
        );
        let oversized = prepared
            .write_frames
            .iter()
            .chain(prepared.readbacks().into_iter().map(|query| &query.bytes))
            .any(|frame| frame.len() > connection.max_write_size());
        if oversized {
            return Err(Interrupt::Control(ControlError::new(
                ControlErrorCode::Unsupported,
                "完整协议设置或回读超出传输写入容量",
            )));
        }
        let last = prepared.write_frames.len().saturating_sub(1);
        for (index, frame) in prepared.write_frames.iter().enumerate() {
            self.write_paced(attempt, &connection, frame, cancel)
                .await?;
            let stage = if index == last {
                SettingStage::Written
            } else {
                SettingStage::PartiallyWritten
            };
            self.setting_stage(
                attempt,
                request,
                stage,
                None,
                Some(index as u32 + 1),
                None,
                None,
            );
        }
        self.confirm_readbacks(attempt, request, &connection, &prepared, cancel)
            .await
    }

    async fn confirm_readbacks(
        &self,
        attempt: &Arc<Attempt>,
        request: &Arc<Request>,
        connection: &Arc<dyn ControlConnection>,
        prepared: &ProtocolSetting,
        cancel: &CancellationToken,
    ) -> Result<ControlResult, Interrupt> {
        let mut confirmation = SettingConfirmation::new(prepared.expected_values.clone());
        for query in prepared.readbacks() {
            let baseline = self
                .write_paced(attempt, connection, &query.bytes, cancel)
                .await?;
            confirmation.begin_query(query, &baseline);
            let mut progress = attempt.progress.subscribe();
            let waiting = async {
                loop {
                    let current = progress.borrow_and_update().clone();
                    if current.ended {
                        return Some(current);
                    }
                    let fields = self.state.read(|s| {
                        s.headset
                            .as_ref()
                            .map(|headset| headset.readings.fields.clone())
                            .unwrap_or_default()
                    });
                    let finished = confirmation.observe(query, &baseline, &current, &fields);
                    self.setting_stage(
                        attempt,
                        request,
                        SettingStage::Written,
                        None,
                        None,
                        None,
                        Some(confirmation.confirmed_fields()),
                    );
                    if finished {
                        return None;
                    }
                    if progress.changed().await.is_err() {
                        return Some(current);
                    }
                }
            };
            let observed = guarded(
                cancel,
                tokio::time::timeout(self.timing.query_timeout, waiting),
            )
            .await?;
            match observed {
                Ok(Some(_)) => return Err(Interrupt::Ended),
                Ok(None) => {}
                Err(_) => {
                    self.ensure_active(attempt)?;
                    return Ok(self.setting_failure(
                        attempt,
                        request,
                        ControlError::new(ControlErrorCode::Timeout, "未收到有效的设置回读"),
                        SettingStage::TimedOut,
                    ));
                }
            }
            self.ensure_active(attempt)?;
        }
        if confirmation.applied() {
            self.setting_stage(
                attempt,
                request,
                SettingStage::Confirmed,
                None,
                None,
                None,
                None,
            );
            Ok(Ok(()))
        } else {
            Ok(self.setting_failure(
                attempt,
                request,
                ControlError::new(ControlErrorCode::NotApplied, "设备回读与请求不一致"),
                SettingStage::NotApplied,
            ))
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn setting_stage(
        &self,
        attempt: &Attempt,
        request: &Request,
        stage: SettingStage,
        error: Option<ControlError>,
        written_frames: Option<u32>,
        total_frames: Option<u32>,
        confirmed_fields: Option<BTreeSet<HeadsetField>>,
    ) {
        self.state.update(|snapshot| {
            let current = snapshot
                .headset
                .as_ref()
                .is_some_and(|headset| headset.session_id == attempt.id);
            if !current || request.is_completed() {
                return;
            }
            if let Some(operation) = snapshot
                .setting_operations
                .iter_mut()
                .find(|operation| operation.id == request.operation_id)
            {
                operation.stage = stage;
                operation.error = error.clone();
                if let Some(written) = written_frames {
                    operation.written_frames = written;
                }
                if let Some(total) = total_frames {
                    operation.total_frames = total;
                }
                if let Some(confirmed) = confirmed_fields {
                    operation.confirmed_fields = confirmed;
                }
            }
            snapshot.last_error = error;
        });
    }

    fn setting_failure(
        &self,
        attempt: &Attempt,
        request: &Request,
        error: ControlError,
        stage: SettingStage,
    ) -> ControlResult {
        self.setting_stage(
            attempt,
            request,
            stage,
            Some(error.clone()),
            None,
            None,
            None,
        );
        Err(error)
    }

    pub(crate) fn end_settings(&self, attempt: &Attempt) {
        let error = ControlError::new(ControlErrorCode::Disconnected, "连接会话已结束");
        let requests: Vec<Arc<Request>> =
            std::mem::take(&mut *attempt.queue.requests.lock().expect("requests lock"))
                .into_values()
                .collect();
        let ids: BTreeSet<u64> = requests
            .iter()
            .map(|request| request.operation_id)
            .collect();
        self.state.update(|snapshot| {
            for operation in &mut snapshot.setting_operations {
                if ids.contains(&operation.id) {
                    operation.stage = SettingStage::Disconnected;
                    operation.error = Some(error.clone());
                }
            }
        });
        for request in requests {
            request.complete(Err(error.clone()));
        }
        attempt.queue.sender.lock().expect("sender lock").take();
        attempt.queue.cancel.cancel();
    }
}
