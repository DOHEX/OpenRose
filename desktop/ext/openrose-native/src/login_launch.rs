use nsh_core::JsonValue;
use nsh_core::prelude::*;
use serde::Serialize;
use std::ffi::{OsStr, OsString};
use std::path::{Component, Path, PathBuf};

const AUTOSTART_ARG: &str = "--started-at-login";
const AUTOSTART_FILE_NAME: &str = "openrose.desktop";

#[derive(Serialize, Debug, Clone, Copy, PartialEq, Eq)]
enum LoginLaunchReason {
    DevelopmentBuild,
    UnsupportedSystem,
    Sandboxed,
    ConfigDirUnknown,
    ConfigReadFailed,
    ConfigWriteFailed,
    InvalidExecutable,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LoginLaunchReport {
    installed: bool,
    supported: bool,
    enabled: bool,
    reason: Option<LoginLaunchReason>,
}

impl LoginLaunchReport {
    fn hidden() -> Self {
        Self {
            installed: false,
            supported: cfg!(target_os = "linux"),
            enabled: false,
            reason: Some(LoginLaunchReason::DevelopmentBuild),
        }
    }

    fn unsupported(reason: LoginLaunchReason) -> Self {
        Self {
            installed: true,
            supported: false,
            enabled: false,
            reason: Some(reason),
        }
    }

    fn ready(enabled: bool) -> Self {
        Self {
            installed: true,
            supported: true,
            enabled,
            reason: None,
        }
    }
}

fn was_started_at_login_in<I, S>(args: I) -> bool
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    args.into_iter()
        .skip(1)
        .any(|arg| arg.as_ref() == OsStr::new(AUTOSTART_ARG))
}

#[cfg(target_os = "linux")]
fn is_source_executable(exe: &Path) -> bool {
    if exe.file_name().and_then(|name| name.to_str()) == Some("nsh-linux-host") {
        return true;
    }
    let components: Vec<&OsStr> = exe
        .components()
        .filter_map(|component| match component {
            Component::Normal(part) => Some(part),
            _ => None,
        })
        .collect();
    if components
        .iter()
        .any(|part| *part == OsStr::new(".nsh"))
    {
        return true;
    }
    components.windows(2).any(|pair| {
        pair[0] == OsStr::new("target")
            && (pair[1] == OsStr::new("debug") || pair[1] == OsStr::new("release"))
    })
}

#[cfg(target_os = "linux")]
fn resolve_installed_executable(
    development: bool,
    appimage: Option<&OsStr>,
    nsh_app_dir: Option<&OsStr>,
    current_exe: Option<&Path>,
) -> Option<PathBuf> {
    if development || nsh_app_dir.is_some() {
        return None;
    }
    if let Some(path) = appimage.filter(|value| !value.is_empty()) {
        return Some(PathBuf::from(path));
    }
    let exe = current_exe?;
    if is_source_executable(exe) {
        return None;
    }
    Some(exe.to_path_buf())
}

#[cfg(target_os = "linux")]
fn is_sandboxed(exe: &Path, flatpak_marker_exists: bool, snap_dir: Option<&OsStr>) -> bool {
    if flatpak_marker_exists {
        return true;
    }
    snap_dir.is_some_and(|snap| !snap.is_empty() && exe.starts_with(snap))
}

#[cfg(target_os = "linux")]
fn autostart_dir_from(xdg_config_home: Option<&OsStr>, home: Option<&OsStr>) -> Option<PathBuf> {
    if let Some(dir) = xdg_config_home.filter(|value| !value.is_empty()) {
        return Some(PathBuf::from(dir).join("autostart"));
    }
    home.filter(|value| !value.is_empty())
        .map(|dir| PathBuf::from(dir).join(".config").join("autostart"))
}

#[cfg(target_os = "linux")]
fn escape_desktop_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let len = value.len();
    for (idx, ch) in value.char_indices() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            ' ' if idx == 0 || idx + 1 == len => out.push_str("\\s"),
            _ => out.push(ch),
        }
    }
    out
}

