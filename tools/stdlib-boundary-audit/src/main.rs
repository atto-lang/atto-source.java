//! Conservative release gates for packaged Oreslang stdlib modules and the Java
//! guest-dispatch boundary. This does NOT replace compiler admission, sandboxing,
//! executable progress tests, or runtime capability checks.
use std::{env, fs, path::Path, process};

const MAX_FILE: u64 = 2 * 1024 * 1024;
const MAX_FILES: usize = 2048;

#[derive(Clone, Debug, PartialEq)]
enum Kind {
    Name,
    Literal,
    Symbol(char),
}
#[derive(Clone, Debug)]
struct Token {
    kind: Kind,
    text: String,
    line: usize,
}
impl Token {
    fn name(&self, name: &str) -> bool {
        self.kind == Kind::Name && self.text == name
    }
    fn symbol(&self, c: char) -> bool {
        self.kind == Kind::Symbol(c)
    }
}

fn lex(s: &str) -> Result<Vec<Token>, String> {
    let b = s.as_bytes();
    let (mut p, mut line, mut out) = (0, 1, Vec::new());
    while p < b.len() {
        let start = p;
        match b[p] {
            b'\n' => {
                line += 1;
                p += 1;
            }
            b' ' | b'\r' | b'\t' => p += 1,
            b'/' if b.get(p + 1) == Some(&b'/') => {
                p += 2;
                while p < b.len() && b[p] != b'\n' {
                    p += 1;
                }
            }
            b'/' if b.get(p + 1) == Some(&b'*') => {
                p += 2;
                let mut depth = 1;
                while p < b.len() && depth > 0 {
                    if b[p..].starts_with(b"/*") {
                        depth += 1;
                        if depth > 64 {
                            return Err(format!("line {line}: comment nesting limit"));
                        }
                        p += 2;
                    } else if b[p..].starts_with(b"*/") {
                        depth -= 1;
                        p += 2;
                    } else {
                        if b[p] == b'\n' {
                            line += 1;
                        }
                        p += 1;
                    }
                }
                if depth > 0 {
                    return Err(format!("line {line}: unterminated comment"));
                }
            }
            b'\'' | b'"' | 96 => {
                let (quote, initial) = (b[p], line);
                p += 1;
                let from = p;
                while p < b.len() && b[p] != quote {
                    if b[p] == b'\\' {
                        p += 1;
                    }
                    if p < b.len() && b[p] == b'\n' {
                        line += 1;
                    }
                    p += 1;
                }
                if p == b.len() {
                    return Err(format!("line {initial}: unterminated literal"));
                }
                let value = s.get(from..p).ok_or("invalid UTF-8 literal boundary")?;
                if quote == 96 && value.contains('$') {
                    return Err(format!(
                        "line {initial}: template expressions require compiler-side review"
                    ));
                }
                out.push(Token {
                    kind: Kind::Literal,
                    text: value.into(),
                    line: initial,
                });
                p += 1;
            }
            c if c.is_ascii_alphabetic() || c == b'_' => {
                p += 1;
                while p < b.len() && (b[p].is_ascii_alphanumeric() || b[p] == b'_') {
                    p += 1;
                }
                out.push(Token {
                    kind: Kind::Name,
                    text: s[start..p].into(),
                    line,
                });
            }
            _ => {
                let c = s[p..].chars().next().ok_or("invalid UTF-8 boundary")?;
                p += c.len_utf8();
                out.push(Token {
                    kind: Kind::Symbol(c),
                    text: c.to_string(),
                    line,
                });
            }
        }
    }
    Ok(out)
}

fn dotted_name(tokens: &[Token], start: usize, parts: &[&str]) -> bool {
    if parts.is_empty() {
        return false;
    }
    let mut index = start;
    for (part_index, part) in parts.iter().enumerate() {
        if !tokens.get(index).is_some_and(|token| token.name(part)) {
            return false;
        }
        index += 1;
        if part_index + 1 < parts.len() {
            if !tokens.get(index).is_some_and(|token| token.symbol('.')) {
                return false;
            }
            index += 1;
        }
    }
    true
}

