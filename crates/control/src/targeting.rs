use crate::core::{Attempt, Core, platform_error};
use crate::error::{ControlError, ControlErrorCode, ControlResult};
use crate::link::AttemptRequest;
use crate::snapshot::{ConnectionState, HeadsetCandidate, SessionReference};
use openrose_headset::HeadsetId;
use std::sync::{Arc, Mutex};
use tokio_util::sync::CancellationToken;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Origin {
    Manual,
    Audio,
    AddHeadset,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Pin {
    Force,
    Follow,
}

#[derive(Default)]
struct Intent {
    epoch: u64,
    headset: Option<HeadsetId>,
    pinned: Option<HeadsetId>,
    add_cancel: CancellationToken,
}

#[derive(Default)]
pub(crate) struct Targeting {
    intent: Mutex<Intent>,
}

impl Targeting {
    fn intent(&self) -> std::sync::MutexGuard<'_, Intent> {
        self.intent.lock().expect("target lock")
    }

    fn intend(&self, headset: &HeadsetId) -> u64 {
        let mut intent = self.intent();
        if intent.headset.as_ref() != Some(headset) {
            intent.epoch += 1;
            intent.headset = Some(headset.clone());
        }
        intent.epoch
    }

    fn begin(
        &self,
        headset: &HeadsetId,
        origin: Origin,
        pin: Pin,
    ) -> (u64, Option<CancellationToken>) {
        let mut intent = self.intent();
        if intent.headset.as_ref() != Some(headset) {
            intent.epoch += 1;
            intent.headset = Some(headset.clone());
        }
        if pin == Pin::Force || intent.pinned.is_some() {
            intent.pinned = Some(headset.clone());
        }
        let cancel = (origin == Origin::AddHeadset).then(|| intent.add_cancel.clone());
        (intent.epoch, cancel)
    }

    fn cancel_add(&self) {
        let mut intent = self.intent();
        let previous = std::mem::replace(&mut intent.add_cancel, CancellationToken::new());
        previous.cancel();
    }

    pub(crate) fn reset(&self) {
        let mut intent = self.intent();
        intent.epoch += 1;
        intent.headset = None;
    }

    fn has_pin(&self) -> bool {
        self.intent().pinned.is_some()
    }

    fn superseded(&self, epoch: u64, audio_driven: bool, headset: Option<&HeadsetId>) -> bool {
        let intent = self.intent();
        if epoch != intent.epoch {
            return true;
        }
        if audio_driven && intent.pinned.is_some() {
            return true;
        }
        match (headset, &intent.pinned) {
            (Some(headset), Some(pinned)) => pinned != headset,
            _ => false,
        }
    }
}

pub(crate) struct Request {
    epoch: u64,
    headset: HeadsetId,
    origin: Origin,
    cancel: Option<CancellationToken>,
}

impl Request {
    fn is_cancelled(&self) -> bool {
        self.cancel
            .as_ref()
            .is_some_and(CancellationToken::is_cancelled)
    }
}

pub(crate) fn superseded_error() -> ControlError {
    ControlError::new(ControlErrorCode::Disconnected, "连接请求已被替换")
}

impl Core {
    pub(crate) fn begin_explicit(&self, headset: &HeadsetId, origin: Origin, pin: Pin) -> Request {
        let (epoch, cancel) = self.targeting.begin(headset, origin, pin);
        let target = self
            .state
            .read(|snapshot| snapshot.known_candidate(headset))
            .unwrap_or_else(|| HeadsetCandidate::unidentified(headset.clone()));
        self.state
            .update(|snapshot| snapshot.connection_target = Some(target));
        Request {
            epoch,
            headset: headset.clone(),
            origin,
            cancel,
        }
    }

    fn superseded(&self, request: &Request, headset: Option<&HeadsetId>) -> bool {
        if request.is_cancelled() {
            return true;
        }
        let audio_driven = request.origin == Origin::Audio;
        self.targeting
            .superseded(request.epoch, audio_driven, headset)
            || (audio_driven
                && self
                    .state
                    .read(|snapshot| snapshot.audio_headset_id.as_ref() != Some(&request.headset)))
    }

    async fn guarded_add<T>(
        &self,
        request: &Request,
        operation: impl Future<Output = T>,
    ) -> Result<T, ControlError> {
        match &request.cancel {
            Some(cancel) => tokio::select! {
                biased;
                _ = cancel.cancelled() => Err(superseded_error()),
                value = operation => Ok(value),
            },
            None => Ok(operation.await),
        }
    }

