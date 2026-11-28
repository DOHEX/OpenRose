use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};

pub const NFPM_CONFIG_REL_PATH: &str = "packaging/linux/nfpm.yaml";
pub const VERSION_PLACEHOLDER: &str = "${VERSION}";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinuxPackageOutputs {
    pub appimage: PathBuf,
    pub deb: PathBuf,
    pub rpm: PathBuf,
    pub pacman: PathBuf,
}

impl LinuxPackageOutputs {
    pub fn all(&self) -> [&Path; 4] {
        [
            self.appimage.as_path(),
            self.deb.as_path(),
            self.rpm.as_path(),
            self.pacman.as_path(),
        ]
    }
}

pub fn linux_package_paths(dist_dir: &Path, version: &str) -> LinuxPackageOutputs {
    LinuxPackageOutputs {
        appimage: dist_dir.join(format!("openrose-{version}-x86_64.AppImage")),
        deb: dist_dir.join(format!("openrose_{version}_amd64.deb")),
        rpm: dist_dir.join(format!("openrose-{version}-1.x86_64.rpm")),
        pacman: dist_dir.join(format!("openrose-{version}-1-x86_64.pkg.tar.zst")),
    }
}

pub fn render_nfpm_config(template: &str, version: &str) -> Result<String, String> {
    crate::version::validate_semver_triple(version)?;
    if !template.contains(VERSION_PLACEHOLDER) {
        return Err(format!(
            "{NFPM_CONFIG_REL_PATH} must contain {VERSION_PLACEHOLDER}"
        ));
    }
    Ok(template.replace(VERSION_PLACEHOLDER, version))
}

pub fn run_package_linux(root: &Path) -> ExitCode {
    if let Err(error) = package_linux(root) {
        eprintln!("{error}");
        return ExitCode::FAILURE;
    }
    ExitCode::SUCCESS
}

fn package_linux(root: &Path) -> Result<(), String> {
    let version = crate::version::read_version_txt(root)?;
    let desktop_dir = root.join("desktop");
    let dist_dir = root.join("dist/desktop");
    if dist_dir.exists() {
        fs::remove_dir_all(&dist_dir)
            .map_err(|error| format!("failed to clean {}: {error}", dist_dir.display()))?;
    }
    fs::create_dir_all(&dist_dir)
        .map_err(|error| format!("failed to create {}: {error}", dist_dir.display()))?;

    let outputs = linux_package_paths(&dist_dir, &version);
    let nsh = crate::nsh_bin();
    let mut nsh_cmd = Command::new(&nsh);
    nsh_cmd
        .args([
            "package",
            "--target",
            "linux",
            "--format",
            "appimage",
            "--app-version",
            &version,
            "--icon",
            "../packaging/linux/openrose.png",
        ])
        .current_dir(&desktop_dir);

    if env::var_os("APPIMAGE_EXTRACT_AND_RUN").is_none() {
        nsh_cmd.env("APPIMAGE_EXTRACT_AND_RUN", "1");
    }
    if let Some(appimagetool) = resolve_appimagetool_path() {
        nsh_cmd.env("NSH_APPIMAGETOOL", appimagetool);
    }

    crate::run_command("Package Linux AppImage with nsh", nsh_cmd)?;

    let built_appimage = desktop_dir.join(".nsh/out/OpenRose-x86_64.AppImage");
    if !built_appimage.is_file() {
        return Err(format!(
            "expected AppImage at {}, but it was not produced",
            built_appimage.display()
        ));
    }
    fs::copy(&built_appimage, &outputs.appimage).map_err(|error| {
        format!(
            "failed to copy {} to {}: {error}",
            built_appimage.display(),
            outputs.appimage.display()
        )
    })?;

    let template_path = root.join(NFPM_CONFIG_REL_PATH);
    let template = fs::read_to_string(&template_path)
        .map_err(|error| format!("failed to read {}: {error}", template_path.display()))?;
    let rendered_nfpm = render_nfpm_config(&template, &version)?;
    let rendered_nfpm_path = desktop_dir.join(".nsh/out/nfpm.yaml");
    fs::write(&rendered_nfpm_path, rendered_nfpm).map_err(|error| {
        format!(
            "failed to write {}: {error}",
            rendered_nfpm_path.display()
        )
    })?;

    let nfpm = resolve_nfpm_bin();
    for packager in ["deb", "rpm", "archlinux"] {
        let mut nfpm_cmd = Command::new(&nfpm);
        nfpm_cmd
            .args([
                "package",
                "-f",
                "desktop/.nsh/out/nfpm.yaml",
                "-p",
                packager,
                "-t",
                "dist/desktop",
            ])
            .current_dir(root);
        crate::run_command(&format!("Package Linux {packager} with nfpm"), nfpm_cmd)?;
    }

    for path in outputs.all() {
        let metadata = fs::metadata(path)
            .map_err(|error| format!("missing expected package {}: {error}", path.display()))?;
        if !metadata.is_file() || metadata.len() == 0 {
            return Err(format!(
                "expected non-empty package file at {}",
                path.display()
            ));
        }
    }

    eprintln!("Packaged Linux artifacts ({version}) in {}:", dist_dir.display());
    for path in outputs.all() {
        eprintln!("  - {}", path.display());
    }
    Ok(())
}

