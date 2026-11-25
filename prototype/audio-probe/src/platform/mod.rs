#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "windows")]
mod windows;

use crate::cli::CliOptions;
use crate::model::ProbeSnapshot;
use std::error::Error;

pub fn collect_snapshot() -> Result<ProbeSnapshot, Box<dyn Error>> {
    #[cfg(target_os = "windows")]
    {
        windows::collect_snapshot()
    }
    #[cfg(target_os = "macos")]
    {
        macos::collect_snapshot()
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        Err(format!(
            "unsupported OS '{}': openrose-audio-probe targets Windows and macOS (run `cargo test` for offline classification tests)",
            std::env::consts::OS
        )
        .into())
    }
}

pub fn watch(options: &CliOptions) -> Result<(), Box<dyn Error>> {
    #[cfg(target_os = "windows")]
    {
        windows::watch(options)
    }
    #[cfg(target_os = "macos")]
    {
        macos::watch(options)
    }
    #[cfg(not(any(target_os = "windows", target_os = "macos")))]
    {
        let _ = options;
        Err(format!(
            "unsupported OS '{}': openrose-audio-probe targets Windows and macOS (run `cargo test` for offline classification tests)",
            std::env::consts::OS
        )
        .into())
    }
}
