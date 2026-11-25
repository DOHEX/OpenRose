use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Violation {
    pub path: PathBuf,
    pub line: usize,
    pub column: usize,
    pub snippet: String,
}

pub fn check_workspace_rust_comments(workspace_root: &Path) -> Result<(), Vec<Violation>> {
    let mut violations = Vec::new();
    for dir in ["crates", "xtask", "desktop/ext"] {
        let candidate = workspace_root.join(dir);
        if candidate.is_dir() {
            visit_dir(workspace_root, &candidate, &mut violations);
        }
    }
    if violations.is_empty() {
        Ok(())
    } else {
        Err(violations)
    }
}

fn visit_dir(workspace_root: &Path, dir: &Path, violations: &mut Vec<Violation>) {
    let Ok(entries) = fs::read_dir(dir) else {
        return;
    };
    let mut paths: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .collect();
    paths.sort();

    for path in paths {
        if path.is_dir() {
            let name = path.file_name().and_then(|n| n.to_str()).unwrap_or("");
            if matches!(name, "target" | ".git" | "build" | "node_modules") {
                continue;
            }
            visit_dir(workspace_root, &path, violations);
        } else if path.extension().and_then(|ext| ext.to_str()) == Some("rs") {
            let Ok(source) = fs::read_to_string(&path) else {
                continue;
            };
            let rel = path
                .strip_prefix(workspace_root)
                .unwrap_or(&path)
                .to_path_buf();
            for (line, column, snippet) in find_comments_in_rust(&source) {
                violations.push(Violation {
                    path: rel.clone(),
                    line,
                    column,
                    snippet,
                });
            }
        }
    }
}

pub fn find_comments_in_rust(source: &str) -> Vec<(usize, usize, String)> {
    let bytes = source.as_bytes();
    let len = bytes.len();
    let mut i = 0;
    let mut line = 1usize;
    let mut col = 1usize;
    let mut brace_depth = 0usize;
    let mut native_module_pending = false;
    let mut native_module_depth: Option<usize> = None;
    let mut violations = Vec::new();

    while i < len {
        let b = bytes[i];

        if b == b'\n' {
            i += 1;
            line += 1;
            col = 1;
            continue;
        }

        if b == b'#' && i + 1 < len && bytes[i + 1] == b'[' {
            let attr_start = i;
            if let Some(end) = find_attribute_end(bytes, i) {
                let attr = &source[attr_start..=end];
                if is_native_module_attribute(attr) {
                    native_module_pending = true;
                }
            }
        }

        if let Some((next_i, next_line, next_col)) = try_skip_raw_string(source, i, line, col) {
            i = next_i;
            line = next_line;
            col = next_col;
            continue;
        }

        if b == b'"' {
            let (next_i, next_line, next_col) = skip_quoted_string(source, i, line, col);
            i = next_i;
            line = next_line;
            col = next_col;
            continue;
        }

        if b == b'\'' {
            if let Some((next_i, next_line, next_col)) = try_skip_char_literal(source, i, line, col)
            {
                i = next_i;
                line = next_line;
                col = next_col;
                continue;
            }
            i += 1;
            col += 1;
            continue;
        }

        if b == b'/' && i + 1 < len && bytes[i + 1] == b'/' {
            let start_line = line;
            let start_col = col;
            let start_i = i;
            let is_outer_doc =
                i + 2 < len && bytes[i + 2] == b'/' && (i + 3 >= len || bytes[i + 3] != b'/');
            while i < len && bytes[i] != b'\n' {
                let ch_len = utf8_char_len(bytes[i]);
                i += ch_len;
                col += 1;
            }
            let allowed = is_outer_doc && (native_module_pending || native_module_depth.is_some());
            if !allowed {
                let snippet = source[start_i..i].trim().to_string();
                violations.push((start_line, start_col, snippet));
            }
            continue;
        }

        if b == b'/' && i + 1 < len && bytes[i + 1] == b'*' {
            let start_line = line;
            let start_col = col;
            let start_i = i;
            let mut depth = 1usize;
            i += 2;
            col += 2;
            while i < len && depth > 0 {
                if bytes[i] == b'\n' {
                    i += 1;
                    line += 1;
                    col = 1;
                } else if bytes[i] == b'/' && i + 1 < len && bytes[i + 1] == b'*' {
                    depth += 1;
                    i += 2;
                    col += 2;
                } else if bytes[i] == b'*' && i + 1 < len && bytes[i + 1] == b'/' {
                    depth -= 1;
                    i += 2;
                    col += 2;
                } else {
                    let ch_len = utf8_char_len(bytes[i]);
                    i += ch_len;
                    col += 1;
                }
            }
            let first_line = source[start_i..i]
                .lines()
                .next()
                .unwrap_or("")
                .trim()
                .to_string();
            violations.push((start_line, start_col, first_line));
            continue;
        }

        if b == b'{' {
            brace_depth += 1;
            if native_module_pending {
                native_module_depth = Some(brace_depth);
                native_module_pending = false;
            }
            i += 1;
            col += 1;
            continue;
        }

        if b == b'}' {
            if native_module_depth == Some(brace_depth) {
                native_module_depth = None;
            }
            brace_depth = brace_depth.saturating_sub(1);
            i += 1;
            col += 1;
            continue;
        }

        if b == b';' && native_module_pending {
            native_module_pending = false;
        }

        let ch_len = utf8_char_len(b);
        i += ch_len;
        col += 1;
    }

    violations
}