const FORBIDDEN_BLOCKING_IO: &[&[&str]] = &[
    &["stdio", "print"],
    &["stdio", "println"],
    &["stdio", "stdout", "write"],
    &["stdio", "stdout", "println"],
    &["stdio", "stdout", "log"],
    &["stdio", "stdout", "logList"],
    &["stdio", "stdin", "read_line"],
    &["fs", "read_text"],
    &["fs", "write_text"],
    &["fs", "write_text_atomic"],
    &["fs", "append_text"],
    &["fs", "list_dir"],
    &["fs", "is_dir"],
    &["fs", "is_symlink"],
    &["fs", "exists"],
    &["fs", "remove"],
    &["fs", "mkdir_all"],
    &["File", "read_text"],
    &["File", "write_text"],
    &["File", "write_text_atomic"],
    &["File", "append_text"],
    &["File", "list_dir"],
    &["File", "is_dir"],
    &["File", "is_symlink"],
    &["File", "exists"],
    &["File", "remove"],
    &["File", "mkdir_all"],
    &["network", "connect"],
    &["net", "connect"],
    &["http", "listen"],
    &["http", "handler"],
    &["http", "claim"],
    &["http", "get_text"],
    &["http", "post_text"],
];


const EVAL_FORBIDDEN_CODE: &[&str] = &[
    "context.nativeIo().submit(",
    "context.nativeIo().submitClose(",
    "NativeIo.readText(",
    "NativeIo.writeText(",
    "NativeIo.writeTextAtomic(",
    "NativeIo.appendText(",
    "NativeIo.exists(",
    "NativeIo.listDir(",
    "NativeIo.isDir(",
    "NativeIo.isSymlink(",
    "NativeIo.remove(",
    "NativeIo.createDirectories(",
    "NativeIo.connect(",
    "NativeIo.httpGetText(",
    "NativeIo.httpPostText(",
    "socket.readLine(",
    "socket.writeText(",
    "socket.close(",
    "context.input().readLine(",
    "context.output().print(",
    "context.output().println(",
    "context.output().flush(",
    "context.http()",
    "server.accept(",
    "server.shutdown(",
    "exchange.bodyText(",
    "exchange.readChunk(",
    "exchange.writeBytes(",
    "exchange.respondBytes(",
    "exchange.respond(",
    "exchange.start(",
    "exchange.write(",
    "exchange.finish(",
    "exchange.abort(",
];

const TYPECHECKER_FORBIDDEN_SURFACE: &[&str] = &[
    "\"read_text\"",
    "\"write_text\"",
    "\"write_text_atomic\"",
    "\"append_text\"",
    "\"list_dir\"",
    "\"is_dir\"",
    "\"is_symlink\"",
    "\"exists\"",
    "\"remove\"",
    "\"mkdir_all\"",
    "\"listen\"",
    "\"get_text\"",
    "\"post_text\"",
    "member().equals(\"print\")",
    "member().equals(\"println\")",
    "member().equals(\"read_line\")",
    "member().equals(\"write\")",
    "member().equals(\"log\")",
    "member().equals(\"logList\")",
    "name.name().equals(\"print\")",
];

fn contains_java_unicode_escape(source: &str) -> bool {
    let bytes = source.as_bytes();
    let mut i = 0usize;
    while i + 1 < bytes.len() {
        if bytes[i] != b'\\' || bytes[i + 1] != b'u' {
            i += 1;
            continue;
        }
        let mut j = i + 1;
        while j < bytes.len() && bytes[j] == b'u' {
            j += 1;
        }
        if j + 4 <= bytes.len() && bytes[j..j + 4].iter().all(u8::is_ascii_hexdigit) {
            return true;
        }
        i = j;
    }
    false
}

