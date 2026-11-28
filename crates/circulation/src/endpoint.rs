use crate::contract::{
    BatterySlots, LocalAudio, PeerId, REMOTE_CONTROL_SETTINGS, RemoteCommand, RemoteControlStatus,
    RemoteError, RemoteReading, RemoteSessionReference, YieldRefusal, YieldResponse,
    is_remote_control_setting, same_address, supports_circulation,
};
use async_trait::async_trait;
use openrose_control::ControlRuntime;
use openrose_control::snapshot::{
    ControlSnapshot, HeadsetCandidate, HeadsetState, SessionReference,
};
use openrose_headset::{
    HeadsetCommand, HeadsetField, HeadsetSetting, ValidationErrorKind, encode_hex,
};
use ring::rand::{SecureRandom, SystemRandom};
use std::collections::BTreeMap;
use std::sync::Arc;

#[async_trait]
pub trait RemoteHandler: Send + Sync {
    async fn handle(&self, command: RemoteCommand) -> Result<RemoteReading, RemoteError>;
    async fn yield_audio(&self, address: &str) -> Result<YieldResponse, RemoteError>;
}

pub struct HeadsetEndpoint {
    peer_id: PeerId,
    control: Arc<ControlRuntime>,
    incarnation: String,
    owner_active: Arc<dyn Fn() -> bool + Send + Sync>,
    audio: Option<Arc<dyn LocalAudio>>,
    yield_lock: tokio::sync::Mutex<()>,
}

impl HeadsetEndpoint {
    pub fn with_owner_active(
        peer_id: PeerId,
        control: Arc<ControlRuntime>,
        owner_active: Arc<dyn Fn() -> bool + Send + Sync>,
    ) -> Self {
        let mut bytes = [0u8; 16];
        let _ = SystemRandom::new().fill(&mut bytes);
        Self {
            peer_id,
            control,
            incarnation: encode_hex(&bytes),
            owner_active,
            audio: None,
            yield_lock: tokio::sync::Mutex::new(()),
        }
    }

    pub fn with_local_audio(mut self, audio: Arc<dyn LocalAudio>) -> Self {
        self.audio = Some(audio);
        self
    }

    fn token(&self, session: &SessionReference) -> String {
        format!("{}/{}", self.incarnation, session.session_id)
    }

