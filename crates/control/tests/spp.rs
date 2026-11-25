mod support;

use async_trait::async_trait;
use openrose_control::snapshot::ConnectionPhase;
use openrose_control::spp::{SppConnection, SppDriver, SppError, SppFailureKind};
use openrose_control::transport::{ControlConnection, OpenFailureKind, TransportError};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use support::settle;
use tokio::sync::{mpsc, watch};
use tokio_util::sync::CancellationToken;

type Incoming = Result<Vec<u8>, SppError>;

struct Fake {
    capacity: usize,
    connect_failure: Mutex<Option<SppError>>,
    connect_gate: Mutex<Option<watch::Receiver<bool>>>,
    write_failure: Mutex<Option<SppError>>,
    refuse_close: AtomicBool,
    close_attempts: AtomicUsize,
    closed: watch::Sender<bool>,
    writes: Mutex<Vec<Vec<u8>>>,
    in_flight: AtomicUsize,
    overlapped: AtomicBool,
    write_gate: Mutex<Option<watch::Receiver<bool>>>,
    incoming: Mutex<Option<mpsc::UnboundedSender<Incoming>>>,
}

impl Fake {
    fn new(capacity: usize) -> Arc<Self> {
        Arc::new(Self {
            capacity,
            connect_failure: Mutex::new(None),
            connect_gate: Mutex::new(None),
            write_failure: Mutex::new(None),
            refuse_close: AtomicBool::new(false),
            close_attempts: AtomicUsize::new(0),
            closed: watch::channel(false).0,
            writes: Mutex::new(Vec::new()),
            in_flight: AtomicUsize::new(0),
            overlapped: AtomicBool::new(false),
            write_gate: Mutex::new(None),
            incoming: Mutex::new(None),
        })
    }

    fn driver(self: &Arc<Self>) -> Box<dyn SppDriver> {
        let (sender, receiver) = mpsc::unbounded_channel();
        *self.incoming.lock().unwrap() = Some(sender);
        Box::new(FakeDriver {
            state: Arc::clone(self),
            incoming: tokio::sync::Mutex::new(receiver),
        })
    }

    fn push(&self, chunk: &[u8]) {
        self.send(Ok(chunk.to_vec()));
    }

    fn send(&self, item: Incoming) {
        if let Some(sender) = self.incoming.lock().unwrap().as_ref() {
            let _ = sender.send(item);
        }
    }

    fn writes(&self) -> Vec<Vec<u8>> {
        self.writes.lock().unwrap().clone()
    }

    fn is_closed(&self) -> bool {
        *self.closed.borrow()
    }
}

struct FakeDriver {
    state: Arc<Fake>,
    incoming: tokio::sync::Mutex<mpsc::UnboundedReceiver<Incoming>>,
}

#[async_trait]
impl SppDriver for FakeDriver {
    fn max_write_size(&self) -> usize {
        self.state.capacity
    }

    async fn connect(&self) -> Result<(), SppError> {
        let gate = self.state.connect_gate.lock().unwrap().clone();
        if let Some(mut gate) = gate {
            let _ = gate.wait_for(|open| *open).await;
        }
        match self.state.connect_failure.lock().unwrap().clone() {
            Some(error) => Err(error),
            None => Ok(()),
        }
    }

    async fn read(&self) -> Result<Vec<u8>, SppError> {
        let mut incoming = self.incoming.lock().await;
        let mut closed = self.state.closed.subscribe();
        tokio::select! {
            _ = closed.wait_for(|closed| *closed) => {
                Err(SppError::new(SppFailureKind::Disconnected, "socket closed"))
            }
            item = incoming.recv() => item.unwrap_or_else(|| {
                Err(SppError::new(SppFailureKind::Disconnected, "stream ended"))
            }),
        }
    }

    async fn write(&self, value: &[u8]) -> Result<(), SppError> {
        if self.state.in_flight.fetch_add(1, Ordering::SeqCst) > 0 {
            self.state.overlapped.store(true, Ordering::SeqCst);
        }
        let gate = self.state.write_gate.lock().unwrap().clone();
        if let Some(mut gate) = gate {
            let _ = gate.wait_for(|open| *open).await;
        }
        self.state.in_flight.fetch_sub(1, Ordering::SeqCst);
        if let Some(error) = self.state.write_failure.lock().unwrap().clone() {
            return Err(error);
        }
        self.state.writes.lock().unwrap().push(value.to_vec());
        Ok(())
    }

