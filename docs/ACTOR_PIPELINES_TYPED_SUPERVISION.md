# Actor pipelines, supervisor separation and typed mail flow

Status: **semantic admission substrate implemented; source lowering pending**.
Java: `ActorPipelineContract`; LLVM: `ActorPipeContract.hpp`. These
contracts validate nominal role position and payload type for all edges.
They do **not** yet allocate mailboxes or enable `spawn ... pipe spawn ...`
in either compiler. Both parsers currently reject unsupported new syntax.

## Proposed source grammar

```ores
define readable pipe actor A1 as
  // emits Event; no data predecessor
end

define transform pipe actor A2 as
  // consumes Event, emits Result
end

define writable pipe actor A3 as
  // consumes Result; terminal sink
end

define duplex pipe actor Bidirectional as
  // advanced protocol; consumes and emits in both directions
end

pub fnc connect_actors(): ActorStream {
    return spawn A1() pipe spawn A2() pipe spawn A3();
}
```

The keyword `pipe` is contextual, not the `|` operator used for
bitwise expression syntax, lambda delimiters and union types. Parse the
chain left-associatively into a dedicated PipelineExpr or checked IR,
not a series of ordinary function calls or generic binary operations.

Actor declarations expose nominal `PipeSource<Out>`,
`PipeTransform<In,Out>`, `PipeSink<In>` or
`PipeDuplex<In,Out,ReplyIn,ReplyOut>` obligations in the compiler. The
`ActorStream` handle owns a structured pipeline lifecycle and connection
capability; it is not an unsafe collection of untyped ActorRefs. The
`ActorStream` handle may be generic (`ActorStream<In,Out>`), with a
non-generic alias for an opaque pipeline handle only when safe. The compiler
must enforce both endpoint message types and direction.

## Hard rejection rules

- At least two stages, source first, sink last.
- Only READABLE/DUPLEX at the head, TRANSFORM/DUPLEX internally,
  WRITABLE/DUPLEX at the tail.
- Payload type of each output must be assignable to the next input,
  using compiler canonical type rules (the initial host gate requires
  exactly identical canonical nominal strings; widening/subtyping is a
  future type-checker extension, **not** implicit casts).
- A `supervisor actor` is never a data stage. Supervisor ref/control
  messages are not user-data pipeline payloads. No implicit coercion
  from `SupervisorRef` to `ActorRef` or `PipeStage`.
- Ordinary actors without an explicit pipe role are rejected even if
  they happen to have `receive` and `send` methods.
- The same actor instance may not be attached to two pipelines without
  explicit arbitration. A cycle requires a separately typed feedback
  channel with bounded capacity; ordinary `pipe` cannot create cycles.
- A pipeline cannot escape its creation scope with borrowed actor-local
  mutable references or a stale supervisor/code incarnation capability.
- Duplicate terminal notification, type erosion to `any` and accidental
  successful return of a partially connected stream must fail closed.

## Data-flow and reply capabilities

Use two typed, *separate* communication surfaces:

```ores
// Illustrative signatures, not presently executable source.
downstream.send(value: Out): Future<void>
upstream.reply(value: Reply): Future<void>
```

- `downstream.send` forwards application data one hop to the next stage.
  The future acknowledges bounded mailbox **acceptance**, not successful
  handling. The caller may suspend, releasing the carrier while the
  downstream mailbox is full. Never park a CONTROL or actor thread.
- `upstream.reply` is an explicit, separately granted capability routed
  to the immediate upstream stage or correlated originator, never an
  ambient `ActorRef` to the parent or supervisor. The ordinary
  data path is **unidirectional by default**. A stage cannot forge
  an upstream reply capability by knowing the sender's address.
- For request/response chains, use a typed correlation ID and a bounded
  reverse channel. Reply payload types and correlation lifetimes are
  checked independently of the forward channel. A normal
  `readable pipe actor` should not automatically receive arbitrary
  reverse payloads. `duplex` opts into bidirectional protocol typing.
- A terminal sink may signal completion or failure over a CONTROL/lifecycle
  path, but must not emit unchecked upstream application payloads.
- Parent/supervisor notifications are about failure, restart and
  lifecycle only; they are **not** automatic application-data replies.

## Atomic construction and supervision

Each actor is spawned under a supervisor before the pipeline is made
visible. A connecting transaction:

