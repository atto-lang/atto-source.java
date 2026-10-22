//! Read-only verification of a private CI carrier's nested source tree.
//! Run before compiling anything. The expected tree must come from separately
//! approved, authenticated source metadata. This program grants NO transfer approval.
use std::{env,fs,path::Path,process::{Command,ExitCode}};

const SUBTREE: &str = "private-oreslang-source";
fn sha40(s: &str) -> bool {
    s.len() == 40 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn git(repo: &Path, args: &[&str]) -> Result<String,String> {
    let res = Command::new("git").arg("-C").arg(repo).args(args)
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .output().map_err(|_| "Git binary unavailable")?;
    if !res.status.success() || res.stdout.len() > 4096 || res.stderr.len() > 4096 {
        return Err("Git object verification failed".into());
    }
    let s = std::str::from_utf8(&res.stdout).map_err(|_| "non-UTF8 Git output")?.trim();
    if s.len() > 1024 || s.contains('\n') { return Err("ambiguous Git output".into()); }
    Ok(s.to_string())
}
fn verify(root: &Path, expected: &str) -> Result<(),String> {
    if !sha40(expected) { return Err("expected source Git tree is not a 40-digit lowercase SHA".into()); }
    // canonicalize() follows a symlink: inspect the caller-provided checkout
    // root first rather than silently accepting a redirected checkout.
    // Check each literal path component before canonicalize. Checking only
    // symlink_metadata(root) can follow a final symlink if root ends in '/'.
    let mut literal = std::path::PathBuf::new();
    for component in root.components() {
        literal.push(component.as_os_str());
        if fs::symlink_metadata(&literal)
            .map_err(|_| "Git checkout path unavailable")?.file_type().is_symlink() {
            return Err("carrier checkout path contains a symlink".into());
        }
    }
    let root_type = fs::symlink_metadata(root)
        .map_err(|_| "Git checkout path unavailable")?.file_type();
    if !root_type.is_dir() || root_type.is_symlink() {
        return Err("carrier checkout root is absent, non-directory or symlinked".into());
    }
    let root = fs::canonicalize(root).map_err(|_| "Git checkout path unavailable")?;
    if !root.is_dir() { return Err("carrier checkout is not a directory".into()); }
    let top = git(&root,&["rev-parse","--show-toplevel"])?;
    let top = fs::canonicalize(&top).map_err(|_| "Git checkout root unavailable")?;
    if top != root { return Err("carrier path is not the checkout root".into()); }
    let path = format!("HEAD:{SUBTREE}");
    let actual = git(&root,&["rev-parse","--verify",&path])?;
    if !sha40(&actual) { return Err("invalid nested source Git object".into()); }
    if git(&root,&["cat-file","-t",&actual])? != "tree" {
        return Err("private source object is not a Git tree".into());
    }
    if actual != expected { return Err("carrier source tree differs from approved source tree".into()); }
    // A matching committed tree does not prove that checked-out files are
    // unchanged. Refuse all staged, unstaged, ignored and untracked changes
    // before any Maven or Graal build executes.
    let source_path = root.join(SUBTREE);
    let source_kind = fs::symlink_metadata(&source_path)
        .map_err(|_| "nested source directory is absent")?.file_type();
    if !source_kind.is_dir() || source_kind.is_symlink() {
        return Err("nested source checkout is not a real directory".into());
    }
    if !git(&root, &["status", "--porcelain=v1",
            "--untracked-files=all", "--ignored=matching", "--", SUBTREE])?.is_empty() {
        return Err("nested source working tree differs from authenticated Git tree".into());
    }
    // Git status hides changes for index entries marked assume-unchanged
    // (lowercase tag) or skip-worktree (S). Refuse both.
    let index = Command::new("git").arg("-C").arg(&root)
        .args(["ls-files", "-v", "--", SUBTREE])
        .env("GIT_TERMINAL_PROMPT", "0")
        .output().map_err(|_| "tracked source index unavailable")?;
    if !index.status.success() || index.stdout.len() > 2_000_000 {
        return Err("tracked source index invalid or exceeds bound".into());
    }
    let tags = std::str::from_utf8(&index.stdout)
        .map_err(|_| "invalid source index encoding")?;
    if tags.lines().any(|line| !line.starts_with("H ")) {
        return Err("source index contains assume-unchanged or skip-worktree entries".into());
    }
    // A matching Git tree and clean status still permit an *approved* source
    // tree to contain tracked symlinks or gitlinks. Neither may be executed in
    // a carrier because the compiler could follow paths outside the subtree.
    // Inspect index modes without printing source paths or file contents.
    let stages = Command::new("git").arg("-C").arg(&root)
        .args(["ls-files", "--stage", "-z", "--", SUBTREE])
        .env("GIT_TERMINAL_PROMPT", "0")
        .output().map_err(|_| "tracked source modes unavailable")?;
    if !stages.status.success() || stages.stdout.len() > 2_000_000 {
        return Err("tracked source modes invalid or exceed bound".into());
    }
    let mut count = 0usize;
    for raw in stages.stdout.split(|byte| *byte == 0).filter(|row| !row.is_empty()) {
        let row = std::str::from_utf8(raw).map_err(|_| "invalid tracked source index encoding")?;
        let (metadata, path) = row.split_once('\t')
            .ok_or("invalid tracked source index record")?;
        let mut fields = metadata.split_ascii_whitespace();
        let (Some(mode), Some(sha), Some(stage), None) =
            (fields.next(), fields.next(), fields.next(), fields.next()) else {
                return Err("invalid tracked source index metadata".into());
            };
        if !matches!(mode, "100644" | "100755") || !sha40(sha)
            || stage != "0" || !path.starts_with("private-oreslang-source/") {
            return Err("tracked source contains an unsupported mode or index stage".into());
        }
        count += 1;
    }
    if count == 0 { return Err("nested source index has no files".into()); }
    Ok(())
}
fn main() -> ExitCode {
    let mut args = env::args().skip(1);
    let (Some(a),Some(repo),Some(b),Some(sha),None) =
        (args.next(),args.next(),args.next(),args.next(),args.next()) else {
        eprintln!("usage: verify-private-source-subtree --repo PATH --expected-tree SHA40");
        return ExitCode::FAILURE;
    };
    if a != "--repo" || b != "--expected-tree" {
        eprintln!("PRIVATE_SOURCE_TREE_REJECTED: unsupported flags");
        return ExitCode::FAILURE;
    }
    match verify(Path::new(&repo),&sha) {
        Ok(()) => { println!("VERIFIED_REVIEWED_SOURCE_TREE"); ExitCode::SUCCESS },
        Err(e) => { eprintln!("PRIVATE_SOURCE_TREE_REJECTED: {e}"); ExitCode::FAILURE }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_lowercase_exact_40_hex_is_admitted() {
        assert!(sha40(&"0".repeat(40)));
        assert!(sha40(&"f".repeat(40)));
        assert!(!sha40(&"f".repeat(39)));
        assert!(!sha40(&"F".repeat(40)));
        assert!(!sha40(&"z".repeat(40)));
    }
    #[test]
    fn absent_checkout_denied_before_source_read() {
        let missing = env::temp_dir().join(format!(
            "oreslang-ci-verifier-no-such-root-{}",std::process::id()));
        assert!(verify(&missing,&"0".repeat(40)).is_err());
    }

    // Synthetic-only Git fixtures; no private Oreslang code or transfer.
    #[cfg(unix)]
    #[test]
    fn valid_tree_admitted_but_linked_root_and_tracked_symlink_denied() {
        use std::os::unix::fs::symlink;
        use std::time::{SystemTime, UNIX_EPOCH};
        let nonce = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let fixture = env::temp_dir().join(format!(
            "oreslang-subtree-guard-{}-{nonce}", std::process::id()));
        let checkout = fixture.join("carrier");
        fs::create_dir_all(checkout.join(SUBTREE)).unwrap();
        let command = |args: &[&str]| {
            let output = Command::new("git").arg("-C").arg(&checkout)
                .args(args)
                .env("GIT_TERMINAL_PROMPT", "0")
                .output().unwrap();
            assert!(output.status.success(), "synthetic git fixture command failed");
            String::from_utf8(output.stdout).unwrap().trim().to_owned()
        };
        command(&["init", "-q"]);
        let input = checkout.join(SUBTREE).join("fixture.ores");
        fs::write(&input, "synthetic fixture only").unwrap();
        command(&["add", "--", SUBTREE]);
        command(&["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
            "commit", "-qm", "synthetic ordinary file"]);
        let source_ref = format!("HEAD:{SUBTREE}");
        let expected = command(&["rev-parse", "--verify", &source_ref]);
        assert!(verify(&checkout, &expected).is_ok());

        let linked_checkout = fixture.join("linked-carrier");
        symlink(&checkout, &linked_checkout).unwrap();
        assert!(verify(&linked_checkout, &expected).is_err(),
            "a symlinked checkout root must never be admitted");
        let linked_with_slash = std::path::PathBuf::from(
            format!("{}/", linked_checkout.display()));
        assert!(verify(&linked_with_slash, &expected).is_err(),
            "a trailing slash must not bypass checkout-root symlink denial");

        fs::remove_file(&input).unwrap();
        symlink(fixture.join("outside-fixture"), &input).unwrap();
        command(&["add", "--", SUBTREE]);
        command(&["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
            "commit", "-qm", "synthetic symlink"]);
        let linked_tree = command(&["rev-parse", "--verify", &source_ref]);
        assert!(verify(&checkout, &linked_tree).is_err(),
            "a tracked symlink in an otherwise clean, matching tree must be denied");
        fs::remove_dir_all(fixture).unwrap();
    }
}
