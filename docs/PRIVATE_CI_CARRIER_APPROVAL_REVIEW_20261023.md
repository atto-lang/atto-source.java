# Review-only private Actions carrier proposal (2026-10-23)

**Status: CANDIDATE ONLY — NOT AUTHORIZED.**
This file is a security-review input for [private-source transfer issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962), not a transfer instruction or allowlist. The protected `.github/private-ci-approved-repositories.txt` remains empty. No source transfer may be attempted on the strength of this document or a green source-free canary.

## Independently retrieved repository identities

| Role | Repository | GitHub numeric ID | Visibility |
| --- | --- | ---: | --- |
| Java source | `ores-truffle-oreslang/oreslang-source.java` | 1400792274 | private |
| LLVM source | `ores-truffle-oreslang/oreslang-source.llvm` | 1410897874 | private |
| **Unapproved** carrier candidate | `ores-otel-test/next-loggers-runtime-conformance` | 1328290859 | private |

This candidate contains unrelated tests. **Independent review must decide whether a dedicated, least-privileged private carrier repo would be safer.** Numeric identities and visibility must be freshly authenticated before every write; this table is evidence for review, not an identity oracle.

## Runner capacity evidence (no Oreslang source involved)

Source-free private Actions [run 39904137406](https://github.com/ores-otel-test/next-loggers-runtime-conformance/actions/runs/39904137406) completed successfully: 3/3 executed job steps, including compiling and running a standalone C11 `-Wall -Wextra -Werror` program. The temporary source-free branch was reset to its pre-canary commit afterward. This shows a working runner at the time of the test, **not** a promise of available budget or approval to run private source.

Existing source-org draft build workflows have reported failures with zero executed job steps; these do not establish any compiler or test result.

## Independent approval gates — every condition mandatory

1. **Dedicated security review.** Identify the reviewer independently of the proposed source author/publisher. Obtain code-owner/security approval and verify effective protected-branch/status rules (see issue #904). No self-approval from this document.
2. **Pinned protected provenance.** Execute the audited publisher from an independently authenticated protected-main commit, never the PR-under-test. Verify immutable source repository numeric ID, private/active status, expected source commit, exact Git tree and supported workflow before reading any source bytes.
3. **Reviewed destination contract.** Approve the carrier numeric ID, exact owner/name, private/active status, protected destination base commit, reviewed carrier workflow path and blob SHA, immutable test plan and allowed PRs. Verify every field again before each write, and reject changed names, ownership, IDs or visibility.
4. **Prevent workflow privilege escalation.** Carrier workflow must have minimum `permissions`, no writable source tokens, no production secrets, no PR-supplied workflows or scripts with network/secret privileges; the review must cover organization access and retention/deletion policy.
5. **Exact-byte verification.** Verify the nested uploaded source tree equals the protected source Git tree SHA. Pin the carrier commit SHA (not a moving branch). Fail closed on missing evidence, interrupted writes, mismatch, mid-transfer policy revocation or stale source PR head.
6. **Offline negative acceptance.** Independently execute tests for swapped source checkout, attacker-controlled local policy, wrong source SHA, changed repo ID/name, public/archived destination, revoked approval, wrong carrier workflow hash, and destination write failure. Each rejected case must prove **zero source writes**.
7. **Review before transfer.** Only after verified acceptance evidence, update the actual protected-main allowlist and destination contract through a separate independently approved PR. Do not use this review document itself as authorization.

## Version-scoped acceptance runs once explicitly approved

Java draft [#1924](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/1924): exact current head to be frozen before transfer, then JDK 25 Maven, supervisor FSM reducer/TLC, actor cancellation/ownership tests and GraalVM AOT/hybrid validation.

LLVM draft [#864](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/864): exact current head to be frozen before transfer, then native CTest targets `source_actor_implicit_child_scope`, `source_actor_opaque_ref_validation`, `native_actor_nested_scope_integration`, `actor_group_lifecycle` and strict compiler, ASan/UBSan, repeated concurrency tests. **A Java-only reviewed policy cannot be treated as authorization for transferring the LLVM source repository.**

Mark the builds verified only if exact-head runner logs and source-tree/commit receipts are available. Keep both lifecycle PRs draft otherwise.

## Non-goals

No source is mirrored by adding this file. It creates no GitHub Actions job and neither expands nor overrides the empty allowlist, branch-protection policy or security issue #962. It does not request that a GitHub token from one organization access another organization's private source.