#[cfg(target_os = "linux")]
fn unescape_desktop_string(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    let mut chars = value.chars();
    while let Some(ch) = chars.next() {
        if ch == '\\' {
            match chars.next() {
                Some('s') => out.push(' '),
                Some('n') => out.push('\n'),
                Some('r') => out.push('\r'),
                Some('t') => out.push('\t'),
                Some('\\') => out.push('\\'),
                Some(other) => {
                    out.push('\\');
                    out.push(other);
                }
                None => out.push('\\'),
            }
        } else {
            out.push(ch);
        }
    }
    out
}

#[cfg(target_os = "linux")]
fn needs_exec_quoting(arg: &str) -> bool {
    arg.is_empty()
        || arg.chars().any(|ch| {
            matches!(
                ch,
                ' ' | '\t'
                    | '\n'
                    | '\r'
                    | '"'
                    | '\''
                    | '\\'
                    | '>'
                    | '<'
                    | '~'
                    | '|'
                    | '&'
                    | ';'
                    | '$'
                    | '*'
                    | '?'
                    | '#'
                    | '('
                    | ')'
                    | '`'
                    | '%'
            )
        })
}

#[cfg(target_os = "linux")]
fn format_exec_arg(arg: &str) -> String {
    if !needs_exec_quoting(arg) {
        return arg.to_string();
    }
    let mut quoted = String::with_capacity(arg.len() + 4);
    quoted.push('"');
    for ch in arg.chars() {
        match ch {
            '"' | '`' | '$' | '\\' => {
                quoted.push('\\');
                quoted.push(ch);
            }
            '%' => quoted.push_str("%%"),
            _ => quoted.push(ch),
        }
    }
    quoted.push('"');
    escape_desktop_string(&quoted)
}

#[cfg(target_os = "linux")]
fn parse_exec_program(exec: &str) -> Option<String> {
    let trimmed = exec.trim_start();
    if trimmed.is_empty() {
        return None;
    }
    let mut chars = trimmed.chars();
    let mut out = String::new();
    match chars.next()? {
        '"' => {
            while let Some(ch) = chars.next() {
                match ch {
                    '"' => {
                        return match chars.next() {
                            None | Some(' ' | '\t') => Some(out),
                            Some(_) => None,
                        };
                    }
                    '\\' => out.push(chars.next()?),
                    '%' => {
                        if chars.next() != Some('%') {
                            return None;
                        }
                        out.push('%');
                    }
                    other => out.push(other),
                }
            }
            None
        }
        '%' => {
            if chars.next() != Some('%') {
                return None;
            }
            out.push('%');
            for ch in chars {
                if ch == ' ' || ch == '\t' {
                    break;
                }
                if ch == '"' {
                    return None;
                }
                out.push(ch);
            }
            Some(out)
        }
        first => {
            out.push(first);
            while let Some(ch) = chars.next() {
                if ch == ' ' || ch == '\t' {
                    break;
                }
                if ch == '"' {
                    return None;
                }
                if ch == '%' {
                    if chars.next() != Some('%') {
                        return None;
                    }
                    out.push('%');
                } else {
                    out.push(ch);
                }
            }
            Some(out)
        }
    }
}

#[cfg(target_os = "linux")]
fn desktop_entry_value(text: &str, key: &str) -> Option<String> {
    let mut in_desktop_entry = false;
    let mut found: Option<String> = None;
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            in_desktop_entry = trimmed == "[Desktop Entry]";
            continue;
        }
        if !in_desktop_entry {
            continue;
        }
        if let Some((k, v)) = trimmed.split_once('=')
            && k.trim() == key
        {
            found = Some(unescape_desktop_string(v.trim_start()));
        }
    }
    found
}

