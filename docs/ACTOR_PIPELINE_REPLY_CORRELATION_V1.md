# Actor pipeline reply correlation — host contract (pre-1.0)

**Status:** Java host reference `ActorPipelineRouteSession` plus an
authenticated, real-mailbox delivery adapter
`ActorPipelineMailboxRouter<M>` implemented on the multi-consumer draft.
**No compiled Oreslang `self.*` lowering** exists. The adapter is restricted
to trusted Java actor code and preserves the existing `ActorRuntime.send`
mailbox/admission/ownership guards. Host traces are not compiled guest proof.

## Message identity and path

Each trusted ingress begins a flow under a positive **graph generation** and
receives an opaque host `MailFrame`. The supervisor must already have
authenticated the source actor identity and its graph epoch. A frame carries:

- `generation`: which immutable graph topology the route belongs to;
- `flowId`: correlation of one originating flow across fan-out branches;
- `routeId`: unique within the host route ledger for each branch/message;
- an immutable path of actor **instance IDs**, not nominal class names.

`forward_all` creates independently identifiable child frames (shared flow,
different route IDs), and `forward_round_robin`, `forward_to`, and
`forward_by_key` create one child. Each selected hop must be a real
supervisor-validated outgoing edge of the current actor instance.

The ledger tracks a bounded set of live frame **identities** and enforces
one-shot terminal use. A copied or replayed Java handle cannot reply or
complete a second time. A stale or foreign handle cannot be used after graph
generation changes or STOP. Branch allocation is checked before fan-out; this
does **not** turn later mailbox admission into an atomic fan-out transaction.

## Executing Java actor mailboxes (not guest Oreslang lowering)

The supervisor may construct `ActorPipelineMailboxRouter<M>` on a trusted
host turn with a validated `ActorPipelineRoutePlan` and an exact set of live,
same-runtime `ActorRef<M>` bindings. Every graph instance must be uniquely
bound; no missing, extra, duplicate or foreign actor references are accepted.
The owner runtime's supervisor authority check forbids construction from actor
turns, ActorGroup CONTROL Mailmen, scheduler continuations and force-cancel
callbacks.

The router does **not** accept a caller-selected source identity. Each route
derives its source from the current runtime's actor execution context and
denies unknown, stopped, foreign-runtime, or untrusted callers **before**
mutating per-source round-robin state. A named target must be an actual
supervisor-approved outgoing edge.

`forwardAll` independently invokes `ActorRuntime.send` for each declared
edge, returning one ordered per-target `Admission` with its individual
success or failure **and immutable supervisor-plan `edgeIndex`**. This
index refers to the original validated edge list (not an unordered map or
a source-local position), matching the native host's labelled routing
receipts. Partial success is not a transaction or a processing
acknowledgment. `forwardRoundRobin`, `forwardTo`, `forwardByKey` and
single-edge `forward` admit through the same established mailbox invariants.

**Unverified integration gate:** `ActorPipelineMailboxRouterTest` exercises
actual Java actor callbacks, STOP of one fan-out recipient, identity spoofing,
and foreign/duplicate actor binding rejection. It must pass under the full
Java Maven/JUnit runner; creating that JUnit fixture is not equivalent to
executing it. The standalone routing-plan and correlation-ledger tests do
not run the full ActorRuntime.

## Backward versus reply

`backward` returns a two-entry reverse path to the **immediate authenticated
upstream sender** for the current frame. `reply` returns the full reverse
route to the originating instance, preserving each intermediate hop. Neither
operation accepts a caller-chosen upstream destination.

Example: `source → worker-a → sink` and `source → worker-b → sink`
are distinct branches. The sink's two replies must use:

- `[sink, worker-a, source]`
- `[sink, worker-b, source]`

They share one flow ID but not a route ID. A single global “previous actor”
field is insufficient and is forbidden for fan-in.

A returned `ReturnPath` is **informational host routing metadata**, not a
bearer capability. The production scheduler must enforce each reverse hop,
verify the receiver's active actor turn and graph generation, and serialize
only approved `Sendable` actor-message values. Users must never be able
to construct, deserialize, or install `MailFrame` as a current turn.

## Scheduling and ownership constraints

- All operations on the ledger are synchronized, but this does not itself
  schedule any guest actor continuation.
- At most one physical native thread may execute user code per actor,
  even with several outstanding logical message lifetimes.
- No actor-owned state loan may cross a suspended route operation; graph
  control state stays scheduler-owned, outside `state { ... }`.