    fn held<'a>(
        &self,
        snapshot: &'a ControlSnapshot,
    ) -> Option<(SessionReference, &'a HeadsetState)> {
        let headset = snapshot.headset.as_ref()?;
        if !supports_circulation(Some(headset.profile())) || !snapshot.headset_has_system_audio() {
            return None;
        }
        let session = snapshot.noise_mode_session()?;
        Some((session, headset))
    }

    pub fn read(&self, snapshot: &ControlSnapshot) -> RemoteReading {
        let Some((session, headset)) = self.held(snapshot) else {
            return audio_only(snapshot);
        };
        let ready = snapshot.ready_session().as_ref() == Some(&session);
        let status = if ready {
            RemoteControlStatus::Ready
        } else {
            RemoteControlStatus::Initializing
        };
        let writable = &headset.readings.capabilities.writable_settings;
        let confirmed_mode_id = headset.readings.confirmed_text(HeadsetField::NoiseMode);
        let noise_mode = headset.readings.confirmed_noise_mode();
        let noise_modes = if writable.contains(&HeadsetSetting::NoiseMode) {
            headset.readings.noise_modes()
        } else {
            Vec::new()
        };
        let battery = Some(BatterySlots {
            left: headset.readings.confirmed_number(HeadsetField::LeftBattery),
            right: headset
                .readings
                .confirmed_number(HeadsetField::RightBattery),
            case: headset.readings.confirmed_number(HeadsetField::CaseBattery),
            layout: headset.profile().battery_layout(),
        });

        let mut reading = RemoteReading {
            status,
            headset_id: Some(headset.headset_id.clone()),
            headset_name: Some(headset_display_name(snapshot, headset)),
            profile: Some(headset.profile()),
            session: Some(RemoteSessionReference {
                peer_id: self.peer_id.clone(),
                headset_id: headset.headset_id.clone(),
                token: self.token(&session),
            }),
            battery,
            noise_mode,
            noise_modes,
            anc_level: None,
            anc_levels: Vec::new(),
            transparency_level: None,
            transparency_levels: Vec::new(),
            eq_preset: None,
            eq_presets: Vec::new(),
            game_mode_supported: false,
            game_mode: None,
            blocked_reasons: BTreeMap::new(),
        };

        if ready {
            fill_ready_settings(&mut reading, headset, confirmed_mode_id);
        }
        reading
    }

    async fn set(
        &self,
        expected: &RemoteSessionReference,
        command: HeadsetCommand,
    ) -> Result<RemoteReading, RemoteError> {
        let snapshot = self.control.snapshot();
        let Some((session, headset)) = self.held(&snapshot) else {
            return Err(RemoteError::SessionExpired);
        };
        if expected.peer_id != self.peer_id
            || expected.headset_id != session.headset_id
            || expected.token != self.token(&session)
        {
            return Err(RemoteError::SessionExpired);
        }
        if !is_remote_control_setting(command.setting()) {
            return Err(RemoteError::UnsupportedSetting);
        }
        if snapshot.session_for(command.setting()).as_ref() != Some(&session) {
            return Err(RemoteError::SessionNotReady);
        }
        if let Err(err) = headset.readings.validate(&command) {
            return Err(match err.kind {
                ValidationErrorKind::NotReady => RemoteError::SessionNotReady,
                ValidationErrorKind::Unsupported => RemoteError::UnsupportedSetting,
                ValidationErrorKind::InvalidArgument => RemoteError::InvalidArgument,
            });
        }
        if let Err(err) = self.control.set(command, Some(session.session_id)).await {
            return Err(RemoteError::ControlFailed(err.code));
        }
        let current = self.control.snapshot();
        if self.held(&current).as_ref().map(|(s, _)| s) != Some(&session) {
            return Err(RemoteError::SessionExpired);
        }
        Ok(self.read(&current))
    }
}

#[async_trait]
impl RemoteHandler for HeadsetEndpoint {
    async fn handle(&self, command: RemoteCommand) -> Result<RemoteReading, RemoteError> {
        if !(self.owner_active)() {
            return Err(RemoteError::OwnerInactive);
        }
        match command {
            RemoteCommand::Read => Ok(self.read(&self.control.snapshot())),
            RemoteCommand::Set { session, command } => self.set(&session, command).await,
        }
    }

    async fn yield_audio(&self, address: &str) -> Result<YieldResponse, RemoteError> {
        if !(self.owner_active)() {
            return Err(RemoteError::OwnerInactive);
        }
        let Some(audio) = &self.audio else {
            return Err(RemoteError::UnsupportedOperation);
        };
        let _guard = self.yield_lock.lock().await;
        yield_via_local_audio(audio.as_ref(), Some(&self.control), address).await
    }
}

pub(crate) async fn yield_via_local_audio(
    audio: &dyn LocalAudio,
    control: Option<&ControlRuntime>,
    address: &str,
) -> Result<YieldResponse, RemoteError> {
    match audio.is_connected(address).await {
        Ok(true) => {}
        Ok(false) => return Ok(YieldResponse::NotHolding),
        Err(refusal) => return Ok(YieldResponse::Refused(refusal)),
    }
    let receipt = match audio.yield_audio(address).await {
        Ok(receipt) => receipt,
        Err(refusal) => return Ok(YieldResponse::Refused(refusal)),
    };
    match audio.is_connected(address).await {
        Ok(false) => {}
        Ok(true) => return Ok(YieldResponse::Refused(YieldRefusal::StillConnected)),
        Err(refusal) => return Ok(YieldResponse::Refused(refusal)),
    }
    if let Some(control) = control {
        let snapshot = control.snapshot();
        if snapshot_matches_address(&snapshot, address) {
            control.disconnect().await;
        }
    }
    Ok(YieldResponse::Yielded(receipt))
}

