# Daedalus private CI carrier: source-free candidate assessment (2026-10-18)

**Status: NON-AUTHORIZING. No Oreslang source transfer has been approved.**

## Candidate and observed evidence

- Candidate: `daedalus-fab/daedalus-fab-e2e`.
- GitHub repository ID: `1311678902` (the exact ID must be pinned in any future reviewed authorization ledger).
- API evidence at assessment: `private=true`, `visibility=private`, `archived=false`, `disabled=false`, `allow_forking=false`.
- Hosted Ubuntu 24 runner really executed a source-free capacity probe:
  https://github.com/daedalus-fab/daedalus-fab-e2e/actions/runs/39176858740
- **Source-free JDK25/GraalVM/LLVM toolchain canary PASSED with executed job steps:**
  https://github.com/daedalus-fab/daedalus-fab-e2e/actions/runs/39177869748
  at exact carrier head `bdebc0e37125c6ac1d97c471b07cd9e1855141c7`.
  - GraalVM `javac 25.0.4`, Maven `3.10.0`, Native Image `25.0.4`;
    synthetic Java returned 42 on the JVM and after actual Native Image compilation.
  - Clang `18.1.3` generated and executed a synthetic LLVM IR binary;
    CMake/CTest executed a synthetic native test (`1/1` passing).
  - Verified actual job logs contain `GRAALVM_JDK25_JAVA_AND_NATIVE_IMAGE_EXECUTED=1`
    and `LLVM_CLANG_CMAKE_CTEST_EXECUTED=1`.
  - Follow-up **source-free**, SHA-pinned Node24-compatible action and JUnit5:
    https://github.com/daedalus-fab/daedalus-fab-e2e/actions/runs/39178968116,
    exact carrier head `3fc2c8a5048abfe27c1a147c0a7a557abbac3aa5`.
    Both hosted jobs passed with executed steps. Maven/Surefire ran 2 synthetic
    JUnit 5 tests (zero failures), JVM and native-image binaries returned 42,
    LLVM/Clang compiled and ran IR, and CTest passed 1/1. Logs include
    `MAVEN_JUNIT5_CANARY_EXECUTED=2` and
    `GRAALVM_JDK25_JAVA_AND_NATIVE_IMAGE_EXECUTED=1`, with no Node20
    deprecation warning. `actions/setup-java` is pinned to exact SHA
    `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` (v6).
  - **Not a compiler qualification:** no Oreslang source, Oreslang JUnit
    compiler suite, or native Oreslang CTest fixtures were transferred
    or executed.
- Source repository: `ores-truffle-oreslang/oreslang-source.java`, ID `1400792274`.
- Target draft source PRs: Java #1406, LLVM #654. Their source heads, trees and authorization receipts are distinct.
- At assessment, `main` in the Java and LLVM source repositories is **unprotected**. Their source-org Actions jobs fail before recording steps.

## Canonical policy conflict; do not merge an allowlist grant

The current source `main` uses a legacy name-only destination list and keeps it empty. The more complete **draft** #1217 review-pinned security design uses an `owner/repo numeric-id` list and separate immutable source snapshot approvals. Its implemented receipt, source dispatcher, carrier workflow, and incident reviews are currently pinned to **Sagitta**, not Daedalus. A Daedalus name-only allowlist line in an independent PR cannot safely enable that architecture.

This candidate assessment deliberately **does not alter** `.github/private-ci-approved-repositories.txt`. Destination selection and authorization are separate decisions.

## Required admission work before any private source Git object write

1. Obtain a genuinely independent reviewer permitted on the source and Daedalus destination, with reviewed immutable numeric IDs. Operator self-approval is insufficient.
2. Reconcile the destination against the **canonical #1217** review-pinned dispatcher, its schema-v3 receipt, independently approved destination PR, source-snapshot ledger and carrier workflow. If Daedalus is selected, modify and independently review all corresponding source/carrier identities, approved exact heads, checks, and authorization logic; do not merely substitute a repository slug.
3. Require reviewed destination `owner/repo 1311678902` and separately approved immutable `source-commit source-root-tree 1311678902` for each source head. Do not use mutable refs or untrusted PR checkout policy as authority.
4. Ensure both outstanding incident gates and their independently bound resume markers are satisfied before unfreezing source transfer. See source issues #904, #962 and #832, and the carrier/conformance incidents cited by #1217.
5. Attest the exact reviewed source dispatcher and active carrier workflows by Git blob SHA. The carrier must check out only the authorized immutable payload commit, after the non-triggering authorization ref exists, and must recheck the nested source tree SHA and provenance.
6. Test the full admission path with source-free adversarial fixtures first: wrong visibility, stale or wrong numeric ID, source/destination branch mutation, missing reviewer, revoked incident approval, altered source-tree SHA, parent/diff poisoning and workflow tampering. Any denial must write zero private source bytes.
7. Only then authorize an isolated **private** source-transfer branch, revalidate before every write, and execute JDK25/JUnit/GraalVM Native Image and LLVM CMake/CTest with real job steps, logs and exact commit/tree attestations. Treat any zero-step failure as NOT_EXECUTED.

This document does not amend CODEOWNERS, security policy, the canonical independent-reviewer configuration, or any allowlist. It does not request or grant source export.