    pub(crate) async fn connect_requested(self: &Arc<Self>, request: Request) -> ControlResult {
        if self.superseded(&request, None) {
            return Err(superseded_error());
        }
        if let Some(error) = self.state.read(platform_error) {
            return self.reject(error);
        }
        let candidate = self
            .guarded_add(&request, self.find_candidate(&request.headset))
            .await?;
        if self.superseded(&request, None) {
            return Err(superseded_error());
        }
        let adopt = self.state.read(|snapshot| {
            snapshot
                .connection_target
                .as_ref()
                .is_some_and(|target| target.id == request.headset)
        }) || (request.origin == Origin::Audio && !self.targeting.has_pin());
        if let Some(candidate) = candidate.clone().filter(|_| adopt) {
            self.state
                .update(|snapshot| snapshot.connection_target = Some(candidate));
        }
        let Some((candidate, profile)) =
            candidate.and_then(|candidate| candidate.profile.map(|profile| (candidate, profile)))
        else {
            return self.reject(ControlError::new(
                ControlErrorCode::Unsupported,
                "没有受支持的型号识别证据",
            ));
        };
        if request.cancel.is_some() {
            let core = Arc::clone(self);
            let stop = tokio::spawn(async move { core.stop_scan().await });
            self.guarded_add(&request, async move {
                let _ = stop.await;
            })
            .await?;
        } else {
            self.stop_scan().await;
        }
        if self.superseded(&request, None) {
            return Err(superseded_error());
        }
        let id = candidate.id.clone();
        let attempt = self
            .begin_attempt(AttemptRequest {
                candidate,
                profile,
                origin: request.origin,
                superseded: &|| self.superseded(&request, Some(&id)),
            })
            .await?;
        self.finish_connect(&attempt, request.origin).await
    }

    async fn finish_connect(&self, attempt: &Arc<Attempt>, origin: Origin) -> ControlResult {
        let outcome = attempt.wait().await;
        if origin != Origin::AddHeadset || outcome.is_err() {
            return outcome;
        }
        let inner = self.lifecycle.lock().await;
        let current = inner.is_active(attempt)
            && self.state.read(|snapshot| {
                snapshot.ready_session()
                    == Some(SessionReference {
                        headset_id: attempt.candidate.id.clone(),
                        session_id: attempt.id,
                    })
            });
        if current {
            Ok(())
        } else {
            Err(ControlError::new(
                ControlErrorCode::Disconnected,
                "本次连接已被替换",
            ))
        }
    }

    pub(crate) async fn cancel_add_headset(&self) {
        let mut inner = self.lifecycle.lock().await;
        self.targeting.cancel_add();
        let owned = inner
            .active
            .as_ref()
            .is_some_and(|active| active.origin == Origin::AddHeadset && !active.is_ready());
        if !owned {
            return;
        }
        self.clear_active(&mut inner).await;
        self.state.update(|snapshot| {
            snapshot.last_error = None;
            snapshot.connection_recovery = None;
        });
    }

    pub(crate) async fn disconnect_target(self: &Arc<Self>) -> bool {
        self.targeting.reset();
        self.disconnect(false).await
    }

    pub(crate) async fn audio_changed(self: &Arc<Self>, headset: Option<HeadsetId>) {
        self.refresh_bonded_headsets().await;
        if self.state.read(|snapshot| snapshot.platform.user_unlocked) {
            self.handle_audio(headset).await;
        }
    }

    pub(crate) async fn handle_current_audio(self: &Arc<Self>) {
        let audio = self
            .state
            .read(|snapshot| snapshot.audio_headset_id.clone());
        if audio.is_some() {
            self.handle_audio(audio).await;
        }
    }

    async fn handle_audio(self: &Arc<Self>, audio: Option<HeadsetId>) {
        let Some(audio) = audio else {
            self.end_audio_session(None).await;
            return;
        };
        let engaged = self.state.read(|snapshot| {
            !matches!(snapshot.connection, ConnectionState::Disconnected)
                && snapshot.headset_has_system_audio()
        });
        if engaged {
            if let Some(active) = &self.lifecycle.lock().await.active {
                active.bind_audio();
            }
            return;
        }
        if self.targeting.has_pin() {
            return;
        }
        let lookup = Request {
            epoch: self.targeting.intend(&audio),
            headset: audio.clone(),
            origin: Origin::Audio,
            cancel: None,
        };
        let candidate = self.find_candidate(&audio).await;
        if self.superseded(&lookup, None)
            || candidate.is_none_or(|candidate| candidate.profile.is_none())
        {
            return;
        }
        self.end_audio_session(Some(&audio)).await;
        let idle = self
            .state
            .read(|snapshot| matches!(snapshot.connection, ConnectionState::Disconnected));
        if idle && !self.targeting.has_pin() {
            let request = Request {
                epoch: self.targeting.intend(&audio),
                headset: audio,
                origin: Origin::Audio,
                cancel: None,
            };
            let core = Arc::clone(self);
            tokio::spawn(async move { core.connect_requested(request).await });
        }
    }

    async fn end_audio_session(self: &Arc<Self>, keep: Option<&HeadsetId>) {
        let mut inner = self.lifecycle.lock().await;
        let bound = inner
            .active
            .as_ref()
            .is_some_and(|active| active.is_audio_bound() && !active.candidate.matches_audio(keep));
        if bound {
            self.targeting.reset();
            self.disconnect_locked(&mut inner, false).await;
        }
    }
}
