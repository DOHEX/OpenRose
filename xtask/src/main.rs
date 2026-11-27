mod no_comments;
mod version;

use std::env;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("check") => run_check(),
        Some("check-version") => run_check_version(&workspace_root()),
        Some("sync-version") => run_sync_version(&workspace_root()),
        Some("no-comments") => run_no_comments(&workspace_root()),
        other => {
            eprintln!(
                "Unknown xtask command: {:?}. Expected: check | check-version | sync-version | no-comments",
                other
            );
            ExitCode::FAILURE
        }
    }
}

fn workspace_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("xtask crate must live directly under workspace root")
        .to_path_buf()
}

fn cargo_bin() -> String {
    env::var("CARGO").unwrap_or_else(|_| "cargo".to_string())
}

fn nsh_bin() -> String {
    env::var("NSH").unwrap_or_else(|_| "nsh".to_string())
}

fn desktop_ext_crates(root: &Path) -> Result<Vec<PathBuf>, String> {
    let ext_dir = root.join("desktop/ext");
    let entries = std::fs::read_dir(&ext_dir)
        .map_err(|error| format!("failed to read {}: {error}", ext_dir.display()))?;
    let mut crates = Vec::new();
    for entry in entries.filter_map(Result::ok) {
        let path = entry.path();
        if path.is_dir() && path.join("Cargo.toml").is_file() {
            crates.push(path);
        }
    }
    crates.sort();
    if crates.is_empty() {
        return Err(format!("no Cargo crates found under {}", ext_dir.display()));
    }
    Ok(crates)
}
fn run_check() -> ExitCode {
    let root = workspace_root();
    let cargo = cargo_bin();

    if run_check_version(&root) != ExitCode::SUCCESS {
        return ExitCode::FAILURE;
    }

    if !run_step(
        "Rust tests",
        &cargo,
        &["test", "--locked", "--workspace"],
        &root,
    ) {
        return ExitCode::FAILURE;
    }

    if !run_step(
        "Rust clippy",
        &cargo,
        &[
            "clippy",
            "--locked",
            "--workspace",
            "--all-targets",
            "--",
            "-D",
            "warnings",
        ],
        &root,
    ) {
        return ExitCode::FAILURE;
    }

    let ext_crates = match desktop_ext_crates(&root) {
        Ok(crates) => crates,
        Err(error) => {
            eprintln!("{error}");
            return ExitCode::FAILURE;
        }
    };

    for crate_dir in &ext_crates {
        let name = crate_dir
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("desktop-ext");
        if !run_step(
            &format!("Desktop Rust tests ({name})"),
            &cargo,
            &["test", "--locked"],
            crate_dir,
        ) {
            return ExitCode::FAILURE;
        }
        if !run_step(
            &format!("Desktop Rust clippy ({name})"),
            &cargo,
            &["clippy", "--locked", "--all-targets", "--", "-D", "warnings"],
            crate_dir,
        ) {
            return ExitCode::FAILURE;
        }
    }

    if run_no_comments(&root) != ExitCode::SUCCESS {
        return ExitCode::FAILURE;
    }

    if !run_step(
        "Rust FFI host library",
        &cargo,
        &["build", "--locked", "-p", "openrose-ffi"],
        &root,
    ) {
        return ExitCode::FAILURE;
    }

    let desktop_dir = root.join("desktop");
    let nsh = nsh_bin();
    if !run_step("Desktop nsh check", &nsh, &["check"], &desktop_dir) {
        return ExitCode::FAILURE;
    }
    if !run_step("Desktop nsh test", &nsh, &["test"], &desktop_dir) {
        return ExitCode::FAILURE;
    }

    let (gradle_program, gradle_args) = gradle_offline_check_command(&root);
    let gradle_arg_refs: Vec<&str> = gradle_args.iter().map(String::as_str).collect();
    if !run_step(
        "Gradle offlineCheck",
        &gradle_program,
        &gradle_arg_refs,
        &root,
    ) {
        return ExitCode::FAILURE;
    }

    ExitCode::SUCCESS
}

