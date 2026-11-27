use std::collections::BTreeSet;
use std::fs;
use std::path::Path;

const MAX_MAJOR_OR_MINOR: u32 = 255;
const MAX_PATCH: u32 = 65_535;

pub fn check_workspace_version(workspace_root: &Path) -> Result<String, Vec<String>> {
    let expected = match read_version_txt(workspace_root) {
        Ok(version) => version,
        Err(error) => return Err(vec![error]),
    };

    let root_toml_path = workspace_root.join("Cargo.toml");
    let root_toml = match fs::read_to_string(&root_toml_path) {
        Ok(content) => content,
        Err(error) => return Err(vec![format!("failed to read Cargo.toml: {error}")]),
    };

    let mut errors = Vec::new();
    match parse_workspace_package_version(&root_toml) {
        Ok(actual) if actual == expected => {}
        Ok(actual) => errors.push(format!(
            "Cargo.toml [workspace.package].version is '{actual}', expected '{expected}' from version.txt"
        )),
        Err(error) => errors.push(error),
    }

    let inherited_packages = match workspace_inherited_packages(workspace_root, &root_toml) {
        Ok(packages) => packages,
        Err(error) => {
            errors.push(error);
            return Err(errors);
        }
    };

    let lock_path = workspace_root.join("Cargo.lock");
    match fs::read_to_string(&lock_path) {
        Ok(lock_content) => {
            errors.extend(check_cargo_lock_versions(
                &lock_content,
                &inherited_packages,
                &expected,
            ));
        }
        Err(error) => errors.push(format!("failed to read Cargo.lock: {error}")),
    }

    if errors.is_empty() {
        Ok(expected)
    } else {
        Err(errors)
    }
}

pub fn sync_workspace_version(workspace_root: &Path) -> Result<String, String> {
    let expected = read_version_txt(workspace_root)?;

    let root_toml_path = workspace_root.join("Cargo.toml");
    let root_toml = fs::read_to_string(&root_toml_path)
        .map_err(|error| format!("failed to read Cargo.toml: {error}"))?;
    let updated_toml = rewrite_workspace_package_version(&root_toml, &expected)?;
    if updated_toml != root_toml {
        fs::write(&root_toml_path, &updated_toml)
            .map_err(|error| format!("failed to write Cargo.toml: {error}"))?;
    }

    let inherited_packages = workspace_inherited_packages(workspace_root, &updated_toml)?;

    let lock_path = workspace_root.join("Cargo.lock");
    let lock_content = fs::read_to_string(&lock_path)
        .map_err(|error| format!("failed to read Cargo.lock: {error}"))?;
    let updated_lock = rewrite_cargo_lock_versions(&lock_content, &inherited_packages, &expected)?;
    if updated_lock != lock_content {
        fs::write(&lock_path, &updated_lock)
            .map_err(|error| format!("failed to write Cargo.lock: {error}"))?;
    }

    Ok(expected)
}

pub fn read_version_txt(workspace_root: &Path) -> Result<String, String> {
    let path = workspace_root.join("version.txt");
    let raw = fs::read_to_string(&path)
        .map_err(|error| format!("failed to read version.txt: {error}"))?;
    let trimmed = raw.trim();
    validate_semver_triple(trimmed)?;
    Ok(trimmed.to_string())
}

fn validate_semver_triple(text: &str) -> Result<(), String> {
    let mut parts = text.split('.');
    let Some(major_str) = parts.next() else {
        return Err(format!("version.txt must hold MAJOR.MINOR.PATCH, found '{text}'"));
    };
    let Some(minor_str) = parts.next() else {
        return Err(format!("version.txt must hold MAJOR.MINOR.PATCH, found '{text}'"));
    };
    let Some(patch_str) = parts.next() else {
        return Err(format!("version.txt must hold MAJOR.MINOR.PATCH, found '{text}'"));
    };
    if parts.next().is_some() {
        return Err(format!("version.txt must hold MAJOR.MINOR.PATCH, found '{text}'"));
    }

    let major = parse_digits(major_str, text)?;
    let minor = parse_digits(minor_str, text)?;
    let patch = parse_digits(patch_str, text)?;
    if major > MAX_MAJOR_OR_MINOR || minor > MAX_MAJOR_OR_MINOR || patch > MAX_PATCH {
        return Err(format!(
            "version.txt '{text}' is out of range: major and minor at most {MAX_MAJOR_OR_MINOR}, patch at most {MAX_PATCH}"
        ));
    }
    Ok(())
}