- Return receipts prove mailbox **admission only**, never processing,
  durability, exactly-once execution, or transactional broadcast.
- A round-robin attempt consumes its selected turn even if subsequent
  admission fails. Its cursor is reset when the graph generation advances.
- A supervisor STOP clears active route contexts and fences all new ingress.
  Late completions fail, rather than leaking into a replacement graph.
- Per-edge message, pending-writer and byte budgets remain mandatory in the
  actual transport; this host ledger only bounds correlation frames.

## Reproducible host verification

From the Java repository root:

```sh
sh scripts/test-actor-pipeline-route-plan.sh
sh scripts/test-actor-pipeline-route-session.sh
```

The second command strictly compiles the production plan, production session
and standalone fixture using JDK 21 `-Xlint:all -Werror`. The fixture covers
branched fan-in, replay rejection, wrong connections, partial fan-out frame
reservation, STOP, generation transition and 2,000 concurrent branch
selections. Maven also invokes the fixture from
`ActorPipelineRouteSessionTest`.

## Explicit clean and aborting generation barriers

A clean `advanceGeneration(successor)` **rejects** while any active
`MailFrame` remains. This is mandatory to avoid silently erasing in-flight
messages during graph reconfiguration. A rejected attempt must not mutate
the current epoch or its round-robin cursor.

Only the trusted runtime/supervisor may call
`abortAndAdvanceGeneration(successor)`. This explicitly invalidates every
outstanding frame, resets route selection, and returns the number of retired
frames. It **does not** itself drain/cancel queued transport mail, settle
pending writer receipts, or stop user actor continuations; those physical
scheduler barriers must be completed before publishing the successor graph.
Neither method is a guest-callable `self.*` method. The Java session and
constructor are package-restricted while source-language lowering is absent.

Standalone JDK 21 tests exercise rejected live changes, successful quiescent
changes, cancelled stale-frame replay, non-increasing generation denial, and
10,000 successive two-consumer fan-outs at the tight three-frame limit.

## Trusted SHARED actor routing and retirement hardening

The real-mailbox Java host adapter is **SHARED-turn-only** until
scheduler-owned, isolation-safe routing can be compiled for PRIVATE and
UNTRUSTED actors. A captured host adapter is not an authority to leak its
mutable selector into a private actor heap. Every route validates the
scheduler's active DATA receive, actual current actor identity, runtime
affinity and source connection; startup, terminal cleanup, arbitrary host
calls and untrusted/private actor turns are rejected before admission.

Each `ActorPipelineMailboxRouter` binding has its **own** deterministic
round-robin cursor. A new binding does not inherit cursor state from a
previous topology generation even if the supervisor reuses an immutable
`ActorPipelineRoutePlan`.

`ActorPipelineMailboxRouter.close()` is supervisor-only. The fair
reader/writer retirement gate protects each entire mailbox admission or
fan-out attempt and establishes the linearization barrier: no new routed
mailbox admission can succeed **after** close returns. Actors use
`tryLock` instead of blocking their carrier while host graph retirement
is queued. Close does NOT silently revoke already accepted actor mail,
cancel accepted processing, or falsely complete `get_done()`.

A new Maven-free CI gate:

```sh
sh scripts/test-actor-pipeline-router-host-gate.sh
```

compiles the actual production Java adapter with JDK 21
`--release 21 -Xlint:all -Werror` against narrow test doubles, exercising
source impersonation denial, PRIVATE/UNTRUSTED rejection, per-target partial
results, per-binding RR reset, stale router denial and supervisor-only
retirement. Its 8-carrier synthetic contention harness makes 384,000 route
attempts in 40 rounds to check the post-close barrier.

**Evidence boundary:** These test doubles do not model true
`ActorRuntime` single-flight execution, Guest `ActorMail<T>`, GraalVM,
JNI, or compiled LLVM execution. The real JUnit `ActorPipelineMailboxRouterTest`
remains a separate mandatory gate before enabling source routing.

## Actual correlated Java mailbox proof — 2026-10-16

`ActorPipelineCorrelatedMailboxRouter<M>` joins the existing
`ActorPipelineRouteSession` to the real `ActorRuntime` mailbox admission
path. It is a **package-private trusted SHARED-actor bridge**, not an Oreslang
guest intrinsic. Supervisor-selected ingress roots create VM-owned route
frames; subsequent forwarding reads the current actor's authenticated
receive frame, validates its physical actor identity and graph instance,
and delegates payload validation, freeze, ownership checks and bounded
mailbox admission to the existing runtime.

