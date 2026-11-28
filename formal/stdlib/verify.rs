//! Fail-closed source-identity and SMT output gate for a *reviewed abstraction*.
//! This is not a source-to-SMT compiler nor a compiler/runtime refinement proof.
//! If an Oreslang implementation changes, update the abstraction + reviewed Git
//! blob id in one PR, with independent review and executable guest evidence.
use std::{env, process::Command};

const CORE_BLOB: &str = "d2cd9b8e0cd3e3bb3ee6a5f9cebf9ab3312c44f4";
const TRACE_BLOB: &str = "f4ff35b10683880e42e3151b34fc140d52704dba";
const TESTING_BLOB: &str = "60d50aa2b00aadb0d738a41fa821a1443465a918";

fn command(program: &str, args: &[&str]) -> Result<String, String> {
    let output = Command::new(program)
        .args(args)
        .output()
        .map_err(|e| format!("cannot execute {program}: {e}"))?;
    if !output.status.success() {
        return Err(format!(
            "{program} {args:?} exited {}: stdout={} stderr={}",
            output.status,
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        ));
    }
    if !output.stderr.is_empty() {
        return Err(format!(
            "{program} {args:?} emitted unexpected stderr: {}",
            String::from_utf8_lossy(&output.stderr)
        ));
    }
    String::from_utf8(output.stdout).map_err(|e| format!("{program} non-UTF8 output: {e}"))
}

fn check_source(path: &str, expected: &str) -> Result<(), String> {
    let actual = command("git", &["hash-object", "--", path])?;
    if actual.trim() != expected {
        return Err(format!(
            "source changed: {path} blob={} != reviewed={expected}; require model review, updated source identity and guest tests",
            actual.trim()
        ));
    }
    println!("reviewed Oreslang source: {path} @ {expected}");
    Ok(())
}

fn classify(stdout: &str, number_of_proofs: usize, number_of_mutants: usize) -> Result<(), String> {
    let values: Vec<&str> = stdout.lines().map(str::trim).filter(|l| !l.is_empty()).collect();
    let expected: Vec<&str> = std::iter::repeat("unsat").take(number_of_proofs)
        .chain(std::iter::repeat("sat").take(number_of_mutants))
        .collect();
    if values != expected {
        return Err(format!(
            "expected {} UNSAT proof obligations and {} SAT mutation controls; got {values:?}",
            number_of_proofs, number_of_mutants
        ));
    }
    Ok(())
}

fn run_solver(path: &str, proofs: usize, mutants: usize) -> Result<(), String> {
    let stdout = command("z3", &["-smt2", path])?;
    classify(&stdout, proofs, mutants)?;
    println!("checked {path}: {proofs} UNSAT obligations; {mutants} SAT negative controls");
    Ok(())
}

fn verify() -> Result<(), String> {
    if env::args().len() != 1 {
        return Err("usage: stdlib-verify (run from repository root)".into());
    }
    check_source("stdlib/core.ores", CORE_BLOB)?;
    check_source("stdlib/trace.ores", TRACE_BLOB)?;
    check_source("stdlib/testing.ores", TESTING_BLOB)?;
    let version = command("z3", &["-version"])?;
    println!("solver: {}", version.trim());
    run_solver("formal/stdlib/core-int64.smt2", 16, 2)?;
    run_solver("formal/stdlib/trace-buffer-counters.smt2", 9, 3)?;
    run_solver("formal/stdlib/testing-summary.smt2", 9, 3)?;
    Ok(())
}

fn main() {
    if let Err(e) = verify() {
        eprintln!("formal stdlib gate FAILED: {e}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::classify;

    #[test]
    fn accepts_proofs_and_counterexamples_only_in_order() {
        assert!(classify("unsat\nunsat\nsat\n", 2, 1).is_ok());
    }

    #[test]
    fn rejects_missing_or_unknown_or_false_proof() {
        for output in [
            "", "unsat\nsat\n", "unsat\nunknown\nsat\n",
            "sat\nunsat\nsat\n", "unsat\nunsat\nunsat\n",
            "unsat\nunsat\nsat\nsat\n", "error\n",
        ] {
            assert!(classify(output, 2, 1).is_err(), "{output}");
        }
    }

    #[test]
    fn rejects_mutant_counterexample_not_found() {
        assert!(classify("unsat\nunsat\n", 1, 1).is_err());
        assert!(classify("unsat\nunknown\n", 1, 1).is_err());
    }
}