fn parse_digits(segment: &str, full: &str) -> Result<u32, String> {
    if segment.is_empty() || !segment.bytes().all(|b| b.is_ascii_digit()) {
        return Err(format!("version.txt must hold MAJOR.MINOR.PATCH, found '{full}'"));
    }
    segment
        .parse::<u32>()
        .map_err(|_| format!("version.txt '{full}' has an out-of-range component"))
}

fn parse_workspace_package_version(root_toml: &str) -> Result<String, String> {
    let mut in_workspace_package = false;
    for line in root_toml.lines() {
        let trimmed = strip_toml_comment(line).trim();
        if let Some(section) = parse_section_header(trimmed) {
            in_workspace_package = section == "workspace.package";
            continue;
        }
        if in_workspace_package
            && let Some((key, value)) = split_key_value(trimmed)
            && key == "version"
        {
            return parse_quoted_string(value).ok_or_else(|| {
                format!("Cargo.toml [workspace.package].version must be a quoted string, found '{value}'")
            });
        }
    }
    Err("Cargo.toml is missing [workspace.package].version".to_string())
}

fn rewrite_workspace_package_version(root_toml: &str, expected: &str) -> Result<String, String> {
    let mut in_workspace_package = false;
    let mut replaced = false;
    let mut out = String::with_capacity(root_toml.len() + 16);

    for segment in root_toml.split_inclusive('\n') {
        let (line, newline) = split_line_ending(segment);
        let trimmed = strip_toml_comment(line).trim();
        if let Some(section) = parse_section_header(trimmed) {
            in_workspace_package = section == "workspace.package";
            out.push_str(segment);
            continue;
        }
        if in_workspace_package
            && !replaced
            && let Some(updated_line) = replace_quoted_assignment(line, "version", expected)
        {
            out.push_str(&updated_line);
            out.push_str(newline);
            replaced = true;
            continue;
        }
        out.push_str(segment);
    }

    if replaced {
        Ok(out)
    } else {
        Err("Cargo.toml is missing [workspace.package].version".to_string())
    }
}

fn workspace_inherited_packages(
    workspace_root: &Path,
    root_toml: &str,
) -> Result<Vec<String>, String> {
    let members = parse_workspace_members(root_toml)?;
    let mut names = Vec::new();
    for member in members {
        let member_toml_path = workspace_root.join(&member).join("Cargo.toml");
        let content = fs::read_to_string(&member_toml_path)
            .map_err(|error| format!("failed to read {member}/Cargo.toml: {error}"))?;
        let (name, inherits_version) = parse_member_package(&content, &member)?;
        if inherits_version {
            names.push(name);
        }
    }
    if names.is_empty() {
        return Err("no workspace members inherit version.workspace = true".to_string());
    }
    Ok(names)
}

fn parse_workspace_members(root_toml: &str) -> Result<Vec<String>, String> {
    let mut in_workspace = false;
    let mut collecting = false;
    let mut array_text = String::new();

    for line in root_toml.lines() {
        let trimmed = strip_toml_comment(line).trim();
        if !collecting && let Some(section) = parse_section_header(trimmed) {
            in_workspace = section == "workspace";
            continue;
        }
        if !in_workspace {
            continue;
        }
        if !collecting {
            if let Some((key, value)) = split_key_value(trimmed)
                && key == "members"
            {
                let rest = value.trim_start();
                let Some(after_open) = rest.strip_prefix('[') else {
                    return Err("Cargo.toml [workspace].members must be an array".to_string());
                };
                if let Some(inside) = after_open.split(']').next()
                    && after_open.contains(']')
                {
                    array_text.push_str(inside);
                    break;
                }
                array_text.push_str(after_open);
                array_text.push('\n');
                collecting = true;
            }
        } else if let Some(before_close) = trimmed.split(']').next() {
            array_text.push_str(before_close);
            if trimmed.contains(']') {
                collecting = false;
                break;
            }
            array_text.push('\n');
        }
    }

    if collecting {
        return Err("unterminated [workspace].members array in Cargo.toml".to_string());
    }

    let mut members = Vec::new();
    for item in array_text.split(',') {
        let trimmed = item.trim();
        if trimmed.is_empty() {
            continue;
        }
        let member = parse_quoted_string(trimmed)
            .ok_or_else(|| format!("invalid workspace member entry '{trimmed}' in Cargo.toml"))?;
        members.push(member);
    }

    if members.is_empty() {
        return Err("Cargo.toml [workspace].members is empty".to_string());
    }
    Ok(members)
}