    async fn close(&self) -> Result<(), SppError> {
        self.state.close_attempts.fetch_add(1, Ordering::SeqCst);
        if self.state.refuse_close.load(Ordering::SeqCst) {
            return Err(SppError::new(SppFailureKind::Unknown, "socket still open"));
        }
        self.state.closed.send_replace(true);
        Ok(())
    }
}

fn phases() -> (
    Arc<Mutex<Vec<ConnectionPhase>>>,
    impl Fn(ConnectionPhase) + Send + Sync,
) {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&seen);
    (seen, move |phase| sink.lock().unwrap().push(phase))
}

async fn open(fake: &Arc<Fake>) -> Arc<SppConnection> {
    let (_, on_phase) = phases();
    SppConnection::open(fake.driver(), &on_phase, &CancellationToken::new())
        .await
        .expect("the driver connects")
}

#[tokio::test(start_paused = true)]
async fn a_connection_reports_its_phase_writes_whole_frames_and_delivers_chunks_in_order() {
    let fake = Fake::new(260);
    let (seen, on_phase) = phases();
    let connection = SppConnection::open(fake.driver(), &on_phase, &CancellationToken::new())
        .await
        .expect("the driver connects");

    assert_eq!(vec![ConnectionPhase::Discovering], *seen.lock().unwrap());
    assert_eq!(260, connection.max_write_size());
    connection
        .subscribe()
        .await
        .expect("RFCOMM needs no subscription");
    connection.write(&[1, 2, 3]).await.expect("write");
    connection.write(&[4]).await.expect("write");
    assert_eq!(vec![vec![1, 2, 3], vec![4]], fake.writes());

    fake.push(&[0xdd, 0x00]);
    fake.push(&[0x02]);
    assert_eq!(
        vec![0xdd, 0x00],
        connection.next_notification().await.unwrap()
    );
    assert_eq!(vec![0x02], connection.next_notification().await.unwrap());
}

fn transport_failure(failure: openrose_control::transport::OpenFailure) -> TransportError {
    match failure.kind {
        OpenFailureKind::Transport(error) => error,
        OpenFailureKind::Cancelled => panic!("the open was not cancelled"),
    }
}

#[tokio::test(start_paused = true)]
async fn failures_keep_their_meaning_when_the_open_is_refused() {
    let cases = [
        (
            SppFailureKind::Permission,
            TransportError::PermissionDenied("boom".to_string()),
        ),
        (
            SppFailureKind::Unsupported,
            TransportError::Unsupported("boom".to_string()),
        ),
        (
            SppFailureKind::Disconnected,
            TransportError::Terminal("boom".to_string()),
        ),
        (
            SppFailureKind::Unknown,
            TransportError::Terminal("boom".to_string()),
        ),
    ];
    for (kind, expected) in cases {
        let fake = Fake::new(260);
        *fake.connect_failure.lock().unwrap() = Some(SppError::new(kind, "boom"));
        let (_, on_phase) = phases();
        let failure = SppConnection::open(fake.driver(), &on_phase, &CancellationToken::new())
            .await
            .expect_err("the driver refuses");
        assert!(failure.unreleased.is_none(), "{kind:?}");
        assert_eq!(expected, transport_failure(failure), "{kind:?}");
        assert!(fake.is_closed(), "{kind:?}");
    }
}

#[tokio::test(start_paused = true)]
async fn a_refused_close_while_opening_is_held_for_the_owners_next_release() {
    let fake = Fake::new(260);
    *fake.connect_failure.lock().unwrap() = Some(SppError::new(SppFailureKind::Unknown, "boom"));
    fake.refuse_close.store(true, Ordering::SeqCst);
    let (_, on_phase) = phases();
    let failure = SppConnection::open(fake.driver(), &on_phase, &CancellationToken::new())
        .await
        .expect_err("the driver refuses");
    let held = failure
        .unreleased
        .expect("the unreleased connection is returned");
    assert!(!fake.is_closed());

    assert!(held.close().await.is_err());
    fake.refuse_close.store(false, Ordering::SeqCst);
    held.close().await.expect("the retry releases the socket");
    held.close().await.expect("closing twice is harmless");
    assert!(fake.is_closed());
    assert_eq!(3, fake.close_attempts.load(Ordering::SeqCst));
}

