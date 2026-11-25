use crate::transport::TransportError;
use std::sync::Mutex;
use tokio::sync::mpsc;
use tokio_util::sync::{CancellationToken, WaitForCancellationFuture};

pub(crate) struct Lifeline {
    release: tokio::sync::Mutex<bool>,
    failure: Mutex<Option<TransportError>>,
    notifications_tx: Mutex<Option<mpsc::Sender<Vec<u8>>>>,
    notifications_rx: tokio::sync::Mutex<mpsc::Receiver<Vec<u8>>>,
    pump: CancellationToken,
}

impl Lifeline {
    pub(crate) fn new(notification_buffer: usize) -> Self {
        let (sender, receiver) = mpsc::channel(notification_buffer);
        Self {
            release: tokio::sync::Mutex::new(false),
            failure: Mutex::new(None),
            notifications_tx: Mutex::new(Some(sender)),
            notifications_rx: tokio::sync::Mutex::new(receiver),
            pump: CancellationToken::new(),
        }
    }

    pub(crate) fn check_open(&self) -> Result<(), TransportError> {
        match self.failure.lock().expect("failure lock").clone() {
            Some(error) => Err(error),
            None => Ok(()),
        }
    }

    pub(crate) fn end(&self, error: TransportError) {
        let mut failure = self.failure.lock().expect("failure lock");
        if failure.is_none() {
            *failure = Some(error);
            self.notifications_tx.lock().expect("sender lock").take();
            self.pump.cancel();
        }
    }

    pub(crate) fn ended(&self) -> WaitForCancellationFuture<'_> {
        self.pump.cancelled()
    }

    pub(crate) fn deliver(&self, bytes: Vec<u8>) {
        let sent = self
            .notifications_tx
            .lock()
            .expect("sender lock")
            .as_ref()
            .is_some_and(|sender| sender.try_send(bytes).is_ok());
        if !sent {
            self.end(TransportError::Terminal(
                "通知接收缓冲区已满或已关闭".to_string(),
            ));
        }
    }

    pub(crate) async fn next_notification(
        &self,
        closed_message: &str,
    ) -> Result<Vec<u8>, TransportError> {
        let mut receiver = self.notifications_rx.lock().await;
        match receiver.recv().await {
            Some(bytes) => Ok(bytes),
            None => Err(self
                .failure
                .lock()
                .expect("failure lock")
                .clone()
                .unwrap_or_else(|| TransportError::Terminal(closed_message.to_string()))),
        }
    }

    pub(crate) async fn release(
        &self,
        close: impl Future<Output = Result<(), TransportError>>,
    ) -> Result<(), TransportError> {
        let mut released = self.release.lock().await;
        if !*released {
            close.await?;
            *released = true;
        }
        Ok(())
    }
}
