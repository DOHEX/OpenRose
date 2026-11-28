#![cfg(unix)]

use openrose_desktop_platform::bluetooth::linux::settings::first_installed_command;
use std::ffi::OsString;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};

fn scratch(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("openrose-settings-{}-{name}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).expect("scratch dir");
    dir
}

fn install(dir: &Path, program: &str, mode: u32) -> PathBuf {
    let path = dir.join(program);
    fs::write(&path, "#!/bin/sh\n").expect("program");
    fs::set_permissions(&path, fs::Permissions::from_mode(mode)).expect("mode");
    path
}

fn search_path(dirs: &[&Path]) -> OsString {
    std::env::join_paths(dirs).expect("path")
}

const CANDIDATES: &[&[&str]] = &[
    &["gnome-control-center", "bluetooth"],
    &["systemsettings", "kcm_bluetooth"],
    &["blueman-manager"],
];

#[test]
fn no_known_settings_program_means_no_entry() {
    let empty = scratch("empty");
    assert_eq!(first_installed_command(&search_path(&[&empty]), CANDIDATES), None);
    let unrelated = install(&empty, "gedit", 0o755);
    assert!(unrelated.exists());
    assert_eq!(first_installed_command(&search_path(&[&empty]), CANDIDATES), None);
}

#[test]
fn a_non_executable_file_is_not_a_settings_program() {
    let dir = scratch("plain");
    install(&dir, "blueman-manager", 0o644);
    assert_eq!(first_installed_command(&search_path(&[&dir]), CANDIDATES), None);
}

#[test]
fn the_first_known_program_in_candidate_order_wins_with_its_arguments() {
    let first = scratch("first");
    let second = scratch("second");
    install(&second, "blueman-manager", 0o755);
    let kde = install(&first, "systemsettings", 0o755);
    let command = first_installed_command(&search_path(&[&first, &second]), CANDIDATES).expect("command");
    assert_eq!(command, vec![kde.to_string_lossy().into_owned(), "kcm_bluetooth".to_string()]);
    let gnome = install(&second, "gnome-control-center", 0o755);
    let command = first_installed_command(&search_path(&[&first, &second]), CANDIDATES).expect("command");
    assert_eq!(command, vec![gnome.to_string_lossy().into_owned(), "bluetooth".to_string()]);
}