#[tokio::test(start_paused = true)]
async fn cancelling_the_open_closes_the_socket() {
    let fake = Fake::new(260);
    let (_gate, gate_receiver) = watch::channel(false);
    *fake.connect_gate.lock().unwrap() = Some(gate_receiver);
    let cancel = CancellationToken::new();
    let opening = tokio::spawn({
        let fake = Arc::clone(&fake);
        let cancel = cancel.clone();
        async move {
            let on_phase = |_phase: ConnectionPhase| {};
            SppConnection::open(fake.driver(), &on_phase, &cancel)
                .await
                .map(|_| ())
        }
    });
    settle().await;
    assert!(!opening.is_finished());

    cancel.cancel();
    let failure = opening.await.unwrap().expect_err("the open is cancelled");
    assert!(matches!(failure.kind, OpenFailureKind::Cancelled));
    assert!(failure.unreleased.is_none());
    assert!(fake.is_closed());
}

#[tokio::test(start_paused = true)]
async fn a_remote_disconnect_delivers_earlier_chunks_then_fails_reads_and_writes() {
    let fake = Fake::new(260);
    let connection = open(&fake).await;
    fake.push(&[1]);
    fake.send(Err(SppError::new(
        SppFailureKind::Disconnected,
        "remote closed",
    )));
    let ended = TransportError::Terminal("remote closed".to_string());

    assert_eq!(vec![1], connection.next_notification().await.unwrap());
    assert_eq!(ended, connection.next_notification().await.unwrap_err());
    assert_eq!(ended, connection.write(&[2]).await.unwrap_err());
    assert_eq!(ended, connection.subscribe().await.unwrap_err());
    settle().await;
    assert!(fake.is_closed());
    assert!(fake.writes().is_empty());
}

#[tokio::test(start_paused = true)]
async fn a_failed_write_ends_the_connection_and_releases_the_socket() {
    let fake = Fake::new(260);
    let connection = open(&fake).await;
    *fake.write_failure.lock().unwrap() =
        Some(SppError::new(SppFailureKind::Unknown, "broken pipe"));
    let ended = TransportError::Terminal("broken pipe".to_string());

    assert_eq!(ended, connection.write(&[1]).await.unwrap_err());
    assert_eq!(ended, connection.next_notification().await.unwrap_err());
    settle().await;
    assert!(fake.is_closed());
}

#[tokio::test(start_paused = true)]
async fn unread_chunks_beyond_the_buffer_end_the_connection_after_the_buffered_ones() {
    let fake = Fake::new(260);
    let connection = open(&fake).await;
    for byte in 0..70u8 {
        fake.push(&[byte]);
    }
    settle().await;

    for byte in 0..64u8 {
        assert_eq!(vec![byte], connection.next_notification().await.unwrap());
    }
    assert_eq!(
        TransportError::Terminal("通知接收缓冲区已满或已关闭".to_string()),
        connection.next_notification().await.unwrap_err()
    );
    assert!(fake.is_closed());
}

#[tokio::test(start_paused = true)]
async fn empty_reads_are_not_notifications() {
    let fake = Fake::new(260);
    let connection = open(&fake).await;
    fake.push(&[]);
    fake.push(&[7]);
    assert_eq!(vec![7], connection.next_notification().await.unwrap());
}

#[tokio::test(start_paused = true)]
async fn concurrent_writes_are_serialized_in_submission_order() {
    let fake = Fake::new(260);
    let connection = open(&fake).await;
    let (gate, gate_receiver) = watch::channel(false);
    *fake.write_gate.lock().unwrap() = Some(gate_receiver);
    let first = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.write(&[1]).await }
    });
    settle().await;
    let second = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.write(&[2]).await }
    });
    settle().await;

    gate.send_replace(true);
    first.await.unwrap().unwrap();
    second.await.unwrap().unwrap();
    assert!(!fake.overlapped.load(Ordering::SeqCst));
    assert_eq!(vec![vec![1], vec![2]], fake.writes());
}

#[tokio::test(start_paused = true)]
async fn closing_wakes_a_pending_read_and_releases_the_socket_once() {
    let fake = Fake::new(260);
    let connection = open(&fake).await;
    let pending = tokio::spawn({
        let connection = Arc::clone(&connection);
        async move { connection.next_notification().await }
    });
    settle().await;
    assert!(!pending.is_finished());

    connection.close().await.expect("close");
    connection.close().await.expect("closing twice is harmless");
    assert_eq!(
        TransportError::Terminal("SPP 连接已关闭".to_string()),
        pending.await.unwrap().unwrap_err()
    );
    settle().await;
    assert!(fake.is_closed());
    assert_eq!(1, fake.close_attempts.load(Ordering::SeqCst));
}
