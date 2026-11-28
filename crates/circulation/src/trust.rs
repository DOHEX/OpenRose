use crate::contract::{Peer, PeerId, RemoteError};
use crate::identity::{constant_time_eq, replace_file_secure};
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tokio::sync::watch;

const PEERS_FILE: &str = "peers.json";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PeerGrant {
    pub peer: Peer,
    pub token: String,
}

#[derive(Serialize, Deserialize, Default)]
struct StoredPeers {
    grants: Vec<PeerGrant>,
}

pub struct PeerTrust {
    file: PathBuf,
    grants: Mutex<BTreeMap<PeerId, PeerGrant>>,
    peers_tx: watch::Sender<Vec<Peer>>,
}

impl PeerTrust {
    pub fn load(directory: &Path) -> Result<Self, RemoteError> {
        fs::create_dir_all(directory).map_err(|_| RemoteError::StorageError)?;
        let file = directory.join(PEERS_FILE);
        let mut map = BTreeMap::new();
        if file.exists() {
            let bytes = fs::read(&file).map_err(|_| RemoteError::StorageError)?;
            let stored: StoredPeers =
                serde_json::from_slice(&bytes).map_err(|_| RemoteError::StorageError)?;
            for grant in stored.grants {
                map.insert(grant.peer.id.clone(), grant);
            }
        }
        let list = sorted_peers(&map);
        let (peers_tx, _) = watch::channel(list);
        Ok(Self {
            file,
            grants: Mutex::new(map),
            peers_tx,
        })
    }

    pub fn watch(&self) -> watch::Receiver<Vec<Peer>> {
        self.peers_tx.subscribe()
    }

    pub fn list(&self) -> Vec<Peer> {
        self.peers_tx.borrow().clone()
    }

    pub fn grant(&self, id: &PeerId) -> Option<PeerGrant> {
        self.grants.lock().expect("trust lock").get(id).cloned()
    }

    pub fn verify(&self, id: &PeerId, token: &str) -> Option<PeerGrant> {
        let guard = self.grants.lock().expect("trust lock");
        let grant = guard.get(id)?;
        if constant_time_eq(grant.token.as_bytes(), token.as_bytes()) {
            Some(grant.clone())
        } else {
            None
        }
    }

    pub fn add(&self, grant: PeerGrant) -> Result<(), RemoteError> {
        self.update(|map| {
            map.insert(grant.peer.id.clone(), grant);
        })
    }

    pub fn remove(&self, id: &PeerId) -> Result<Option<PeerGrant>, RemoteError> {
        let mut removed = None;
        self.update(|map| {
            removed = map.remove(id);
        })?;
        Ok(removed)
    }

    fn update(&self, mutate: impl FnOnce(&mut BTreeMap<PeerId, PeerGrant>)) -> Result<(), RemoteError> {
        let mut guard = self.grants.lock().expect("trust lock");
        let mut next = guard.clone();
        mutate(&mut next);
        let stored = StoredPeers {
            grants: next.values().cloned().collect(),
        };
        let bytes = serde_json::to_vec(&stored).map_err(|_| RemoteError::StorageError)?;
        replace_file_secure(&self.file, &bytes)?;
        let list = sorted_peers(&next);
        *guard = next;
        let _ = self.peers_tx.send(list);
        Ok(())
    }
}

fn sorted_peers(map: &BTreeMap<PeerId, PeerGrant>) -> Vec<Peer> {
    let mut peers: Vec<Peer> = map.values().map(|g| g.peer.clone()).collect();
    peers.sort_by(|a, b| a.name.cmp(&b.name).then_with(|| a.id.cmp(&b.id)));
    peers
}