fn normalize_java(source: &str, strip_literals: bool) -> Result<String, String> {
    // Java translates Unicode escapes before comments and string literals are
    // tokenized. Reject them outright in strict-audit targets so an escape
    // cannot synthesize a newline/identifier and bypass this source gate.
    if contains_java_unicode_escape(source) {
        return Err("Java Unicode escapes are forbidden in strict audit targets".into());
    }
    #[derive(Clone, Copy, PartialEq)]
    enum State {
        Code,
        LineComment,
        BlockComment,
        String(char),
    }

    let chars: Vec<char> = source.chars().collect();
    let mut state = State::Code;
    let mut depth = 0usize;
    let mut escaped = false;
    let mut out = String::with_capacity(source.len());
    let mut i = 0usize;
    while i < chars.len() {
        let c = chars[i];
        let next = chars.get(i + 1).copied();
        match state {
            State::Code if c == '/' && next == Some('/') => {
                state = State::LineComment;
                i += 2;
                continue;
            }
            State::Code if c == '/' && next == Some('*') => {
                state = State::BlockComment;
                depth = 1;
                i += 2;
                continue;
            }
            State::Code if c == '"' || c == '\'' => {
                state = State::String(c);
                escaped = false;
                if !strip_literals {
                    out.push(c);
                }
                i += 1;
                continue;
            }
            State::Code => {
                if !c.is_whitespace() {
                    out.push(c);
                }
                i += 1;
            }
            State::LineComment => {
                if c == '\n' {
                    state = State::Code;
                }
                i += 1;
            }
            State::BlockComment => {
                if c == '/' && next == Some('*') {
                    depth += 1;
                    i += 2;
                } else if c == '*' && next == Some('/') {
                    depth = depth.saturating_sub(1);
                    i += 2;
                    if depth == 0 {
                        state = State::Code;
                    }
                } else {
                    i += 1;
                }
            }
            State::String(quote) => {
                if !strip_literals {
                    out.push(c);
                }
                if escaped {
                    escaped = false;
                } else if c == '\\' {
                    escaped = true;
                } else if c == quote {
                    state = State::Code;
                } else if c == '\n' {
                    return Err("unterminated Java string/character literal".into());
                }
                i += 1;
            }
        }
    }

    match state {
        State::Code | State::LineComment => Ok(out),
        State::BlockComment => Err("unterminated Java block comment".into()),
        State::String(_) => Err("unterminated Java string/character literal".into()),
    }
}

fn reject_needles(path: &Path, source: &str, needles: &[&str], strip_literals: bool) -> Result<(), String> {
    let normalized = normalize_java(source, strip_literals)?;
    let mut found = Vec::new();
    for needle in needles {
        let normalized_needle: String = needle.chars().filter(|c| !c.is_whitespace()).collect();
        if normalized.contains(&normalized_needle) {
            found.push(*needle);
        }
    }
    if found.is_empty() {
        Ok(())
    } else {
        Err(format!(
            "{}: strict nonblocking guest boundary reaches forbidden construct(s): {}",
            path.display(),
            found.join(", ")
        ))
    }
}

fn audit_strict_java_guest_boundary(repo_root: &Path) -> Result<(), String> {
    let eval = repo_root.join("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java");
    let types = repo_root.join("src/main/java/dev/oreslang/types/TypeChecker.java");
    for path in [&eval, &types] {
        let meta = fs::symlink_metadata(path).map_err(|e| format!("{}: {e}", path.display()))?;
        if !meta.is_file() || meta.file_type().is_symlink() || meta.len() > MAX_FILE * 8 {
            return Err(format!("{}: invalid strict-audit target", path.display()));
        }
    }
    let eval_source = fs::read_to_string(&eval).map_err(|e| format!("{}: {e}", eval.display()))?;
    reject_needles(&eval, &eval_source, EVAL_FORBIDDEN_CODE, true)?;
    let type_source = fs::read_to_string(&types).map_err(|e| format!("{}: {e}", types.display()))?;
    reject_needles(&types, &type_source, TYPECHECKER_FORBIDDEN_SURFACE, false)?;
    Ok(())
}

fn reject_blocking_io_aliases(tokens: &[Token]) -> Result<(), String> {
    const ROOTS: &[&str] = &["stdio", "fs", "File", "network", "net", "http"];
    for start in 0..tokens.len() {
        if !ROOTS.iter().any(|root| tokens[start].name(root)) {
            continue;
        }
        let mut index = start + 1;
        while index + 1 < tokens.len() && tokens[index].symbol('.') {
            let member = &tokens[index + 1];
            if member.kind != Kind::Name {
                break;
            }
            if member.text.ends_with("_blocking") || member.text.ends_with("_sync") {
                return Err(format!(
                    "line {}: blocking/synchronous I/O alias '{}' is forbidden in packaged stdlib",
                    member.line, member.text
                ));
            }
            index += 2;
        }
    }
    Ok(())
}

