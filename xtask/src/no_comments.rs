use std::fs;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Violation {
    pub path: PathBuf,
    pub line: usize,
    pub column: usize,
    pub snippet: String,
}

#[derive(Copy, Clone)]
enum Language {
    Rust,
    Tsx,
}

impl Language {
    fn matches_path(self, path: &Path) -> bool {
        let ext = path.extension().and_then(|e| e.to_str());
        match self {
            Self::Rust => ext == Some("rs"),
            Self::Tsx => matches!(ext, Some("ts" | "tsx")),
        }
    }

    fn find_comments(self, source: &str) -> Vec<(usize, usize, String)> {
        match self {
            Self::Rust => find_comments_in_rust(source),
            Self::Tsx => find_comments_in_tsx(source),
        }
    }
}

pub fn check_workspace_comments(workspace_root: &Path) -> Result<(), Vec<Violation>> {
    let mut violations = Vec::new();
    for (dir, language) in [
        ("crates", Language::Rust),
        ("xtask", Language::Rust),
        ("desktop/ext", Language::Rust),
        ("desktop", Language::Tsx),
    ] {
        let candidate = workspace_root.join(dir);
        if candidate.is_dir() {
            visit_dir(workspace_root, &candidate, language, &mut violations);
        }
    }
    if violations.is_empty() {
        Ok(())
    } else {
        Err(violations)
    }
}

