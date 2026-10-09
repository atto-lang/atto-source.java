# Oreslang-native OTP / Vert.x messaging (incremental draft)

Status: **draft implementation of local mailbox-address routing** on the Java/Truffle
runtime. This is an evolutionary addition, **not** an Erlang VM or Vert.x wrapper.
The guest-language module/builtin, distributed protocol, and restart strategy
layers below are proposals, not implemented source-language features.

## Reuse rather than duplicate

| Existing component | Reused for | New work in this PR |
|---|---|---|
| `ActorRuntime.ActorRef.send` | actor isolation, payload validation, mailbox capacity, cancellation | target dispatch for named addresses |
| `ActorRuntime.ActorGroup` | capability-secured membership and cleanup; orthogonal to supervision | address names scoped within a group |
| `ActorEventBus` | RELIABLE/LATEST/LOSSY data-only fanout, reductions | unchanged; not replaced by address routing |
| Actor ready/done, parent/child tree | lifecycle barriers, structured cancellation | address registrations removed at actor termination |
| Source actor `on_start`, `receive`, `on_stop` lifecycle design | verticle-like serialized execution | no extra threads or event loops |
| `OresFuture`, channels, `select` | future request/reply and stream operations | request/reply **not** added in this draft |

**Important distinction:** An ActorGroup is an authorized cooperation domain; an
address registry is a set of named mailbox recipients **inside** that domain;
a supervisor owns actor restart/cancellation. Neither the address registry nor
the existing group event bus is an actor, a new global scheduler, or a substitute
for the parent's supervision tree.

## Implemented: local address bus (`ActorAddressBus`)

A host or group-creator defines a name and a Java payload class with
`group.addresses().defineAddress("payments.authorize", Payload.class)`.
The class is a provisional JVM contract boundary; native Oreslang TypeSpec /
type descriptors will eventually replace it.

A group member registers **only itself** inside its executing mailbox turn:
`addresses.joinCurrent("payments.authorize", context.self())`. A named route
requires pre-definition (no implicit topic creation). Registration is idempotent.
An actor can join at most 64 distinct addresses; the number of definitions
is bounded by the group event-topic quota. Invalid names, null/wrong-type
messages, spoofed members, foreign runtime access and registering without group
membership are rejected.

- `members(name)` returns a read-only list of actor IDs (not references).
- `send(name, payload)` chooses one registered actor round-robin, preserves
  ordinary `ActorRef.send` policies and returns an optional selected actor ID.
  Failure is visible, not silently retried on a different member.
- `publish(name, payload)` attempts all current subscribers on a snapshot and
  returns admitted/rejected counts. A full or stopped mailbox counts as
  rejected; a policy/transport security failure aborts the call.
- `leaveCurrent(name)`, leaving an ActorGroup, actor termination, group close,
  and runtime close reclaim route registrations. Definitions themselves remain
  stable for the life of the group.
- Local routing provides **admission**, not processing acknowledgement;
  no durable replay, cross-publisher order, exactly-once, remote clustering or
  distributed membership consensus is implied.

The `ActorAddressBus` is the first **host-side runtime substrate**. Ordinary
Oreslang source does **not** yet call this Java class directly: doing so through
unrestricted JVM reflection would violate the deny-by-default host access
boundary. Wire it through explicit typed guest intrinsics and actor-owned
handles in a follow-up commit/PR.

## Planned native Oreslang surface

Public names and signatures below require parser/type-checker review; they are
**API sketches**, not runnable examples:

```text
// Native typed membership, not an Erlang process ID / Java object:
const pg = ActorGroup.join(capability);
pg.defineAddress<PaymentCommand>("payments.authorize"); // creator only
pg.register<PaymentCommand>("payments.authorize", self);
pg.members("payments.authorize");                  // List<ActorId>
pg.send("payments.authorize", payment);            // one consumer
pg.publish("payments.authorize", payment);         // all consumers
pg.leave("payments.authorize");
```