#[cfg(target_os = "linux")]
fn build_desktop_entry(exe: &Path) -> Result<String, LoginLaunchReason> {
    let exe_str = exe
        .to_str()
        .filter(|value| !value.is_empty() && !value.chars().any(char::is_control))
        .ok_or(LoginLaunchReason::InvalidExecutable)?;
    Ok(format!(
        "[Desktop Entry]\nType=Application\nVersion=1.0\nName=OpenRose\nComment=OpenRose 耳机控制客户端\nExec={} {AUTOSTART_ARG}\nTryExec={}\nIcon=openrose\nTerminal=false\nCategories=AudioVideo;Utility;\nX-GNOME-Autostart-enabled=true\n",
        format_exec_arg(exe_str),
        escape_desktop_string(exe_str),
    ))
}

#[cfg(target_os = "linux")]
fn read_enabled_in(dir: &Path, exe: &Path) -> Result<bool, LoginLaunchReason> {
    let path = dir.join(AUTOSTART_FILE_NAME);
    let bytes = match std::fs::read(&path) {
        Ok(bytes) => bytes,
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => return Ok(false),
        Err(_) => return Err(LoginLaunchReason::ConfigReadFailed),
    };
    let text = String::from_utf8_lossy(&bytes);
    if desktop_entry_value(&text, "Hidden").is_some_and(|v| v.trim() == "true") {
        return Ok(false);
    }
    if desktop_entry_value(&text, "X-GNOME-Autostart-enabled").is_some_and(|v| v.trim() == "false")
    {
        return Ok(false);
    }
    let Some(exec_program) =
        desktop_entry_value(&text, "Exec").and_then(|v| parse_exec_program(&v))
    else {
        return Ok(false);
    };
    if Path::new(&exec_program) != exe {
        return Ok(false);
    }
    if let Some(try_exec) = desktop_entry_value(&text, "TryExec")
        && Path::new(&try_exec) != exe
    {
        return Ok(false);
    }
    Ok(true)
}

#[cfg(target_os = "linux")]
fn set_enabled_in(dir: &Path, exe: &Path, enabled: bool) -> Result<bool, LoginLaunchReason> {
    let path = dir.join(AUTOSTART_FILE_NAME);
    if enabled {
        let entry = build_desktop_entry(exe)?;
        std::fs::create_dir_all(dir)
            .map_err(|_| LoginLaunchReason::ConfigWriteFailed)?;
        let tmp = dir.join(format!(".{AUTOSTART_FILE_NAME}.{}.tmp", std::process::id()));
        std::fs::write(&tmp, entry)
            .and_then(|()| std::fs::rename(&tmp, &path))
            .map_err(|_| {
                let _ = std::fs::remove_file(&tmp);
                LoginLaunchReason::ConfigWriteFailed
            })?;
        read_enabled_in(dir, exe)
    } else {
        match std::fs::remove_file(&path) {
            Ok(()) => Ok(false),
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Err(_) => Err(LoginLaunchReason::ConfigWriteFailed),
        }
    }
}

#[cfg(target_os = "linux")]
fn linux_context(development: bool) -> Result<(PathBuf, PathBuf), LoginLaunchReport> {
    let current_exe = std::env::current_exe().ok();
    let appimage = std::env::var_os("APPIMAGE");
    let nsh_app_dir = std::env::var_os("NSH_APP_DIR");
    let Some(exe) = resolve_installed_executable(
        development,
        appimage.as_deref(),
        nsh_app_dir.as_deref(),
        current_exe.as_deref(),
    ) else {
        return Err(LoginLaunchReport::hidden());
    };
    if is_sandboxed(
        &exe,
        Path::new("/.flatpak-info").exists(),
        std::env::var_os("SNAP").as_deref(),
    ) {
        return Err(LoginLaunchReport::unsupported(LoginLaunchReason::Sandboxed));
    }
    let Some(dir) = autostart_dir_from(
        std::env::var_os("XDG_CONFIG_HOME").as_deref(),
        std::env::var_os("HOME").as_deref(),
    ) else {
        return Err(LoginLaunchReport::unsupported(LoginLaunchReason::ConfigDirUnknown));
    };
    Ok((dir, exe))
}

