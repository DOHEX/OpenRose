use std::ffi::OsStr;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OpenSettingsError {
    NotInstalled,
    LaunchFailed { detail: String },
}

impl OpenSettingsError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::NotInstalled => "SettingsNotInstalled",
            Self::LaunchFailed { .. } => "SettingsLaunchFailed",
        }
    }
}

pub fn open_settings(command: Option<Vec<String>>) -> Result<(), OpenSettingsError> {
    let command = command.ok_or(OpenSettingsError::NotInstalled)?;
    let (program, args) = command
        .split_first()
        .ok_or(OpenSettingsError::NotInstalled)?;
    std::process::Command::new(program)
        .args(args)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(|_| ())
        .map_err(|error| OpenSettingsError::LaunchFailed {
            detail: error.to_string(),
        })
}

pub fn first_installed_command(path_var: &OsStr, candidates: &[&[&str]]) -> Option<Vec<String>> {
    let dirs: Vec<PathBuf> = std::env::split_paths(path_var).collect();
    for candidate in candidates {
        let Some((program, args)) = candidate.split_first() else {
            continue;
        };
        for dir in &dirs {
            let base = if dir.as_os_str().is_empty() {
                Path::new(".")
            } else {
                dir.as_path()
            };
            let executable = base.join(program);
            if is_executable_file(&executable) {
                let mut command = Vec::with_capacity(1 + args.len());
                command.push(executable.to_string_lossy().into_owned());
                command.extend(args.iter().map(|arg| (*arg).to_string()));
                return Some(command);
            }
        }
    }
    None
}

fn is_executable_file(path: &Path) -> bool {
    let Ok(metadata) = std::fs::metadata(path) else {
        return false;
    };
    if !metadata.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        metadata.permissions().mode() & 0o111 != 0
    }
    #[cfg(not(unix))]
    {
        true
    }
}
