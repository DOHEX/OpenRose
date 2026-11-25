use crate::snapshot::ControlSnapshot;
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;

pub(crate) struct StateCell {
    current: Mutex<ControlSnapshot>,
    published: mpsc::UnboundedSender<Arc<ControlSnapshot>>,
}

impl StateCell {
    pub(crate) fn new(
        initial: ControlSnapshot,
    ) -> (Self, mpsc::UnboundedReceiver<Arc<ControlSnapshot>>) {
        let (published, receiver) = mpsc::unbounded_channel();
        let _ = published.send(Arc::new(initial.clone()));
        (
            Self {
                current: Mutex::new(initial),
                published,
            },
            receiver,
        )
    }

    pub(crate) fn snapshot(&self) -> ControlSnapshot {
        self.current.lock().expect("state lock").clone()
    }

    pub(crate) fn read<R>(&self, read: impl FnOnce(&ControlSnapshot) -> R) -> R {
        read(&self.current.lock().expect("state lock"))
    }

    pub(crate) fn update(&self, change: impl FnOnce(&mut ControlSnapshot)) {
        let mut current = self.current.lock().expect("state lock");
        let before = current.clone();
        change(&mut current);
        current.refresh_reasons();
        if *current != before {
            current.revision += 1;
            let _ = self.published.send(Arc::new(current.clone()));
        }
    }
}
