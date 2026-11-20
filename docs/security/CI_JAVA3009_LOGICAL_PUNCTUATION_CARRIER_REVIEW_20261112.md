# Review-only: authorized exact-tree carrier for Java logical punctuation PR #3009

> **NO SOURCE TRANSFER AUTHORIZED BY THIS DOCUMENT.** Do not mirror even a subset of private Java source before independent security review and effective source-main policy (or exact V3 independent approval attestation). A user request, private destination, and a passing source-free Actions canary alone are not sufficient authorization.

## Immutable review target (2026-11-13 UTC)

| Binding | Value |
| --- | --- |
| Source repository | `ores-truffle-oreslang/oreslang-source.java` |
| Source repository numeric ID | `1400792274` |
| Pull request | [#3009](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/3009) |
| Exact source head | `55af759e513be013e14789f112761bc6ff52139f` |
| Exact Git root tree | `8d5376be717e2d329d46ea8acae3ec15691d1012` |
| Proposed PRIVATE carrier | `beamscale/bmscl-compiler` |
| Destination immutable numeric ID | `1383937120` |
| Source-free budget/runner canary | [run 43333973516](https://github.com/beamscale/bmscl-compiler/actions/runs/43333973516): SUCCESS, three executed steps, but **NOT Java compiler evidence** |

The exact PR head/tree **must be rechecked immediately before any future source read and upload**; changed refs invalidate this plan, and require a new approved contract. Neither `Java #3025` (a review-only actor-PR proposal) nor its test workflow authorizes this operator PR.

## Requested independent approval and carrier contract

1. Independent `ORESoftware`/security reviewer, **not author/publisher**, approves a narrowly scoped source-transfer agreement. Verify the actually effective source `.github/private-ci-approved-repositories.txt` on protected main and `.github/private-ci-carrier-contracts.json`. As of this proposal, the candidate is marked proposed; there is NO scoped contract for PR #3009.
2. Confirm the destination numeric ID, PRIVATE/active status, least-privileged access, restricted readership, cleanup/retention and the exact protected destination base SHA.
3. Independently review and pin the destination workflow file **Git blob SHA**, minimal contents-read permissions, no source checkout tokens, no PAT/GH_TOKEN secrets, no untrusted PR-controlled workflow permissions, no arbitrary network exfiltration, and a dedicated test-plan SHA bound to PR #3009 only.
4. Require a separately reviewed source-main policy/contract activation or a verifiable V3 independent exact-head authorization receipt **before** mirroring any Java blob. Comments and a green canary alone never authorize transfer.
5. Publisher must fail closed on wrong source/destination IDs, changed visibility, stale head/tree, wrong carrier workflow/test-plan SHA, attempted extra source directories, public/archived destination, missing approval, or destination write failure. Offline negative mutation tests must show **zero private source writes** in each rejected case.

## Authorized *future* execution (only after all gates above)

1. Mirror the **entire** audited source Git tree with the first-party GitHub connector into a private temporary branch. Preserve file bytes, nested trees, and executable bits. Never use the destination `GITHUB_TOKEN` to check out the source repository; its own token reads only its local mirror.
2. Compute/read back each destination blob and destination mirrored root Git tree; demand exact root `8d5376be717e2d329d46ea8acae3ec15691d1012`. A hash-matching selected subset is valuable scoped evidence but is **not** exact full-tree evidence.
3. Run a reviewed, protected carrier workflow that independently checks frozen source and destination IDs, exact workflow blob, immutable test plan and clean git state. Pin JDK 25/GraalVM and Maven tooling. Execute real `dev.oreslang.LogicalPunctuationOperatorTest` (JUnit), preserving XML reports and test count/failed/skipped metrics. Suggested command, subject to reviewed build configuration: `mvn -B -ntp -Dtest=LogicalPunctuationOperatorTest test`.
4. Require compiled guest execution proving shared truth-table exit 42, `&&` and `||` RHS suppression on trap/throw, `^^` LTR exactly-once RHS and LHS evaluation (also in suspending evaluator if supported), precedence/associativity, type-first `bool and/or/xor` local declarations, and compile-negative integer/single `|&^ ` and spaced caret/conditional-comma cases.
5. Pin PR-head commit/tree, workflow run ID/attempt, runner ID, NONZERO executed steps, test report hashes, exact guest runtime, and proof that no job was silently skipped. Keep independent approval and all other required release gates; CI green alone is not a merge instruction.

## Current evidence boundaries

- Java #3009 source-org CI has reported `steps=null`/no runner admission: **NOT_EXECUTED**, not a compiler semantic failure.
- LLVM [#1728](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/1728) already has allowlisted **LLVM-only** 62-blob compiler guest evidence in [run 43336001714](https://github.com/streempilot-test/browser-studio-e2e/actions/runs/43336001714): bool truth tables, native O0/O2, short-circuit/trap, XOR exactly-once ordering, invalid-source negatives. That does **not** authorize transfer of Java source or certify full LLVM root-tree identity.
- The canonical source-only contract remains spec [#45](https://github.com/ores-truffle-oreslang/attolang-language-spec.md/pull/45) and conformance [#713](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/713).
