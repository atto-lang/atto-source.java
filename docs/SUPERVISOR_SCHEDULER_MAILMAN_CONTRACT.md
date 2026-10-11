# Scheduler, Mailman and Supervisor: source/host/native parity contract

Status: **incremental runtime substrate; source declarations NOT yet executable**.

## Source-level entity grammar (target)

```ores
define supervisor S as
  // User-authored CONTROL-plane supervision policy and handler methods
end

define mailman M as
  // Rare advanced customization of outbox routing / bounded delivery
end

define scheduler X as
  // Rare advanced customization of scheduling policy (not host threads)
end
```

These must become **distinct nominal declaration kinds**, not aliases for
`define class`, `define actor`, or unsafe host callbacks. The lexer, AST,
resolver, type checker, codegen and import pipeline in BOTH compilers must
agree before either backend claims source support. Until then, reject clearly,
rather than silently lowering these declarations as ordinary actor classes.

## Always-on supervisor assignment

1. Every ActorGroup receives one registered supervisor **within its constructor**,
   before publication in the runtime's live group map. There is no unsupervised
   observable intermediate state and no public reassignment operation.
2. `createActorGroup()` remains compatible, bound to the runtime-owned root
   supervisor. `createActorGroup(supervisor)` explicitly binds a registered,
   same-runtime supervisor and requires host/CONTROL authority. A guest actor
   cannot nominate another supervisor using a captured host reference.
3. The supervisor enumerates only live advised groups. On closure, the live
   association vanishes; stale group objects retain their original supervisor
   **identity**, never acquire another supervisor, and cannot be revived.
4. Advisory scope is **not** group membership, managed-worker ownership,
   structured actor ancestry or arbitrary lifecycle authority. Group
   membership and Mailman CONTROL-carrier identity confer no supervisor rights.
5. Root supervisor and explicit registered identities in this runtime slice
   are not yet compiler-lowered source `define supervisor` instances. Their
   only implemented capabilities are association and read-only enumeration.

## Intended source APIs (not yet implemented)

- An ActorGroup constructor must require a `SupervisorRef<S>` or use the
  owning runtime's root supervisor implicitly; in both cases the binding is
  complete before event-bus admission or worker spawning.
- `SupervisorRef<S>` exposes supervised-group *identity snapshots* and
  controlled lifecycle requests, not raw mutable ActorGroup internals.
- Typed `on_throw` / `on_raise` hooks may run on the failing actor only
  after ordinary handlers fail, but actor **panic bypasses actor-local hooks**
  and goes directly to the supervisor's priority CONTROL path.
- Restart strategy must specify one-for-one / one-for-all / rest-for-one,
  bounded retry budgets, backoff, and the causal generation/fencing token.
  A restarted actor receives a new incarnation and no stale task or mail may
  mutate it. Teardown and memory reclamation precede `get_done()`.
- Mailmen are one logical consumer per ActorGroup, bounded and backpressured.
  They may suspend cooperatively; producer completion threads must only enqueue
  resumed CONTROL turns. A Mailman must not install itself as supervisor.
- Schedulers own logical task affinity, fairness and cancellation; `await`
  must release carrier threads. CONTROL always has bounded reserved capacity
  and priority over ordinary actor mail. Custom schedulers cannot commandeer
  native threads or bypass scheduler-owned continuation admission.
- Explicit supervisor selection is privileged, but ordinary ActorGroup
  creation by actor code stays available with a safe root fallback.

## Parity gates

| Invariant | Java substrate | LLVM substrate | Source-level parity |
| --- | --- | --- | --- |
| Every ActorGroup bound at birth | Runtime root / explicit registration | Root / explicit immutable ID | Pending |
| No cross-runtime supervisor injection | Runtime object-identity check | Nonzero native id; host binding still required | Pending |
| Live advised-group enumeration | Runtime snapshot | Host registry still needed | Pending |
| Logical scheduler / nonblocking awaits | Existing OresScheduler task turns | Native scheduler + partial await substrate | Pending |
| Mailman bounded serialized CONTROL turns | Existing cooperative Mailman | Full mailman lowering not implemented | Pending |
| Typed supervisor policy and panic dispatch | Existing host/control mechanisms only | Native reserved CONTROL mechanisms only | Pending |

**Merge gate:** Matching executable Java and LLVM tests for immutable binding,
then compiler admission tests proving source syntax is either fully supported
or explicitly rejected until executable semantics exist. No nominal keyword may
be represented as a simple class alias. Do not report full parity from these
host-only changes.

## Hierarchical supervisor actor direction (October 11 update)

The canonical source form is `define supervisor actor Name as ... end`.
`define supervisor Name as ... end` may be treated as shorthand once both
compilers implement nominal supervisor-actor lowering. `define proxy actor`
is a typed cross-process transport endpoint, not a supervisor by default.

Java now models one immutable parent per registered supervisor (except root),
direct child-supervisor enumeration, direct ActorGroup scope and transitive
subtree ActorGroup observation. All registration and explicit group binding
reject cross-runtime supervisor handles, including identities with identical
human-readable or numeric labels.

See [full architecture and hot-code upgrade protocol](OTP_SUPERVISION_TREE_HOT_RELOAD.md).
Supervisor code hot replacement, CONTROL handlers and process proxies remain
design gates, not completed runtime features.
