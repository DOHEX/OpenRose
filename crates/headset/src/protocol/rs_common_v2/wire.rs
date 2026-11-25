pub(super) const DEFAULT_INFO_IDS: &str =
    "010708090c0d0e122a2b2c2d2e2f313233363738393a3b3c3d3f454649";

pub(super) const NOISE_MODES: [&str; 7] = [
    "anc",
    "normal",
    "transparency",
    "wind",
    "auto",
    "deep",
    "dynamic",
];

pub(super) const FIND_STATES: [(u8, &str); 6] = [
    (1, "start_left"),
    (2, "start_right"),
    (3, "start_both"),
    (4, "stop_both"),
    (5, "stop_left"),
    (6, "stop_right"),
];

pub(super) const PROMPT_LANGUAGES: [&str; 2] = ["chinese", "english"];

pub(super) const CYCLE_ORDER: [&str; 4] = ["anc", "wind", "normal", "transparency"];

pub(super) const SPATIAL_MODES: [&str; 3] = ["off", "head_tracking", "fixed"];

pub(super) const SPATIAL_CONTENTS: [&str; 4] = ["tv", "music", "movie", "game"];

pub(super) const AUDIO_MODES: [&str; 7] = [
    "aac_sbc",
    "ldac",
    "lhdc",
    "lc3",
    "aptx",
    "aptx_hd",
    "aptx_adaptive",
];

pub(super) const GESTURES: [&str; 4] = ["single", "double", "triple", "long"];

pub(super) const ACTIONS: [&str; 9] = [
    "none",
    "play_pause",
    "previous_track",
    "next_track",
    "volume_up",
    "volume_down",
    "game_mode",
    "voice_assistant",
    "noise_cycle",
];

pub(super) const PRESET_CODES: [(&str, u8); 6] = [
    ("hifi", 0),
    ("pop", 1),
    ("rock", 2),
    ("light", 3),
    ("eap", 6),
    ("game_1", 0xa0),
];

pub(super) const READ_PRESETS: [(u8, &str); 8] = [
    (0, "pop"),
    (1, "hifi"),
    (2, "rock"),
    (3, "light"),
    (4, "custom"),
    (5, "custom_slot"),
    (6, "eap"),
    (0xa0, "game_1"),
];

pub(super) const TOUCH_SLOTS: [u8; 8] = [1, 2, 3, 4, 0x11, 0x12, 0x13, 0x14];

pub(super) const TAP_SLOTS: [u8; 4] = [2, 3, 0x12, 0x13];

pub(super) const CUSTOM_EQ_BANDS: usize = 10;

pub(super) fn key_hex(id: u8) -> String {
    format!("{id:02x}")
}
