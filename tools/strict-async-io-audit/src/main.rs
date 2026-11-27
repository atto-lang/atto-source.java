use std::{env, fs, path::{Path, PathBuf}, process};

const LEGACY_SUFFIXES: &[&str] = &[
    "_async", "_sync", "_blocking",
];

fn read(path: &Path) -> Result<String, String> {
    fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))
}

fn slice_between<'a>(source: &'a str, start: &str, end: &str, label: &str) -> Result<&'a str, String> {
    let from = source.find(start).ok_or_else(|| format!("{label}: missing start marker"))?;
    let tail = &source[from..];
    let to = tail.find(end).ok_or_else(|| format!("{label}: missing end marker"))?;
    Ok(&tail[..to])
}

fn reject_contains(label: &str, source: &str, needles: &[&str]) -> Result<(), String> {
    let found: Vec<&str> = needles.iter().copied().filter(|n| source.contains(n)).collect();
    if found.is_empty() {
        Ok(())
    } else {
        Err(format!("{label}: forbidden construct(s): {}", found.join(", ")))
    }
}

fn audit_java(root: &Path) -> Result<(), String> {
    let eval_path = root.join("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java");
    let type_path = root.join("src/main/java/dev/oreslang/types/TypeChecker.java");
    let eval = read(&eval_path)?;
    let types = read(&type_path)?;

    // Restrict the executable-edge audit to the guest member-dispatch region.
    // Quarantined migration facades may still contain blocking host calls, but
    // no strict core/std member may reach them.
    let dispatch = slice_between(
        &eval,
        "if (receiver instanceof StdioFacade stdio)",
        "if (receiver instanceof EnvFacade environment)",
        "OresEvalRootNode guest I/O dispatch",
    )?;

    reject_contains("guest I/O dispatch", dispatch, &[
        "context.nativeIo().submit(",
        "context.nativeIo().submitClose(",
        "fs::readText",
        "fs::writeText",
        "fs::writeTextAtomic",
        "fs::appendText",
        "fs::listDir",
        "fs::isDir",
        "fs::isSymlink",
        "fs::exists",
        "fs::remove",
        "fs::mkdirAll",
        "network::connect",
        "http::getText",
        "http::postText",
        "socket.readLine(",
        "socket.writeText(",
        "socket.close(",
        "context.http().listen(",
        "context.http().handler(",
        "context.http().claim(",
    ])?;

    for suffix in LEGACY_SUFFIXES {
        if dispatch.contains(suffix) {
            return Err(format!("guest I/O dispatch contains legacy core/std suffix {suffix}"));
        }
    }

    for required in [
        "strictNonblockingIoFuture("fs." + name)",
        "strictNonblockingIoFuture("network.connect")",
        "strictNonblockingIoFuture("network.socket." + name)",
        "strictNonblockingIoFuture("http.listen")",
        "strictNonblockingIoFuture("http." + name)",
        "strictNonblockingIoFuture("stdio.out." + name)",
        "strictNonblockingIoFuture("stdio.stdin.read_line")",
    ] {
        if !dispatch.contains(required) {
            return Err(format!("guest I/O dispatch missing strict fail-closed edge: {required}"));
        }
    }

    // Production typechecking must expose canonical names only. The legacy
    // spellings are rejected by falling through the strict namespace guard.
    for legacy in [
        ""read_text_async"", ""write_text_async"", ""append_text_async"",
        ""exists_async"", ""remove_async"", ""mkdir_all_async"",
        ""connect_async"", ""read_line_async"", ""close_async"",
        ""get_text_async"", ""post_text_async"", ""listen_async"",
        ""read_text_sync"", ""read_text_blocking"",
    ] {
        if types.contains(legacy) {
            return Err(format!("TypeChecker still contains legacy I/O spelling {legacy}"));
        }
    }

    for canonical in [
        "case "read_text" ->",
        "case "list_dir" ->",
        "case "is_dir", "is_symlink", "exists" ->",
        "case "write_text", "write_text_atomic", "append_text" ->",
        "case "connect"",
        "case "read_line" ->",
        "case "get_text" ->",
        "case "post_text" ->",
        "case "listen" ->",
    ] {
        if !types.contains(canonical) {
            return Err(format!("TypeChecker missing canonical async-default I/O spelling {canonical}"));
        }
    }

    Ok(())
}

