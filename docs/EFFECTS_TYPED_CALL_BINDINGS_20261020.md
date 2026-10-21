# Oreslang checked-call effects: scope, invariants and validation gate

## Implementation

This draft adds a **narrow, real type-checker-derived call binding** to the
existing optional `OresCompiler.requireConservativelyEffectFree` path.
It does **not** yet install a general runtime or codegen effects system.

- `TypeChecker` records an identity-keyed `Ast.CallExpr -> Ast.FunctionDecl`
  binding only after its existing resolution of a **local unqualified** or
  **qualified source-module function** has completed arity, type/generic
  arguments, actor-boundary and suspension admission.
- `TypeCheckResult.resolvedLocalCalls()` is a read-only snapshot backed by
  `IdentityHashMap`. Structural equality of AST records is not enough to
  borrow another call site's binding. The existing two- and three-argument
  `TypeCheckResult` constructors remain supported.
- `OresCompiler.parseAndTypeCheckForEffectAudit(source)` parses and runs
  the ordinary type check and ownership checker **before** returning call
  bindings. An unchecked or consumer-constructed result is never accepted by
  the source-effects public entrypoint.
- `SourceEffectAudit.inspect(source)` processes only that newly checked
  program and its exact AST-keyed call bindings; it constructs graph edges
  using target `FunctionDecl` *identity* within the same compilation unit.
  It never resolves targets by a matching text label.
- The fixed-point `EffectContracts.Graph.infer()` propagates effects over
  local recursive call graphs. Proven static name/module references are not
  counted as executable ambient reads, while **all value arguments and
  spreads are still inspected**.
- Unresolved callbacks, method dispatch, first-class function values,
  constructors, operators, foreign or missing targets, and all linked/external
  definitions retain conservative effects or unsupported-declaration status.
  Generic, structural, nlex, actor and trapped targets remain ineligible for
  purity until separately proven.

The returned `SourceEffectAudit.Report` retains its opaque constructor
and SHA-256 source identity. Neither the map, hash, nor graph is an
authenticated codegen certificate. **Current Java/Graal lowering is not
required to consume this report.** No general statement about production
enforcement or Haskell `IO` runtime semantics follows.

## Added regression tests

- `CheckedEffectCallBindingsTest`: two equal-but-distinct direct call ASTs
  have independent bindings; a newly constructed equal AST key is rejected;
  local pure calls propagate empty rows; recursively indirect impure calls
  propagate `UNKNOWN_CALL`; changed source bytes change the digest.
- `SourceEffectAuditTest`: updates the previously correct conservative
  unknown behavior for *unproven calls* to allow only **checker-resolved local
  calls**. An unknown operator in a resolved callee remains unknown in the
  caller, and the opt-in effect-free compiler gate rejects it.
- `SourceEffectAuditShapeTest`: proofless AST inspection still cannot
  authorize even textually direct-looking callbacks and preserves earlier
  allocation/exception/suspension negative cases.

## Missing verification / release gates

The changes are committed to the Java draft but **not yet exercised through
full Maven/JDK25, GraalVM Native Image, complete Java AST+typechecker integration
or native LLVM guest execution**. Existing standalone JDK closure and latent
effect models run independent classes and cannot validate these new checker
modifications.

Tests must run at an immutable **full Git-tree SHA**. The source repo's
`.github/private-ci-approved-repositories.txt` currently contains zero
authorized private destinations. The proposed narrow private carrier
`ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra` was tested
using a **source-free** capacity workflow:

- Branch: `ci/ores-effects-sourcefree-capacity-20261020`
- Commit: `1c3d0aa655809e283f23116e8a99f7a0910433a7`
- [Run 39525262357](https://github.com/ores-dummy-org-oreslang-stack/ores-dummy-org-oreslang-stack-infra/actions/runs/39525262357):
  **failure, zero executed steps**.

That does not establish available private Actions minutes. No source was
transferred. Do not approve a source mirror solely on this evidence.

Next: authorized source administrators verify protected-main approvals and
actual private carrier budgeting; only then mirror the full tree, compare root
Git tree SHAs and execute Maven/JDK25, Native Image and native/guest differential
parity. Keep Java #1705 in draft behind #1604 until that succeeds.
