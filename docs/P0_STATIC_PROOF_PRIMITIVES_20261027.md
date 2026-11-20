# P0 static proof helpers — Java/GraalVM

**Status: compiler helper implementation and unit tests on draft PR; not `.ores` source lowering or guest Native Image AOT evidence.** Linked program: [12-feature draft contract](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/172) and [Java tracking issue #2152](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/2152).

## Implemented helper APIs

- `TypeclassRegistry.Registry.resolveWithBindings` exposes the exact unique static dictionary witness plus substitutions from nominal/generic head matching. Existing `resolve` behavior is preserved.
- `AssociatedTypeWitnesses` admits a *complete* fixed set of associated equations, checks bound variables, orphan rules, overlapping instances, symbol uniqueness, recursion/resource budgets, and resolves fully ground projected types deterministically.
- `SessionProtocolProof` is a conservative finite control-flow analyzer for one opaque session handle. Branch joins retain **all** reachable phases, and a step must be legal for every incoming phase. Admitted auth work consumes `Created`; timeout creates `AuthUncertain` rather than returning `Created`. Copy, generation rebound, wrong-phase send and bad CFG edges reject.
- `RefinementProofs` checks finite signed-i64 interval implication and function argument/normal-return constraints; addition/subtraction/multiplication use unbounded BigInteger intermediate bounds and reject overflow. Inferred effects outside allowance reject; `UNKNOWN_CALL` / `FFI_UNKNOWN` cannot be certified.

These helpers cannot be treated as compiler front-end admission **until** they are connected to actual typed AST/HIR/MIR, imported symbol authorities and provenance. The Java parser does not receive new associated-type/session/refinement source syntax from this PR. The function-return helper requires the caller to supply all CFG normal returns; it **does not prove that the caller's list is complete**. One-session CFG facts don't prove runtime session mint authorization, distributed settlement or crash recovery.

## Tests and limitations

Run `mvn -B -ntp -Dtest=AssociatedTypeWitnessesTest,SessionProtocolProofTest,RefinementProofsTest test`. Exact-head workflow: `.github/workflows/p0-static-proof-helpers.yml`.

Required next gates:

1. Typed compiler IR nodes that own phase authority, generic witness and refinement evidence; no user-supplied/fake proof JSON.
2. Admit source-level syntax only after parser, formatter, borrow/effect checker and import boundaries are wired to these gates; compile-negative source fixtures for wrong phase, affine copy, unknown/out-of-range refinement, orphan witness and conflicting impl.
3. Generate **program-specific Oreslang guest methods** and continuations prior to GraalVM Native Image build; compiling only the Truffle interpreter is insufficient.
4. Native guest executable test traces, image/source/witness/prover SHA, proof of no eval/JIT fallback, and actual full-AOT certificate. Separate soft/hard-RT, memory quiescence and reconfiguration gates apply.

All feature cells remain `not_executed` until actual guest compiler/binary evidence. Zero-step GitHub Actions is not proof.
