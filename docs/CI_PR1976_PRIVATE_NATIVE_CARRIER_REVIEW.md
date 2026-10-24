# PR #1976 GraalVM Native Image — private CI carrier review packet

**Status: REVIEW ONLY — NO SOURCE TRANSFER AUTHORIZED.**

This document is an evidence and requirement packet. It does not modify
`.github/private-ci-approved-repositories.txt` or
`.github/private-ci-carrier-contracts.json`, does not invoke the mirror publisher,
does not grant anyone transfer authority, and must not be interpreted as a waiver
of the source security controls.

## Exact identities and scope

| Role | GitHub repo | Immutable repository ID | Visibility |
| --- | --- | ---: | --- |
| Source | `ores-truffle-oreslang/oreslang-source.java` | `1400792274` | private |
| Candidate runner | `cliptown/cliptown-e2e` | `1324410267` | private |

The two identities were read from GitHub on 2026-10-24. The candidate is
not archived, disabled, or a fork and its default branch is `main`.
**This is only a point-in-time metadata check, not transfer admission.**
Every assertion must be revalidated immediately before all source operations
against the protected, independently reviewed policy.

The exact PR #1976 head must be re-fetched **at authorization and execution
time**; its SHA must equal the head of the still-open PR targeting source
`main`. The source root Git-tree SHA must also be pinned and compared. Do
not substitute a synthetic pull-request merge commit for the PR head.

## Existing proof (source-free, not an Oreslang build)

1. GitHub Actions [carrier run 40065041539](https://github.com/cliptown/cliptown-e2e/actions/runs/40065041539)
   checked out an isolated, connector-created **synthetic four-file Git tree**,
   configured GraalVM, built a 5.44 MiB native executable through a Maven
   Native Image profile, and ran the executable successfully.
2. The synthetic root Git tree was
   `c99e3d0644442c7afaaf69fb18e65ed5b8864b8c`;
   carrier commit `1968a6f87dd9590277aa2f9a828921bf8b217751`;
   synthetic workflow blob `2a3f437ed053403f4a0afe3437b3c7a93fb97201`.
3. This synthetic workflow is **not suitable for confidential source
   execution**: it does not implement the full source-attestation,
   authorization-ref, isolated-blob-checkout, credential-scrubbing,
   offline-build, or controlled-egress boundaries.
4. Previously executed source AOT, hybrid, and isolate build evidence is
   recorded in [PR #1976](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1976),
   but belongs to an older source commit.
5. Source Actions at the latest PR head repeatedly end with **zero executed
   job steps**; this is not a compiler or runtime test result.

## Mandatory independent blockers (fail closed)

- [Security issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962)
  is open. Its protected-origin/preflight checks must be wired into the
  **actual V3 source publisher**, rather than relying only on an adjacent
  standalone script. A test runner cannot override this block.
- [Authority PR #1724](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1724)
  is an explicitly documented HOLD/no-op authority PR. It must not be
  approved/merged as-is. The independent incident-owner dispositions,
  security review, exact reviewer identity and source policy SHA required by
  that PR must be demonstrated; similarly numbered issues in an unrelated
  project do not establish a disposition.
- [Branch-protection issue #904](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/904)
  remains unresolved. If protected-main mode cannot be demonstrated, V3's
  immutable exact review-attested mode must pass **without bypass**.
- The **current source allowlist is empty**. The existing carrier contract
  contains a separate `opto-sync-test/go-java-validation` entry restricted
  to **PR #1482** only; it is not authority for PR #1976, and no carrier
  should be silently added or reused for a new PR.
- Candidate `cliptown/cliptown-e2e` `main` was reported **unprotected**.
  It contains unrelated private project state. A proper source-free,
  immutable carrier base and an independently reviewed isolation workflow
  would be required; a normal checkout of all carrier blobs would violate
  confidentiality assumptions.

## Required carrier contract before enabling the gate

An independently reviewed **new** carrier contract and exact test plan must pin:

1. Source and destination names **and immutable numeric IDs**, both private,
   active, distinct, and rechecked against fresh live GitHub metadata.
2. A source-free carrier `main` **commit SHA** and exact reviewed workflow
   **blob SHA**. Workflow must verify immutable authorizing ref, receipt,
   manifest, source tree and source PR-head commit before running code.
3. A unique exact test-plan path and **blob SHA** allowing only PR #1976,
   with a pinned `pom.xml` blob and curated fixed command arguments and
   execution timeouts; the user must not be able to inject shell commands.
4. Only sparse + blob-filtered checkout of the source subtree and reviewed
   control files. Prove unrelated carrier files are absent from both
   worktree **and** local `.git/objects`; do not offer the test code an
   authenticated `git fetch` path.
5. Before code execution, drop the checkout credential/remote and scrub
   `GH_TOKEN`, `GITHUB_TOKEN`, HOME/XDG auth, secrets, and unnecessary
   filesystem access. Restrict outbound network. The reviewed Maven plan
   must use **offline mode** after separately prepared, verified dependencies.
6. The source-executing job must have `contents: read`; ref cleanup is
   a separate narrow-permission job. Deleting refs does **not** securely
   erase source blobs from GitHub storage.
7. Independently verified carrier isolation under adverse conditions:
   altered protected workflow, unauthorized ref, revoked approval,
   swapped commit/tree, wrong repo ID, visibility change, stale PR head,
   missing offline dependencies, leaking build output and network egress.

The tested synthetic four-file workflow may serve as **runner-capacity
evidence only**. Never reuse it as the source-carrying production workflow.

## Exact native test plan to execute *after* approval

All selected commands should use a reviewed offline runner environment and
immutable checkout. PR #1976's smoke script now supports `ORES_NATIVE_IMAGE_OFFLINE=1`, which
passes `-o` to Maven and fails closed on invalid flag values. **21 tests passed
locally against the exact committed script/test Git blob versions**, including
two offline success configurations and invalid-mode denial. A future carrier
must still prove a source-free verified dependency cache, effective egress
restriction and hardened sandbox; a Maven offline flag alone is insufficient.

- Baseline: `mvn -B -ntp -o -Dores.runtime.carriers=native verify`.
- Native AOT compressed32: build `native-aot`, confirm `--no-fallback`,
  inspect binary format/embedded manifest, execute `together.ores`,
  `native-core-library.ores` and `native-testing.ores`.
- Native AOT native64: same tests with `native-aot,native-ref64`,
  assert the baked `native64` reference mode.
- Native hybrid compressed32: same tests using `native-hybrid` and confirm
  the baked hybrid mode, with no JVM fallback.
- Native isolate: build `native-isolate` and execute Oreslang via the
  JNI-backed strict isolate, plus stdlib and root-await integration.
- All jobs must expose executed-step logs and exact source commit/tree SHAs.
  Native tests must validate guest output and process status, not green
  labels or a mock binary alone.

## Reviewer decision (must be independently recorded)

- [ ] Independent incident owner resolved the specific P0 incident blockers
- [ ] Source V3 live/private/provenance publisher closes #962 with tests
- [ ] Protection or exact review-attestation source policy is demonstrable
- [ ] Dedicated carrier source-free base, isolation, pinned workflow and
      PR-1976-only plan have been reviewed and executed in source-free mode
- [ ] Destination is newly approved in protected source allowlist/contract
- [ ] @ORESoftware (independent of author) approved exact authority head
- [ ] Source code export has not begun before all of the above pass

**Until every requirement is satisfied: do not transfer private Oreslang
source bytes, create source blobs in other repositories, authorize source
Actions, or merge PR #1976 based on synthetic evidence alone.**
