//! Execute actual Java host actor-output tests via Maven; not a source-only model.
//! Rust is the test orchestration language, Java JUnit tests the actual runtime.
use std::{env, path::PathBuf, process::Command};

#[test]
fn actor_output_immediate_poll_executes_on_jvm() {
    let root = PathBuf::from(env!("PWD"));
    assert!(root.join("pom.xml").is_file(), "execute from repository root");
    let run = Command::new("mvn")
        .args(["-B", "-ntp", "-Dtest=ActorOutputStreamTest",
               "-DfailIfNoTests=true", "test"])
        .current_dir(&root)
        .output()
        .expect("invoke Maven to compile and execute real Java actor runtime tests");
    assert!(run.status.success(),
        "Maven actor output tests failed: status {:?}\nstdout:\n{}\nstderr:\n{}",
        run.status.code(),
        String::from_utf8_lossy(&run.stdout),
        String::from_utf8_lossy(&run.stderr));
    let stdout = String::from_utf8_lossy(&run.stdout);
    assert!(stdout.contains("ActorOutputStreamTest"),
        "no proof of actual ActorOutputStreamTest execution");
}