fn inspect(source: &str) -> Result<(), String> {
    let tokens = lex(source)?;
    reject_blocking_io_aliases(&tokens)?;
    for index in 0..tokens.len() {
        for path in FORBIDDEN_BLOCKING_IO {
            if dotted_name(&tokens, index, path) {
                return Err(format!(
                    "line {}: synchronous/blocking stdlib I/O path '{}' is forbidden; use async or continuation-suspending strict I/O",
                    tokens[index].line,
                    path.join(".")
                ));
            }
        }
    }
    for (i, t) in tokens.iter().enumerate() {
        if t.name("java")
            && tokens
                .get(i + 1)
                .is_some_and(|n| n.symbol('{') || n.symbol(':'))
        {
            return Err(format!(
                "line {}: Java host interop forbidden in stdlib",
                t.line
            ));
        }
        if t.name("import") {
            let rest = &tokens[i + 1..];
            let size = rest
                .iter()
                .position(|x| x.symbol(';'))
                .ok_or_else(|| format!("line {}: import without terminator", t.line))?;
            if size > 128 {
                return Err(format!("line {}: oversized import", t.line));
            }
            let statement = &rest[..size];
            for (j, part) in statement.iter().enumerate() {
                if part.name("from") {
                    let src = &statement[j + 1..];
                    if src.is_empty() {
                        return Err(format!("line {}: missing import source", part.line));
                    }
                    let name = src.iter().map(|t| t.text.as_str()).collect::<String>();
                    if !name.starts_with("std/")
                        || name.contains("..")
                        || name.starts_with('/')
                        || name.contains(':')
                        || name.contains('\\')
                    {
                        return Err(format!("line {}: unsafe stdlib import source", part.line));
                    }
                }
            }
        }
    }
    Ok(())
}

