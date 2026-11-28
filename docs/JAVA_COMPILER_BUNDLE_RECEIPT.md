# Java exact-source compiler bundle evidence (draft)

**BUILD NOT VERIFIED ON GITHUB.** This is local self-reported compiler evidence, not independently attested source-to-binary provenance or real Oreslang ML guest conformance.

## Workflow and security boundary

The source-organization-only workflow is .github/workflows/java-compiler-bundle-evidence.yml. It runs only on pushes to integration/java-exact-compiler-receipt-20261018 with contents:read permission, no deployment secrets and no pull-request-triggered source builds.

- Checkout is pinned to the event's immutable Git commit SHA.
- GraalVM 25.3 / Java 25 and Maven verify compile the Java/Truffle source, including the native-thread carrier.
- A JVM bundle is assembled outside the Git checkout with the Maven JAR (compiled OresMain.class), all runtime-scope dependency JARs, native/liboresthread.so, and a module-path launcher.
- scripts/verify_compiler_build.py checks the exact source commit/tree/root and clean Git index, then hashes every bundled component. Neither the JAR nor its dependencies may be symlinks/hardlinks.
- The verifier requires a real build-info response consistent with the JVM execution mode, a successful positive check of examples/together.ores, and a formatted source-specific negative diagnostic for an invalid temporary .ores file.
- Source and bundled component identities are rechecked after these probes; the second invocation uses --verify to recompute and compare the entire original receipt.
- Java, Maven and Native Image version outputs are written to a bounded toolchain report; its SHA-256 and text are included in the receipt. These are reported versions, not cryptographically signed toolchain attestations.
- Both checkout and artifact-upload actions are pinned to exact upstream commits. Only the JSON evidence receipt is uploaded for one day; no Oreslang compiler JAR, source tree, or runtime library is uploaded to another organization.

## Bundle and receipt

The expected bundle includes exactly these paths:

    launcher
    oreslang-source.jar
    lib/*.jar               (at least one)
    native/liboresthread.so  (required on Linux)

Receipt schema: oreslang-java-compiler-local-evidence/v1. It records source_commit, source_tree, mode, launcher_build_info, all relative file SHA-256 hashes and sizes, toolchain_report, and positive/negative compiler checks. Fields independent_provenance_attested and guest_ml_executed are deliberately false even if all local checks succeed. It is not a release approval or an independently verified reproducible build.

Only JVM/JIT mode is built in this workflow. AOT/hybrid Native Image release evidence requires separate builds and reviews.

## Local adversarial tests

    python3 -m unittest discover -s tests -p test_verify_compiler_build.py -v

18/18 local synthetic tests passed on October 18, 2026 against exact Git-blob-matched files. The tests use disposable local Git repositories, synthetic JARs, and launcher doubles; these do not demonstrate real Java compilation or guest execution.

Negative cases include wrong commit/tree, dirty/masked Git index, missing/symlinked dependency, missing launcher class, unsupported mode/capability, duplicate JSON keys, accepting malformed source, altered dependency hashes after the receipt is written, unexpected extra bundle content, missing toolchain report and toolchain-report drift.

## Outstanding trust and execution gates

Before any release promotion: restore stepful source-org runners (Java issue #963); independently review the source checkout, Maven dependencies, JDK/GraalVM toolchain, protected branch/workflow and source-to-binary reproducibility; execute actual JVM and Native Image conformance; and then run ML Conformance draft PR #7's six genuine pinned Tensor/Autograd/ML .ores guest programs, numerical negative tests and Java/LLVM parity tests.

Open private-source incidents:
- https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22
- https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/77

The pending private-CI dispatcher review is https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1217. No alternative-organization private-source mirror is authorized by this draft, even when that other organization has available GitHub Actions minutes.
