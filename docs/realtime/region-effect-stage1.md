# Real-time actor region/effect model — stage 1 symbolic foundation

Status: **experimental, JDK-only, not integrated with the Oreslang frontend/typed IR**. Source of truth for actor heap ownership remains the compiler and runtime, not caller-defined symbolic metadata.

This patch introduces `RealtimeRegionEffectModel`, a deliberately closed-world symbolic transition checker for allocation regions and object/borrow lifetime obligations. The caller explicitly supplies immutable region descriptions, authorized trust domains, a named finite call graph, and an ordered list of effects. The model fails closed on effects outside its whitelist. Its assurance string is `SYMBOLIC_REGION_MODEL_ONLY;SOURCE_LOWERING_AND_PHYSICAL_VM_UNVERIFIED`.

The model checks actor-owned vs scoped vs shared immutable vs JVM heap/unknown regions, generation-checked binding references, region capacities, cumulative arena allocations, bounded copies, call depth/recursion, operation count, exclusive/shared borrows, suspension while borrowed, scope expiration, lifetime rank on stores, and unauthorized raw mailbox/export of actor-private or mutable references.

It intentionally **does not** implement physical actor arenas, RTSJ scoped allocation, executable Oreslang actor lowering, actual JVM object provenance, reliable serializer/FFI contracts, exception/unwinding effects, whole-program dynamic dispatch, per-branch CFG joins, bytecode-level resource proof, or worst-case execution time. A model pass cannot be fed directly into `RealtimeExecutionContract.Capabilities` as hard-real-time evidence.

## Invariants to preserve when integrating with typed IR

- Symbolic events must be produced by compiler-trusted lowering of source and all synthesized operations; never by untrusted user annotations or caller-controlled boolean evidence.
- Unresolved calls/FFI, reflection, dynamic loading, cleanup, exception paths, actor lifecycle and scheduler operations must be `UnknownEffect` (rejected) until independently qualified.
- A generation token is checked on each value/region use, but this prototype does not yet model region reuse and incremented generations.
- Long-lived regions must not retain references to shorter-lived scopes. Physical heap and trust-domain boundaries must be verified independently on the runtime and backend.
- Mutable actor-private values cannot be sent as raw pointers. `ImportShared` is a promise that an already qualified deeply immutable allocation and authorization exist; it does not prove that promise.
- `CopyBytes` is a claimed copy bound, not verified serializer instrumentation. Copy/arena budgets must eventually be inferred from actual IR and checked post-lowering.
- This model is linear, not a CFG with join/widening; it does not admit loops or arbitrary branch plans. It is an analysis building block, not a language feature.

## Next stage

1. Define canonical target-independent effects/region IDs in `oreslang-common-source`.
2. Wire the typed Oreslang frontend after `TypeChecker` and `OwnershipChecker` so it emits this model from actual IR and reports unknown callees/regions.
3. Add CFG joins, bounded loop induction and state-sensitive exceptional/cancellation paths.
4. Verify Java/LLVM lowering preserves the effects and physically enforces region allocation and lifetime.
5. Qualify a specific RTSJ VM/RTOS/CPU timing model; treat measurements as corroboration, not proof.

Portable regression entry:

```sh
javac --release 21 -d /tmp/rt-region-tests src/main/java/dev/oreslang/compiler/RealtimeRegionEffectModel.java src/test/java/dev/oreslang/compiler/RealtimeRegionEffectModelSelfTest.java
java -ea -cp /tmp/rt-region-tests dev.oreslang.compiler.RealtimeRegionEffectModelSelfTest
```

## Retained reference/Drop and scope-closure hardening

The model now records both the *source region* and source *binding name* for each pointer stored in another region. A binding `Drop` may trigger a destructor, so dropping or moving a binding whose address is still stored fails closed even while the originating arena remains allocated. Retained pointers stored inside an expiring scope are discarded when that scope ends. A source scope cannot close if any other still-live region retains an address originating from it. Every declared scoped region must close before the entry point completes, including scopes whose bindings have been dropped.

The model does not implement field overwrite, reference removal, refcounts, nested dynamic lifetime proofs, or relocation of stored addresses. Therefore a legitimate program needing those operations must wait for typed-IR proofs rather than bypass these restrictions. Lifetime ranks are symbolic declarations—not independently verified facts—and a caller-created plan remains **untrusted for hard-RT admission**.

The follow-up audit adds negative cases for forgotten scope closure, equal-rank independent scopes, retained pointers surviving a dropped source binding, stale destination regions, and forbidden destruction/moves of region-retained objects. The test suite currently contains 31 negative policy checks. CI status must be tracked separately: zero-step GitHub Actions failures do not constitute an executed test run.