The compiler must derive the payload type and the current `self` identity.
A guest must never be allowed to supply an arbitrary `ActorRef`, JVM
`Class`, or group manager capability by converting an untrusted scalar.
For private/untrusted actors enforce explicit per-recipient copy/serialization
and appropriate permissions. `ActorEventBus` still forbids private-actor
zero-copy subscriptions; adding this address bus does not lift that limit.

## OTP parity roadmap

1. **pg**: local named groups, join/leave, membership lookups, broadcast.
   This draft provides group-scoped equivalents; not a global process registry.
   Extend to distributed groups only with an authenticated membership protocol,
   leases/epochs, partitions, and clear eventual-consistency semantics.
2. **monitor/link**: add opaque monitor refs, `DOWN`/exit signals, optional
   `trap_exit`, reason propagation and a deterministic lifecycle queue.
   The existing `ready`/`done` and parent ownership are inputs, not yet OTP
   monitor-equivalent APIs.
3. **supervision**: typed specs, transient/permanent/temporary restart modes,
   one-for-one / one-for-all / rest-for-one, bounded restart intensity and
   backoff, no resurrection of cancelled or stale actors. Preserve supervisor
   authority and actor-local state confinement. ActorGroup is never the
   supervision tree.
4. **GenServer-like calls**: request/reply with opaque correlation IDs, bounded
   inflight requests, cancel/deadline propagation, nonblocking futures and
   late-reply discarding; no callback may seize a carrier while awaiting I/O.
5. **GenStateMachine / selective receive**: finite-state dispatch and guards,
   fairness for selective receive, bounded search and cancellation checkpoints;
   not an invitation to introduce unbounded mailbox scans.

## Vert.x parity roadmap

- A **verticle** is a deployable Oreslang actor with `on_start` /
  `on_stop`, a serialized receive handler and a runtime-managed deployment
  handle; it does not own a thread or implicit mutable global context.
- The **local event bus** provides send (point-to-point round-robin) and
  publish (fanout); a typed request/reply and consumer `unregister` contract
  follows, sharing the same mailbox backpressure and message ownership rules.
- **Deploy/undeploy** must be tied to ready/done and supervisor rules; avoid
  "redeploy resurrects terminated actor" races, dangling routes, callback
  leaks and any per-verticle worker pool.
- **Clusters** require remote codec/contract IDs, membership epochs, reconnect
  fences and consumer-credit/backpressure. A network capability must be explicit,
  never acquired simply by joining a local ActorGroup.

## Performance, concurrency and security gates

- Fast-path routing is group-local and never holds the registry monitor across
  actor-mailbox admission, freezing, or user work.
- A broadcast is **not atomic fanout**. Unrelated recipients may see admission
  or rejection independently. Use the existing RELIABLE `ActorEventBus` when
  bounded subscriber-level reliable admission is needed.
- No consumer sees arbitrary host object graphs via a source builtin. Per-send
  `ActorRef` transport remains the security authority and budget boundary.
- Actors outside the group cannot list, join or send by guessing an address
  string. Even group members must prove `self` when registering.
- Never infer message or class safety from erasure of a Java generics type.
  Source-level contracts, versioning and schema conformance remain required.
- Tests must cover no leaked registrations on leave/termination/close,
  unauthorized join, cross-runtime access, round robin, fanout, payload types,
  mailbox backpressure and teardown memory accounting.

## PR review / merge gates

- Run `mvn -q -Dtest=ActorAddressBusTest test` and the complete JVM suite.
- Check the exact Git tree SHA tested on an allocated runner, not just a
  pre-runner Actions failure. If the source organization's runner is unavailable,
  mirror the exact tree into a funded test organization and verify blob identity.
- Add source-language type-checker/evaluator intrinsics, native Oreslang
  conformance tests and a no-Java-interop proof **before** advertising the
  public APIs as language features.
- GraalVM native-image, shutdown races, over-quota and adversarial-isolate
  integration remain separate merge gates.
