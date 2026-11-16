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
    // Do not confuse a successful Maven invocation or a class name printed
    // in the logs with real JUnit execution. Require a fresh Surefire XML
    // receipt, nonzero tests, zero skips/failures, and named regressions.
    let stdout = String::from_utf8_lossy(&run.stdout);
    assert!(stdout.contains("ActorOutputStreamTest"),
        "Maven output did not identify the selected actor test suite");
    let report = root.join(
        "target/surefire-reports/TEST-dev.oreslang.runtime.ActorOutputStreamTest.xml");
    let xml = std::fs::read_to_string(&report).unwrap_or_else(|failure| {
        panic!("missing executed JUnit receipt at {}: {failure}", report.display())
    });
    let start = xml.find("<testsuite ").expect("missing XML testsuite root");
    let header_end = xml[start..].find('>').expect("malformed XML testsuite tag") + start;
    let header = &xml[start..header_end];
    fn count(header: &str, attribute: &str) -> usize {
        let key = format!("{attribute}=\""); 
        let tail = header.split(&key).nth(1)
            .unwrap_or_else(|| panic!("missing {attribute} in Surefire testsuite"));
        tail.split('"').next().expect("malformed Surefire attribute")
            .parse::<usize>().expect("non-numeric Surefire count")
    }
    assert!(count(header, "tests") >= 8, "expected all eight actor output tests");
    for attribute in ["errors", "failures", "skipped"] {
        assert_eq!(count(header, attribute), 0,
            "the actual JUnit suite must not skip or fail tests: {attribute}");
    }
    for required in [
        "failedImmediateOutputPollDoesNotRetainDrainedStream",
        "immediatePollDrainsBufferedOutputBeforeSurfacingActorFailure",
        "immediatePollDoesNotStealOutputFromRegisteredAsyncReader",
    ] {
        assert!(xml.contains(&format!("name=\"{required}\"")),
            "missing executed actor-output regression: {required}");
    }
}
