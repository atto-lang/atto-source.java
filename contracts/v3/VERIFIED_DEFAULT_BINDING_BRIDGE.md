# V3 typed default and selected-call adapter boundary

Status: **staged semantic preflight plus pure integrity receipts**. Neither
component is executable omission/default lowering or authority to dispatch.

## Typed literal preflight

`StagedDefaultTypeV3.preflight(program)` executes inside
`TypeChecker.collect` AFTER the source-local arity-overlap preflight and
BEFORE `UnloweredDefaultArgValsGuard.reject`. The latter remains mandatory.
The same collect path is used for imported-class checking, which independently
rejects unlowered imported signatures.

For directly spelled builtin parameter types, the preflight rejects:
- wrong literal kinds (integer vs boolean vs string);
- integer overflow for i8/u8/i16/u16/i32/u32/i64/u64/int/uint;
- negative values for unsigned types;
- `None` on non-Option types;
- u64 values that fit the declared width but not the pure model's current
  signed-long constant representation;
- nested Option scalar defaults that would require multiple Some wrappers,
  which the current planner cannot represent.

Unknown/aliased type spellings return NO proof; they are never accepted for
execution, because the unlowered guard still denies the declaration. A real
future typed checker must obtain the intrinsic Option symbol from the nominal
resolver (not raw `TypeRef.name()`), resolve aliases/generics, reject ownership
and capability hazards, and prove checked default materialization.

## Selected declaration/snapshot integrity

`SelectedCallSnapshotV3` is a pure SHA-256 canonical-encoding receipt over:
- selected target kind, owner, name, ABI version, and externally supplied
  checked-signature digest;
- formal names, Option/omittability flags, typed closed defaults;
- source lexical argument labels and omission markers;
- every source-to-formal and evaluation provenance index from the call plan.

The `Receipt.verify` method compares target identity, recomputes the
pure plan, and checks its canonical digest. It detects accidental or malicious
replay against changed target identity, argument layout, formal defaults,
or ABI fingerprint. **It is not a secret MAC, authenticity proof,
runtime authorization, capability, ownership proof, or type-checking result.**
The selected signature digest must be minted by a trusted compiler and held
alongside the actual selected method symbol.

## Execution integration dependency

Executable required named calls live on sibling PR #1508; its
`Ast.StagedCallExpr`, `Ast.CallArgument`, checked proof cache and
`NamedCallBindingPlan` AST surface is NOT in this branch. Therefore no
unsafe partial splice was attempted.

After review/rebase, the next integrated compiler pass must:
1. select a single declaration and mint a checked signature digest;
2. resolve intrinsic Option and type/overflow-check every default;
3. construct formal metadata and source items from the actual AST,
   deliberately excluding hidden method receiver;
4. create and re-verify the immutable source/ABI receipt;
5. evaluate only supplied source Expr nodes once in lexical order,
   materialize defaults into ABI slots without hidden guest expression;
6. preserve borrow/effect/caller-authorization/actor-message proofs;
7. reject all paths without the complete proof, including imported,
   inherited, dynamic, generic, actor, constructor and LLVM paths.

Keep every runtime/backend fail-closed until an executable exact-tree
Maven/JDK25, GraalVM Native Image and LLVM parity matrix passes.

## Local evidence — October 19, 2026

An isolated Java 21 build with an AST surface shim ran:
- 100,000 deterministic integer-boundary trials, 51,699 accepted,
  151,708 checks, no detected invariant failures;
- nested Option default rejection and outer None acceptance;
- 10,000 selected-call snapshot replay/mutation trials,
  20,006 checks, no detected integrity failures.

This is **not** a full Oreslang Maven/JDK25 project run. JUnit source
regressions are committed, not claimed green. The source org's GitHub Actions
has failed before job steps. Protected `main` has an empty private
cross-org mirror allowlist and security review PR #1326 is unapproved.
No private source was transferred to another organization.
