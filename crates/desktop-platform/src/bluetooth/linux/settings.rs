use std::ffi::OsStr;
use std::path::{Path, PathBuf};

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
