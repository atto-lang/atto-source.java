# Canonical sealed actor state-loan modes — source compiler candidate

**Syntax decision:** use **`critical[read]`** and **`critical[write]`**
as explicit modifiers of a lexical `self.handle_state` callback.

```oreslang
define actor Counter as
  state { revision: int = 0; }
  receive(ActorMail<String> mail): void {
    self.handle_state(critical[write] |s| -> {
      s.revision = s.revision + 1;
    });
    self.handle_state(critical[read] |s| -> {
      val revision = s.revision;
    });
    self.end();
    return;
  }
end
```

## Semantics

- `critical[read]` grants an **immutable, lexical** borrow of the actor's
  sealed state. The type checker uses both a read-only structural record
  and a nonmutable borrow; assignments and `&mut` escalation are rejected.
  The guest runtime independently rejects writes through a read view.
- `critical[write]` grants an exclusive mutable loan of the actor-owned
  schema. Other actor fields remain sealed.
- Both require **one synchronous, void, inline lambda**. No await, channel,
  select, runtime cooperations, nested closure escape, or return of state
  references while a loan is active.
- The loan is revoked in `finally` after the callback and guest runtime
  rejects post-revocation access.
- The existing one-argument `self.handle_state(|s| -> {...})` is temporarily
  a compatibility **write** form for existing fixtures; formatter migration
  should canonicalize it to `critical[write]`. New code must use explicit
  modes.
- `critical[read]` currently supports **scalar values only** at runtime,
  failing closed on potentially mutable composite references until
  immutable snapshots or typed immutable projection are proven.
- **No concurrent-reader guarantee yet.** This feature is a read-only
  capability plus existing actor serialized admission; it does **not**
  implement scheduler-level shared reader permits. A future runtime RW
  admission implementation must preserve writer exclusivity and fairness,
  and provide separate concurrency/fault tests.

## Why brackets?

`critical[mode]` is a single selector grammar with one closed set of
modes. It avoids the ambiguity of three competing spellings:
`critical write`, `critical-write`, and bare `critical`.
It also permits future explicitly specified modes without silently
granting write access to an unknown mode.

The parser lowers the selector to an internal literal mode marker
(`critical:read` or `critical:write`) with the callback. Both type
checker and evaluator validate the mode independently. This is a
**typing/capability rule**, not a cryptographic token.

## Acceptance

`ActorCriticalStateModeTest` registers eight positive/negative Java/Graal
tests: real guest write/read, rejected read mutation, rejected mutable
reborrow, unknown mode, callback suspension, nonvoid callback, direct
state access and nested closure escape.

**Status: implementation staged, NOT_EXECUTED.** An exact-head Maven/JUnit
run on GraalVM 25/JDK25, explicit runner job steps and positive/negative
output evidence are required before compiler readiness. Static source
checks and an older compiler branch's test results cannot establish
correctness of this forward-port. Java runtime on the current workspace
is JDK21 with no Maven; no local full compiler execution is available.

The corrected step-function demo PR #10 must be updated to use
`critical[write]` as a **statement** and capture only a detached response
scalar. No actor `send` or `await` is allowed inside its state loan.
