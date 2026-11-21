# Actor-state authority: bounded safety model and refinement obligations

Status: **runtime retirement hardening and bounded explicit-state model**. This
document is **not** a claim of full Java/LLVM mechanized soundness or formal
verification of the complete compiler.

## Normative guest-visible invariant

For each actor incarnation `A`, the only persistent user state is the
fixed-schema `state { ... }` record. Stateless actors have no access grant.

* `initialize(A)` establishes typed keys and actor-owned defaults before
  readiness, only under trusted runtime initialization.
* `write(A,k,v)` is permitted only if the executing incarnation is `A`,
  the owning actor turn is admitted, a **live exclusive write loan** exists,
  `k` belongs to the schema, `v` has the field's type, and no loan crosses
  a suspension or publication boundary.
* `read(A,k)` requires a live state loan for `A`; read grants cannot be
  upgraded to write grants, including through nested calls.
* `retire(A)` is permitted only after user turns quiesce and the runtime
  installs its own *ephemeral* retirement authority for that incarnation.
  Supplying a matching `ActorId` is **not** sufficient authorization.
* `self.state`, inherited or ordinary actor fields, side-table storage,
  borrowed aliases, reflection/FFI access, forged intrinsics, and exported
  loan objects are **not** alternate guest-state access paths.

The exact safety assertion, over post-initialization transitions, is:

```text
state_changed(A) =>
    (event = WRITE
       and current_turn.actor = A
       and loan.live and loan.mode = WRITE
       and loan.actor = A)
 or (event = RETIRE
       and runtime_retirement_authority.actor = A
       and no_active_turn(A)
       and no_active_loan(A))
```

Initialization is a separate trusted transition. The formula is about
**persistent state**, not runtime-owned lifecycle/mailbox metadata.

## Checked bounded abstraction

Run `node scripts/verify_actor_state_authority.mjs`.

The checker performs complete reachable-state enumeration (not randomized
fuzzing) over one owner and one foreign actor, two scalar payload values,
three loan modes (none, read, write), two lane states plus idle, and the
stop/retire/permit lifecycle. It checks every transition and positively
reaches valid writes and valid retirement. Four deliberately broken
transition variants **must** produce counterexample traces:

1. Actor ID substituted for runtime retirement authority.
2. Write through a read-only loan.
3. Foreign actor state write.
4. Foreign actor state retirement.

This proves the invariant **for the bounded transition relation defined by
the model**; it does not prove that an implementation faithfully refines it.

## JVM refinement checklist (release blocking)

| Obligation | Enforcement point | Status |
| --- | --- | --- |
| Single guest state schema, no extra fields or inherited storage | `ActorStateContract.verifyActor` | Integration draft; negative compiler tests needed |
| No state capability escape, borrow, nested mutation or suspension | `ActorStateContract` / `OwnershipChecker` / `DeclaredEffectVerifier` | Partial; interprocedural/effects proof pending |
| Guest field reads and assignments route to scoped `Access` only | `OresEvalRootNode` | Interpreter path inspected; lowering-equivalence proof pending |
| `Access.write` checks owner, live loan, write mode, key/type | `SealedActorState` | Runtime guard in integration draft |
| Runtime retirement is authenticated separately from owner identity | `ActorRuntime.finalizeStopLocked` / `SealedActorState.retireFromRuntime` | Hardened in this PR; JUnit execution pending |
| Physical single-carrier turn + epoch fence | actor scheduler / source-task admission | Separate runtime proof obligations |
| Native LLVM typed-map / write effect / loan lifetime parity | `oreslang-source.llvm` restricted lowering | Full parity **not** proven |
| Arbitrary Java reflection, Unsafe, host FFI or native memory access | host capability boundary | Not covered; must be denied to guest code |

## Additional verification gates

Run exact-tree Java 25 `mvn verify`, `SourceCriticalActorStateTest`,
`SealedActorStateTest`, `ActorStateCrossBackendConformanceTest`, native
image AOT/hybrid, plus LLVM actor scope and codegen tests. For a formal proof
of all guest writes, add a typed compiler IR authority/provenance pass and
mechanically check that **every** emitted persistent state store is dominated
by authenticated write admission and postdominated by revocation on every
exit (normal, throw, raise, panic, cancel). A model checker alone cannot
establish this compiler-to-runtime refinement.

### Security assumptions

The host JVM and trusted runtime implementation are in the trusted computing
base; arbitrary Java agents, reflection/Unsafe, JNI or process memory
tampering are out of scope. User source must not obtain a raw
`SealedActorState` object, host retirement callback, thread-local
retirement permit, arbitrary host callbacks or forged ActorRuntime context.
Admission and teardown must remain internally issued and incarnation-fenced.