#[cfg(target_os = "linux")]
fn read_status(development: bool) -> LoginLaunchReport {
    let (dir, exe) = match linux_context(development) {
        Ok(ctx) => ctx,
        Err(report) => return report,
    };
    match read_enabled_in(&dir, &exe) {
        Ok(enabled) => LoginLaunchReport::ready(enabled),
        Err(reason) => LoginLaunchReport::unsupported(reason),
    }
}

#[cfg(not(target_os = "linux"))]
fn read_status(development: bool) -> LoginLaunchReport {
    if development {
        LoginLaunchReport::hidden()
    } else {
        LoginLaunchReport::unsupported(LoginLaunchReason::UnsupportedSystem)
    }
}

#[cfg(target_os = "linux")]
fn update_enabled(development: bool, enabled: bool) -> Result<LoginLaunchReport, LoginLaunchReason> {
    let (dir, exe) = match linux_context(development) {
        Ok(ctx) => ctx,
        Err(report) => {
            return Err(report.reason.unwrap_or(LoginLaunchReason::UnsupportedSystem));
        }
    };
    let active = set_enabled_in(&dir, &exe, enabled)?;
    Ok(LoginLaunchReport::ready(active))
}

#[cfg(not(target_os = "linux"))]
fn update_enabled(development: bool, _enabled: bool) -> Result<LoginLaunchReport, LoginLaunchReason> {
    if development {
        Err(LoginLaunchReason::DevelopmentBuild)
    } else {
        Err(LoginLaunchReason::UnsupportedSystem)
    }
}

fn current_args() -> Vec<OsString> {
    std::env::args_os().collect()
}

/// Desktop launch-at-login status, autostart entry management, and startup flag detection.
#[module]
pub struct OpenRoseLoginLaunch;

#[module]
impl OpenRoseLoginLaunch {
    /// Reports whether launch-at-login is available for this build and whether it is enabled.
    pub fn status(development: bool) -> JsonValue {
        serde_json::to_value(read_status(development)).expect("login launch report serializes")
    }

    /// Enables or disables launch-at-login by writing or removing the XDG autostart entry.
    pub fn set_enabled(development: bool, enabled: bool) -> Result<JsonValue, JsError> {
        let report = update_enabled(development, enabled)
            .map_err(|reason| JsError::Error(format!("{reason:?}")))?;
        Ok(serde_json::to_value(report).expect("login launch report serializes"))
    }

