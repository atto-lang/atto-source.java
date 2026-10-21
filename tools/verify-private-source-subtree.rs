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
}
