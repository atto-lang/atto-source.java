# Supervisor CONTROL trace replay and incarnation fencing

Status: **draft host-side integration and authored tests**; do not claim the
implementation is fully verified until exact-head Java 21/JDK25 tests and TLC
are actually executed. This work is stacked on #1946 -> #1924 -> #1911.

## Opt-in CONTROL trace

`SupervisorShutdownTree(runtime, control, true)` enables an in-memory,
bounded journal of immutable `(sequence, supervisorId, before, event, after)`
entries. The default constructor disables journaling to avoid production
allocation and event-copy overhead. Every accepted lifecycle transition is
committed under `treeLock`; the journal records it in that same order.
Duplicate/stale no-op events do not consume trace capacity.

`tree.trace()` requires the trusted host supervisor authority. Snapshot
fields `dropped` and `issued` expose truncated histories. There is no
automatic source export, network sink, callback, or actor message generated
by tracing. This is not durable crash recovery.

`SupervisorLifecycleTrace.verifyComplete(snapshot)` must fail closed when
any entries are dropped, sequences are missing, the prior node state does
not match the earlier transition, or replay through the **actual pure FSM**
does not produce the recorded after-state. It also checks
**cross-node ordering**: every parent `ChildFinished` must refer to a child
whose earlier trace events have actually reached logical STOPPED with a
matching failed outcome; `ChildReclaimed` requires an earlier child
RECLAIMED phase. It also rejects child identities claimed by two
different parents or a cyclic supervisor hierarchy. It detects intra-node
ordering, premature parent acknowledgment, and callback-generation mismatches
that reach the FSM. It
is **not** an independent formal specification: the replay uses the same Java
reducer. Separately run TLA+ TLC with sibling and three-level topologies,
and eventually prove a refinement mapping from recorded traces to independent
specification actions.

## Child generation guards

At the admission freeze, each parent snapshots
`Map<supervisorId, childEpoch>`. The public read-only `NodeStatus`
exposes these immutable pinned epochs. Before admitting a child retirement
receipt to the parent FSM, the CONTROL coordinator compares:

- the child's stable supervisor ID,
- the original callback-captured child epoch,
- the parent's frozen child-epoch map, and
- the child's **currently observed** epoch, and
- the child's actual **logically stopped** FSM state and matching recorded
  success/failure outcome.

A missing identity, stale generation, unfinished child, contradictory
outcome, or generation mismatch throws before
issuing `ChildFinished`; CONTROL's existing emergency path then treats the
protocol fault as failed rather than successfully completing the parent.

These are in-process Java **supervisor incarnation** tokens. External
supervisors, hot-reloaded proxy supervisors, nested ActorRef generation
receipts, and physical-release acknowledgments require an authenticated
runtime generation journal. They are **not** considered implemented by this
patch. The current pure FSM still uses a set of stable child IDs; the
runtime fence validates epochs before forwarding its ChildFinished event.
A future explicit generation-aware formal TLA+ refinement is required.

## Adversarial verification

`SupervisorLifecycleTraceOracle` compiles directly with Java 21 and the
production `SupervisorLifecycleFsm`, `SupervisorLifecycleTrace`, and
`SupervisorChildGenerationFence` classes. The oracle constructs a
parent/child shutdown trace, verifies replay, then exercises forged
after-states, gaps, bounded-buffer overflow, duplicate/stale events,
unknown children, and changed incarnation tokens.

The real `SupervisorLifecycleFsmIntegrationTest` creates an actual
`ActorRuntime` with the trusted host CONTROL executor, performs a nested
supervisor shutdown, verifies the journal, and checks that the tree snapshot
retains the frozen child incarnation without claiming arena reclamation.

The formal workflow now runs the new Java 21 oracle alongside the original
FSM bounded explorer and sibling/depth-three TLC configurations. The
JDK25 targeted runtime JUnit includes the integration test.

## Runner/transfer gate

Source `main` is not branch-protected and its private-CI destination
allowlist is empty as of this patch. Security issue #962 requires independent
protected source provenance, reviewed destination numeric repository ID and
exact Git-tree attestation before transferring **any new private source**
to another organization. Do not rely on older mirrored blobs for approval.
Source GitHub Actions have recently reported completed failures with zero
executed steps; this is **unverified runtime code**, not a passing test.
Source-org runner admission should be restored, or #962 formally approved,
before this draft becomes mergeable.

Remaining release gates: in-band authenticated child generation receipts,
partial admission-fence refinement, watchdog/failure outcomes in independent
TLA+, durable process restart semantics, real `PhysicalRelease` and
`ChildReclaimed` sources, full JDK25 Maven/JUnit and Graal, LLVM
ASan/UBSan, JVM/native parity, plus on-shutdown hook compilation and
OresVM root close.