    /// Reports whether this process was launched from the login autostart entry.
    pub fn was_started_at_login() -> bool {
        was_started_at_login_in(current_args())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "openrose-login-launch-{name}-{}",
            std::process::id()
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).expect("create temp dir");
        dir
    }

    #[test]
    fn detects_started_at_login_flag_only_in_arguments() {
        assert!(!was_started_at_login_in(["/opt/openrose/openrose"]));
        assert!(!was_started_at_login_in(["--started-at-login"]));
        assert!(was_started_at_login_in([
            "/opt/openrose/openrose",
            "--started-at-login"
        ]));
        assert!(was_started_at_login_in([
            "/home/user/OpenRose.AppImage",
            "--other",
            "--started-at-login"
        ]));
    }

    #[test]
    fn development_build_is_always_hidden_even_when_appimage_is_set() {
        let report = OpenRoseLoginLaunch::status(true);
        let obj = report.as_object().expect("report object");
        assert_eq!(obj.get("installed").and_then(|v| v.as_bool()), Some(false));
        assert_eq!(obj.get("enabled").and_then(|v| v.as_bool()), Some(false));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn source_builds_are_excluded_while_installed_and_appimage_count_as_installed() {
        assert_eq!(
            resolve_installed_executable(
                true,
                None,
                None,
                Some(Path::new("/opt/openrose/openrose"))
            ),
            None
        );
        assert_eq!(
            resolve_installed_executable(
                false,
                None,
                Some(OsStr::new("/tmp/nsh-app")),
                Some(Path::new("/opt/openrose/openrose"))
            ),
            None
        );
        assert_eq!(
            resolve_installed_executable(
                false,
                None,
                None,
                Some(Path::new("/repo/target/debug/openrose"))
            ),
            None
        );
        assert_eq!(
            resolve_installed_executable(
                false,
                None,
                None,
                Some(Path::new("/repo/desktop/.nsh/rust/target/release/nsh-linux-host"))
            ),
            None
        );
        assert_eq!(
            resolve_installed_executable(
                false,
                Some(OsStr::new("/home/user/Apps/OpenRose.AppImage")),
                None,
                Some(Path::new("/tmp/.mount_OpenRo123/openrose"))
            ),
            Some(PathBuf::from("/home/user/Apps/OpenRose.AppImage"))
        );
        assert_eq!(
            resolve_installed_executable(
                false,
                None,
                None,
                Some(Path::new("/opt/openrose/openrose"))
            ),
            Some(PathBuf::from("/opt/openrose/openrose"))
        );
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn resolves_xdg_autostart_dir_with_home_fallback() {
        assert_eq!(
            autostart_dir_from(Some(OsStr::new("/tmp/xdg-cfg")), Some(OsStr::new("/home/u"))),
            Some(PathBuf::from("/tmp/xdg-cfg/autostart"))
        );
        assert_eq!(
            autostart_dir_from(Some(OsStr::new("")), Some(OsStr::new("/home/u"))),
            Some(PathBuf::from("/home/u/.config/autostart"))
        );
        assert_eq!(autostart_dir_from(None, None), None);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn autostart_writer_creates_reads_respects_disabled_and_removes_entry() {
        let root = temp_dir("writer");
        let autostart = root.join("autostart");
        let exe = Path::new("/opt/openrose/openrose");

        assert!(!read_enabled_in(&autostart, exe).expect("missing file reads false"));

        assert!(set_enabled_in(&autostart, exe, true).expect("enable writes desktop file"));
        let entry_path = autostart.join("openrose.desktop");
        let contents = std::fs::read_to_string(&entry_path).expect("read written desktop file");
        assert!(contents.contains("[Desktop Entry]"));
        assert!(contents.contains("Name=OpenRose"));
        assert!(contents.contains("Exec=/opt/openrose/openrose --started-at-login"));
        assert!(contents.contains("TryExec=/opt/openrose/openrose"));
        assert!(contents.contains("X-GNOME-Autostart-enabled=true"));
        assert!(read_enabled_in(&autostart, exe).expect("written entry reads true"));

        let disabled_by_de = format!("{contents}\nX-GNOME-Autostart-enabled=false\n");
        std::fs::write(&entry_path, disabled_by_de).expect("write DE disabled override");
        assert!(!read_enabled_in(&autostart, exe).expect("DE-disabled entry reads false"));

        assert!(!set_enabled_in(&autostart, exe, false).expect("disable removes desktop file"));
        assert!(!entry_path.exists());
        assert!(!set_enabled_in(&autostart, exe, false).expect("disable is idempotent"));

        let _ = std::fs::remove_dir_all(&root);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn autostart_writer_quotes_appimage_paths_with_spaces() {
        let root = temp_dir("spaces");
        let autostart = root.join("autostart");
        let exe = Path::new("/home/user/My Apps/OpenRose 0.1.AppImage");

        assert!(set_enabled_in(&autostart, exe, true).expect("enable writes quoted path"));
        let contents =
            std::fs::read_to_string(autostart.join("openrose.desktop")).expect("read entry");
        assert!(contents.contains(
            "Exec=\"/home/user/My Apps/OpenRose 0.1.AppImage\" --started-at-login"
        ));
        assert!(contents.contains("TryExec=/home/user/My Apps/OpenRose 0.1.AppImage"));

        let _ = std::fs::remove_dir_all(&root);
    }
}