The frame is attached to the **internal `MessageEnvelope`**, **not to the
`ActorInboxMail<M>.value()` guest data**. Per-envelope frame identity
contains immutable graph generation, flow ID, distinct route ID, complete
verified forward actor-instance path, and reverse hop cursor. It is never
deserialized from a user message or selected from a caller-supplied name.
Incoming ordinary messages have no frame and cannot acquire routing
authority. Registry creation and ingress require supervisor context;
each binding must be a live SHARED actor in the owning runtime.

`forward`, `forward_all`, `forward_round_robin`, `forward_to` and
`forward_by_key` create independently accounted child frames and publish
the ordinary typed Java host payload over actual mailboxes. Partial
`forward_all` failures retire their failed child while preserving receipts
for already admitted recipients. A fatal publication error also retires
unattempted children. Frame cleanup happens after normal receive, source
receive continuation completion, discarded queue messages, and STOP
teardown. No post-retirement use of a stale frame is allowed.

**Reverse DATA is now executable in this host bridge:** a sink calls
`backward(payload)` and the runtime delivers one hop to the immediate
verified predecessor from its immutable route path. That predecessor may
call `backward(payload)` again, walking the sealed path to the source.
Fan-in branches cannot guess a global "previous worker" or forge an
upstream destination. Each reverse hop consumes its previous route frame
and gets a unique new route ID while retaining the flow ID. A host
`currentCorrelation()` gives read-only metadata for the authenticated
current turn.

The executable Java JUnit fixture
`ActorPipelineCorrelatedMailboxRouterTest` exercises a four-instance
source → two workers → sink fan-out/fan-in and **two reverse acknowledgments
through their original workers to the source**, without embedding route
metadata in user payloads. The standalone strict-JDK21 ledger smoke also
checks per-branch reverse paths, one-shot return, replay rejection, the
origin barrier, and tight frame credit recovery. These fixtures are
**committed but not yet verified by an executing exact-tree Maven runner**.

Limitations: this uses the Java host's one generic payload type `M`
across both directions; the real guest compiler must check *separate
forward and backward nominal types* and Sendable/ownership rules. Guest
`self.reply` to origin is not enabled, and distributed/remote routing,
PRIVATE/UNTRUSTED actor isolation-safe handles, native/LLVM reverse
mailboxes, and .net/.wasm/.forge parity remain pending. A host return
**hop** does not prove source-level `self.backward` or `self.reply`.

## Still required before guest-language release

1. Compile source-declared actor graphs with runtime-assigned instance IDs.
2. Lower **sealed** `self.forward`, `self.forward_all`,
   `self.forward_round_robin`, `self.forward_to`, and
   `self.forward_by_key` to scheduler-owned typed message admission.
3. Bind `self.backward` and `self.reply` to the authenticated current
   `ActorMail<T>` context, with per-hop generation fencing.
4. Integrate real `Sendable` actor messages; reject host pointers,
   cross-actor mutable aliasing and escaping borrows.
5. Run exact-tree Java Maven + Native Image and LLVM CTest/ASan/UBSan
   with compiled 3+-actor fan-out and fan-in programs. Verify both
   partial-pressure and physical STOP/DONE ordering.
6. Keep compiler rejection for unsupported guest methods until they genuinely
   execute; never silently reinterpret them as ordinary methods.

## Final mailbox recipient binding — 2026-10-17

The trusted Java router now captures an immutable mapping from checked graph
**instance ID** to real, same-runtime `ActorId` at supervisor registration.
The `ActorPipelineRouteSession` binds that mapping to its sealed frames.
At the **last `ActorRuntime.sendWithPipelineFrame` DATA admission boundary**,
the runtime independently enforces: (1) live SHARED receiver kind,
(2) authenticated active frame and generation, and (3) exact equality of
the frame receiver's bound `ActorId` and the target `ActorRef.id()`.
The guard runs **before mailbox slot reservation** and payload copying.

Selector-only standalone route sessions remain useful for pure routing
contract tests, but deliberately lack runtime bindings and **cannot**
publish mail envelopes. A wrong target, foreign/stale frame, private
recipient, and unbound selector frame must fail closed without consuming
a mailbox slot or route credit. Added standalone JDK21 smoke coverage
and real ActorRuntime JUnit tests. Null forward/reverse DATA is also
rejected **before** route selection or one-shot frame consumption.

This does not grant guest-accessible routing: compiler guest lowering,
fully typed forward/backward payload contracts, and exact-tree CI remain
separate release gates. These new tests are committed, not claimed as
successfully executed on the latest exact PR head.

