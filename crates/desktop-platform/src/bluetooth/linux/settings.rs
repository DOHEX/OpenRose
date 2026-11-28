use crate::settings::first_installed_command;

pub fn bluetooth_settings_command() -> Option<Vec<String>> {
    let path_var = std::env::var_os("PATH")?;
    first_installed_command(
        &path_var,
        &[
            &["gnome-control-center", "bluetooth"],
            &["systemsettings", "kcm_bluetooth"],
            &["blueman-manager"],
        ],
    )
}
