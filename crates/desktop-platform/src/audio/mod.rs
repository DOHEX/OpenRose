pub mod linux;
pub mod unsupported;

use openrose_circulation::LocalAudio;
use std::sync::Arc;

#[cfg(target_os = "linux")]
pub fn create_local_audio() -> Arc<dyn LocalAudio> {
    let transport = crate::bluetooth::linux::transport::BluezControlTransport::open();
    Arc::new(linux::local_audio::LinuxLocalAudio::new(
        transport.audio_profiles(),
    ))
}

#[cfg(not(target_os = "linux"))]
pub fn create_local_audio() -> Arc<dyn LocalAudio> {
    Arc::new(unsupported::UnsupportedLocalAudio)
}

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

pub fn open_sound_settings() -> Result<(), String> {
    let command =
        sound_settings_command().ok_or_else(|| "未找到系统声音设置程序".to_string())?;
    let (program, args) = command
        .split_first()
        .ok_or_else(|| "声音设置命令为空".to_string())?;
    std::process::Command::new(program)
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("无法打开系统声音设置: {error}"))
}
