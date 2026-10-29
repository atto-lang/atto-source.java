# Sealed actor-state admission gate: implementation sequencing

**Status (2026-10-29): targeted fail-closed parser gate and negative
tests. NOT executable state-schema/loan support.**

The userland demos need these source forms:

```ores
define isolated actor WorkflowCoordinator as
  state {
    phase: String = "created";
  }
  receive(ActorMail<String> mail): void {
    val response = self.handle_state(critical |s| -> {
      if s.phase == "created"; do
        s.phase = "running";
        return "started";
      fi
      return "rejected";
    });
    self.send(response);
    return;
  }
end
```

The present parser builds `Ast.ClassDecl` from ordinary actor fields and
methods. It has no independently typed, sealed `state` schema in that AST,
and the VM's `ActorStateLoans` is not connected to source callbacks.
Silently treating these forms as ordinary fields/methods would violate
the single-schema state isolation invariant.

This PR gives an *actionable diagnostic* for `state { ... }`, and rejects
user-defined `handle_state` methods so that an unchecked shadow intrinsic
cannot be silently accepted. It does **not** enable either feature.

## Implementation sequence (do not shortcut)

1. **AST/parser:** add a dedicated optional `ActorStateSchema` to
   `Ast.ClassDecl`, with one typed field table and initializers. Reserve
   `state` only in actor-member schema position. Reject multiple schemas,
   ordinary actor instance fields and shadowed/inherited schemas; allow
   stateless actor declarations without empty state blocks.
2. **Static semantics:** resolve types and ownership of each schema field;
   initialize exactly once per actor incarnation. `self.state` and
   arbitrary cross-actor access are forbidden. The only state access is
   a scoped `handle_state` loan or verified lexical equivalent.
   Critical-write callbacks cannot return state references, store loans,
   invoke unknown effects, await/select/cooperate or send messages, or
   call another state-loan-taking method.
3. **Runtime:** per-actor state allocation in an actor-owned private arena
   for isolated actors, with bounded charge/admission, generation/identity
   fencing, deterministic cleanup and validated field layout. Bind state
   loans to logical turn/continuation identities, not OS threads.
4. **Lowering:** both JVM/Truffle and LLVM must bind callbacks to the same
   state admission and enforce exclusive critical check-and-write. Do not
   emulate by unprotected field mutation or a JVM-wide mutex. Implement
   immutable state reads separately from critical writes, with no unsafe
   auto-upgrade.
5. **Guest tests:** compile and execute the step-function coordinator and
   all 20 AI agent actors on pinned compiler/source tree, with exact stdout
   and actual nonzero job steps. Cover duplicate/out-of-order transitions,
   stale generations and concurrent mutation. Negative cases must reject
   ordinary actor fields, duplicate schema, direct `self.state`,
   shadowed `handle_state`, escaped loans, nested loans, suspension,
   cross-actor access and unbounded resource use.
6. **Durability/parity:** an in-memory state schema does not create a
   durable checkpoint. Prove committed snapshot+WAL/outbox recovery,
   independent effect reconciliation, then run the same language guest
   tests on LLVM, .NET and Wasm where supported.

Do not merge demo state migrations on a source-only structural test.
Keep source-side CI runner-admission failures (zero executed steps)
separate from compiler test results. Never transfer private source
to another organization without the independent export/carrier approval.