fn gradle_offline_check_command(root: &Path) -> (String, Vec<String>) {
    gradle_offline_check_command_for(root, env::var_os("CI").is_some())
}

fn gradle_offline_check_command_for(root: &Path, is_ci: bool) -> (String, Vec<String>) {
    let mut args = Vec::new();
    if is_ci {
        args.push("--no-daemon".to_string());
        args.push("--stacktrace".to_string());
    }
    args.push("check".to_string());
    args.push("offlineCheck".to_string());

    if cfg!(windows) {
        let script = root.join("gradlew.bat").to_string_lossy().into_owned();
        let mut cmd_args = vec!["/C".to_string(), script];
        cmd_args.extend(args);
        ("cmd".to_string(), cmd_args)
    } else {
        let script = root.join("gradlew").to_string_lossy().into_owned();
        (script, args)
    }
}

fn run_check_version(root: &Path) -> ExitCode {
    eprintln!("==> Workspace version check");
    match version::check_workspace_version(root) {
        Ok(ver) => {
            eprintln!("Workspace and desktop versions match version.txt ({ver}).");
            ExitCode::SUCCESS
        }
        Err(errors) => {
            for error in &errors {
                eprintln!("{error}");
            }
            eprintln!(
                "Run `cargo xtask sync-version` to derive Cargo and desktop versions from version.txt."
            );
            ExitCode::FAILURE
        }
    }
}

fn run_sync_version(root: &Path) -> ExitCode {
    eprintln!("==> Sync Cargo workspace and desktop versions from version.txt");
    match version::sync_workspace_version(root) {
        Ok(ver) => {
            eprintln!(
                "Synced Cargo.toml, Cargo.lock, desktop/nsh.json, and desktop/ext/openrose-native to {ver}."
            );
            ExitCode::SUCCESS
        }
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}

fn run_no_comments(root: &Path) -> ExitCode {
    eprintln!("==> Rust and TSX no-comments check");
    match no_comments::check_workspace_comments(root) {
        Ok(()) => ExitCode::SUCCESS,
        Err(violations) => {
            for violation in &violations {
                eprintln!(
                    "{}:{}:{}: comment forbidden by ADR 0021 / ADR 0038: {}",
                    violation.path.display(),
                    violation.line,
                    violation.column,
                    violation.snippet
                );
            }
            eprintln!("Found {} forbidden comment(s).", violations.len());
            ExitCode::FAILURE
        }
    }
}

fn run_step(label: &str, program: &str, args: &[&str], cwd: &Path) -> bool {
    eprintln!("==> {label}");
    match Command::new(program).args(args).current_dir(cwd).status() {
        Ok(status) if status.success() => true,
        Ok(status) => {
            eprintln!("Step '{label}' failed with exit status: {status}");
            false
        }
        Err(error) => {
            eprintln!("Step '{label}' failed to start '{program}': {error}");
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{desktop_ext_crates, gradle_offline_check_command_for};
    use std::path::Path;

    #[test]
    fn local_and_ci_both_run_check_and_offline_check() {
        let root = Path::new("/repo");
        let (_, local_args) = gradle_offline_check_command_for(root, false);
        let (_, ci_args) = gradle_offline_check_command_for(root, true);

        assert!(local_args.ends_with(&["check".to_string(), "offlineCheck".to_string()]));
        assert!(ci_args.ends_with(&["check".to_string(), "offlineCheck".to_string()]));
        assert!(!local_args.contains(&"--no-daemon".to_string()));
        assert!(ci_args.contains(&"--no-daemon".to_string()));
    }

    #[test]
    fn discovers_openrose_native_under_desktop_ext() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
        let crates = desktop_ext_crates(root).unwrap();
        assert!(crates.iter().any(|p| p.ends_with("desktop/ext/openrose-native")));
    }
}