fn parse_member_package(content: &str, member: &str) -> Result<(String, bool), String> {
    let mut in_package = false;
    let mut name: Option<String> = None;
    let mut inherits_version = false;

    for line in content.lines() {
        let trimmed = strip_toml_comment(line).trim();
        if let Some(section) = parse_section_header(trimmed) {
            in_package = section == "package";
            continue;
        }
        if !in_package {
            continue;
        }
        if let Some((key, value)) = split_key_value(trimmed) {
            match key {
                "name" => {
                    name = parse_quoted_string(value);
                }
                "version.workspace" if value.trim() == "true" => {
                    inherits_version = true;
                }
                "version" if is_inline_workspace_true(value) => {
                    inherits_version = true;
                }
                _ => {}
            }
        }
    }

    let package_name = name.ok_or_else(|| format!("{member}/Cargo.toml is missing [package].name"))?;
    Ok((package_name, inherits_version))
}

fn is_inline_workspace_true(value: &str) -> bool {
    let trimmed = value.trim();
    let Some(inner) = trimmed.strip_prefix('{').and_then(|s| s.strip_suffix('}')) else {
        return false;
    };
    inner.split(',').any(|entry| {
        split_key_value(entry.trim())
            .is_some_and(|(k, v)| k == "workspace" && v.trim() == "true")
    })
}

fn check_cargo_lock_versions(
    lock_content: &str,
    packages: &[String],
    expected: &str,
) -> Vec<String> {
    let targets: BTreeSet<&str> = packages.iter().map(String::as_str).collect();
    let mut seen: BTreeSet<String> = BTreeSet::new();
    let mut errors = Vec::new();

    for block in split_lock_blocks(lock_content) {
        let Some((name, version, has_source)) = parse_lock_package_block(block) else {
            continue;
        };
        if has_source || !targets.contains(name.as_str()) {
            continue;
        }
        seen.insert(name.clone());
        if version != expected {
            errors.push(format!(
                "Cargo.lock package '{name}' has version '{version}', expected '{expected}' from version.txt"
            ));
        }
    }

    for package in packages {
        if !seen.contains(package) {
            errors.push(format!(
                "Cargo.lock is missing workspace package '{package}'"
            ));
        }
    }

    errors
}

fn rewrite_cargo_lock_versions(
    lock_content: &str,
    packages: &[String],
    expected: &str,
) -> Result<String, String> {
    let targets: BTreeSet<&str> = packages.iter().map(String::as_str).collect();
    let mut seen: BTreeSet<String> = BTreeSet::new();
    let mut out = String::with_capacity(lock_content.len() + 32);

    for block in split_lock_blocks(lock_content) {
        if let Some((name, _version, has_source)) = parse_lock_package_block(block)
            && !has_source
            && targets.contains(name.as_str())
        {
            seen.insert(name.clone());
            let mut replaced = false;
            for segment in block.split_inclusive('\n') {
                let (line, newline) = split_line_ending(segment);
                if !replaced
                    && let Some(updated_line) = replace_quoted_assignment(line, "version", expected)
                {
                    out.push_str(&updated_line);
                    out.push_str(newline);
                    replaced = true;
                } else {
                    out.push_str(segment);
                }
            }
            if !replaced {
                return Err(format!(
                    "Cargo.lock package '{name}' is missing a version line"
                ));
            }
        } else {
            out.push_str(block);
        }
    }

    for package in packages {
        if !seen.contains(package) {
            return Err(format!(
                "Cargo.lock is missing workspace package '{package}'"
            ));
        }
    }

    Ok(out)
}

