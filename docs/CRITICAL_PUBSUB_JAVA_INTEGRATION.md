# Java/GraalVM critical + pub-sub integration — 2026-11-07

**Status: DRAFT, source-integrated; compiled guest and CI execution NOT_EXECUTED.**

Integration branch: `integration/critical-suspends-pubsub-20261107`.
The initial two-parent source-tree merge is
`e4d8d9e92a2f4974915fd86295a0a47dc08f86e7`
(tree `ba7ebb6bc2190d873b7c81ce4cb1fcb9996ea44d`).

Exact input parents:

- Java pub-sub PR #2440: `f1f3db3681333a0c5e16cb6ff3e8c2a2c89816a5`
- Java critical/suspends PR #2422: `f45f3bba0aa25e168f37262e58ad54e97e18b7f1`
- Common merge base: `241bd67c39443cc2ef18df7e4ed456454af378c7`

All 28 critical/suspends changed paths were integrated: 25 are
disjoint from pub-sub and copied by exact Git blob; 17 critical patch hunks
in the three overlapping files matched the full old context on the pub-sub
head and were applied without discarding pub-sub changes. Merged blobs:

- `src/main/java/dev/oreslang/nodes/OresEvalRootNode.java`:
  `f21b1db347877cddc45f8b9fbcb33d37ce9e27d0`
- `src/main/java/dev/oreslang/runtime/ActorRuntime.java`:
  `fe1590e6a82d95c5e69846f23b8d9e8b22146938`
- `src/main/java/dev/oreslang/types/TypeChecker.java`:
  `15651d5c512c064e63fde86f1e03eb428ad98cd1`

This context-exact textual integration is **not** a Java compiler pass.
Cross-file effect/ownership and runtime behavior still require the tests below.
The critical branch retains fail-closed restrictions on unverified critical
forms; the pub-sub branch retains non-Send Topic/Subscription/ActorGroup
SourceTask boundaries and negative fixture diagnostics.

## Exact-head gate

`bash scripts/verify-critical-pubsub-integration.sh` requires:

1. Exact SHA, raw Git index/tree/worktree identity (including ignored
   untracked files and smudge-filter substitutions), and self-tests.
2. Original canonical pub-sub fixture blob
   `fe10d9e5018a3f030a10bc31586e851198aaa4a3` and critical
   fixture blob `50a62a32495989366624145c08c832e38c2c1a78`.
3. Identical `.atto`/`.ores` integration guest bytes,
   Git blob `9fe469af120fc948e1e68a55b783162c0b146105`.
4. Existing pub-sub exact-method Maven/GraalVM gate and actor
   stress/negative suites, not only a compiler class file.
5. Actor-critical ownership/runtime JUnit suites and the new
   `CriticalPubSubIntegrationGuestTest`.
6. Actual Polyglot guest execution in one source program:
   RELIABLE subscription + publication, then actor-owned critical gate;
   stdout exactly `subscribed|p-1|42`.
7. Surefire XML with nonzero test cases, zero failures/errors/skips,
   concrete required method names, and compiler class digest.

The `.atto` integration program exercises both runtime features on the
same GraalVM Context; it is not a claim that native AOT, LLVM or any other
backend can execute these features yet.

## Outstanding conditions

- GitHub Actions runner admission remains blocked (issue #315). A job with
  `steps=null` is `NOT_EXECUTED`, never proof of compiler failure/success.
- Review merged parser/TypeChecker/OwnershipChecker semantics, legacy
  async rejection, actor-critical cleanup, scheduler ownership and the
  Topic Send negative fixture (issue #2644) under executable JUnit.
- Require an independent valid PR approval and main branch protection.
- Do not promote a draft or merge until exact-head executed tests and
  review/security policy requirements are met.
- Preserve the private-source cross-organization transfer hold. The
  integration remains inside the original repository and organization.
- LLVM, .NET NativeAOT, Wasm, and Forge guest parity remains unproven.
