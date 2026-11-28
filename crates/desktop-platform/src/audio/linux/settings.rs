use crate::bluetooth::linux::settings::first_installed_command;
use std::ffi::OsStr;

pub const SOUND_SETTINGS_CANDIDATES: &[&[&str]] = &[
    &["pavucontrol"],
    &["gnome-control-center", "sound"],
    &["systemsettings", "kcm_pulseaudio"],
];

pub fn sound_settings_command() -> Option<Vec<String>> {
    let path_var = std::env::var_os("PATH")?;
    sound_settings_command_in(&path_var)
}

pub fn sound_settings_command_in(path_var: &OsStr) -> Option<Vec<String>> {
    first_installed_command(path_var, SOUND_SETTINGS_CANDIDATES)
}