fn resolve_nfpm_bin() -> String {
    if let Ok(explicit) = env::var("NFPM")
        && !explicit.is_empty()
    {
        return explicit;
    }
    if Command::new("nfpm")
        .arg("--version")
        .output()
        .is_ok_and(|out| out.status.success())
    {
        return "nfpm".to_string();
    }
    if let Some(dir) = mise_where(&["nfpm"]) {
        let candidate = dir.join("nfpm");
        if candidate.is_file() {
            return candidate.to_string_lossy().into_owned();
        }
    }
    "nfpm".to_string()
}

fn resolve_appimagetool_path() -> Option<PathBuf> {
    if env::var_os("NSH_APPIMAGETOOL").is_some_and(|v| !v.is_empty()) {
        return None;
    }
    if is_on_path("appimagetool") {
        return None;
    }
    let dir = mise_where(&["github:AppImage/appimagetool"])?;
    for name in ["appimagetool", "appimagetool.AppImage"] {
        let candidate = dir.join(name);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

fn mise_where(specs: &[&str]) -> Option<PathBuf> {
    for spec in specs {
        let Ok(output) = Command::new("mise").args(["where", spec]).output() else {
            continue;
        };
        if output.status.success() {
            let trimmed = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !trimmed.is_empty() {
                return Some(PathBuf::from(trimmed));
            }
        }
    }
    None
}

fn is_on_path(program: &str) -> bool {
    let Some(path_var) = env::var_os("PATH") else {
        return false;
    };
    env::split_paths(&path_var).any(|dir| {
        let candidate = dir.join(program);
        candidate.is_file()
    })
}

#[cfg(test)]
mod tests {
    use super::{
        NFPM_CONFIG_REL_PATH, linux_package_paths, render_nfpm_config,
    };
    use crate::version::read_version_txt;
    use std::fs;
    use std::path::Path;

    fn workspace_root() -> &'static Path {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .expect("xtask lives under workspace root")
    }

    #[test]
    fn render_nfpm_config_substitutes_version_and_validates_semver() {
        let template = "name: openrose\nversion: \"${VERSION}\"\n";
        let rendered = render_nfpm_config(template, "0.1.1").unwrap();
        assert_eq!(rendered, "name: openrose\nversion: \"0.1.1\"\n");

        assert!(render_nfpm_config(template, "0.1").is_err());
        assert!(render_nfpm_config(template, "0.1.1-dev").is_err());
        assert!(render_nfpm_config(template, "v0.1.1").is_err());
        assert!(render_nfpm_config("name: openrose\nversion: \"0.1.1\"\n", "0.1.1").is_err());
    }

    #[test]
    fn actual_nfpm_yaml_renders_with_version_txt_and_declares_required_fields() {
        let root = workspace_root();
        let version = read_version_txt(root).unwrap();
        let template = fs::read_to_string(root.join(NFPM_CONFIG_REL_PATH)).unwrap();
        let rendered = render_nfpm_config(&template, &version).unwrap();

        assert!(!rendered.contains("${VERSION}"));
        assert!(rendered.contains("name: openrose\n"));
        assert!(rendered.contains(&format!("version: \"{version}\"\n")));
        for expected in [
            "bluez",
            "libgtk-4-1 (>= 4.14)",
            "gtk4 >= 4.14",
            "gtk4>=4.14",
            "libdbus-1-3",
            "dbus-libs",
            "dbus",
            "/usr/bin/openrose",
            "/usr/share/applications/io.github.dohex.openrose.desktop",
            "/usr/share/icons/hicolor/512x512/apps/io.github.dohex.openrose.png",
        ] {
            assert!(
                rendered.contains(expected),
                "rendered nfpm config missing {expected}"
            );
        }
    }

    #[test]
    fn actual_desktop_entry_has_expected_name_exec_and_icon() {
        let root = workspace_root();
        let desktop = fs::read_to_string(
            root.join("packaging/linux/io.github.dohex.openrose.desktop"),
        )
        .unwrap();
        assert!(desktop.contains("Name=OpenRose\n"));
        assert!(desktop.contains("Exec=openrose\n"));
        assert!(desktop.contains("Icon=io.github.dohex.openrose\n"));
        assert!(desktop.contains("StartupWMClass=io.github.dohex.openrose\n"));
    }

    #[test]
    fn linux_package_paths_uses_expected_filenames() {
        let outputs = linux_package_paths(Path::new("/out"), "1.2.3");
        assert_eq!(
            outputs.appimage,
            Path::new("/out/openrose-1.2.3-x86_64.AppImage")
        );
        assert_eq!(outputs.deb, Path::new("/out/openrose_1.2.3_amd64.deb"));
        assert_eq!(outputs.rpm, Path::new("/out/openrose-1.2.3-1.x86_64.rpm"));
        assert_eq!(
            outputs.pacman,
            Path::new("/out/openrose-1.2.3-1-x86_64.pkg.tar.zst")
        );
    }
}