1. Validate all nominal roles/types, supervisor handles, budget and
   transport authority; reserve bounded channels and ActorGroup capacity.
2. Register the pipeline's ActorGroup with its supervisor **at birth**.
3. Create each actor and attach typed directional endpoints while it
   cannot yet process ordinary mail.
4. Publish the whole connected `ActorStream` atomically and release
   readiness barriers. If *any* stage initialization fails, roll back
   unobserved links and await physical worker termination before memory
   reclamation. No partial stream is returned.
5. On teardown, fence new admissions, propagate end-of-stream/cancellation,
   drain or discard payloads according to explicit policy, retire mailmen,
   and wait for worker `get_done()` before declaring complete.

A pipe stage's supervisor controls its lifecycle but may never read or
mutate the stage's private data buffer merely by virtue of being supervisor.

## Scheduler and Mailman interaction

- Per-stage mailbox and inter-stage channel capacity are bounded.
  Normal actor data never enters a reserved supervisor CONTROL queue.
  Fairness must prevent an active upstream stage from starving downstream.
- A Mailman is the ActorGroup's serialized output manager, **not**
  automatically a per-pipe-stage mailbox. If customized, it uses
  cooperative suspension and no direct host blocking calls. It receives
  only narrow group send capabilities, not supervisor power.
- A Scheduler owns continuation placement and carrier release; its
  customizable policy cannot run arbitrary guest code inline on a Future
  producer thread or bypass authorization at the CONTROL boundary.
- Backpressure is a pipeline property with budgets, not an invitation to
  spawn extra carrier threads or grow unbounded queues.

## OTP-style fault and reload behavior

Supervisors supervise child supervisors, ActorGroups and explicitly
owned workers. Pipeline actors are *workers*, never supervisors. A stage
panic bypasses actor-local panic handlers and reports by reserved
CONTROL to its supervising actor. Restart strategies apply to the
explicit pipeline child spec, including a consistent choice to restart
a whole stream or a stage with a generation-fenced reconnection. A
closed ActorStream cannot be resurrected by a stale worker.

Code upgrades preserve stable supervisor identity and group association,
but old actor message/stream endpoints are generation-fenced.
`code_change`/state migration and pinned continuation safepoints must
occur before publishing a new executable policy. A supervisor's own
panic goes to its parent supervisor; the failing supervisor actor
does not intercept its own panic.

## Proof and conformance matrix

1. Parser fixtures for every role spelling, keyword nesting, ordinary
   `|` compatibility and `pipe` precedence; both backends must either
   admit *and execute* equivalent semantics or reject distinctly.
2. Static stage checks: position, mismatched message types,
   supervisor/ordinary actor exclusion, null/empty type and roles.
3. Runtime: FIFO/order, bounded backpressure, fair scheduling,
   cancellation mid-send, sender-reply correlation, dual-direction
   isolation, exactly-once terminal notification per incarnation.
4. Supervision: creation-time association, child-parent reload,
   fail-stop/restart fencing and proper panic escalation.
5. Native: exact-source CMake/CTest, ASan/UBSan and high-contention
   cancellation races. Java: source parser/type checker, JUnit and
   Graal execution tests. Shared fixtures must run on both.

The contract-only tests do not establish message transport,
hot-code replacement, source grammar support or full parity.

## Incremental bounded transport substrate (new)

`ActorPipeTransport.open(runtime, supervisor, stages, hostTypes, capacity)`
now allocates bounded `ChannelRuntime` forward edges and binds a new
ActorGroup to its same-runtime supervisor **before** publication. Host
wiring returns separate writer and reader capabilities. Writes and reads
use nonblocking `OresFuture` results, with per-edge outstanding waiter
limits in addition to bounded buffers. ActorGroup close runs an infrastructure
close hook to fence pending pipeline operations even when the host closes
the group without first closing its transport object.

Until real compiler-checked Sendable serialization is available, host
payload types are limited to an explicit immutable-scalar whitelist and
messages have a 1 MiB maximum representation budget. Mutable Java maps,
lists, arrays and generic Objects are not admitted.

There is still **no source actor spawning/connection or automatic transform
execution** in this class, nor a reverse reply channel; a source PipelineExpr
must be compiled and checked before user code can instantiate stages.
The native `NativeActorPipeTransport` currently offers similarly bounded
host-side forward-byte channels and nonblocking host futures, but source
continuation lowering and message codecs are still missing.
