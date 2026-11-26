mod no_comments;

use std::env;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};

fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("check") => run_check(),
        Some("no-comments") => run_no_comments(&workspace_root()),
        other => {
            eprintln!("Unknown xtask command: {:?}. Expected: check", other);
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

fn run_check() -> ExitCode {
    let root = workspace_root();
    let cargo = cargo_bin();

    if !run_step("Rust tests", &cargo, &["test", "--workspace"], &root) {
        return ExitCode::FAILURE;
    }

    if !run_step(
        "Rust clippy",
        &cargo,
        &[
            "clippy",
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
    let is_ci = env::var_os("CI").is_some();
    let mut args = Vec::new();
    if is_ci {
        args.push("--no-daemon".to_string());
        args.push("--stacktrace".to_string());
        args.push("check".to_string());
    }
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

fn run_no_comments(root: &Path) -> ExitCode {
    eprintln!("==> Rust no-comments check");
    match no_comments::check_workspace_rust_comments(root) {
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
            eprintln!("Found {} forbidden Rust comment(s).", violations.len());
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
