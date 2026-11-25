use crate::core::{Core, LinkInner, LocalClock, OwnedConnections, SessionTiming, platform_error};
use crate::error::{ControlError, ControlErrorCode, ControlResult};
use crate::snapshot::{ControlSnapshot, PlatformState};
use crate::state::StateCell;
use crate::transport::ControlTransport;
use openrose_headset::{HeadsetCommand, HeadsetId};
use std::future::Future;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use tokio_util::sync::CancellationToken;

pub trait ControlObserver: Send + Sync + 'static {
    fn state_changed(&self, snapshot: &ControlSnapshot);
}

pub struct ControlConfig {
    pub platform: PlatformState,
    pub clock: Arc<dyn LocalClock>,
    pub timing: SessionTiming,
    pub session_id_base: u64,
}

impl ControlConfig {
    pub fn new(platform: PlatformState, clock: Arc<dyn LocalClock>) -> Self {
        Self {
            platform,
            clock,
            timing: SessionTiming::default(),
            session_id_base: 0,
        }
    }
}

pub struct ControlRuntime {
    core: Arc<Core>,
}

async fn detached<T: Send + 'static>(work: impl Future<Output = T> + Send + 'static) -> T {
    match tokio::spawn(work).await {
        Ok(value) => value,
        Err(error) => std::panic::resume_unwind(error.into_panic()),
    }
}

impl ControlRuntime {
    pub fn new(
        transport: Arc<dyn ControlTransport>,
        observer: Arc<dyn ControlObserver>,
        config: ControlConfig,
    ) -> Self {
        let (state, mut published) = StateCell::new(ControlSnapshot::initial(config.platform));
        let core = Arc::new(Core {
            state,
            transport,
            clock: config.clock,
            timing: config.timing,
            lifecycle: tokio::sync::Mutex::new(LinkInner {
                active: None,
                generation: config.session_id_base,
                released: false,
            }),
            connections: OwnedConnections::default(),
            operation_ids: AtomicU64::new(0),
            shutdown: CancellationToken::new(),
        });
        tokio::spawn(async move {
            while let Some(snapshot) = published.recv().await {
                observer.state_changed(&snapshot);
            }
        });
        tokio::spawn(Arc::clone(&core).observe_audio_connection());
        Self { core }
    }

    pub fn snapshot(&self) -> ControlSnapshot {
        self.core.state.snapshot()
    }

    pub fn update_platform_state(&self, platform: PlatformState) {
        self.core.state.update(|snapshot| snapshot.platform = platform);
    }

    pub async fn refresh_bonded_headsets(&self) {
        let core = Arc::clone(&self.core);
        detached(async move { core.refresh_bonded_headsets().await }).await
    }

    pub async fn connect(&self, headset: HeadsetId) -> ControlResult {
        let core = Arc::clone(&self.core);
        detached(async move { core.connect_headset(headset).await }).await
    }

    pub async fn disconnect(&self) -> bool {
        let core = Arc::clone(&self.core);
        detached(async move { core.disconnect(false).await }).await
    }

    pub async fn release(&self) -> bool {
        let core = Arc::clone(&self.core);
        detached(async move { core.disconnect(true).await }).await
    }

    pub async fn set(
        &self,
        command: HeadsetCommand,
        expected_session: Option<u64>,
    ) -> ControlResult {
        self.core.set(command, expected_session).await
    }
}

impl Drop for ControlRuntime {
    fn drop(&mut self) {
        self.core.shutdown.cancel();
    }
}

impl Core {
    async fn refresh_bonded_headsets(&self) {
        let bonded = self.transport.bonded_headsets().await;
        self.state.update(|snapshot| snapshot.bonded_headsets = bonded);
    }

    async fn connect_headset(self: &Arc<Self>, id: HeadsetId) -> ControlResult {
        if let Some(error) = self.state.read(platform_error) {
            return self.reject(error);
        }
        let find = |core: &Core| {
            core.state
                .read(|s| s.bonded_headsets.iter().find(|c| c.id == id).cloned())
        };
        let mut candidate = find(self);
        if candidate.as_ref().and_then(|c| c.profile).is_none() {
            self.refresh_bonded_headsets().await;
            candidate = find(self);
        }
        match candidate.and_then(|candidate| candidate.profile.map(|profile| (candidate, profile))) {
            Some((candidate, profile)) => self.connect_candidate(candidate, profile).await,
            None => self.reject(ControlError::new(
                ControlErrorCode::Unsupported,
                "没有受支持的型号识别证据",
            )),
        }
    }

    async fn observe_audio_connection(self: Arc<Self>) {
        let mut audio = self.transport.audio_connected_headset();
        loop {
            let headset = audio.borrow_and_update().clone();
            self.state.update(|snapshot| snapshot.audio_headset_id = headset);
            tokio::select! {
                _ = self.shutdown.cancelled() => break,
                changed = audio.changed() => {
                    if changed.is_err() {
                        break;
                    }
                }
            }
        }
    }
}
