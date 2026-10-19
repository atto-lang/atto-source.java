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
and `negate` uses Java `-long`. The sixteen typed observations characterize
the resulting wrapping behavior, including cancellation after an intermediate
overflow; the divisor-zero test exercises two guest expressions and rejects unrelated parser, host, or startup errors.
The separate JS Program IR v2 implementation currently reports checked
overflow. That mismatch **must not be represented as verified parity**.

## Reproducing on an authorized runner

The reviewed workflow `.github/workflows/graal-typed-i64-characterization.yml`
is push-only and source-repository-only. It refuses execution until an
independent administrator verifies the protected source ref, source repository
identity/privacy, exact reviewed commit and tree, and a disposable source-local
runner labelled `oreslang-private-ephemeral`. The independently reviewed
repository variables `ORESLANG_I64_PRIVATE_RUNNER`,
`ORESLANG_I64_APPROVED_SHA`, and `ORESLANG_I64_APPROVED_TREE` must match the
approved push; neither a private external carrier nor an unprotected PR ref is
sufficient. The actual authorized GraalVM JDK 25 workflow runs:

1. `mvn -B -ntp -Dtest=GraalI64RuntimeCharacterizationTest test`
2. The existing Java semantic fixture validator and real Java compiler/guest
   probe for all required cases from `contracts/v1/semantics/cases.json`.
3. A source-local adversarial regression suite with an enforced minimum
   of 33 executed tests and no failures/skips/expected failures.
4. `scripts/ci/verify_graal_i64_evidence.py`: fail-closed receipt admission
   for at least 17 actual JUnit cases, all 15 canonical semantic fixture IDs,
   their exact expected full-width-independent guest exit codes or diagnostic
   classes, the independently recomputed canonical suite SHA-256, and approved
   source commit/tree identity. Duplicate JSON keys, NaN, symlinked inputs,
   pending cases, extra or missing cases, and forged success labels are rejected.
   The Surefire XML must identify the exact `GraalI64RuntimeCharacterizationTest`
   class and all 17 reviewed test names; arbitrary passing JUnit records are
   rejected.
5. A bounded 7-day artifact containing only the approved Surefire XML and
   semantic JSON report, uploaded only after all required tests pass.

An independent source-free Java/GraalVM **host arithmetic** reference can
exercise the same expected signed-long values in an alternative private CI
organization, without copying Oreslang source. Such a run is a toolchain
characterization only: it is **not** Oreslang guest execution or parity proof.
The source-local runner preflight rejects inherited Git/Python/Maven launch
injection and denies Maven `.mvn` configuration overrides not present in the
approved source tree. These checks require a reviewed, disposable runner and
cannot replace runner isolation, independent branch protection, or signed
provenance.
The Python JSON Schema dependency is top-level pinned but its transitive
dependency graph remains subject to supply-chain review before certification.

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