fn scan(root: &Path) -> Result<usize, String> {
    if fs::symlink_metadata(root)
        .map_err(|e| e.to_string())?
        .file_type()
        .is_symlink()
    {
        return Err("stdlib root is a symlink".into());
    }
    let mut pending = vec![(root.to_path_buf(), 0)];
    let mut count = 0;
    while let Some((dir, depth)) = pending.pop() {
        if depth > 16 {
            return Err("stdlib nesting exceeds limit".into());
        }
        for entry in fs::read_dir(&dir).map_err(|e| e.to_string())? {
            let path = entry.map_err(|e| e.to_string())?.path();
            let meta = fs::symlink_metadata(&path).map_err(|e| e.to_string())?;
            if meta.file_type().is_symlink() {
                return Err(format!("{}: symlink refused", path.display()));
            }
            if meta.is_dir() {
                pending.push((path, depth + 1));
                continue;
            }
            if !meta.is_file() {
                return Err(format!("{}: unsupported entry", path.display()));
            }
            if path.extension().is_none_or(|e| e != "ores") {
                continue;
            }
            count += 1;
            if count > MAX_FILES || meta.len() > MAX_FILE {
                return Err(format!("{}: resource budget exceeded", path.display()));
            }
            let source = fs::read_to_string(&path).map_err(|e| e.to_string())?;
            inspect(&source).map_err(|e| format!("{}: {e}", path.display()))?;
        }
    }
    if count == 0 {
        return Err("stdlib contains no .ores files".into());
    }
    Ok(count)
}

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() > 2 || args.iter().any(|s| s.starts_with('-')) {
        eprintln!("usage: oreslang-stdlib-boundary-audit [stdlib-directory] [repository-root]");
        process::exit(2);
    }
    let stdlib = args.first().map(String::as_str).unwrap_or("stdlib");
    let repo_root = args.get(1).map(String::as_str).unwrap_or(".");
    let result = scan(Path::new(stdlib))
        .and_then(|n| audit_strict_java_guest_boundary(Path::new(repo_root)).map(|_| n));
    match result {
        Ok(n) => {
            println!("verified {n} native Oreslang stdlib source file(s)");
            println!("STRICT_NONBLOCKING_STDLIB_IO=PASS");
            println!("blocking_stdlib_io_violations=0");
        }
        Err(e) => {
            eprintln!("stdlib boundary audit failed: {e}");
            process::exit(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn normal_module() {
        assert!(
            inspect("define module testing\npub fnc f(): String { return \"java:foo\"; } end")
                .is_ok()
        );
    }
    #[test]
    fn nested_comments() {
        assert!(inspect("// java { }\n/* java { /* nested */ } */ fnc f(): void {}").is_ok());
    }
    #[test]
    fn rejects_java_block() {
        assert!(inspect("do java { System.exit(0); }").is_err());
    }
    #[test]
    fn rejects_java_import() {
        assert!(inspect("import java:java.lang.System;").is_err());
    }
    #[test]
    fn rejects_parent_import() {
        assert!(inspect("import types Foo from '../../other';").is_err());
    }
    #[test]
    fn rejects_external_url() {
        assert!(inspect("import types Foo from 'https://elsewhere';").is_err());
    }
    #[test]
    fn rejects_unclosed_comment() {
        assert!(inspect("/* broken").is_err());
    }
    #[test]
    fn rejects_unclosed_string() {
        assert!(inspect("let s = 'broken").is_err());
    }
    #[test]
    fn rejects_unsafe_template() {
        assert!(inspect("let s = `$x`;").is_err());
    }
    #[test]
    fn rejects_ambient_import() {
        assert!(inspect("import module App from 'app/local';").is_err());
    }
    #[test]
    fn accepts_std_import() {
        assert!(inspect("import types Foo from 'std/core';").is_ok());
    }
    #[test]
    fn accepts_utf8_text() {
        assert!(inspect("let s = 'π';").is_ok());
    }
    #[test]
    fn rejects_sync_stdio_write() {
        assert!(inspect("fnc f(): void { stdio.stdout.write(\"x\"); }").is_err());
    }
    #[test]
    fn rejects_sync_filesystem_read() {
        assert!(inspect("fnc f(): void { fs.read_text(\"x\"); }").is_err());
    }
    #[test]
    fn rejects_sync_network_connect_alias() {
        assert!(inspect("fnc f(): void { net.connect(\"x\", 80); }").is_err());
    }
    #[test]
    fn rejects_legacy_http_transport_controls() {
        assert!(inspect("fnc f(): void { http.handler(\"H\"); }").is_err());
        assert!(inspect("fnc f(): void { http.claim(\"token\"); }").is_err());
    }
    #[test]
    fn accepts_async_io_names() {
        assert!(inspect(
            "fnc f(): void { fs.read_text_async(\"x\"); stdio.stdout.write_async(\"x\"); }"
        )
        .is_ok());
    }
    #[test]
    fn rejects_explicit_blocking_and_sync_io_aliases() {
        assert!(inspect("fnc f(): void { fs.read_text_blocking(\"x\"); }").is_err());
        assert!(inspect("fnc f(): void { network.connect_sync(\"x\", 80); }").is_err());
        assert!(inspect("fnc f(): void { stdio.stdout.write_blocking(\"x\"); }").is_err());
    }
    #[test]
    fn java_normalizer_ignores_comments_and_literals_for_code_edges() {
        let source = r#"
            // context.nativeIo().submit(
            String sample = "context.http()";
            /* NativeIo.readText( */
            strictNonblockingIoFuture("fs.read_text_async");
        "#;
        let normalized = normalize_java(source, true).unwrap();
        assert!(!EVAL_FORBIDDEN_CODE.iter().any(|needle| normalized.contains(*needle)));
    }
    #[test]
    fn java_unicode_escape_preprocessing_is_rejected() {
        assert!(normalize_java(
            r#"// escape before lexing: \\u000a context.nativeIo().submit(() -> work());"#,
            true
        )
        .is_err());
        assert!(normalize_java(
            r#"String value = "\\u0066s.read_text";"#,
            true
        )
        .is_err());
    }

    #[test]
    fn java_code_edge_guard_rejects_blocking_dispatch() {
        let source = "return context.nativeIo().submit(() -> NativeIo.readText(path));";
        assert!(reject_needles(
            Path::new("OresEvalRootNode.java"),
            source,
            EVAL_FORBIDDEN_CODE,
            true
        )
        .is_err());
    }
    #[test]
    fn typechecker_surface_guard_distinguishes_async_names() {
        let async_only = r#"case "read_text_async" -> future;"#;
        assert!(reject_needles(
            Path::new("TypeChecker.java"),
            async_only,
            TYPECHECKER_FORBIDDEN_SURFACE,
            false
        )
        .is_ok());
        let sync = r#"case "read_text" -> string;"#;
        assert!(reject_needles(
            Path::new("TypeChecker.java"),
            sync,
            TYPECHECKER_FORBIDDEN_SURFACE,
            false
        )
        .is_err());
    }

}
