use async_trait::async_trait;
use openrose_control::audio::{RawAudioDevice, RawAudioEvent, RawAudioSnapshot};
use openrose_control::gatt::{
    GattCharacteristic, GattEvent, GattFailureKind, GattService, GattSubscription,
};
use openrose_control::snapshot::ControlSnapshot;
use openrose_ffi::{
    AudioEvents, AudioFactsDriver, ControlObserver, ControlTransport, GattDriver, GattDriverError,
    ScanDriver, SppDriver, TransportDriverError,
};
use openrose_headset::simulator::SimulatedHeadset;
use openrose_headset::{FirmwareVersion, HeadsetId, ModelProfile, encode_hex};
use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use tokio::sync::{Mutex as AsyncMutex, mpsc};

const WRITER: u64 = 1;
const NOTIFIER: u64 = 2;

#[derive(Default)]
pub struct Recorder {
    snapshots: Mutex<Vec<ControlSnapshot>>,
}

impl Recorder {
    pub fn all(&self) -> Vec<ControlSnapshot> {
        self.snapshots.lock().unwrap().clone()
    }
}

impl ControlObserver for Recorder {
    fn state_changed(&self, snapshot: ControlSnapshot) {
        self.snapshots.lock().unwrap().push(snapshot);
    }
}

pub struct FakeHeadset {
    profile: ModelProfile,
    address: String,
    device: Mutex<SimulatedHeadset>,
    muted: AtomicBool,
    fail_connect: Mutex<Option<GattDriverError>>,
    panic_on_open: AtomicBool,
    events: mpsc::UnboundedSender<GattEvent>,
    inbox: AsyncMutex<mpsc::UnboundedReceiver<GattEvent>>,
    writes: Mutex<Vec<String>>,
    opens: AtomicUsize,
}

impl FakeHeadset {
    pub fn new(profile: ModelProfile, address: &str) -> Arc<Self> {
        let (events, inbox) = mpsc::unbounded_channel();
        Arc::new(Self {
            profile,
            address: address.to_string(),
            device: Mutex::new(
                SimulatedHeadset::new(profile, FirmwareVersion::new(vec![1, 2, 5], "125"))
                    .expect("simulated device"),
            ),
            muted: AtomicBool::new(false),
            fail_connect: Mutex::new(None),
            panic_on_open: AtomicBool::new(false),
            events,
            inbox: AsyncMutex::new(inbox),
            writes: Mutex::new(Vec::new()),
            opens: AtomicUsize::new(0),
        })
    }

    pub fn transport(self: &Arc<Self>) -> Arc<dyn ControlTransport> {
        Arc::new(FakeTransport(Arc::clone(self)))
    }

    pub fn mute(&self, muted: bool) {
        self.muted.store(muted, Ordering::SeqCst);
    }

    pub fn fail_connect_with(&self, error: Option<GattDriverError>) {
        *self.fail_connect.lock().unwrap() = error;
    }

    pub fn panic_on_open(&self, panic: bool) {
        self.panic_on_open.store(panic, Ordering::SeqCst);
    }

    pub fn inject(&self, hex: &str) {
        let value = openrose_headset::decode_hex(hex).expect("reply hex");
        self.events
            .send(GattEvent::Notification {
                instance_id: NOTIFIER,
                value,
            })
            .expect("link is open");
    }

    pub fn written(&self) -> Vec<String> {
        self.writes.lock().unwrap().clone()
    }

    pub fn opens(&self) -> usize {
        self.opens.load(Ordering::SeqCst)
    }

    fn service(&self) -> GattService {
        let gatt = self.profile.gatt();
        GattService {
            uuid: gatt.service_uuid,
            characteristics: vec![
                GattCharacteristic {
                    instance_id: WRITER,
                    uuid: gatt.write_uuid,
                    can_write: true,
                    can_write_without_response: true,
                    can_notify: false,
                    can_indicate: false,
                },
                GattCharacteristic {
                    instance_id: NOTIFIER,
                    uuid: gatt.notify_uuid,
                    can_write: false,
                    can_write_without_response: false,
                    can_notify: true,
                    can_indicate: false,
                },
            ],
        }
    }
}

struct FakeTransport(Arc<FakeHeadset>);