fn visit_dir(
    workspace_root: &Path,
    dir: &Path,
    language: Language,
    violations: &mut Vec<Violation>,
) {
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
            if matches!(
                name,
                "target" | ".git" | "build" | "node_modules" | ".nsh" | "dist"
            ) {
                continue;
            }
            visit_dir(workspace_root, &path, language, violations);
        } else if language.matches_path(&path) {
            let Ok(source) = fs::read_to_string(&path) else {
                continue;
            };
            let rel = path
                .strip_prefix(workspace_root)
                .unwrap_or(&path)
                .to_path_buf();
            for (line, column, snippet) in language.find_comments(&source) {
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
                if is_nsh_export_attribute(attr) {
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
            let (next_i, next_line, next_col) = skip_quoted_literal(source, i, line, col, b'"');
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
            let after_line = i;
            let allowed = is_outer_doc
                && (native_module_pending
                    || native_module_depth == Some(brace_depth)
                    || doc_precedes_nsh_export(source, after_line));
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

pub fn find_comments_in_tsx(source: &str) -> Vec<(usize, usize, String)> {
    let bytes = source.as_bytes();
    let len = bytes.len();
    let mut i = 0usize;
    let mut line = 1usize;
    let mut col = 1usize;
    let mut brace_depth = 0usize;
    let mut template_interp_depths: Vec<usize> = Vec::new();
    let mut regex_allowed = true;
    let mut violations = Vec::new();

    while i < len {
        let b = bytes[i];

        if b == b'\n' {
            i += 1;
            line += 1;
            col = 1;
            continue;
        }

        if matches!(b, b' ' | b'\t' | b'\r') {
            i += 1;
            col += 1;
            continue;
        }

        if b == b'"' || b == b'\'' {
            let (next_i, next_line, next_col) = skip_quoted_literal(source, i, line, col, b);
            i = next_i;
            line = next_line;
            col = next_col;
            regex_allowed = false;
            continue;
        }

        if b == b'`' {
            let (next_i, next_line, next_col, entered_interp) =
                scan_template_body(source, i + 1, line, col + 1);
            i = next_i;
            line = next_line;
            col = next_col;
            if entered_interp {
                brace_depth += 1;
                template_interp_depths.push(brace_depth);
                regex_allowed = true;
            } else {
                regex_allowed = false;
            }
            continue;
        }

        if b == b'<' && i + 1 < len && bytes[i + 1] == b'/' {
            i += 2;
            col += 2;
            regex_allowed = false;
            continue;
        }

        if b == b'/' && i + 1 < len && bytes[i + 1] == b'/' {
            let start_line = line;
            let start_col = col;
            let start_i = i;
            while i < len && bytes[i] != b'\n' {
                let ch_len = utf8_char_len(bytes[i]);
                i += ch_len;
                col += 1;
            }
            violations.push((start_line, start_col, source[start_i..i].trim().to_string()));
            continue;
        }

        if b == b'/' && i + 1 < len && bytes[i + 1] == b'*' {
            let start_line = line;
            let start_col = col;
            let start_i = i;
            i += 2;
            col += 2;
            while i < len {
                if bytes[i] == b'\n' {
                    i += 1;
                    line += 1;
                    col = 1;
                } else if bytes[i] == b'*' && i + 1 < len && bytes[i + 1] == b'/' {
                    i += 2;
                    col += 2;
                    break;
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

        if b == b'/'
            && regex_allowed
            && let Some((next_i, next_col)) = try_skip_regex_literal(source, i, col)
        {
            i = next_i;
            col = next_col;
            regex_allowed = false;
            continue;
        }

        if b == b'{' {
            brace_depth += 1;
            regex_allowed = true;
            i += 1;
            col += 1;
            continue;
        }

        if b == b'}' {
            if template_interp_depths.last().copied() == Some(brace_depth) {
                template_interp_depths.pop();
                brace_depth = brace_depth.saturating_sub(1);
                let (next_i, next_line, next_col, entered_interp) =
                    scan_template_body(source, i + 1, line, col + 1);
                i = next_i;
                line = next_line;
                col = next_col;
                if entered_interp {
                    brace_depth += 1;
                    template_interp_depths.push(brace_depth);
                    regex_allowed = true;
                } else {
                    regex_allowed = false;
                }
                continue;
            }
            brace_depth = brace_depth.saturating_sub(1);
            regex_allowed = true;
            i += 1;
            col += 1;
            continue;
        }

        if is_js_ident_start(b) {
            let start = i;
            while i < len && is_js_ident_continue(bytes[i]) {
                i += 1;
                col += 1;
            }
            let word = &source[start..i];
            regex_allowed = matches!(
                word,
                "return"
                    | "throw"
                    | "case"
                    | "default"
                    | "delete"
                    | "void"
                    | "typeof"
                    | "in"
                    | "instanceof"
                    | "new"
                    | "else"
                    | "do"
                    | "await"
                    | "yield"
                    | "of"
                    | "export"
            );
            continue;
        }

        if b.is_ascii_digit() {
            while i < len && (bytes[i].is_ascii_alphanumeric() || matches!(bytes[i], b'_' | b'.')) {
                i += 1;
                col += 1;
            }
            regex_allowed = false;
            continue;
        }

        if matches!(b, b'+' | b'-') && i + 1 < len && bytes[i + 1] == b {
            i += 2;
            col += 2;
            regex_allowed = false;
            continue;
        }

        regex_allowed = !matches!(b, b')' | b']');
        let ch_len = utf8_char_len(b);
        i += ch_len;
        col += 1;
    }

    violations
}

fn is_js_ident_start(b: u8) -> bool {
    b.is_ascii_alphabetic() || b == b'_' || b == b'$'
}

fn is_js_ident_continue(b: u8) -> bool {
    is_js_ident_start(b) || b.is_ascii_digit()
}

fn scan_template_body(
    source: &str,
    mut idx: usize,
    mut line: usize,
    mut col: usize,
) -> (usize, usize, usize, bool) {
    let bytes = source.as_bytes();
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
        } else if bytes[idx] == b'$' && idx + 1 < bytes.len() && bytes[idx + 1] == b'{' {
            return (idx + 2, line, col + 2, true);
        } else if bytes[idx] == b'`' {
            return (idx + 1, line, col + 1, false);
        } else {
            let ch_len = utf8_char_len(bytes[idx]);
            idx += ch_len;
            col += 1;
        }
    }
    (idx, line, col, false)
}

fn try_skip_regex_literal(source: &str, start: usize, mut col: usize) -> Option<(usize, usize)> {
    let bytes = source.as_bytes();
    let mut idx = start + 1;
    col += 1;
    let mut in_char_class = false;
    while idx < bytes.len() {
        let b = bytes[idx];
        if b == b'\n' || b == b'\r' {
            return None;
        }
        if b == b'\\' {
            idx += 1;
            col += 1;
            if idx >= bytes.len() || matches!(bytes[idx], b'\n' | b'\r') {
                return None;
            }
            let ch_len = utf8_char_len(bytes[idx]);
            idx += ch_len;
            col += 1;
            continue;
        }
        if b == b'[' {
            in_char_class = true;
        } else if b == b']' {
            in_char_class = false;
        } else if b == b'/' && !in_char_class {
            idx += 1;
            col += 1;
            while idx < bytes.len() && bytes[idx].is_ascii_alphabetic() {
                idx += 1;
                col += 1;
            }
            return Some((idx, col));
        }
        let ch_len = utf8_char_len(b);
        idx += ch_len;
        col += 1;
    }
    None
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

fn skip_quoted_literal(
    source: &str,
    start: usize,
    mut line: usize,
    mut col: usize,
    quote: u8,
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
        } else if bytes[idx] == quote {
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

fn is_nsh_export_attribute(attr: &str) -> bool {
    let inner = attr
        .strip_prefix("#[")
        .and_then(|s| s.strip_suffix(']'))
        .unwrap_or("")
        .trim();
    let head = inner.split('(').next().unwrap_or("").trim();
    let tail = head.rsplit("::").next().unwrap_or(head);
    matches!(
        tail,
        "native_module" | "module" | "class" | "component" | "object"
    )
}

fn doc_precedes_nsh_export(source: &str, mut idx: usize) -> bool {
    let bytes = source.as_bytes();
    while idx < bytes.len() {
        while idx < bytes.len() && matches!(bytes[idx], b' ' | b'\t' | b'\r' | b'\n') {
            idx += 1;
        }
        if idx + 2 < bytes.len()
            && bytes[idx] == b'/'
            && bytes[idx + 1] == b'/'
            && bytes[idx + 2] == b'/'
            && (idx + 3 >= bytes.len() || bytes[idx + 3] != b'/')
        {
            while idx < bytes.len() && bytes[idx] != b'\n' {
                idx += 1;
            }
            continue;
        }
        if idx + 1 < bytes.len() && bytes[idx] == b'#' && bytes[idx + 1] == b'[' {
            let Some(end) = find_attribute_end(bytes, idx) else {
                return false;
            };
            if is_nsh_export_attribute(&source[idx..=end]) {
                return true;
            }
            idx = end + 1;
            continue;
        }
        return false;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::{check_workspace_comments, find_comments_in_rust, find_comments_in_tsx};
    use std::fs;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT_TEMP_ID: AtomicU64 = AtomicU64::new(1);

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

        let nsh_exports = concat!(
            "/// Exported module struct.\n",
            "#[module]\n",
            "pub struct Catalog;\n",
            "#[class]\n",
            "impl Client {\n",
            "    /// Exported method doc.\n",
            "    pub fn connect(&self) {\n",
            "        /// Forbidden inside method body.\n",
            "    }\n",
            "}\n",
            "/// Forbidden on non-exported fn.\n",
            "fn helper() {}\n",
        );
        let found_nsh = find_comments_in_rust(nsh_exports);
        let nsh_lines: Vec<usize> = found_nsh.iter().map(|(line, _, _)| *line).collect();
        assert_eq!(nsh_lines, vec![8, 11]);
    }

    #[test]
    fn detects_tsx_line_doc_jsx_and_block_comments() {
        let source = concat!(
            "export function App() {\n",
            "  // line comment\n",
            "  const x = 1; /* block comment */\n",
            "  /** jsdoc comment */\n",
            "  /// triple slash not allowed in tsx\n",
            "  return <View>{/* jsx comment */}</View>;\n",
            "}\n",
        );
        let found = find_comments_in_tsx(source);
        let lines: Vec<usize> = found.iter().map(|(line, _, _)| *line).collect();
        assert_eq!(lines, vec![2, 3, 4, 5, 6]);
    }

    #[test]
    fn ignores_tsx_comment_tokens_in_strings_templates_and_regex() {
        let source = concat!(
            "export function demo(a: number, b: number, c: number) {\n",
            "  const dq = \"https://example.com /* not */ \\\" // still\";\n",
            "  const sq = 'http://localhost:8080 /* not */ \\' // still';\n",
            "  const tpl = `https://example.com\n",
            "    // inside template\n",
            "    /* also inside */ \\` // still`;\n",
            "  const re1 = /https?:\\/\\//;\n",
            "  const re2 = /\\/\\*[\\s\\S]*?\\*\\//g;\n",
            "  const re3 = /[/\\*]/;\n",
            "  const ratio = a / b / c;\n",
            "  return <View class=\"https://ok\"><Text>{`${dq}${sq}${tpl}${ratio}`}</Text></View>;\n",
            "}\n",
        );
        assert!(find_comments_in_tsx(source).is_empty());
    }

    #[test]
    fn detects_comments_inside_template_interpolations_with_nested_braces() {
        let source = concat!(
            "const a = `prefix ${/* bad 1 */ name} // not a comment`;\n",
            "const b = `obj ${ { k: 1 } /* bad 2 */ } // still literal`;\n",
            "const c = `nested ${`inner // ok` // bad 3\n",
            "} // still ok`;\n",
        );
        let found = find_comments_in_tsx(source);
        let lines: Vec<usize> = found.iter().map(|(line, _, _)| *line).collect();
        assert_eq!(lines, vec![1, 2, 3]);
    }

    #[test]
    fn workspace_check_flags_rust_and_tsx_and_skips_generated_dirs() {
        let id = NEXT_TEMP_ID.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!(
            "openrose-xtask-nocomments-test-{}-{id}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("crates/core/src")).unwrap();
        fs::create_dir_all(dir.join("desktop/src")).unwrap();
        fs::create_dir_all(dir.join("desktop/.nsh/types")).unwrap();
        fs::create_dir_all(dir.join("desktop/ext/native/src")).unwrap();

        fs::write(dir.join("crates/core/src/lib.rs"), "// rust comment\npub fn x() {}\n").unwrap();
        fs::write(dir.join("desktop/App.tsx"), "export const url = \"https://example.com\";\n").unwrap();
        fs::write(dir.join("desktop/src/view.ts"), "export const a = 1; // ts comment\n").unwrap();
        fs::write(dir.join("desktop/.nsh/types/nsh.d.ts"), "// generated comment\n").unwrap();
        fs::write(
            dir.join("desktop/ext/native/src/lib.rs"),
            "#[native_module]\nmod api {\n    /// allowed\n    pub fn f() {}\n}\n",
        )
        .unwrap();

        let result = check_workspace_comments(&dir);
        let _ = fs::remove_dir_all(&dir);

        let violations = result.unwrap_err();
        let paths: Vec<PathBuf> = violations.into_iter().map(|v| v.path).collect();
        assert_eq!(
            paths,
            vec![
                PathBuf::from("crates/core/src/lib.rs"),
                PathBuf::from("desktop/src/view.ts"),
            ]
        );
    }
}