fn split_lock_blocks(lock_content: &str) -> Vec<&str> {
    let mut starts = Vec::new();
    let mut offset = 0usize;
    for segment in lock_content.split_inclusive('\n') {
        if segment.trim() == "[[package]]" {
            starts.push(offset);
        }
        offset += segment.len();
    }

    if starts.is_empty() {
        return vec![lock_content];
    }

    let mut blocks = Vec::with_capacity(starts.len() + 1);
    if starts[0] > 0 {
        blocks.push(&lock_content[..starts[0]]);
    }
    for index in 0..starts.len() {
        let start = starts[index];
        let end = starts.get(index + 1).copied().unwrap_or(lock_content.len());
        blocks.push(&lock_content[start..end]);
    }
    blocks
}

fn parse_lock_package_block(block: &str) -> Option<(String, String, bool)> {
    let mut lines = block.lines();
    if lines.next()?.trim() != "[[package]]" {
        return None;
    }

    let mut name: Option<String> = None;
    let mut version: Option<String> = None;
    let mut has_source = false;

    for line in lines {
        let trimmed = strip_toml_comment(line).trim();
        if let Some((key, value)) = split_key_value(trimmed) {
            match key {
                "name" => name = parse_quoted_string(value),
                "version" => version = parse_quoted_string(value),
                "source" => has_source = true,
                _ => {}
            }
        }
    }

    Some((name?, version?, has_source))
}

fn parse_section_header(trimmed: &str) -> Option<&str> {
    if trimmed.starts_with("[[") || !trimmed.starts_with('[') || !trimmed.ends_with(']') {
        return None;
    }
    Some(trimmed[1..trimmed.len() - 1].trim())
}

fn split_key_value(trimmed: &str) -> Option<(&str, &str)> {
    let (key, value) = trimmed.split_once('=')?;
    Some((key.trim(), value.trim()))
}

fn parse_quoted_string(value: &str) -> Option<String> {
    let trimmed = value.trim();
    let inner = trimmed.strip_prefix('"')?.strip_suffix('"')?;
    if inner.contains('"') {
        return None;
    }
    Some(inner.to_string())
}

fn replace_quoted_assignment(line: &str, expected_key: &str, new_value: &str) -> Option<String> {
    let (code, comment) = split_toml_comment(line);
    let eq_index = code.find('=')?;
    let key = code[..eq_index].trim();
    if key != expected_key {
        return None;
    }
    let after_eq = &code[eq_index + 1..];
    let open_quote = after_eq.find('"')?;
    let rest_after_open = &after_eq[open_quote + 1..];
    let close_quote = rest_after_open.find('"')?;
    let prefix_end = eq_index + 1 + open_quote + 1;
    let suffix_start = prefix_end + close_quote;
    let mut updated = String::with_capacity(line.len() + new_value.len());
    updated.push_str(&code[..prefix_end]);
    updated.push_str(new_value);
    updated.push_str(&code[suffix_start..]);
    updated.push_str(comment);
    Some(updated)
}

fn strip_toml_comment(line: &str) -> &str {
    split_toml_comment(line).0
}

fn split_toml_comment(line: &str) -> (&str, &str) {
    let mut in_quotes = false;
    let mut escaped = false;
    for (idx, ch) in line.char_indices() {
        if in_quotes {
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_quotes = false;
            }
        } else if ch == '"' {
            in_quotes = true;
        } else if ch == '#' {
            return (&line[..idx], &line[idx..]);
        }
    }
    (line, "")
}

fn split_line_ending(segment: &str) -> (&str, &str) {
    if let Some(stripped) = segment.strip_suffix("\r\n") {
        (stripped, "\r\n")
    } else if let Some(stripped) = segment.strip_suffix('\n') {
        (stripped, "\n")
    } else {
        (segment, "")
    }
}

#[cfg(test)]
mod tests {
    use super::{check_workspace_version, sync_workspace_version};
    use std::fs;
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEMP_ID: AtomicU64 = AtomicU64::new(1);

    struct TempWorkspace {
        dir: PathBuf,
    }

