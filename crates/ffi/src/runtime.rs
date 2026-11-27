use crate::ControlFailure;
use crate::drivers::{
    ClockAdapter, ControlObserver, ControlTransport, LocalClock, ObserverAdapter, TransportAdapter,
};
use openrose_control::settings::ControlSettings;
use openrose_control::snapshot::{ControlSnapshot, PlatformState};
use openrose_control::{ControlConfig, ControlRuntime as CoreControlRuntime, SessionTiming};
use openrose_headset::{HeadsetCommand, HeadsetId};
use std::sync::{Arc, LazyLock};
use tokio::runtime::{Builder, Runtime};

static SHARED_RUNTIME: LazyLock<Runtime> = LazyLock::new(|| {
    Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("openrose-core")
        .enable_all()
        .build()
        .expect("openrose tokio runtime")
});

fn with_runtime<T>(work: impl FnOnce() -> T) -> T {
    if tokio::runtime::Handle::try_current().is_ok() {
        work()
    } else {
        let _guard = SHARED_RUNTIME.enter();
        work()
    }
}

#[derive(uniffi::Object)]
pub struct ControlRuntime {
    inner: CoreControlRuntime,
}

#[uniffi::export]
impl ControlRuntime {
    #[uniffi::constructor]
    pub fn new(
        transport: Arc<dyn ControlTransport>,
        observer: Arc<dyn ControlObserver>,
        clock: Arc<dyn LocalClock>,
        platform: PlatformState,
        settings: ControlSettings,
        timing: SessionTiming,
        session_id_base: u64,
    ) -> Arc<Self> {
        let config = ControlConfig {
            platform,
            settings,
            clock: Arc::new(ClockAdapter(clock)),
            timing,
            session_id_base,
        };
        let inner = with_runtime(|| {
            CoreControlRuntime::new(
                Arc::new(TransportAdapter(transport)),
                Arc::new(ObserverAdapter(observer)),
                config,
            )
        });
        Arc::new(Self { inner })
    }

    pub fn snapshot(&self) -> ControlSnapshot {
        self.inner.snapshot()
    }

    pub fn update_platform_state(&self, platform: PlatformState) {
        with_runtime(|| self.inner.update_platform_state(platform));
    }

    pub fn select_current_headset(&self, headset: HeadsetId) {
        self.inner.select_current_headset(headset);
    }

    pub fn update_settings(&self, settings: ControlSettings) {
        self.inner.update_settings(settings);
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl ControlRuntime {
    pub async fn refresh_bonded_headsets(&self) {
        self.inner.refresh_bonded_headsets().await;
    }

    pub async fn start_scan(&self) -> Result<(), ControlFailure> {
        self.inner.start_scan().await.map_err(ControlFailure::from)
    }

    pub async fn stop_scan(&self) {
        self.inner.stop_scan().await;
    }

    pub async fn connect(&self, headset: HeadsetId) -> Result<(), ControlFailure> {
        self.inner
            .connect(headset)
            .await
            .map_err(ControlFailure::from)
    }

    pub async fn connect_pinned(&self, headset: HeadsetId) -> Result<(), ControlFailure> {
        self.inner
            .connect_pinned(headset)
            .await
            .map_err(ControlFailure::from)
    }

    pub async fn connect_for_add_headset(&self, headset: HeadsetId) -> Result<(), ControlFailure> {
        self.inner
            .connect_for_add_headset(headset)
            .await
            .map_err(ControlFailure::from)
    }

    pub async fn cancel_add_headset(&self) {
        self.inner.cancel_add_headset().await;
    }

    pub async fn disconnect(&self) -> bool {
        self.inner.disconnect().await
    }

    pub async fn release(&self) -> bool {
        self.inner.release().await
    }

    pub async fn set(
        &self,
        command: HeadsetCommand,
        expected_session: Option<u64>,
    ) -> Result<(), ControlFailure> {
        self.inner
            .set(command, expected_session)
            .await
            .map_err(ControlFailure::from)
    }
}
