# Sealed actor state: safety argument and proof obligations (draft)

**Status:** finite abstract state-space model checked; Java source-admission hardening
committed on this branch; no Java/LLVM whole-program isolation theorem is claimed.

## Intended source contract

```ores
define actor Counter as
  state { count: int = 0; }

  read(): int with state.read {
    return using state.read(|s| -> { return s.count; });
  }

  increment(): int with state.read, state.write {
    return using state.write(critical |s| -> {
      s.count = s.count + 1;
      return s.count;
    });
  }
end
```

`state { ... }` is a **closed typed schema**, represented by one hidden
compiler-owned storage aggregate. `self.state`, ordinary actor instance fields,
user-defined `handle_state`, direct references and exported loan objects are
invalid. The `with` row bounds effects; it does NOT grant access.
`using state.read/write` or the sealed `self.handle_state` obtains a
lexical, trusted-scheduler-admitted loan. An unmarked callback is read-only.
`critical` is mandatory for write authority even on serial actors.

## Safety property

Let `S[a,k]` denote the runtime-owned value for actor incarnation `a` and
declared key `k`. For every step:

```
S_next[a,k] != S_now[a,k]
    => AuthenticatedCriticalWrite(a, k, turn, epoch)
       OR TrustedActorInitialization(a, k, incarnation)
       OR TrustedCheckpointMigration(a, k, incarnation)
```

No guest code may invoke initialization or migration. Additionally:

- **Layout:** at most one private, compiler-owned state aggregate; no hidden
  actor-owned mutable fields, inherited side tables or mutable static storage.
- **Schema:** each key is unique, statically named and statically typed;
  unknown keys and incompatible values reject before mutation.
- **Capability:** write requires the actor's owner ID, active continuation,
  generation, exclusive writer ticket, explicit `critical`, and `with state.write`.
  Read-only loans are never upgraded by a permissive `with` declaration.
- **Exclusion:** a writer cannot coexist with any reader or another writer;
  readers are allowed concurrently only when actor isolation permits.
- **Scope:** no state loan may escape via return, alias, closure, mailbox,
  channel, global, pointer, reflection or unsafe FFI. Loans end on every exit.
- **Suspension:** no `await`, `select`, `yield`, `rt cooperate`, compiler
  safepoint or blocking host call may retain a live state loan.
- **Lifetime:** stop/restart/reload revokes older generation tickets and
  prevents late callbacks from accessing a retired map.
- **Frame:** read-only callbacks cannot write even through nested containers,
  method dispatch or transitive effects.

These are **proof obligations**, not all discharged by this PR.

## Bounded protocol exploration

Run `python3 formal/check_sealed_actor_state.py` (Python standard library).
The explorer enumerates every reachable transition of a finite abstract
protocol, including concurrent readers, exclusive writers, suspension,
release, STOP, restart, incarnation fences, and an append-only write receipt.
It checks read/write exclusion, owner/epoch authenticity and that all
modelled writes create a matching authorized write receipt. The separate
`authenticated_write_transition(before, after, owners)` check compares
every reachable edge: mutations must be atomic, must extend the receipt
ledger, and must originate while the owning turn holds the live exclusive
write loan. A *coherent forged receipt* may pass the snapshot invariant,
but fails this causal edge check when no write loan was admitted.

This is an abstract transition-refinement obligation, not a cryptographic
or runtime credential. A malicious native writer able to imitate an authorized
transition cannot be distinguished by this abstract state alone. The JVM and
LLVM implementations must enforce unforgeable gate acquisition and guarantee
that no other store path exists.

Observed locally:

| Turns mapped to actors | Reachable states | Transitions |
| --- | ---: | ---: |
| (0, 0) | 275 | 748 |
| (0, 0, 1) | 10,933 | 47,856 |
| (0, 1, 2) | 53,507 | 267,432 |

Unlogged raw writes, forged write receipts, and read/write overlap mutation controls are rejected. Bounds: at most three actors,
three turns, one write per actor, one stop/restart generation. This is
**bounded exhaustive model checking**, not an unbounded mathematical proof.

## Trusted computing base and outstanding work

This protocol model grants no authority to source-written C++/Java host code.
An arbitrary native FFI/host callback can retain raw pointers or directly
modify host memory; compiler source restrictions alone cannot prove isolation
when that is permitted. Native actor-lane ticket creation, LLVM store lowering,
Java heap reflection, JIT/Native Image, macros/annotation expansion and
interprocedural effect inference remain inside the unverified TCB.

Before claiming end-to-end isolation:

1. Inventory *every* Java/LLVM IR write to actor-owned state: require a single
   sealed write intrinsic with authenticated ticket/owner/generation and no
   aliasing store paths. Check constructors, destructors and bulk operations.
2. Validate that generated code cannot expose a writable heap pointer,
   mutable reference or pointer derived from `state`. Ban unrestricted FFI
   in a critical callback; make native host boundary explicitly unsafe/trusted.
3. Make effect and alias checks interprocedural, including trait/interface
   dispatch, inheritance, closures, implicit loops and optimization passes.
4. Prove the runtime/transport scheduler implements the model's admission,
   exclusivity, release and stop-generation transitions (e.g. TLA+/TLC or
   refinement proofs), plus crash and cancellation tests.
5. Require source-to-IR negative tests for every escape route and real
   multithreaded race tests, Native Image/AOT and cross-backend conformance.
6. Specify trusted initialization, checkpoint and reload as separate
   privileged paths; preserve schema typing and epoch fencing through them.

Do not describe a pass of the AST checker, this bounded model, or a native
unit test as a whole-program formal verification proof.