fn utf8_char_len(first_byte: u8) -> usize {
    if first_byte < 0x80 {
        1
    } else if first_byte & 0xe0 == 0xc0 {
        2
    } else if first_byte & 0xf0 == 0xe0 {
        3
    } else {
        4
    }
}

fn is_ident_continue(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

fn try_skip_raw_string(
    source: &str,
    start: usize,
    mut line: usize,
    mut col: usize,
) -> Option<(usize, usize, usize)> {
    let bytes = source.as_bytes();
    if start > 0 && is_ident_continue(bytes[start - 1]) {
        return None;
    }
    let mut idx = start;
    if matches!(bytes[idx], b'b' | b'c') && idx + 1 < bytes.len() && bytes[idx + 1] == b'r' {
        idx += 2;
        col += 2;
    } else if bytes[idx] == b'r' {
        idx += 1;
        col += 1;
    } else {
        return None;
    }
    let mut hashes = 0usize;
    while idx < bytes.len() && bytes[idx] == b'#' {
        hashes += 1;
        idx += 1;
        col += 1;
    }
    if idx >= bytes.len() || bytes[idx] != b'"' {
        return None;
    }
    idx += 1;
    col += 1;
    while idx < bytes.len() {
        if bytes[idx] == b'\n' {
            idx += 1;
            line += 1;
            col = 1;
        } else if bytes[idx] == b'"' {
            idx += 1;
            col += 1;
            let mut matched = 0usize;
            while matched < hashes && idx < bytes.len() && bytes[idx] == b'#' {
                matched += 1;
                idx += 1;
                col += 1;
            }
            if matched == hashes {
                return Some((idx, line, col));
            }
        } else {
            let ch_len = utf8_char_len(bytes[idx]);
            idx += ch_len;
            col += 1;
        }
    }
    Some((idx, line, col))
}

fn skip_quoted_string(
    source: &str,
    start: usize,
    mut line: usize,
    mut col: usize,
) -> (usize, usize, usize) {
    let bytes = source.as_bytes();
    let mut idx = start + 1;
    col += 1;
    while idx < bytes.len() {
        if bytes[idx] == b'\n' {
            idx += 1;
            line += 1;
            col = 1;
        } else if bytes[idx] == b'\\' && idx + 1 < bytes.len() {
            if bytes[idx + 1] == b'\n' {
                idx += 2;
                line += 1;
                col = 1;
            } else {
                let ch_len = utf8_char_len(bytes[idx + 1]);
                idx += 1 + ch_len;
                col += 2;
            }
        } else if bytes[idx] == b'"' {
            idx += 1;
            col += 1;
            break;
        } else {
            let ch_len = utf8_char_len(bytes[idx]);
            idx += ch_len;
            col += 1;
        }
    }
    (idx, line, col)
}

fn try_skip_char_literal(
    source: &str,
    start: usize,
    line: usize,
    mut col: usize,
) -> Option<(usize, usize, usize)> {
    let bytes = source.as_bytes();
    let mut idx = start + 1;
    if idx >= bytes.len() || bytes[idx] == b'\n' {
        return None;
    }
    col += 1;
    if bytes[idx] == b'\\' {
        idx += 1;
        col += 1;
        while idx < bytes.len() && bytes[idx] != b'\n' && bytes[idx] != b'\'' {
            let ch_len = utf8_char_len(bytes[idx]);
            idx += ch_len;
            col += 1;
        }
        if idx < bytes.len() && bytes[idx] == b'\'' {
            return Some((idx + 1, line, col + 1));
        }
        return None;
    }
    let ch_len = utf8_char_len(bytes[idx]);
    let after = idx + ch_len;
    if after < bytes.len() && bytes[after] == b'\'' {
        return Some((after + 1, line, col + 2));
    }
    None
}

fn find_attribute_end(bytes: &[u8], start: usize) -> Option<usize> {
    let mut depth = 0usize;
    let mut idx = start + 1;
    while idx < bytes.len() {
        match bytes[idx] {
            b'[' => depth += 1,
            b']' => {
                depth -= 1;
                if depth == 0 {
                    return Some(idx);
                }
            }
            b'\n' if depth == 0 => return None,
            _ => {}
        }
        idx += 1;
    }
    None
}

fn is_native_module_attribute(attr: &str) -> bool {
    let inner = attr
        .strip_prefix("#[")
        .and_then(|s| s.strip_suffix(']'))
        .unwrap_or("")
        .trim();
    let head = inner.split('(').next().unwrap_or("").trim();
    head == "native_module" || head.ends_with("::native_module")
}

#[cfg(test)]
mod tests {
    use super::find_comments_in_rust;

    #[test]
    fn detects_line_doc_and_block_comments() {
        let source = concat!(
            "fn a() {\n",
            "    // plain comment\n",
            "    let x = 1; /* block */\n",
            "}\n",
            "/// outer doc\n",
            "//! inner doc\n",
            "/** block doc */\n",
            "/* nested /* block */ comment */\n",
        );
        let found = find_comments_in_rust(source);
        let lines: Vec<usize> = found.iter().map(|(line, _, _)| *line).collect();
        assert_eq!(lines, vec![2, 3, 5, 6, 7, 8]);
    }

    #[test]
    fn ignores_comment_tokens_inside_strings_raw_strings_chars_and_lifetimes() {
        let source = concat!(
            "fn a<'a>(s: &'a str) -> (&'static str, char, &'static [u8]) {\n",
            "    let url = \"https://example.com/*not*/\";\n",
            "    let escaped = \"quote \\\" // still string\";\n",
            "    let raw = r#\"// raw /* string */\"#;\n",
            "    let raw_bytes = br##\"// raw bytes\"##;\n",
            "    let bytes = b\"// bytes\";\n",
            "    let cstr = c\"// cstr\";\n",
            "    let ch = '/';\n",
            "    let esc = '\\'';\n",
            "    let _ = (s, url, escaped, raw, raw_bytes, cstr, ch, esc);\n",
            "    (\"ok\", '/', bytes)\n",
            "}\n",
        );
        assert!(find_comments_in_rust(source).is_empty());
    }

    #[test]
    fn allows_triple_slash_doc_comments_only_for_native_module_exports() {
        let source = concat!(
            "#[native_module]\n",
            "mod api {\n",
            "    /// Exported API doc required by nsh build.\n",
            "    pub fn ping() -> i32 { 1 }\n",
            "}\n",
            "fn outside() {}\n",
        );
        assert!(find_comments_in_rust(source).is_empty());

        let with_plain_comment_inside = concat!(
            "#[native_module]\n",
            "mod api {\n",
            "    // regular comment still forbidden\n",
            "    pub fn ping() -> i32 { 1 }\n",
            "}\n",
        );
        let found = find_comments_in_rust(with_plain_comment_inside);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].0, 3);

        let with_doc_comment_after = concat!(
            "#[native_module]\n",
            "mod api {\n",
            "    /// allowed inside\n",
            "    pub fn ping() -> i32 { 1 }\n",
            "}\n",
            "/// forbidden outside\n",
            "pub fn other() {}\n",
        );
        let found_after = find_comments_in_rust(with_doc_comment_after);
        assert_eq!(found_after.len(), 1);
        assert_eq!(found_after[0].0, 6);
    }
}