fn snapshot_matches_address(snapshot: &ControlSnapshot, address: &str) -> bool {
    let headset = snapshot.headset.as_ref().is_some_and(|h| {
        same_address(h.headset_id.as_str(), address)
            || h.classic_address
                .as_deref()
                .is_some_and(|classic| same_address(classic, address))
    });
    let target = snapshot.connection_target.as_ref().is_some_and(|c| {
        same_address(c.id.as_str(), address)
            || c.classic_address
                .as_deref()
                .is_some_and(|classic| same_address(classic, address))
    });
    headset || target
}

fn fill_ready_settings(
    reading: &mut RemoteReading,
    headset: &HeadsetState,
    confirmed_mode_id: Option<&str>,
) {
    let writable = &headset.readings.capabilities.writable_settings;
    let noise_control = headset.readings.capabilities.noise_control.as_ref();

    if writable.contains(&HeadsetSetting::AncLevel)
        && let Some(anc) = noise_control.and_then(|nc| nc.anc_level.as_ref())
        && anc.is_visible_in(confirmed_mode_id)
    {
        reading.anc_levels = anc.levels.iter().copied().collect();
        reading.anc_level = headset.readings.confirmed_number(HeadsetField::AncLevel);
    }

    if writable.contains(&HeadsetSetting::TransparencyLevel)
        && let Some(transparency) = noise_control.and_then(|nc| nc.transparency_level.as_ref())
        && transparency.is_visible_in(confirmed_mode_id)
    {
        reading.transparency_levels = transparency.levels.iter().copied().collect();
        reading.transparency_level = headset
            .readings
            .confirmed_number(HeadsetField::TransparencyLevel);
    }

    let presets = headset.readings.capabilities.writable_presets();
    if !presets.is_empty() {
        reading.eq_presets = presets.into_iter().collect();
        reading.eq_preset = headset
            .readings
            .confirmed_text(HeadsetField::Eq)
            .map(str::to_string);
    }

    if writable.contains(&HeadsetSetting::GameMode) {
        reading.game_mode_supported = true;
        reading.game_mode = headset.readings.confirmed_toggle(HeadsetField::GameMode);
    }

    for &setting in REMOTE_CONTROL_SETTINGS {
        if let Some(prereq) = headset.readings.setting_prerequisite(setting) {
            reading.blocked_reasons.insert(setting, prereq);
        }
    }
}

fn audio_only(snapshot: &ControlSnapshot) -> RemoteReading {
    let Some(audio) = snapshot.audio_headset_id.as_ref() else {
        return RemoteReading::idle();
    };
    let held = snapshot
        .connection_target
        .iter()
        .chain(&snapshot.bonded_headsets)
        .chain(&snapshot.candidates)
        .find(|candidate| {
            supports_circulation(candidate.profile) && candidate.matches_audio(Some(audio))
        });
    match held {
        Some(candidate) => RemoteReading {
            headset_id: Some(candidate.id.clone()),
            headset_name: Some(candidate_display_name(candidate)),
            profile: candidate.profile,
            ..RemoteReading::empty(RemoteControlStatus::Recovering)
        },
        None => RemoteReading::idle(),
    }
}

fn headset_display_name(snapshot: &ControlSnapshot, headset: &HeadsetState) -> String {
    snapshot
        .connection_target
        .iter()
        .chain(&snapshot.bonded_headsets)
        .chain(&snapshot.candidates)
        .find(|candidate| {
            candidate.id == headset.headset_id
                || headset
                    .classic_address
                    .as_deref()
                    .is_some_and(|addr| candidate.classic_address.as_deref() == Some(addr))
        })
        .and_then(|candidate| non_empty_name(candidate.name.as_deref()))
        .unwrap_or_else(|| headset.profile().display_name().to_string())
}

fn candidate_display_name(candidate: &HeadsetCandidate) -> String {
    non_empty_name(candidate.name.as_deref())
        .or_else(|| candidate.profile.map(|p| p.display_name().to_string()))
        .unwrap_or_else(|| candidate.id.as_str().to_string())
}

fn non_empty_name(name: Option<&str>) -> Option<String> {
    name.map(str::trim)
        .filter(|trimmed| !trimmed.is_empty())
        .map(str::to_string)
}
