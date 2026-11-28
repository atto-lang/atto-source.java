# Atto Java incremental PR #1744 — private CI security review packet

**Status: REVIEW ONLY — DENY PRIVATE SOURCE EXPORT.** This draft is a request for an independent security decision, **not** an approval, an allowlist entry, an executable transfer plan, or authorization to move source. Security owner: **@ORESoftware**. [Blocking issue #65](https://github.com/atto-lang/atto-source.java/issues/65). [Compiler draft #1744](https://github.com/atto-lang/atto-source.java/pull/1744).

## Exact frozen candidate (metadata observed 2026-11-22)

| Field | Exact observed value |
| --- | --- |
| Origin repository | `atto-lang/atto-source.java`, private, numeric ID `1448697198` |
| PR #1744 candidate source HEAD | `c09cada6e7c22fb545ea22b5d9a7b87e58c6644e` |
| Candidate Git **root tree** | `f25becfe44e789df645ad57c61bda21818ba7bff` |
| Full recursive tree | **Not truncated**; 3,777 entries, 3,394 blobs, 23,372,306 blob bytes |
| Mode and prohibited types | 18 executable blobs; 0 symlinks; 0 submodules |
| Biggest blob | 793,164 bytes |
| Source main during review | `f7b0db8edba040008c94beb5d00d924415601825`, **protected=false** |
| Active cross-repo allowlist | **Empty** |
| Focused compiler test | `IncrementalFileFallbackV1Test`, **15 authored** methods, none proven stepfully executed |

The legacy private publisher has measured ceilings of 4,096 files, 40 MiB total and 10 MiB per single file. This observed tree fits **size bounds only**; that is NOT authorization or a transferred-tree verification. Its full metadata was read through GitHub without cross-org copying. Any source HEAD movement invalidates this exact snapshot and requires renewed review; never silently repin.

Data-only evidence: [admission JSON](reviews/atto-incremental-1744-private-ci-admission.json) and [inert JUnit plan proposal](reviews/atto-incremental-1744-junit-plan-proposal.json). Neither is installed into the live carrier contract.

## Two carrier candidates, neither currently approved

**`opto-sync-test/go-java-validation`**, private repo ID `1323598177`, has default branch `main`. Its old Oreslang carrier workflow blob is `a071ba75b75b3a6c784a3e0e9c6e1817a111f139`, but the old live contract is bound to PR #1482, not #1744, and the carrier main head differs from that contract. Its [source-free probe #44955824813](https://github.com/opto-sync-test/go-java-validation/actions/runs/44955824813), retried on November 22 (attempt 3), still failed with **0 executed steps and no runner**, so **current capacity is NOT PROVEN**. Never force an unreviewed plan through the old workflow.

**`opto-sync/atto-ci`**, private repo ID `1458322748`, showed eight genuinely executed source-free steps on November 22 in [capacity run #44979279980](https://github.com/opto-sync/atto-ci/actions/runs/44979279980). It has a non-`main` default branch and lacks the exact approved V3 source-free carrier `main`/plan/contract. Its good Actions budget is **not** approval to store private Atto source there.

## Origin incompatibility

The imported Oreslang publisher pins **legacy** `ores-truffle-oreslang/oreslang-source.java` (numeric ID `1400792274`). The Atto origin has ID `1448697198`. Do not rename constants, spoof repository IDs, use caller-controlled environment variables, or bypass the source exporter to work around this. A reviewed Atto-origin implementation retaining the existing V3 fail-closed properties is necessary.

## Exact actions required from the independent security owner

1. **@ORESoftware:** record in [issue #65](https://github.com/atto-lang/atto-source.java/issues/65) whether V3 applies to this Atto workflow. Until clarified, keep DENY.
2. **Repo administrator:** enable and independently verify protected source `main` via [Settings → Branches](https://github.com/atto-lang/atto-source.java/settings/branches). The V3 gate requires the actual protection settings (CODEOWNER review, at least one independent approval, required status checks, admin enforcement, no force-push/delete/bypass, stale review discipline), not just an `AGENTS.md` declaration. An unaudited protection change is not sufficient.
3. **@ORESoftware + protected source PR:** approve one **specific** private carrier owner/repo numeric ID, live private visibility, permitted readers, retention/cleanup, immutable source-free carrier `main` base, workflow blob, Maven dependency-preparation/isolation implementation, and a plan pinned to #1744. Only an independently reviewed policy PR should populate `.github/private-ci-approved-repositories.txt` and `.github/private-ci-carrier-contracts.json`; this proposal deliberately changes neither.
4. **Authorized operator:** first prove fresh **stepful source-free** carrier capacity. Then freeze and independently review the *current* exact #1744 head/tree, all changed artifacts and plan bytes. Every source-bearing write needs re-attestation; each blob SHA/path/Git mode and the whole root-tree hash must match. No PAT, cross-org private checkout, ambient secrets, or source-bearing artifacts in a public repo.
5. **Authorized isolated CI:** prefetch dependencies without executing untrusted source; run approved Maven command on JDK 25 with `-o -C`, network isolated, a fresh HOME and no inherited credentials. Require a nonempty Surefire XML report of all **15 named non-skipped tests**, no errors/failures, and nonzero actual runner steps. This proves only Java in-process frontend caching. Separate plan needed for clean-versus-incremental compiled guest and Native Image AOT.

If the carrier default branch cannot satisfy V3, select another reviewed source-free carrier instead of treating an existing runner success as a security override.

## Review/implementation overlap

[Java #697](https://github.com/atto-lang/atto-source.java/pull/697) already contains parse-cache improvements and test coverage that have been partially incorporated into #1744. [#1536](https://github.com/atto-lang/atto-source.java/pull/1536) owns multi-file namespace contract proof, and [#1545](https://github.com/atto-lang/atto-source.java/pull/1545) owns manifest package membership. No claim is made that Java file cache means package/namespace codegen parity. Keep source PR #1744 and this security review draft open until independent review and exact-head, **stepfully executed** CI.

**This review-only branch must not be merged as an active private-source export grant.** Its test asserts the source allowlist remains empty and PR #1744 is not an approved carrier plan. After independent security resolution, prepare a distinct controlled authorization change under protected main; do not edit this inert packet into a self-approval.
