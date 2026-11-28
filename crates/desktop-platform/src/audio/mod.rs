pub mod linux;
pub mod unsupported;

pub async fn current_output() -> Result<String, String> {
    #[cfg(target_os = "linux")]
    {
        linux::local_audio::current_output().await
    }
    #[cfg(not(target_os = "linux"))]
    {
        unsupported::current_output().await
    }
}

pub fn sound_settings_command() -> Option<Vec<String>> {
    #[cfg(target_os = "linux")]
    {
        linux::settings::sound_settings_command()
    }
    #[cfg(not(target_os = "linux"))]
    {
        None
    }
}

pub fn open_sound_settings() -> Result<(), crate::settings::OpenSettingsError> {
    crate::settings::open_settings(sound_settings_command())
}
