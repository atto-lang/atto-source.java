# AttoLang numerical PR #1736 — private CI admission review

**Review-only; no authorization, no source transfer, no merge.** Security owner: `@ORESoftware`. Tracker: [atto-source.java issue #65](https://github.com/atto-lang/atto-source.java/issues/65). Language work: [Java draft #1736](https://github.com/atto-lang/atto-source.java/pull/1736); [spec #209](https://github.com/atto-lang/atto-spec/pull/209); [conformance #563](https://github.com/atto-lang/atto-platform-conformance/pull/563). The checked-in machine-readable [review packet](reviews/atto-numeric-1736-private-ci-admission.json) is **inert data**, not an approval policy.

## 1. Observed exact Atto Java snapshot (must be rechecked before each use)

| Field | Observed value |
| --- | --- |
| Source GitHub repository | `atto-lang/atto-source.java` (private), ID `1448697198` |
| Source PR | `#1736`, draft; target `main` |
| Candidate source head | `c16a0313b166f4b426e5c4aa2b3f487475e107b6` |
| Git root tree | `c8fe1626629782a171e6950ba3d7080e489160cd` |
| Exact tree regular blobs | **2,906** |
| Sum of Git blob sizes | **20,904,978 bytes** |
| Largest blob | 737,506 bytes |
| `pom.xml` blob | `7ace0b647b2f07e1ed7b771421cc7bd60c2585a3` |
| Source main at audit | `169a541e5957f5706404a30aa6b5a607d63083dc`, `protected=false` |
| Live destination approvals | **None** in `.github/private-ci-approved-repositories.txt` |

The current private publisher's **code** uses `MAX_FILES = 4096`, `MAX_BYTES = 40 MiB`, `MAX_SINGLE = 10 MiB`. The generic policy prose mentioning **1,024 files** is outdated and must be corrected separately after review. This snapshot fits those *implemented* capacity bounds. The full tree still must be verified, including every blob SHA and executable mode, and limits must never be silently widened.

## 2. Two independent security blockers, regardless of available Actions budget

**A. Origin identity is wrong for AttoLang.** The imported `scripts/publish-private-ci-source.py` names `ores-truffle-oreslang/oreslang-source.java`, GitHub repository ID `1400792274`. The V3 dispatcher also pins `SOURCE_REPOSITORY_ID = 1400792274` and imports those legacy constants. AttoLang's actual repository ID is `1448697198`. Therefore the current V3 source-identity contract **cannot** be used to publish AttoLang PR #1736. **Do not** merely replace the Oreslang constants: the same legacy module also carries Oreslang-specific incident blocks, security governance, branch names, receipt schemas, and policy logic. Any Atto-specific publisher needs a separately reviewed, fail-closed origin adapter or independently audited entry point that retains the legacy Oreslang protections. No destination-selection input, environment override, PR-controlled code, or unsafe `GITHUB_TOKEN` cross-org checkout may grant an Atto source identity.

**B. Source governance is not admitted.** GitHub reported source `main protected=false`, issue #65 is unresolved, and no active destination allowlist entry exists. `.github/private-ci-carrier-contracts.json` contains reviewed-looking records for legacy **Oreslang** PRs #1482 and #616, not AttoLang #1736. A user instruction in chat, a proposed security PR, a source-free success, or a manually created matching tree does **not** replace the independent `@ORESoftware` review and protected-origin verification required by the source policy.

## 3. Validated alternate runner versus candidate V3 destination

- **Real alternate-org proof:** [opto-sync/atto-ci run #44973090105](https://github.com/opto-sync/atto-ci/actions/runs/44973090105) completed both stepful jobs. Its source-free head `9d7bf996d2340ee735676cae65f32d8f161278e1`, root tree `bf41a1e7921e1b80ef5c637f4f952e2ff8be6a1c`, contained **exactly three** source-free files. The Java oracle and a GraalVM **25.3.4.1** `native-image --no-fallback` host build independently passed **213,495** arithmetic checks each. **No Atto guest compiler or private Atto source was present.**
- `opto-sync/atto-ci` is private, ID `1458322748`, but its default branch is currently **not `main`**, and its default tree has unrelated private source content. This source-free proof **does not** make that repository a reviewed V3 carrier. A protected, source-free `main` with an independently pinned workflow/test plan and isolation proof would be required if this destination is selected.
- `opto-sync-test/go-java-validation` is private, ID `1323598177`, default branch `main`, and has an existing Oreslang V3 control plane. That does not authorize Atto source: the exact reviewed base/workflow/test plan **and** a new Atto-only origin-admission contract would need to be reviewed. Working runner minutes for the proposed Atto test plan must be demonstrated in a **new** source-free stepful run.

## 4. Security-owner decision needed (issue #65)

1. **Scope:** Decide explicitly whether the imported Oreslang V3 policy governs AttoLang private-source tests. If yes, review a separate Atto source-identity adapter and preserve all legacy restrictions. If no, document a reviewed alternate Atto-private-CI policy *before* transferring any bytes.
2. **Source controls:** Enable and independently verify *live* source `main` protection and every required CODEOWNER/status/admin/no-bypass/no-force-push/no-delete rule. The connector cannot administer branch protection. Protecting merely the PR branch does not satisfy the policy.
3. **Destination selection:** Independently choose one exact private repository and immutable numeric ID. Verify private visibility, the reviewed source-free base (and carrier `main` identity), CODEOWNER approval or the exact reviewed-carrier fallback, workflow blob, test-plan blob, untrusted build isolation and bounded cleanup. Recheck them before every source-bearing destination write.
4. **Source PR approval:** Bind the exact open #1736 source head/root tree and independently approved reviewer to that reviewed control plane. Any source PR head/main/governance/carrier advance invalidates the authorization until re-reviewed.
5. **Execution contract:** Use the exact `pom.xml` blob; prefetch pinned dependencies in a source-free environment; run Maven offline with a clean non-privileged HOME, no token/network, and without access to unrelated carrier files. A proposed numerical JDK 25 JUnit suite includes `NumericValueOrderingTest`, `NumericValueEqualityExactTest`, `NumericGuestOrderingV1Test`, `TreeShakerNumericEqualityTest`, `NumericCheckedI64CurrentMainTest`, `NumericIntegerDivisionV1Test`, `NumericTrapResultV1Test`, `NumericErrorAbiTest`. Full *guest* Native Image AOT is a separate job; a `native-image --no-fallback` **host oracle** is not proof of guest AOT.
6. **Receipts:** Validate source original commit SHA, complete tree SHA, every path/mode/blob SHA, one-use authorization ref, destination commit/tree SHA, stepful Maven/JUnit/guest/native execution, negative failures and no interpreter/JIT fallback. Never count zero-step jobs, host-only math or bare matching Git hashes as compiler parity.

## 5. Proposed sequencing and stop conditions

The next implementation PR should **first** add an independently audited Atto origin adapter and adversarial fail-closed tests without adding the carrier to the active allowlist. The selected carrier must have a separately reviewed, immutable source-free control plane and Atto test plan. The security owner then enables and verifies source protection and reviews the exact carrier identity/base/workflow/test-plan binding via protected `main`; only *after all gates* does the one-use publisher mirror the exact source tree. Do not accept the source-under-test's own modified policy or scripts as transfer authority.

**Current disposition: DENY.** Leave numerical #1736 and this security packet as drafts until the independent security owner completes these gates. Never push the private source into any unapproved alternate organization, even if its Actions minutes work.
