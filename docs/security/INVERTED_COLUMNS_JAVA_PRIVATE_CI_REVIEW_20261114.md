# Inverted SoA Java PRs #3093/#3094: private CI review packet

**Status: REVIEW ONLY / DENY EXPORT.** No effective carrier authorization, secret, source copy, policy relaxation, or security-owner approval is made by this packet.

## Frozen candidate identities (2026-11-14; verify again before use)

- Private source: `ores-truffle-oreslang/oreslang-source.java` (numeric repo ID `1400792274`).
- Teardown [PR #3093](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/3093): head `80a2016ec01d01d2e6571ad9947e2039c4c75263`, root tree `886fe0928eae0df7bbafece01bf37b7c30c1a58b`.
- Guarded-access [PR #3094](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/3094): head `acb9d1967dff8f714f5f87cad6a17e44b29c20c5`, root tree `587ab333c79ab04166ffa70191570b234777c95f`.
- Combined review-only integration [PR #3101](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/3101): head `3beb3d8fca127cd666bf4ec8f55a6db586e9f9c6`, exact root tree `d8a2e78472d7ab382fe44c2bd4ed125eb08daa4d`. It is a stacked draft based on #3094, incorporating #3093 close scrub and adding a close-versus-guard regression; it is **not** an independently authorized source export or a merged mainline result.
- Candidate private carrier: `beamscale/bmscl-compiler`, numeric ID `1383937120`, observed base `3d1fe5886e3b166163245020acef6af2410ad3d8`.
- Carrier Actions health: [run 43643639329](https://github.com/beamscale/bmscl-compiler/actions/runs/43643639329) actually executed compiler version checks. **This establishes neither source-build correctness nor approval to export private source.**
- Source and candidate carrier `main` both report `protected:false` with required checks off.
- Source `.github/private-ci-approved-repositories.txt` itself labels `beamscale/bmscl-compiler 1383937120` a **proposed carrier requiring independent @ORESoftware review**. Its current carrier-contract manifest does not approve #3093/#3094. The individual PR heads represent different trees; a combined integration candidate now exists at #3101, but **no combined implementation has been executed or merged**.

## Mandatory security and integration gates

1. Enforce protected source `main` with independent CODEOWNER/security-owner approval, required checks and effective non-bypass enforcement, or reject. Do not treat an author's own PR or this document as approval.
2. Independently attest the exact private carrier ID, visibility, destination branch/protection and immutable workflow SHA; prohibit secrets and writable cross-org tokens. An unprotected destination requires the v3 review-attested equivalent where permitted by the source policy.
3. Pin a reviewed immutable test plan separately for each source PR/head. Because #3093 and #3094 modify the **same Java runtime file**, first test each PR independently and then build and test the reviewed integrated tree; do not claim that separately passing tests proves their merged correctness.
4. Verify every nested Git blob/tree SHA, exact checkout/ref, protected publisher identity, audit log and atomic rollback/cleanup. Reject stale source head, lost independent signoff, public destination, wrong repository numeric ID, missing plan/workflow or unrelated repo content before **any** source write.
5. Merge a separate protected-source carrier authorization PR only with independent `@ORESoftware` review; no export is allowed before it becomes effective.

## Test plan once authorized

For each approved exact source revision and then the approved integration commit:

```bash
set -euo pipefail
echo "EXACT_COMMIT=$(git rev-parse HEAD)"
echo "EXACT_TREE=$(git rev-parse 'HEAD^{tree}')"
java --version
mvn -B -ntp -Dtest=InvertedColumnStoreTest test
```

Run JDK 21 and JDK 25, class/loan teardown concurrency regressions, and full Java CI/GraalVM smoke as appropriate. Record JUnit test count, exact head+tree, executed steps and exit status. Native Image compilation of the host runtime does **not** count as guest `.atto` full AOT.

## Current result

A source-owned JDK 21/25 [targeted JUnit workflow](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/3094) is committed to both PR heads. Source-org jobs reported `steps:null` (including #3094 run `43644870051`), so the JUnit result remains **NOT_EXECUTED**. Review and approval tracking: [issue #3099](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/3099).
