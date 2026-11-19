# Atto aggregate JDK25 private CI — review-only authorization packet
**Date:** 2026-11-10
**Status:** SOURCE-FREE / NOT AUTHORIZED TO EXPORT / NOT_EXECUTED COMPILER / NOT_CERTIFIED

## Frozen input identities
Source repository: `ores-truffle-oreslang/oreslang-source.java`
Immutable source repository ID: `1400792274` (private).
Source `main`: `6d8eb70048eafc9a84c5aebe9c1bb06104f16e76`; `protected=false` as verified by GitHub REST.
- Java type alias PR #2733: HEAD `acf87370bf374a279fedffc522e17c792710bf4d`;
  full Git root-tree SHA `42d6e89909749ba8a2d8fd4055860449b02615d4`.
- Java array/tuple constructor PR #2734: HEAD `1326cc83102721918f6387fefd81bb9b45e75f26`;
  full Git root-tree SHA `976fa382459b046b99906940661fde10964e2a87`.
  #2734 stacks on #2733. Any commit changes the exact-source evidence pin.

## Proposed destination, not approval
Private candidate repository: `voxletra/voxletra-e2e`.
Immutable GitHub repo ID `1311678027`; observed private and writable.
An independent source-free probe already proved **a real JDK25/Maven runner**:
- Carrier run `42853886977` on exact carrier commit `734310926545ffaaa84a5df4298956309ac16308`;
- workflow `.github/workflows/atto-collection-jdk25-source-free.yml`;
- workflow blob `f44ff33648e2cddd710b776bbad580da82ac3b94`;
- `jdk25-host-smoke` completed SUCCESS; steps checked no source checkout,
  installed Temurin JDK25, and ran `javac -version` / `mvn -version`.
- Verified job logs on 2026-11-10 record `openjdk version "25.0.5"`,
  `javac 25.0.5`, `Apache Maven 3.10.0`,
  and `SOURCE_FREE_JDK25_ADMISSION=PASS`.
- This **does not** prove Java AttoLang source compilation or available remaining Actions budget.

## Executed read-only source-static audit (not compiler tests)
At the pinned #2733/#2734 Git heads above, the GitHub connector retrieved:
- `Parser.java` blob `a21c6e3419134bd8259f08c24117f82672bd792c`
- `TypeChecker.java` blob `fb1b9c5fa2ffb2875f838941f9e009907f5187ac`
- `TildeAggregateTypeAliasCompilerTest.java` blob
  `2a209d615fc3fd0edfd5d263e402552e3db9e255`
- `TildeArrayTupleValueGuestTest.java` blob
  `dfba879df01f2f73b609379c3d803970f1b06c7d`.

Twelve source-static assertions passed: stacked base/head relation; old arr and
tuple parsing; sigil adjacency; preexisting AST node reuse; typed witness
fail-closed path; intrinsic shape before alias lookup; forbidden-null test fix;
nominal shadowing test; Graal guest test authored; once-only argument side
effect test authored; and absence of unsupported factory AST lowering. These
are checks on source text, **NOT compiled Java, JUnit, guest, ownership or AOT
evidence**.

## Hard export gate
Current `.github/private-ci-approved-repositories.txt` does **not** list
the proposed destination. Existing
`.github/private-ci-carrier-contracts.json` only scopes carriers for
other historical PRs. Java policy #2694 and #2705 report an unreviewed prior
provisional carrier entry; merged #2137 received zero independent reviews.
**No Git blobs or source code may be mirrored based on this packet alone.**

Require ALL before copying ANY private AttoLang source:
1. Independent eligible `@ORESoftware` codeowner approval for this exact
   source head, destination immutable ID, workflow blob, and test-plan blob.
2. Verified enforcement of source protected-main and mandatory security checks
   or policy-authorized V3 gate with equally strong effective authorization;
   do not treat a comment in an allowlist or author's self-approval as proof.
3. Source-owned live allowlist/contract explicitly permits these PR heads,
   destination immutable ID, exact carrier base/workflow/test-plan blobs and
   narrowly scoped testing; default deny and fail closed on changes.
4. Review all carrier workflows, PR triggers, artifact uploads, caching,
   logging and third-party actions; avoid untrusted source execution in a
   privileged context, action secrets and unbounded cross-repo permissions.
5. Zero source transfer on missing or stale approvals, including changed
   blob SHA, codeowner/branch protection, carrier policy or unexpected paths.

## Proposed exact-tree mirror after approval (NOT EXECUTED)
- Create a temporary private carrier branch, *not* a public repo.
- Keep the **carrier's reviewed workflow** under its own
  `.github/workflows` and place the guest repository as a nested
  `atto-private-source/` subtree. This prevents source workflow files from
  accidentally triggering as carrier workflows.
- Transfer only reviewed source Git blobs through the connector; never use
  personal access tokens, cross-org GITHUB_TOKEN checkout, or hidden secrets.
- Independently verify all source path-to-mode-to-blob-SHA entries against the
  immutable source root tree; a full-tree mirror must reproduce its exact
  tree SHA as the nested subtree. Verify write-by-write source and carrier
  blob identities, and require that the carrier workflow verifies subtree
  hash before running. Stop on any mismatch.
- Pin runner access to approved private branch/ref and explicit read-only
  permissions; disable persisted checkout credentials; forbid source
  artifacts, caches and diagnostic dumps containing private files.
- Set exact tree/commit receipt and distinguish checkout, compiler, JUnit,
  Truffle guest, ownership, Native Image and actual AOT phases.
- Delete transient source after approvals permit cleanup; preserve only
  non-sensitive receipts and independently verified hashes.

## Exact-head compiler gate after approved mirror
For #2734's merged source tree, execute from `atto-private-source/`:

```bash
mvn -B -ntp -DfailIfNoTests=true \
  -Dtest=TildeAggregateTypeAliasCompilerTest,TildeArrayTupleValueGuestTest,CollectionShapeMetadataTest,ArgumentsTupleSpreadTest,NamedNominalSlotIntegrationTest test
```

Require JDK25, nonzero runner job steps, compiled project, exact JUnit counts,
real Truffle guest assertions and positive/negative fixture logs. A skip,
runner-admission failure, stale head, empty receipt or host-only proof is
`NOT_EXECUTED` or `NOT_CERTIFIED`, never `PASS`.

Separate release checks still include `~type` factory-equivalence semantics,
formatter round-trip, aliases under name shadowing, actor memory/ownership,
Native Image, and cross-backend LLVM/Wasm/Forge/.NET compiled guests.

**Approval action requested:** review and authorize a PR-scoped, immutable,
private tree transfer separately. This review-only file changes NO
runtime, compiler, policy allowlist, or carrier authorization.