fn walk_ores(dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), String> {
    if !dir.exists() {
        return Ok(());
    }
    for entry in fs::read_dir(dir).map_err(|e| format!("{}: {e}", dir.display()))? {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();
        let meta = fs::symlink_metadata(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        if meta.file_type().is_symlink() {
            return Err(format!("refusing symlink in packaged stdlib: {}", path.display()));
        }
        if meta.is_dir() {
            walk_ores(&path, out)?;
        } else if path.extension().and_then(|x| x.to_str()) == Some("ores") {
            out.push(path);
        }
    }
    Ok(())
}

fn audit_stdlib(root: &Path) -> Result<(), String> {
    let mut files = Vec::new();
    walk_ores(&root.join("stdlib"), &mut files)?;
    if files.is_empty() {
        return Err("no packaged stdlib .ores files found".into());
    }
    files.sort();
    for path in files {
        let source = read(&path)?;
        for suffix in LEGACY_SUFFIXES {
            if source.contains(suffix) {
                return Err(format!("{}: packaged stdlib contains legacy I/O suffix {suffix}", path.display()));
            }
        }
        for host in ["java.", "javax.", "jdk.", "sun."] {
            if source.contains(host) {
                return Err(format!("{}: packaged stdlib contains host interop marker {host}", path.display()));
            }
        }
    }
    Ok(())
}

fn run(root: &Path) -> Result<(), String> {
    audit_java(root)?;
    audit_stdlib(root)?;
    Ok(())
}

fn main() {
    let root = env::args().nth(1).map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    if let Err(error) = run(&root) {
        eprintln!("strict async-I/O audit failed: {error}");
        process::exit(1);
    }
    println!("STRICT_ASYNC_IO_AUDIT_OK");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slice_rejects_worker_offload_and_legacy_suffix() {
        let bad = r#"
            if (receiver instanceof StdioFacade stdio) {
                context.nativeIo().submit(context, p, "fs.read_text_async", work);
            }
            if (receiver instanceof EnvFacade environment) {
        "#;
        let dispatch = slice_between(
            bad,
            "if (receiver instanceof StdioFacade stdio)",
            "if (receiver instanceof EnvFacade environment)",
            "synthetic",
        ).unwrap();
        assert!(reject_contains("synthetic", dispatch, &["context.nativeIo().submit("]).is_err());
        assert!(LEGACY_SUFFIXES.iter().any(|suffix| dispatch.contains(suffix)));
    }

    #[test]
    fn canonical_fail_closed_slice_is_accepted() {
        let ok = r#"
            if (receiver instanceof StdioFacade stdio) {
                strictNonblockingIoFuture("stdio.out." + name);
                strictNonblockingIoFuture("stdio.stdin.read_line");
                strictNonblockingIoFuture("fs." + name);
                strictNonblockingIoFuture("network.connect");
                strictNonblockingIoFuture("network.socket." + name);
                strictNonblockingIoFuture("http.listen");
                strictNonblockingIoFuture("http." + name);
            }
            if (receiver instanceof EnvFacade environment) {
        "#;
        let dispatch = slice_between(
            ok,
            "if (receiver instanceof StdioFacade stdio)",
            "if (receiver instanceof EnvFacade environment)",
            "synthetic",
        ).unwrap();
        assert!(reject_contains("synthetic", dispatch, &["context.nativeIo().submit("]).is_ok());
        assert!(!LEGACY_SUFFIXES.iter().any(|suffix| dispatch.contains(suffix)));
    }
}
