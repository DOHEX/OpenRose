use crate::core::{Core, LinkInner, LocalClock, OwnedConnections, SessionTiming, detached};
use crate::error::ControlResult;
use crate::settings::ControlSettings;
use crate::snapshot::{ControlSnapshot, PlatformState, TransportAvailability};
use crate::state::StateCell;
use crate::targeting::{Origin, Pin, Targeting};
use crate::transport::ControlTransport;
use openrose_headset::{HeadsetCommand, HeadsetId};
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use tokio::sync::watch;
use tokio_util::sync::CancellationToken;

pub trait ControlObserver: Send + Sync + 'static {
    fn state_changed(&self, snapshot: &ControlSnapshot);
}

pub struct ControlConfig {
    pub platform: PlatformState,
    pub settings: ControlSettings,
    pub clock: Arc<dyn LocalClock>,
    pub timing: SessionTiming,
    pub session_id_base: u64,
}

impl ControlConfig {
    pub fn new(platform: PlatformState, clock: Arc<dyn LocalClock>) -> Self {
        Self {
            platform,
            settings: ControlSettings::default(),
            clock,
            timing: SessionTiming::default(),
            session_id_base: 0,
        }
    }
}

pub struct ControlRuntime {
    core: Arc<Core>,
}

impl ControlRuntime {
    pub fn new(
        transport: Arc<dyn ControlTransport>,
        observer: Arc<dyn ControlObserver>,
        config: ControlConfig,
    ) -> Self {
        let audio_readable = !config.platform.permission.missing();
        let (state, mut published) =
            StateCell::new(ControlSnapshot::initial(config.platform, config.settings));
        let core = Arc::new(Core {
            state,
            audio: transport.audio_facts(),
            audio_readable: watch::channel(audio_readable).0,
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
            scan: Default::default(),
            targeting: Targeting::default(),
        });
        tokio::spawn(async move {
            while let Some(snapshot) = published.recv().await {
                observer.state_changed(&snapshot);
            }
        });
        tokio::spawn(Arc::clone(&core).observe_audio_facts());
        Self { core }
    }

    pub fn snapshot(&self) -> ControlSnapshot {
        self.core.state.snapshot()
    }

    pub fn update_platform_state(&self, platform: PlatformState) {
        let readable = !platform.permission.missing();
        let unlocked = platform.user_unlocked;
        let transport_available = platform.transport == TransportAvailability::Available;
        let mut previous = None;
        self.core.state.update(|snapshot| {
            previous = Some(std::mem::replace(&mut snapshot.platform, platform));
        });
        let previous = previous.expect("the platform update ran");
        self.core.audio_readable.send_replace(readable);
        let unlocking = !previous.user_unlocked && unlocked;
        let transport_ready =
            previous.transport != TransportAvailability::Available && transport_available;
        if !previous.permission.missing() && readable && unlocked && (unlocking || transport_ready)
        {
            let core = Arc::clone(&self.core);
            tokio::spawn(async move { core.handle_current_audio().await });
        }
    }

    pub fn select_current_headset(&self, headset: HeadsetId) {
        self.core
            .state
            .update(|snapshot| snapshot.select_current_headset(&headset));
    }

    pub fn update_settings(&self, settings: ControlSettings) {
        self.core
            .state
            .update(|snapshot| snapshot.settings = settings);
    }

    pub async fn refresh_bonded_headsets(&self) {
        let core = Arc::clone(&self.core);
        detached(async move { core.refresh_bonded_headsets().await }).await
    }

    pub async fn start_scan(&self) -> ControlResult {
        let core = Arc::clone(&self.core);
        detached(async move { core.start_scan().await }).await
    }

    pub async fn stop_scan(&self) {
        let core = Arc::clone(&self.core);
        detached(async move { core.stop_scan().await }).await
    }

    pub async fn connect(&self, headset: HeadsetId) -> ControlResult {
        self.connect_as(headset, Origin::Manual, Pin::Follow).await
    }

    pub async fn connect_pinned(&self, headset: HeadsetId) -> ControlResult {
        self.connect_as(headset, Origin::Manual, Pin::Force).await
    }

    pub async fn connect_for_add_headset(&self, headset: HeadsetId) -> ControlResult {
        self.connect_as(headset, Origin::AddHeadset, Pin::Follow)
            .await
    }

    async fn connect_as(&self, headset: HeadsetId, origin: Origin, pin: Pin) -> ControlResult {
        let request = self.core.begin_explicit(&headset, origin, pin);
        let core = Arc::clone(&self.core);
        let attempt = detached(async move { core.start_requested(request).await }).await?;
        self.core.finish_connect(&attempt, origin).await
    }

    pub async fn cancel_add_headset(&self) {
        let core = Arc::clone(&self.core);
        detached(async move { core.cancel_add_headset().await }).await
    }

    pub async fn disconnect(&self) -> bool {
        let core = Arc::clone(&self.core);
        detached(async move { core.disconnect_target().await }).await
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