    impl TempWorkspace {
        fn new(version_txt: &str, cargo_toml_version: &str, lock_version: &str) -> Self {
            let id = NEXT_TEMP_ID.fetch_add(1, Ordering::Relaxed);
            let dir = std::env::temp_dir().join(format!(
                "openrose-xtask-version-test-{}-{id}",
                std::process::id()
            ));
            let _ = fs::remove_dir_all(&dir);
            fs::create_dir_all(dir.join("crates/headset")).unwrap();
            fs::create_dir_all(dir.join("xtask")).unwrap();

            fs::write(dir.join("version.txt"), format!("{version_txt}\n")).unwrap();
            fs::write(
                dir.join("Cargo.toml"),
                format!(
                    concat!(
                        "[workspace]\n",
                        "resolver = \"2\"\n",
                        "members = [\n",
                        "    \"crates/headset\",\n",
                        "    \"xtask\",\n",
                        "]\n",
                        "\n",
                        "[workspace.package]\n",
                        "version = \"{v}\"\n",
                        "edition = \"2024\"\n",
                    ),
                    v = cargo_toml_version,
                ),
            )
            .unwrap();
            fs::write(
                dir.join("crates/headset/Cargo.toml"),
                concat!(
                    "[package]\n",
                    "name = \"openrose-headset\"\n",
                    "version.workspace = true\n",
                ),
            )
            .unwrap();
            fs::write(
                dir.join("xtask/Cargo.toml"),
                concat!(
                    "[package]\n",
                    "name = \"openrose-xtask\"\n",
                    "version = { workspace = true }\n",
                ),
            )
            .unwrap();
            fs::write(
                dir.join("Cargo.lock"),
                format!(
                    concat!(
                        "version = 4\n",
                        "\n",
                        "[[package]]\n",
                        "name = \"openrose-headset\"\n",
                        "version = \"{v}\"\n",
                        "\n",
                        "[[package]]\n",
                        "name = \"openrose-xtask\"\n",
                        "version = \"{v}\"\n",
                        "\n",
                        "[[package]]\n",
                        "name = \"serde\"\n",
                        "version = \"1.0.228\"\n",
                        "source = \"registry+https://github.com/rust-lang/crates.io-index\"\n",
                    ),
                    v = lock_version,
                ),
            )
            .unwrap();

            Self { dir }
        }

        fn path(&self) -> &Path {
            &self.dir
        }
    }

    impl Drop for TempWorkspace {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.dir);
        }
    }

    #[test]
    fn matching_workspace_passes_version_check() {
        let ws = TempWorkspace::new("0.2.0", "0.2.0", "0.2.0");
        assert_eq!(check_workspace_version(ws.path()), Ok("0.2.0".to_string()));
    }

    #[test]
    fn drifted_cargo_toml_and_cargo_lock_fail_version_check() {
        let ws = TempWorkspace::new("0.3.0", "0.2.0", "0.1.1");
        let errors = check_workspace_version(ws.path()).unwrap_err();
        assert_eq!(errors.len(), 3);
        assert!(errors[0].contains("Cargo.toml"));
        assert!(errors.iter().any(|e| e.contains("openrose-headset")));
        assert!(errors.iter().any(|e| e.contains("openrose-xtask")));
    }

    #[test]
    fn sync_derives_cargo_toml_and_cargo_lock_without_touching_registry_crates() {
        let ws = TempWorkspace::new("1.4.2", "0.1.1", "0.1.1");
        assert_eq!(sync_workspace_version(ws.path()), Ok("1.4.2".to_string()));
        assert_eq!(check_workspace_version(ws.path()), Ok("1.4.2".to_string()));

        let lock = fs::read_to_string(ws.path().join("Cargo.lock")).unwrap();
        assert!(lock.contains("name = \"serde\"\nversion = \"1.0.228\""));

        let toml_before = fs::read_to_string(ws.path().join("Cargo.toml")).unwrap();
        assert_eq!(sync_workspace_version(ws.path()), Ok("1.4.2".to_string()));
        assert_eq!(
            fs::read_to_string(ws.path().join("Cargo.toml")).unwrap(),
            toml_before
        );
    }

    #[test]
    fn invalid_version_txt_fails_check_and_sync() {
        let ws = TempWorkspace::new("1.2", "0.1.1", "0.1.1");
        assert!(check_workspace_version(ws.path()).is_err());
        assert!(sync_workspace_version(ws.path()).is_err());
    }

    #[test]
    fn current_repository_matches_version_txt() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap();
        assert!(check_workspace_version(root).is_ok());
    }
}
