use async_trait::async_trait;
use openrose_control::audio::AudioFactsDriver;
use openrose_control::gatt::{
    GattCharacteristic, GattDriver, GattError, GattEvent, GattService, GattSubscription,
};
use openrose_control::scan::ScanDriver;
use openrose_control::simulator::SimulatedTransport;
use openrose_control::transport::{ControlTransport, TransportError};
use openrose_headset::HeadsetId;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use tokio::sync::{mpsc, watch};

const NOTIFY_CHARACTERISTIC: u64 = 2;

#[derive(Clone, Default)]
pub struct LinkFaults {
    pub fail_subscription: Option<GattError>,
    pub fail_write_at: Option<(usize, GattError)>,
    pub hang_write_at: Option<usize>,
    pub silent: bool,
    pub first_reply: Option<Vec<u8>>,
    pub event_on_first_write: Option<GattEvent>,
}

pub struct LinkLog {
    writes: Mutex<Vec<String>>,
    closed: AtomicBool,
    silent: AtomicBool,
    injector: mpsc::UnboundedSender<GattEvent>,
}

impl LinkLog {
    pub fn writes(&self) -> Vec<String> {
        self.writes.lock().unwrap().clone()
    }

    pub fn closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    pub fn answer_again(&self) {
        self.silent.store(false, Ordering::SeqCst);
    }

    pub fn raise(&self, event: GattEvent) {
        let _ = self.injector.send(event);
    }

    pub fn deliver(&self, bytes: Vec<u8>) {
        self.raise(GattEvent::Notification {
            instance_id: NOTIFY_CHARACTERISTIC,
            value: bytes,
        });
    }
}

pub struct FaultyTransport {
    pub sim: Arc<SimulatedTransport>,
    plan: Box<dyn Fn(usize) -> LinkFaults + Send + Sync>,
    links: Mutex<Vec<Arc<LinkLog>>>,
    opens: AtomicUsize,
    previous_links_closed_at_every_open: AtomicBool,
    hold: Mutex<Option<(usize, watch::Receiver<bool>)>>,
    recovery: bool,
}

impl FaultyTransport {
    pub fn new(
        sim: Arc<SimulatedTransport>,
        recovery: bool,
        plan: impl Fn(usize) -> LinkFaults + Send + Sync + 'static,
    ) -> Arc<Self> {
        Arc::new(Self {
            sim,
            plan: Box::new(plan),
            links: Mutex::new(Vec::new()),
            opens: AtomicUsize::new(0),
            previous_links_closed_at_every_open: AtomicBool::new(true),
            hold: Mutex::new(None),
            recovery,
        })
    }

    pub fn hold_open(&self, number: usize) -> watch::Sender<bool> {
        let (sender, receiver) = watch::channel(false);
        *self.hold.lock().unwrap() = Some((number, receiver));
        sender
    }

    pub fn links(&self) -> Vec<Arc<LinkLog>> {
        self.links.lock().unwrap().clone()
    }

    pub fn opens(&self) -> usize {
        self.opens.load(Ordering::SeqCst)
    }

    pub fn previous_links_closed_at_every_open(&self) -> bool {
        self.previous_links_closed_at_every_open
            .load(Ordering::SeqCst)
    }
}

#[async_trait]
impl ControlTransport for FaultyTransport {
    async fn open_gatt(&self, headset: &HeadsetId) -> Result<Box<dyn GattDriver>, TransportError> {
        let number = self.opens.fetch_add(1, Ordering::SeqCst) + 1;
        let connect_gate = {
            let mut hold = self.hold.lock().unwrap();
            match hold.as_ref() {
                Some((held, _)) if *held == number => hold.take().map(|(_, gate)| gate),
                _ => None,
            }
        };
        if self.links.lock().unwrap().iter().any(|link| !link.closed()) {
            self.previous_links_closed_at_every_open
                .store(false, Ordering::SeqCst);
        }
        let inner = self.sim.open_gatt(headset).await?;
        let faults = (self.plan)(number);
        let (injector, injected) = mpsc::unbounded_channel();
        let log = Arc::new(LinkLog {
            writes: Mutex::new(Vec::new()),
            closed: AtomicBool::new(false),
            silent: AtomicBool::new(faults.silent || faults.first_reply.is_some()),
            injector,
        });
        self.links.lock().unwrap().push(Arc::clone(&log));
        Ok(Box::new(FaultyGatt {
            inner,
            faults,
            log,
            connect_gate,
            injected: tokio::sync::Mutex::new(injected),
        }))
    }

    async fn start_scan(&self) -> Result<Box<dyn ScanDriver>, TransportError> {
        self.sim.start_scan().await
    }

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver> {
        self.sim.audio_facts()
    }

    fn supports_initialization_recovery(&self) -> bool {
        self.recovery
    }
}

struct FaultyGatt {
    inner: Box<dyn GattDriver>,
    faults: LinkFaults,
    log: Arc<LinkLog>,
    connect_gate: Option<watch::Receiver<bool>>,
    injected: tokio::sync::Mutex<mpsc::UnboundedReceiver<GattEvent>>,
}

#[async_trait]
impl GattDriver for FaultyGatt {
    async fn next_event(&self) -> Option<GattEvent> {
        let mut injected = self.injected.lock().await;
        tokio::select! {
            event = self.inner.next_event() => event,
            event = injected.recv() => event,
        }
    }

    async fn connect(&self) -> Result<(), GattError> {
        if let Some(gate) = &self.connect_gate {
            let _ = gate.clone().wait_for(|open| *open).await;
        }
        self.inner.connect().await
    }

    async fn discover_services(&self) -> Result<Vec<GattService>, GattError> {
        self.inner.discover_services().await
    }

    async fn request_mtu(&self, preferred: u16) -> Result<Option<u16>, GattError> {
        self.inner.request_mtu(preferred).await
    }

    async fn set_notification_routing(
        &self,
        characteristic: &GattCharacteristic,
        enabled: bool,
    ) -> Result<(), GattError> {
        self.inner
            .set_notification_routing(characteristic, enabled)
            .await
    }

    async fn configure_subscription(
        &self,
        characteristic: &GattCharacteristic,
        subscription: GattSubscription,
    ) -> Result<(), GattError> {
        if subscription != GattSubscription::Disabled
            && let Some(failure) = &self.faults.fail_subscription
        {
            return Err(failure.clone());
        }
        self.inner
            .configure_subscription(characteristic, subscription)
            .await
    }

    async fn write(
        &self,
        characteristic: &GattCharacteristic,
        value: &[u8],
        with_response: bool,
    ) -> Result<(), GattError> {
        let index = {
            let mut writes = self.log.writes.lock().unwrap();
            writes.push(openrose_headset::encode_hex(value));
            writes.len() - 1
        };
        if self.faults.hang_write_at == Some(index) {
            std::future::pending::<()>().await;
        }
        if let Some((at, failure)) = &self.faults.fail_write_at
            && *at == index
        {
            return Err(failure.clone());
        }
        if index == 0
            && let Some(event) = &self.faults.event_on_first_write
        {
            self.log.raise(event.clone());
            return Ok(());
        }
        if index == 0
            && let Some(reply) = &self.faults.first_reply
        {
            self.log.deliver(reply.clone());
            return Ok(());
        }
        if self.log.silent.load(Ordering::SeqCst) {
            return Ok(());
        }
        self.inner.write(characteristic, value, with_response).await
    }

    async fn close(&self) -> Result<(), GattError> {
        self.log.closed.store(true, Ordering::SeqCst);
        self.inner.close().await
    }
}
