# V3 call-site receipt audit — 2026-10-19

Status: staged security/integrity model only. No optional/default guest execution is enabled.

## Confirmed vulnerabilities in prior snapshot model

1. **Cross-call receipt reuse:** the structural digest covers argument label/omission
   layout, not actual expression-node identity or expression content. Thus
   `f(a)` and `f(b)` (same labels/counts, different ASTs) could share a
   structural fingerprint for the same selected target/formals. The digest is
   not enough to authorize executable lowering.
2. **Shape-only downgrade:** adding a strong site-specific verification method
   while allowing AST-bound receipts through the older `verify()` method
   would silently bypass the new site guard.
3. **Malformed UTF-16 digest collision:** Java `String.getBytes(UTF_8)` by
   default replaces unpaired surrogate code units, so distinct Java strings
   can hash to identical replacement bytes. That breaks canonicalization.

## Changes

- `SelectedCallSnapshotV3.bindForCallSite(Target, Ast.Expr, formals, items)`
  records the **exact AST Expr object identity**. The compiler must use
  `Receipt.verifyForCallSite(expectedExpr, ...)` for site-bound plans.
  A structurally identical but different AST occurrence fails.
- Weak `Receipt.verify(...)` now rejects site-bound receipts rather than
  allowing a downgrade. It remains shape-only for receipts created with
  the old `bind(...)` API, which are NOT execution authority.
- Digest strings use `CharsetEncoder` with `CodingErrorAction.REPORT` to
  reject malformed Unicode instead of silently replacing code units.
  Valid surrogate pairs (e.g. emoji) retain their canonical UTF-8 bytes.
- Committed negative JUnit tests for a different AST node with equal content,
  weak-verifier downgrade, missing call-site binding, malformed UTF-16 and
  valid Unicode.

## Boundaries

The site identity check works **only within one in-memory compiler AST graph**.
Copying/deserializing ASTs invalidates it, by design; a stable IR migration
requires a separately verified source-node ID plus canonical semantic digest.
Object identity, the structural SHA-256, and caller-supplied
`checkedSignatureDigest` are NOT authentication, capability authority,
type-checker evidence, or runtime permission.

The compiler still needs to resolve the actual selected target and bind its
nominal Option identity, default types, ownership/effects/caller-role proofs,
and actor boundary before admitting any executable default or omitted call.
Keep `UnloweredDefaultArgValsGuard` in TypeChecker, OwnershipChecker, and
interpreter paths. The sibling required named-call PR #1508 has a divergent
AST surface; no unsafe runtime merge was made in this audit.

## Testing and private CI

Isolated Java 21 reproduction (minimal AST shim) passed:
- 10,000 deterministic distinct-call-site replay attempts and strict UTF-8
  adversarial cases: 20,007 focused assertions;
- original 10,000 target/ABI/default replay trials: 20,006 checks;
- nested Option scalar/None regression tests;
- prior staged typed-default 100,000 numeric cases (51,699 admitted,
  151,708 checks).

**None of these is a full compiler/LLVM/Graal build.** Regression JUnit sources
are committed but repository JDK25 Maven CI remains unverified.

On 2026-10-19, protected `main` still had **zero authorized private CI
mirror destinations** and source-org CI failed without job steps. A new
**source-free** Actions runner canary in private
`benefactor-cc/benefactor-e2e` at branch
`probe/sourcefree-actions-capacity-v3-20261019` also failed before any job
steps (run 39314604527). Sagitta source-free canaries were similarly failing.
That does not prove a specific billing cause. No Oreslang private code was
exported to another organization; exact-tree compiler CI is gated on security
approval PR #1326 and actual available runner capacity.
