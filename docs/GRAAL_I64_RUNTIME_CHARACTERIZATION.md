# GraalVM i64 runtime characterization and cross-backend gate

This test is a **behavioral witness**, not a decision about the canonical
Oreslang integer model.

`GraalI64RuntimeCharacterizationTest` executes actual `.ores` programs
inside `org.graalvm.polyglot.Context`. Each expression calls parameterized
guest functions and returns a full-width `long` through `Value.asLong()`.
This avoids both a Java-only arithmetic oracle and the 8-bit range of a
process exit status.

The currently inspected `OresEvalRootNode.numeric` uses unchecked Java
`long` operations for addition, subtraction, multiplication and remainder,
and `negate` uses Java `-long`. The twelve typed observations characterize
the resulting wrapping behavior, including cancellation after an intermediate
overflow; a thirteenth test checks a true guest remainder-by-zero failure.
The separate JS Program IR v2 implementation currently reports checked
overflow. That mismatch **must not be represented as verified parity**.

## Reproducing on an authorized runner

Run `.github/workflows/graal-typed-i64-characterization.yml` with
GraalVM JDK 25, a complete and independently pinned source Git checkout, and
Maven. The workflow runs:

1. `mvn -B -ntp -Dtest=GraalI64RuntimeCharacterizationTest test`
2. The existing Java semantic fixture validator and real Java compiler/guest
   probe for all required cases from `contracts/v1/semantics/cases.json`.
3. Archival of Surefire reports, Java semantic results and retained
   Truffle engine logs.

A Java semantic report must be admitted only after external verification of
the actual GitHub Actions job steps, source Git tree, compiled artifact
identity and suite digest. The self-reported `source_revision` is not a
cryptographic attestation of the built binary.

**Private cross-organization transfer guard:** this repository's
`.github/private-ci-approved-repositories.txt` currently contains no
approved destinations. Do not mirror the Java source to another private
organization until the exact destination has been reviewed and approved on a
protected branch by maintainers and security owners. This workflow therefore
does not perform a cross-organization checkout or use cross-organization
credentials.

## Decision still required

Choose checked or wrapping signed-i64 as the canonical Oreslang arithmetic
model. Then update the Java evaluator, JS Program IR integer-model tag,
LLVM/Wasm lowering and TypeSpec/JSON Schema conformance contracts under a
single versioned policy. All four backends must execute equivalent fixtures
before advertising cross-backend arithmetic parity. Until that happens,
Java numeric observations should be labelled **characterization only**.
