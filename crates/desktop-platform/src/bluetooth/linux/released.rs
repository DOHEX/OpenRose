use super::bus::{
    ADAPTER, BluezBus, BluezError, BluezObjects, BluezSignal, BluezValue, CLASSIC_BEARER, DEVICE,
    LE_BEARER, MEDIA_TRANSPORT, adapter_of, media_belongs_to, on_powered_adapter,
};
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;
use tokio::sync::{Mutex, broadcast};

struct Release {
    id: u64,
    owner: String,
    confirmed: bool,
    resumed_locally: bool,
}

#[derive(Default)]
struct ReleasedInner {
    next_id: u64,
    released: HashMap<String, Arc<tokio::sync::Mutex<Release>>>,
}

pub struct ReleasedBluezLinks {
    lock: Mutex<ReleasedInner>,
}

impl Default for ReleasedBluezLinks {
    fn default() -> Self {
        Self::new()
    }
}

impl ReleasedBluezLinks {
    pub fn new() -> Self {
        Self {
            lock: Mutex::new(ReleasedInner::default()),
        }
    }

    pub async fn resume_local(&self, path: &str) {
        let mut guard = self.lock.lock().await;
        if let Some(release) = guard.released.remove(path) {
            release.lock().await.resumed_locally = true;
        }
    }

    pub async fn release<F, Fut>(
        &self,
        bus: &dyn BluezBus,
        paths: &[String],
        confirm: F,
    ) -> Result<(), String>
    where
        F: FnOnce() -> Fut,
        Fut: Future<Output = Result<(), String>>,
    {
        let owner = bus
            .owner()
            .ok_or_else(|| "BlueZ service unavailable".to_string())?;
        let release = {
            let mut guard = self.lock.lock().await;
            guard.next_id += 1;
            Arc::new(tokio::sync::Mutex::new(Release {
                id: guard.next_id,
                owner: owner.clone(),
                confirmed: false,
                resumed_locally: false,
            }))
        };
        let release_id = release.lock().await.id;

        struct Cleanup<'a> {
            owner_lock: &'a Mutex<ReleasedInner>,
            release_id: u64,
            armed: bool,
        }
        impl Drop for Cleanup<'_> {
            fn drop(&mut self) {
                if self.armed
                    && let Ok(mut guard) = self.owner_lock.try_lock()
                {
                    guard.released.retain(|_, entry| {
                        entry
                            .try_lock()
                            .map(|r| r.id != self.release_id)
                            .unwrap_or(true)
                    });
                }
            }
        }
        let mut cleanup = Cleanup {
            owner_lock: &self.lock,
            release_id,
            armed: true,
        };

        let result: Result<(), String> = async {
            {
                let mut guard = self.lock.lock().await;
                let initial = if paths.is_empty() {
                    BluezObjects::new()
                } else {
                    bus.objects().await.map_err(|err| err.to_string())?
                };
                for path in paths {
                    if let Some(interfaces) = initial.get(path) {
                        bus.disconnect_audio(path, &owner, interfaces)
                            .await
                            .map_err(|err| err.to_string())?;
                    }
                }
                if bus.owner().as_deref() != Some(owner.as_str()) {
                    return Err("BlueZ service changed while releasing".to_string());
                }
                for path in paths {
                    guard.released.insert(path.clone(), Arc::clone(&release));
                }
                if !paths.is_empty() {
                    let objects = bus.objects().await.map_err(|err| err.to_string())?;
                    for path in paths {
                        release_returned_le(&mut guard, bus, path, &objects, &release)
                            .await
                            .map_err(|err| err.to_string())?;
                    }
                }
            }

            confirm().await?;

            {
                let mut guard = self.lock.lock().await;
                {
                    let mut state = release.lock().await;
                    if state.resumed_locally {
                        return Err("local side requested the headset again".to_string());
                    }
                    if bus.owner().as_deref() != Some(state.owner.as_str()) {
                        return Err("BlueZ service changed while releasing".to_string());
                    }
                    state.confirmed = true;
                }
                let mut remaining = Vec::new();
                for path in paths {
                    if let Some(entry) = guard.released.get(path)
                        && Arc::ptr_eq(entry, &release)
                    {
                        remaining.push(path.clone());
                    }
                }
                if !remaining.is_empty() {
                    let objects = bus.objects().await.map_err(|err| err.to_string())?;
                    for path in &remaining {
                        if !release_returned_le(&mut guard, bus, path, &objects, &release)
                            .await
                            .map_err(|err| err.to_string())?
                        {
                            return Err("local side reconnected the headset audio".to_string());
                        }
                    }
                }
                if bus.owner().as_deref() != Some(owner.as_str()) {
                    return Err("BlueZ service changed while releasing".to_string());
                }
            }
            Ok(())
        }
        .await;

        if result.is_ok() {
            cleanup.armed = false;
        } else {
            let mut guard = self.lock.lock().await;
            let mut to_remove = Vec::new();
            for (path, entry) in &guard.released {
                if Arc::ptr_eq(entry, &release) {
                    to_remove.push(path.clone());
                }
            }
            for path in to_remove {
                guard.released.remove(&path);
            }
            cleanup.armed = false;
        }
        result
    }

    pub async fn observe(&self, bus: Arc<dyn BluezBus>) {
        let mut rx = bus.signals();
        loop {
            let signal = match rx.recv().await {
                Ok(signal) => signal,
                Err(broadcast::error::RecvError::Lagged(_)) => continue,
                Err(broadcast::error::RecvError::Closed) => break,
            };
            if !concerns_release(&signal) {
                continue;
            }
            let mut guard = self.lock.lock().await;
            if guard.released.is_empty() {
                continue;
            }
            match signal {
                BluezSignal::Owner { .. } => guard.released.clear(),
                BluezSignal::Removed { path, interfaces } => {
                    if interfaces.contains(DEVICE) {
                        guard.released.remove(&path);
                    }
                    if interfaces.contains(ADAPTER) {
                        forget_adapter(&mut guard, &path);
                    }
                }
                BluezSignal::Added { path, interfaces } => {
                    for (name, values) in interfaces {
                        let mapped: BTreeMap<String, Option<BluezValue>> =
                            values.into_iter().map(|(k, v)| (k, Some(v))).collect();
                        handle_changed(&mut guard, &*bus, &path, &name, &mapped).await;
                    }
                }
                BluezSignal::Changed {
                    path,
                    interface_name,
                    values,
                } => {
                    handle_changed(&mut guard, &*bus, &path, &interface_name, &values).await;
                }
                BluezSignal::PrepareForSleep { .. } => {}
            }
        }
    }
}

