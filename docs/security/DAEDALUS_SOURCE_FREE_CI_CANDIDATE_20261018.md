# Daedalus private CI carrier: source-free candidate assessment (2026-10-18)

**Status: NON-AUTHORIZING. No Oreslang source transfer has been approved.**

## Candidate and observed evidence

- Candidate: `daedalus-fab/daedalus-fab-e2e`.
- GitHub repository ID: `1311678902` (the exact ID must be pinned in any future reviewed authorization ledger).
- API evidence at assessment: `private=true`, `visibility=private`, `archived=false`, `disabled=false`, `allow_forking=false`.
- Hosted Ubuntu 24 runner really executed a source-free capacity probe:
  https://github.com/daedalus-fab/daedalus-fab-e2e/actions/runs/39176858740
- Source-free Java/GraalVM/LLVM toolchain canary:
  https://github.com/daedalus-fab/daedalus-fab-e2e/actions/workflows/oreslang-zero-source-toolchain-canary.yml
  (independent toolchain verification; results must be checked on exact run)
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
