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

## Implemented in stacked Java stage two (draft / unexecuted)

The static module contract may also require a method on its named actor:

```atto
define contract CartModule as
  actor Cart;
  pub mtd get_count(Cart cart)(): int;
end

define module carts conforms CartModule as
  pub define actor Cart as
    pub get_count(): int { return 1; }
  end
end
```

The first parenthesis names a **non-generic nominal actor**, not a value
argument or a transferable receiver token. The second parenthesis contains
method parameters. The checker requires an existing method declared within
the matching public non-generic actor, an exact name+arity slot, matching
argument/return types, and public visibility when specified. Duplicate
contract receiver slots of the same actor/name/arity are rejected (argument
types cannot overload the same slot). Inherited contract actor requirements
are usable without repeating `actor Cart;`. This does **not** mint an
`ActorContext<Cart>` or any state-loan authority and does not let module
functions invoke actor methods without the mailbox. Stage two rejects
unproven generic contract substitution, borrowed receiver types,
structural method arguments, and checked actor effects.

Runtime-only lifecycle/control names such as `tear_down`,
`on_panic`, `on_restart`, and compiler-owned `handle_state` cannot be
declared as ordinary receiver-method requirements. The module must not
turn a supervisor/control operation into a user-invokable mailbox API.
Likewise, an ordinary module `fnc` with the same name does not satisfy
a receiver requirement.

The Java frontend and linked/export ABI paths are changed; the corpus in
`contracts/actor-receiver-v1/` and JUnit source tests are **UNEXECUTED**.
Backend support, real guest execution, and parity remain unverified.

## Stacked Java stage three: compact actors and guarded named bindings

The following compact actor-type syntax reuses the **existing empty-body
actor class** and carries existing nominal actor metadata, default serial
scheduler settings, class export rules and contract identity:

```atto
define contract EmptyActorApi as
  actor Cart;
end

define module carts conforms EmptyActorApi as
  pub define actor Cart;
end
```

`pub define actor Cart;` is an equivalent spelling of
`pub define actor Cart as end`. It does **not** create an actor instance
or invent any methods. Existing class-style actor syntax remains supported.

The requested named binding is recognized and preserved as an explicit
`ClassDecl.standaloneBinding` field:

```atto
define module carts as
  pub define actor Cart as cart;
end
```

This **parses**, but **must fail** type checking, incremental ABI
generation, and exported-interface certification until checked activation
and external receiver-method dispatch are implemented. No `cart` value,
`ActorRef<Cart>`, implicit singleton, state loan or supervisor authority
may be synthesized. This restriction applies to private and public actor
bindings. Generic, inherited and transport-aware named bindings are not
admitted in this slice.

The stacked `StandaloneActorDeclarationAdmissionTest` adds 19 authored
JUnit tests (UNEXECUTED), including namespace/contract conformance,
old syntax equivalence, fail-closed named bindings, ABI equivalence,
backward-compatible constructors, and direct ABI refusal. In particular,
`AnnotationExpander` must preserve `standaloneBinding` when it rebuilds
class declarations: erasing the marker would bypass type-check admission.
A dedicated regression case checks this preservation and rejection.
Portable `.atto` samples live in `contracts/standalone-actor-v1/`.
Java/Graal parser/type-checker coverage and executable guest behavior
remain **unverified** until exact-head, stepful runs.

## Stacked Java actor-target contract stage (draft / unexecuted)

```atto
define contract FooContract for actor as
  mtd abc(): void;
  pub mtd xyz(): void;
end

pub define actor Foo implements FooContract as
  pub abc(): void { return; }
  pub xyz(): void { return; }
end
```

An actor-targeted contract is a nominal collection of required **public
actor method slots**. It is neither a module nor a namespace contract;
ordinary classes may not `implements` it. Existing actor methods must
satisfy the contract, and the normal actor mailbox still mediates calls.
The v1 actor contract rejects generics, inheritance, fields, `fnc`
requirements and unsupported checked effects. Required methods must be
**directly declared, public, concrete, nonstatic and synchronous** on the
actor itself; a parent class method, private method, suspended/async method
or explicit receiver with unproved lowering is not a witness. Runtime-only
`tear_down`, CONTROL `on_*` hooks, compiler-owned `handle_state`, and
special `receive` handlers cannot be laundered into ordinary public
actor contracts. The parser and type checker also reject `pub tear_down`
even without a contract; teardown remains private and synchronous.
`on_stop` and `on_restart` cannot be user-defined until compiler-owned
generation-fenced CONTROL lowering exists. These hooks require their own typed lowering.
It **does not** grant actor-state, mailbox-dispatch or supervisor authority. Imported
cross-file actor contract metadata and external executable method
bodies are outside this stage. The actor-contract fixture suite and
JUnit tests are authored but **NOT EXECUTED**.

## Proposed next stage: executable external receiver methods

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