impl ControlTransport for FakeTransport {
    fn open_gatt(&self, _headset: HeadsetId) -> Result<Arc<dyn GattDriver>, TransportDriverError> {
        if self.0.panic_on_open.load(Ordering::SeqCst) {
            panic!("the platform driver crashed");
        }
        self.0.opens.fetch_add(1, Ordering::SeqCst);
        Ok(Arc::new(FakeGatt(Arc::clone(&self.0))))
    }

    fn start_scan(&self) -> Result<Arc<dyn ScanDriver>, TransportDriverError> {
        Err(TransportDriverError::Unsupported {
            detail: "no scanner".to_string(),
        })
    }

    fn open_spp(
        &self,
        _headset: HeadsetId,
        _address: String,
        _service_uuid: String,
    ) -> Result<Arc<dyn SppDriver>, TransportDriverError> {
        Err(TransportDriverError::Unsupported {
            detail: "no RFCOMM".to_string(),
        })
    }

    fn audio_facts(&self) -> Arc<dyn AudioFactsDriver> {
        Arc::new(FakeAudio(Arc::clone(&self.0)))
    }

    fn supports_initialization_recovery(&self) -> bool {
        false
    }
}

struct FakeGatt(Arc<FakeHeadset>);

#[async_trait]
impl GattDriver for FakeGatt {
    async fn next_event(&self) -> Option<GattEvent> {
        self.0.inbox.lock().await.recv().await
    }

    async fn connect(&self) -> Result<(), GattDriverError> {
        match self.0.fail_connect.lock().unwrap().take() {
            Some(error) => Err(error),
            None => Ok(()),
        }
    }

    async fn discover_services(&self) -> Result<Vec<GattService>, GattDriverError> {
        Ok(vec![self.0.service()])
    }

    async fn request_mtu(&self, _preferred: u16) -> Result<Option<u16>, GattDriverError> {
        Ok(Some(185))
    }

    async fn set_notification_routing(
        &self,
        _characteristic: GattCharacteristic,
        _enabled: bool,
    ) -> Result<(), GattDriverError> {
        Ok(())
    }

    async fn configure_subscription(
        &self,
        _characteristic: GattCharacteristic,
        _subscription: GattSubscription,
    ) -> Result<(), GattDriverError> {
        Ok(())
    }

    async fn write(
        &self,
        characteristic: GattCharacteristic,
        value: Vec<u8>,
        _with_response: bool,
    ) -> Result<(), GattDriverError> {
        if characteristic.instance_id != WRITER {
            return Err(GattDriverError::Failed {
                kind: GattFailureKind::InvalidValue,
                detail: "not the writer".to_string(),
            });
        }
        self.0.writes.lock().unwrap().push(encode_hex(&value));
        if !self.0.muted.load(Ordering::SeqCst) {
            let frames = self.0.device.lock().unwrap().receive(&value);
            for frame in frames {
                let _ = self.0.events.send(GattEvent::Notification {
                    instance_id: NOTIFIER,
                    value: frame,
                });
            }
        }
        Ok(())
    }

    async fn close_link(&self) -> Result<(), GattDriverError> {
        Ok(())
    }
}

struct FakeAudio(Arc<FakeHeadset>);

struct Silence;

#[async_trait]
impl AudioEvents for Silence {
    async fn next_event(&self) -> Option<RawAudioEvent> {
        std::future::pending().await
    }
}

impl FakeAudio {
    fn paired(&self) -> RawAudioDevice {
        RawAudioDevice::new(Some(self.0.profile.display_name()), &self.0.address)
    }
}

#[async_trait]
impl AudioFactsDriver for FakeAudio {
    fn subscribe(&self) -> Arc<dyn AudioEvents> {
        Arc::new(Silence)
    }

    async fn bonded_devices(&self) -> Result<Vec<RawAudioDevice>, TransportDriverError> {
        Ok(vec![self.paired()])
    }

    async fn snapshot(&self, _target_addresses: Vec<String>) -> Option<RawAudioSnapshot> {
        Some(RawAudioSnapshot {
            enabled: true,
            spp_available: false,
            le_audio_observable: false,
            bonded_devices: vec![self.paired()],
            profiles: BTreeMap::new(),
        })
    }
}
