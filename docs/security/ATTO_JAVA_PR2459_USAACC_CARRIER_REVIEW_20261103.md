# AttoLang Java .atto compiler: private carrier approval review (NON-AUTHORIZING)

**Status: REVIEW REQUEST ONLY — PRIVATE SOURCE TRANSFER DENIED.** This is a
source-free governance record. It neither changes the approved-destination
allowlist nor the publisher, carrier contract, CI workflow, or security policy.
It does **not** approve, schedule, or perform any source export.

## Exact reviewed candidates (November 3, 2026)

| Property | Verified candidate |
|---|---|
| Source repository | `ores-truffle-oreslang/oreslang-source.java` |
| Source immutable GitHub repository ID | `1400792274` |
| Source visibility | Private |
| Target compiler PR | [#2459](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2459), still DRAFT |
| Source PR head | `374b62ad6c8d108f47628f273458ad4f0039c4a7` |
| Source PR Git root tree | `ffab3e9dc443126d57ac7ca6c495347a815ad7d6` |
| Prospective private carrier | `usa-acc/usa-acc-e2e` |
| Carrier immutable GitHub repository ID | `1346346666` |
| Carrier visibility | Private |
| Candidate carrier PR | [#58](https://github.com/usa-acc/usa-acc-e2e/pull/58), still DRAFT |
| Carrier PR head | `01e53c82b3649d24a2aff2b2adeeb9db7cdfa1a7` |
| Carrier PR Git tree | `7fa0e94f31ed046abeafdba43719fe88bf3b4494` |
| Carrier `main` at review | `3c0160ae7e867571198526d055b48574e0f81fe4` |
| Source-free runner workflow Git blob | `10e57ee42a7666edfe90a5058096d1eeb46e8ee6` (`.github/workflows/oreslang-source-free-llvm-toolchain.yml`) |
| Proposed private carrier workflow Git blob | `debadde9f542c3fc9d945c7b6d65839c4ce5aabe` (`.github/workflows/oreslang-exact-tree-carrier.yml`) |

**These pins are snapshots, not approvals.** Any changed source head/tree,
carrier head/base, carrier workflow, test plan, repository name/ID, visibility,
or security rules must trigger independent re-review before a private write.

## Executed evidence versus missing evidence

- [USA-ACC carrier source-free run #41529766013](https://github.com/usa-acc/usa-acc-e2e/actions/runs/41529766013)
  executed a real runner: GraalVM JDK 25 synthetic Java compilation/launch,
  nine Rust source-selection tests, source-free LLVM CMake/CTest, and sanitizers.
  It logged `PRIVATE_SOURCE_MIRROR=NOT_PERFORMED`.
- [Java PR fast-CI run #41587603802](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41587603802)
  initial attempt and November 3 failed-job retry both ended with `steps=null`.
  This is runner admission failure, **not compiler/guest execution**.
- [LRU PR #1 source-local CI #41589649403](https://github.com/lru-cache-atto/lru-cache.atto/actions/runs/41589649403)
  at `b2d2c00315da537a408bbe75d5aa61dbb498e56c` ran 12 actual steps:
  30/30 host/protocol tests, 10/10 synthetic gate cases and 10/10 pinned
  native-source blobs. It explicitly reports guest execution NOT_EXECUTED.
- Java Maven/JUnit and the seven real `.atto` LRU guest fixtures remain
  **NOT_EXECUTED**. No true native compiler-built artifact is available for
  this transfer plan from these CI receipts.

## Hard blockers observed in the GitHub API

1. Source `main` reports `protected=false`, required status-check
   `enforcement_level=off`. V3 security policy explicitly disallows a
   source-side unprotected-main fallback.
2. Prospective carrier `main` also reports `protected=false`.
   Carrier fallback is only permitted by a separately approved, immutable,
   exact-reviewed V3 carrier contract; a private successful canary is not
   sufficient.
3. Source `.github/private-ci-approved-repositories.txt` contains **no
   active destination**. `usa-acc/usa-acc-e2e 1346346666` is NOT approved.
4. The reviewed source carrier-contract file has **no USA-ACC contract**
   authorizing PR #2459's immutable source snapshot/workflow/test plan.
5. There is **no recorded independent `@ORESoftware` approval** of this
   transfer and no verified protected-origin publisher provenance.
6. The source and carrier compiler test runners must be shown with actual
   nonempty job steps; a `steps=null` failure cannot count as negative
   compiler evidence.

## Independent-owner activation sequence (no bypass)

- [ ] A repository administrator enables and independently verifies source
  `main` protection: CODEOWNER requirement, stale approval dismissal,
  at least one genuinely independent approval, strict nonempty required
  status checks, administrator enforcement, and no force-push/deletion/bypass.
- [ ] Security owner separately reviews destination numeric ID, live privacy,
  carrier `main` protection OR an exact carrier-review fallback, immutable
  `main`/workflow/test-plan blobs and dependency/artifact isolation.
- [ ] Security owner approves the exact source repo ID, source commit/tree,
  approved destination `owner/repo + numeric-ID`, immutable carrier
  contract, allowed PR/test-plan list and publisher controls **on protected
  source main**. A draft PR or comment cannot activate authorization.
- [ ] Before source bytes are read or written, the trusted V3 publisher
  revalidates identity/privacy and the immutable protected-source policy,
  without consuming untrusted PR-supplied policy or tokens.
- [ ] Independent adversarial tests demonstrate zero source writes on
  source-ref swap, wrong tree, renamed/replaced destination ID, changed
  workflow/test plan, revoked approval, forged receipt and duplicate job name.
- [ ] Only after those gates are green may an authorized publisher create
  an isolated private carrier branch containing the **exact** source Git
  tree, verify nested blob/tree identity after the destination commit
  read-back and execute the approved test plan.
- [ ] Record immutable compiler build artifact, actual Maven/JUnit commands,
  nonzero executed runner steps, guest output/exit, negative mutation
  diagnostics, and cross-repo source parity. Keep host-only and guest-backed
  results labeled separately.

## Requested reviewer decision

`@ORESoftware`: review these exact identities and the missing security
controls; **do not treat approval of this document as export permission**.
Authorization requires the independently enforced protection + exact
allowlist/carrier-contract/publisher approvals above. If any element cannot
be verified, keep private-source transfer DENIED under
[security issue #962](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962).
