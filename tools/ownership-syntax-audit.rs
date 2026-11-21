//! Read-only Oreslang canonical ownership-spelling audit.
//! Standalone Rust; no crates, credentials, network calls or Python required.
//! Never prints source lines, only paths and line numbers.
use std::env;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

const NEGATIVE_TEST_FILE: &str = "CanonicalMutableBorrowParserTest.java";
const NEGATIVE_MARKER: &str = "ORESLANG_EXPECT_INVALID_LEGACY_BORROW";
const EXTENSIONS: &[&str] = &[
    "ores", "java", "md", "ebnf", "yml", "yaml", "sh", "rs",
    "py", "toml", "json", "js", "mjs", "c", "h", "xml"
];

fn ident(byte: u8) -> bool {
    byte.is_ascii_alphanumeric() || byte == b'_'
}

fn word(bytes: &[u8], i: &mut usize, expected: &[u8]) -> bool {
    let end = *i + expected.len();
    if end > bytes.len() || &bytes[*i..end] != expected
        || (end < bytes.len() && ident(bytes[end])) {
        return false;
    }
    *i = end;
    true
}

fn spacing(bytes: &[u8], i: &mut usize) -> bool {
    let start = *i;
    while *i < bytes.len() && bytes[*i].is_ascii_whitespace() {
        *i += 1;
    }
    *i > start
}

/// Locate exact legacy token sequences across arbitrary ASCII whitespace.
/// Each result points to the line on which 'rt' begins.
fn legacy_lines(input: &str) -> Vec<usize> {
    let bytes = input.as_bytes();
    let mut output = Vec::new();
    for start in 0..bytes.len() {
        if bytes[start] != b'r' || (start != 0 && ident(bytes[start - 1])) {
            continue;
        }
        let mut cursor = start;
        if word(bytes, &mut cursor, b"rt")
            && spacing(bytes, &mut cursor)
            && word(bytes, &mut cursor, b"borrow")
            && spacing(bytes, &mut cursor)
            && word(bytes, &mut cursor, b"mut") {
            output.push(1 + bytes[..start].iter().filter(|b| **b == b'\n').count());
        }
    }
    output
}

fn is_explicit_negative_fixture(path: &Path, text: &str, line: usize) -> bool {
    if path.file_name().and_then(|p| p.to_str()) != Some(NEGATIVE_TEST_FILE) {
        return false;
    }
    // Do not exempt entire files: only the exact marked negative-test line.
    text.lines().nth(line.saturating_sub(1))
        .is_some_and(|source_line| source_line.contains(NEGATIVE_MARKER))
}

fn is_source(path: &Path) -> bool {
    path.extension().and_then(|x| x.to_str())
        .is_some_and(|ext| EXTENSIONS.contains(&ext))
}

fn scan(path: &Path, findings: &mut Vec<(PathBuf, usize)>, depth: u32) -> io::Result<()> {
    if depth > 64 {
        return Err(io::Error::other("directory nesting exceeds 64 levels"));
    }
    let kind = fs::symlink_metadata(path)?.file_type();
    if kind.is_symlink() {
        // A source auditor cannot silently skip paths that might hold code.
        return Err(io::Error::other("symlink encountered in source audit scope"));
    }
    if kind.is_dir() {
        if path.file_name().and_then(|x| x.to_str())
            .is_some_and(|name| matches!(name, ".git" | "target" | "node_modules")) {
            return Ok(());
        }
        for entry in fs::read_dir(path)? {
            scan(&entry?.path(), findings, depth + 1)?;
        }
    } else if kind.is_file() && is_source(path) {
        let text = fs::read_to_string(path)?;
        for line in legacy_lines(&text) {
            if !is_explicit_negative_fixture(path, &text, line) {
                findings.push((path.to_path_buf(), line));
            }
        }
    }
    Ok(())
}

fn run() -> Result<i32, String> {
    let mut strict = false;
    let mut roots = Vec::new();
    for arg in env::args().skip(1) {
        match arg.as_str() {
            "--strict" => strict = true,
            "--report" => strict = false,
            "--help" | "-h" => {
                println!("usage: ownership-syntax-audit [--strict|--report] ROOT...");
                return Ok(0);
            }
            _ if arg.starts_with('-') => return Err(format!("unknown option: {arg}")),
            _ => roots.push(PathBuf::from(arg)),
        }
    }
    if roots.is_empty() {
        roots = ["src", "stdlib", "examples", "docs", "contracts", "formal",
            ".github", ".oreslang", "scripts", "tests", "tools", "README.md"]
            .iter().map(PathBuf::from).collect();
    }
    if roots.is_empty() {
        return Err("no directories to audit; pass an explicit ROOT".into());
    }
    let mut findings = Vec::new();
    for root in roots {
        scan(&root, &mut findings, 0)
            .map_err(|err| format!("audit failed at {}: {err}", root.display()))?;
    }
    findings.sort();
    for (path, line) in &findings {
        println!("LEGACY_MUT_BORROW {}:{}", path.display(), line);
    }
    println!("AUDITED_LEGACY_MUT_BORROW_COUNT={}", findings.len());
    Ok(if strict && !findings.is_empty() { 1 } else { 0 })
}

fn main() {
    match run() {
        Ok(status) => std::process::exit(status),
        Err(reason) => {
            eprintln!("ownership syntax audit refused: {reason}");
            std::process::exit(2);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catches_legacy_source_even_in_java_text_blocks() {
        let legacy = ["rt", "borrow", "mut"].join(" ");
        assert_eq!(legacy_lines(&format!("fnc f() {{ {legacy} owner; }}")), vec![1]);
        assert_eq!(legacy_lines("prefix\n  rt\tborrow\n mut owner"), vec![2]);
    }

    #[test]
    fn canonical_and_identifier_substrings_do_not_match() {
        assert!(legacy_lines("rt mut borrow owner").is_empty());
        assert!(legacy_lines("prtrt borrow mut x").is_empty());
        assert!(legacy_lines("rt borrow mutable").is_empty());
        assert!(legacy_lines("rt borrow_owner mut").is_empty());
    }

    #[test]
    fn missing_audit_root_refuses_success() {
        let nonexistent = std::env::temp_dir().join(format!(
            "oreslang-audit-absent-root-{}", std::process::id()));
        assert!(scan(&nonexistent, &mut Vec::new(), 0).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn symlink_in_audit_scope_fails_closed() {
        let link = std::env::temp_dir().join(format!(
            "oreslang-audit-symlink-probe-{}", std::process::id()));
        std::os::unix::fs::symlink(std::env::temp_dir(), &link).unwrap();
        assert!(scan(&link, &mut Vec::new(), 0).is_err());
        std::fs::remove_file(&link).unwrap();
    }

    #[test]
    fn negative_exemption_is_file_and_line_scoped() {
        let legacy = ["rt", "borrow", "mut"].join(" ");
        let source = format!("{legacy} x; // ORESLANG_EXPECT_INVALID_LEGACY_BORROW\\n{legacy} y;");
        let expected_path = Path::new("src/test/java/dev/oreslang/CanonicalMutableBorrowParserTest.java");
        assert!(is_explicit_negative_fixture(expected_path, &source, 1));
        assert!(!is_explicit_negative_fixture(expected_path, &source, 2));
        assert!(!is_explicit_negative_fixture(Path::new("src/Parser.java"), &source, 1));
    }
}
