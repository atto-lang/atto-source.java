# Actor types, external receiver methods, and module contracts

**Status (2026-11-10): staged / draft.** This document distinguishes the
implemented Java frontend **stage-one nominal actor requirement** from the
proposed external receiver-method model. Do not report the latter as compiled,
executed, NativeAOT-verified, or portable across backends until exact-head
guest execution confirms it.

## Implemented in this Java compiler change (stage one)

A module contract can require a named public actor **type**:

```atto
define contract WorkerModule as
  actor Worker;
end

define module worker_mod conforms WorkerModule as
  pub define actor Worker as
  end
end
```

- `actor Worker;` is a nominal type-presence requirement. It creates no
  instance, mailbox, method, state loan, or runtime capability.
- A matching `pub` **non-generic** actor declaration in the **conforming
  module** is required. A private actor, similarly named class, a generic
  `Worker<T>` actor, an actor in a different module, or a missing declaration
  is a compile-time conformance error. Generic actor requirements need a
  separate explicit type-parameter contract before being accepted.
- Required actor names may not collide with required fields/functions,
  including inherited requirements, or reuse a contract generic parameter.
  The module's runtime value namespace is not allowed to become ambiguous.
- The requirement participates in inherited module contracts, incremental
  export signatures, and the exported interface fingerprint.
- Duplicate actor requirements in a contract are rejected.
- Module conformance continues to use the established `conforms` syntax.
  Do not introduce an incompatible module-level `implements` spelling.
- A contract is a **module contract**, not an actor interface or trait.
  The module requirement does not grant access to actor-owned mutable state.
- This stage does **not** provide standalone actor declarations or externally
  defined receiver methods. The existing class-like actor declaration remains
  the executable definition.

## Proposed next stage: standalone actors and receiver methods

The intended surface grammar is:

```atto
define contract ActorModule as
  actor A;
  mtd receive(A a)(Message msg): void;
  pub mtd get_count(A a)(): int;
end

define module actor_mod conforms ActorModule as
  pub define actor A as a;

  define mtd receive(A a)(Message msg): void {
    // Dispatched on actor A, with compiler-issued execution authority.
  }

  define pub mtd get_count(A a)(): int {
    return 0;
  }

  define pub fnc bar(A a): void {
    // Ordinary actor reference; no direct actor-state authority.
  }
end
```

**This example is a design fixture, NOT executable AttoLang today.**
The `Message` type is illustrative. The proposed `define state A as ... end`
syntax is a separate future state-layout design and must not be implemented
as an unrestricted alias for host memory.

### Required representation and semantic checks

1. Introduce a typed receiver-method declaration node that references
   precisely one canonical actor identity. It is not a free function with
   a privileged parameter.
2. Resolve external methods against the actor's *declaring module* with
   explicit import provenance. Reject orphan implementations, foreign actor
   extensions, duplicate method slots, conflicting definitions, and cycles.
3. Bind the receiver to an unforgeable `ActorContext<A>` capability **only
   during authorized actor dispatch**. A normal `fnc bar(A a)` obtains an
   `ActorRef<A>`, not mutable state, and may schedule calls but cannot
   directly invoke receiver bodies.
4. Lower the standalone and class-like spellings into the same actor
   metadata, ownership model, mailbox scheduler, and supervisor boundary.
   Preserve existing actor syntax as compatible sugar.
5. Keep actor nonreentrancy across suspension: an `await` must not permit
   a second receiver turn until the first logically completes or is
   canceled and physically quiescent.
6. Enforce borrow lifetimes, fair reader/writer admission for state loans,
   one semantic owner, generation-fenced restart/hot reload, and
   cancellation/STOP cleanup. Supervisors never inherit writable actor
   state authority by virtue of declaring the module.
7. Define a separate *actor contract* (receiver and handler requirements)
   from a *module contract* (exported actor types and functions). Resolve
   required visibility, parameter types, return types, checked effects, and
   message dispatch without runtime reflection.
8. Existing method overload identity is name + arity. Multiple
   `receive(A)(DifferentMessage)` declarations with equal arity MUST NOT
   silently collide or silently resolve by static argument type. Reject
   ambiguous overloads until a distinct, proven typed-message dispatch
   identity and runtime mapping are available.
9. Include method signatures, actor identity, contract requirements,
   effects, and capability restrictions in deterministic ABI fingerprints,
   including cross-file linkage. ABI drift must invalidate dependents.
10. Do not let a contract witness be treated as a runtime capability. A
    witness binds signatures at compile time; actual dispatch verifies actor
    identity, generation, and execution permit at runtime.

### Acceptance tests to implement with the receiver-method work

| Case | Expected |
| --- | --- |
| Standalone public actor + required receiver methods | Compile and execute |
| Class-style actor with equivalent receiver methods | Same externally observable behavior |
| Missing required method | Compile error |
| Wrong return/argument/effect/visibility on a method | Compile error |
| Non-actor passed to receiver `mtd` | Compile error |
| Orphan/foreign/duplicate receiver implementation | Compile error |
| Ordinary `fnc` tries to read or write actor state | Compile error |
| Receiver capability retained in a task or closure | Compile error |
| Two one-arity `receive` handlers without unique typed dispatch | Compile error |
| External actor call invokes receiver directly outside scheduler | Reject |
| Await/cancel/restart while receiver has outstanding loans | No reentrancy, no stale authority |
| Cross-module change of required method slot or actor name | Rebuild/reject |
| Module conformance on Java, LLVM, .NET, Wasm, Forge | Exact-head executed guest receipts per backend |

## Evidence gates

Stage-one tests live in
`src/test/java/dev/oreslang/ModuleActorContractRequirementTest.java`,
which also reads the reusable `.atto` corpus at
`contracts/actor-module-v1/` through the real Java parser/type checker.
The corpus includes an acceptance fixture and negative fixtures for
visibility, kind, generic arity, and inherited contract namespace collisions.
The acceptance fixture still needs actual compiled **guest execution** for
its `main(): int` value; a Java type-checking pass alone does not prove that.

These tests prove Java parser/type-checker and ABI behavior **only when executed**.
They are not compiler-backed guest behavior, NativeAOT evidence, or evidence
of any other backend. An Actions workflow with `steps=[]`, `steps=null`,
or `runner_id=0` is a runner-admission failure, not a pass.

Before promoting stage one: execute JUnit tests, run the compiler on a
standalone `.atto` guest sample, record head commit/tree and test-step
receipts, and re-run full module/actor compatibility suites. For the
receiver-method stage, add real accepted/rejected `.atto` compiler
fixtures and executable actor isolation/dispatch oracles; no host-only
emulation may be reported as guest parity.

The source repository remains `oreslang-source.java`; AttoLang naming
and `.atto` examples are canonical while legacy `.ores` remains supported.