fn forget_adapter(guard: &mut ReleasedInner, adapter_path: &str) {
    guard
        .released
        .retain(|device_path, _| adapter_of(device_path) != adapter_path);
}

async fn handle_changed(
    guard: &mut ReleasedInner,
    bus: &dyn BluezBus,
    path: &str,
    interface_name: &str,
    values: &BTreeMap<String, Option<BluezValue>>,
) {
    match interface_name {
        ADAPTER => {
            if values.contains_key("Powered")
                && values.get("Powered") != Some(&Some(BluezValue::Bool(true)))
            {
                forget_adapter(guard, path);
            }
        }
        CLASSIC_BEARER | LE_BEARER => {
            if values.get("Connected") == Some(&Some(BluezValue::Bool(true))) {
                reconcile_signal(guard, bus, path).await;
            }
        }
        MEDIA_TRANSPORT => {
            let props: BTreeMap<String, BluezValue> = values
                .iter()
                .filter_map(|(k, v)| v.as_ref().map(|val| (k.clone(), val.clone())))
                .collect();
            let target = guard
                .released
                .keys()
                .find(|device_path| media_belongs_to(device_path, path, &props))
                .cloned();
            if let Some(device_path) = target {
                reconcile_signal(guard, bus, &device_path).await;
            }
        }
        _ => {}
    }
}

async fn reconcile_signal(guard: &mut ReleasedInner, bus: &dyn BluezBus, path: &str) {
    let Some(release) = guard.released.get(path).cloned() else {
        return;
    };
    let Ok(objects) = bus.objects().await else {
        return;
    };
    let _ = release_returned_le(guard, bus, path, &objects, &release).await;
}

async fn release_returned_le(
    guard: &mut ReleasedInner,
    bus: &dyn BluezBus,
    path: &str,
    objects: &BluezObjects,
    release: &Arc<tokio::sync::Mutex<Release>>,
) -> Result<bool, BluezError> {
    let (owner, confirmed) = {
        let state = release.lock().await;
        (state.owner.clone(), state.confirmed)
    };
    let interfaces = objects.get(path);
    let still_released = bus.owner().as_deref() == Some(owner.as_str())
        && on_powered_adapter(objects, path)
        && interfaces.is_some_and(|ifaces| ifaces.contains_key(DEVICE));
    let classic = interfaces
        .and_then(|ifaces| ifaces.get(CLASSIC_BEARER))
        .and_then(|props| props.get("Connected"))
        .and_then(BluezValue::as_bool);
    let le = interfaces
        .and_then(|ifaces| ifaces.get(LE_BEARER))
        .and_then(|props| props.get("Connected"))
        .and_then(BluezValue::as_bool);
    let has_media = super::bus::has_media_transport(objects, path);

    if !still_released {
        guard.released.remove(path);
    } else if classic == Some(true) || has_media {
        if confirmed {
            guard.released.remove(path);
        }
    } else if classic == Some(false) && le == Some(true) {
        bus.disconnect_le(path, &owner).await?;
    }

    Ok(!still_released || (classic != Some(true) && !has_media))
}

fn concerns_release(signal: &BluezSignal) -> bool {
    let is_release_iface = |iface: &str| {
        matches!(
            iface,
            ADAPTER | DEVICE | CLASSIC_BEARER | LE_BEARER | MEDIA_TRANSPORT
        )
    };
    match signal {
        BluezSignal::Owner { .. } => true,
        BluezSignal::Changed {
            interface_name,
            values,
            ..
        } => {
            is_release_iface(interface_name)
                && (values.contains_key("Connected") || values.contains_key("Powered"))
        }
        BluezSignal::Added { interfaces, .. } => interfaces.keys().any(|k| is_release_iface(k)),
        BluezSignal::Removed { interfaces, .. } => {
            interfaces.contains(DEVICE) || interfaces.contains(ADAPTER)
        }
        BluezSignal::PrepareForSleep { .. } => false,
    }
}
