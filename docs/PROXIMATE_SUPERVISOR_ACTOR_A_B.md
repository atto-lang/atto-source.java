# Proximate supervisor S, requester A, worker B

Status (2026-10-20): **proposed source semantics**, with a separate **host-runtime
JUnit proof** in `ProximateSupervisorSeparationTest`. This document does not
claim `define supervisor`, `SupervisorRef.start_child`, supervisor CONTROL
delivery, actor failure restart or Java/LLVM source parity is executable.

## Decision

Spawning an actor is not becoming its supervisor. Distinguish:

- **Request origin**: A asked for B to start. It may hold a typed ActorRef<B>.
- **Lifecycle supervisor**: S authoritatively owns B's restart/stop policy.
- **Structural task/actor scope**: ordinary A->B ancestry, if any, is a separate
  cancellation/lifetime rule and never grants access to S's CONTROL queue.
- **Source locality**: declarations of A, S and B can be in the same .ores file;
  file adjacency is *not* parentage, trust or capability transfer.

A supervisor is a nominal runtime entity with a stable identity and one
immutable supervisor parent (root by default). `define supervisor S` is the
canonical shorter spelling; `define supervisor actor S` remains an explicit
equivalent. Neither may be parsed as an ordinary actor/class alias.

## Source shape (design fixture, NOT presently compiled)

```ores
define supervisor S as
  // Defaults: one_for_one, bounded restart intensity.
end

define actor A as
  // Ordinary application worker; never a supervisor merely for spawning.
end

define actor B as
  receive(ActorMail<String> mail): void {
    self.end();
    return;
  }
end

pub async routine main(): void {
  // The trusted bootstrap creates S under the runtime root supervisor.
  val s = await supervisor.start<S>();

  // S, not main or A, owns B's lifecycle and immutable child spec.
  val b = await s.start_child<B>(
    restart: #permanent,
    shutdown: #graceful
  );

  b.send("hello");
  await b.get_done();
  return;
}
```

The `start_child` spelling is proposed: `SupervisorRef<S>` invokes a
validated CONTROL request, installs child spec and owning group *before*
publishing the child ref, then executes B's on_start. `await` waits for
readiness and releases the worker carrier. A typed `ActorRef<B>` returned
to the requester is data authority, not B's cancellation/restart authority.

For **A requests S to start B**, A should send an authorized start request to
S's separately exposed application DATA endpoint, not directly invoke S's
privileged CONTROL handlers. The supervisor validates a runtime-issued,
scope-limited `StartChild<B>` permit before internally enqueuing the CONTROL
operation. Merely knowing S's name or sharing a source file conveys no permit.
B may also be a static child declared in S's specs during initialization.

## Runtime tree for a same-file declaration

```text
runtime root supervisor
  ├── S : SupervisorRef<S>       [one immutable parent]
  │    └── B : ActorRef<B>       [S lifecycle / restart owner]
  └── ordinary actor A          [requester, NOT supervisor]
         └── request ──────────> S application DATA endpoint

A stopping does not stop B if B was created directly under S and S's
lifecycle scope remains alive. If A creates B inside A's own structured scope
instead, the scope's cancellation rules may stop B; that does not promote A
to a supervisor.
```

## Minimum source/compiler contract

1. AST has a distinct `SupervisorDecl`, nominal `SupervisorRef<S>` and a
   restricted `start_child` operation. Java/LLVM agree on source spelling.
2. The compiler must reject `ActorRef` -> `SupervisorRef` coercion, guest
   `createChildSupervisor`, caller-forged child spec IDs or CONTROL envelopes,
   and unauthorized `start_child` invocations.
3. Start-child is transactional: validate type and authority, reserve a bounded
   child slot and group, bind to S, create B behind a readiness barrier, then
   publish; roll back on failure. No running, unowned transient B.
4. Every child has exactly one supervisor and independently versioned actor
   incarnation; no reparenting by the spawner, ActorGroup member or hot reload.
5. Unexpected B failure produces one authenticated, bounded CONTROL notice to
   S, never actor A. Apply `one_for_one`, `one_for_all`, `rest_for_one` as
   specified; fence stale B refs and escalate exhausted restart budgets.
6. S panic routes to parent/root, never its own policy handlers. Ordinary
   error/reply DATA cannot forge privileged CONTROL.
7. Under S, repeated A spawn requests are subject to quota/backpressure and
   permitted B types; authenticated requests cannot starve reserved CONTROL.
8. Test Java/GraalVM and LLVM guest-source fixture for same-file declarations,
   dynamic B creation, A stop independence, startup failure rollback, B panic
   restart, generation fencing, foreign-runtime permit rejection, cancellation,
   and hot-code revision pinning before claiming end-to-end support.

## Evidence and remaining gap

Existing Java host branch `hardening/supervisor-group-binding-20261011`
implements root/child supervisor registration and `createActorGroup(S)`,
plus a restricted `S.startChildHost(group, ...)` host/CONTROL bridge. The accompanying JUnit proves that A cannot use
those supervisor-only APIs; the privileged host bridge can start B through
S's already-bound group; A's clean stop leaves B alive until S's group closes.
It does **not** prove compiler-generated supervisor dispatch or real restart.

The Java source parser deliberately rejects `define supervisor S as ... end`
in `SupervisorActorSourceAdmissionTest`. LLVM has a native hierarchy registry
and explicit source rejection, but still needs the same compiler lowering.
Full execution requires source AST/type checking, supervisor CONTROL mailbox,
child-spec admission, restart policy, and dual-backend E2E tests.
