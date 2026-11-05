# Oreslang logical stack traces: contract and admission gates

Status: **design and targeted Java host-boundary hardening**, not full Java/LLVM guest parity.
`throw`, `raise`, `panic` and `recover` remain reserved in the production Java
parser; LLVM supports only a limited local synchronous throw experiment. Do not
present the examples below as captured Oreslang runtime output.

## Required trace model

An Oreslang failure has a stable **origin** (the first effect site), a bounded
ordered list of **logical source frames**, a typed `throw | raise | panic` effect
classification and zero or more **causal links**. Frame identity includes:
- logical code-unit ID, file path (redacted according to policy), line and column;
- function/method and concrete overload slot, plus code **generation**;
- origin/resume/await/spawn/actor-mail/supervisor-CONTROL boundary kind;
- actor identity and task ID only when safely authorized for the observer.

A rethrow preserves the original origin and appends a propagation marker, not
a fabricated new origin. `raise` is not a downgraded `throw`; `panic` is not
caught by ordinary `catch` or `trap`. All unwinding is tied to a logical
ExecutionContext, never the transient JVM thread or LLVM carrier stack.
Detaching tasks and sending between actors starts a **new** logical stack.
Cross-actor reporting carries bounded immutable diagnostic data, not references
to another actor's exception object or heap.

## Example output **targets**, not observed runtime output

### Ordinary throw

```text
error[E_THROW] NotFoundError: user 42 not found
  at users.lookup(id)             app/users.ores:41:9
  at api.load_user(id)            app/api.ores:88:16
  at main()                       app/main.ores:12:7
```

### Raise across an await

```text
error[E_RAISE] StorageUnavailable: shard 3 unavailable
  at store.fetch(shard)           app/store.ores:74:11
  at jobs.run()                   app/jobs.ores:38:18
  --- await jobs.run()            app/worker.ores:57:12
  at Worker.receive(mail)         app/worker.ores:57:12
  --- actor terminal failure      actor=Worker generation=7
  --- supervisor CONTROL event    supervisor=WorkerParent
```

### Panic with cleanup failure

```text
error[E_PANIC] InvariantViolation: free-list corrupted
  at Cache.remove(key)            app/cache.ores:114:13
  at CacheWorker.receive(mail)    app/cache_actor.ores:43:11
  --- actor terminal failure      actor=CacheWorker generation=4
  suppressed[E_THROW] CloseError: cleanup failed
    at CacheWorker.cleanup()      app/cache_actor.ores:23:9
  --- supervisor CONTROL event    supervisor=CacheSupervisor
```

No trace may imply that `raise` or `panic` is caught by regular
`catch`/`trap`. The supervisor is a new causal segment, not another stack
frame in the failing child's invocation.

## Existing Java source marker limitations

`runtime/SourceBoundaryTrace` currently attaches up to 64 suppressed,
stackless source-boundary markers to an original host Throwable. Tests verify
async `leaf -> middle -> main` and actor `receive -> main` names. It does
**not** yet provide full source file/line/column, causal actor segments, or a
single human-oriented Oreslang renderer. Raw JVM `printStackTrace()` is not
the public contract. Java host stack traces remain available only for
authorized diagnostic modes.

Hardening in this branch bounds and sanitizes metadata before attaching a
marker. Source labels cannot inject newline/ANSI/bidi control characters or
grow without limit; the original Throwable identity/message/cause stays intact.

## Source-location confidentiality

Host-boundary `SourceBoundaryTrace` code-unit labels are metadata supplied to a
diagnostic renderer, not trusted filesystem paths or credential-free URLs.
Absolute Unix/Windows paths are reduced to their final filename; URL
authorities (including userinfo), query strings and fragments are not printed.
Ordinary relative source/module names remain recognizable. All rendered
labels are still bounded and control-character sanitized. For code-unit
metadata, an input longer than **4,096 UTF-16 units** is rejected before
any path or URL parsing: it renders exactly `<source-id-too-long>`.
This guards the error-reporting path against expensive processing of giant
credential-bearing source identifiers. A label that is at most 4,096 units
can still be scanned, but its rendered code-unit component is capped at 160
UTF-16 units (including any ellipsis).

**Scope:** this redaction applies to source *marker labels*. It does not
rewrite the original Throwable message/cause, arbitrary user-created
exceptions, separate structured diagnostic outputs or externally emitted
logs. Consumers displaying exception messages must enforce their own
privacy policy. Runtime source-path metadata must never confer filesystem
authority or be used as a trusted source-map lookup key merely because a
label is sanitized.

## Critical diagnostic/classification invariant

The Java host `TrapFailurePolicy` has a 64-node limit on real Throwable
cause/suppressed graphs, while `SourceBoundaryTrace` can attach 64 stackless
diagnostic markers. Before this audit, a fully annotated ordinary failure
could exceed the 64-node classifier budget and become non-trappable solely
because tracing was enabled. Trusted `SourceBoundaryTrace` markers are now
excluded from that classifier budget. **All non-marker suppressed failures**
remain part of traversal, including cancellation/panic/fatal during cleanup.
The regression test verifies both sides of the invariant.

## Bounded diagnostic truncation behavior

Java source boundary capture retains a maximum of **64 suppressed entries**.
Once the 64th entry would be used by a source boundary, it is replaced with
the stackless notice `ores source trace truncated: one or more source boundaries omitted`.
At most 63 earlier source boundaries are preserved when no other suppressed
entries exist. The notice is intentionally conservative: it signals an omitted
boundary at the cap, not the exact omitted count. Existing non-marker
suppressed failures remain intact and retain their classification (panic,
cancellation, fatal, ordinary throw).

A source trace that does not reach the limit must not show a truncation notice.
The real Throwable remains the authoritative failure; source markers are
diagnostic-only and excluded from `TrapFailurePolicy`'s classification-node
budget.

## Host-fatal emergency path

Java source diagnostic capture is intentionally **best effort** for fatal JVM
failures. `SourceBoundaryTrace.record` returns the **same** `VirtualMachineError`,
`ThreadDeath`, or `LinkageError` without creating source markers or allocating
additional diagnostic strings. This avoids multiplying allocation and
stack-use failures during VM out-of-memory, stack overflow, or linkage faults.
Such failures are terminal host conditions, not ordinary `throw` values.

This emergency fast path does **not** suppress Oreslang guest panic source
markers, which remain typed and non-trappable. The compiler must never treat a
fatal host exception as an ordinary guest `throw` or convert it to `None`.

## Admission matrix

Before shipping common guest stack traces on both backends:
1. Exact same Oreslang fixture on Java/GraalVM and LLVM: caught throw, uncaught
   throw, trap-to-None, raise bypass, panic bypass; exact typed outcomes.
2. 1/2/64/>64 recursive frames; truncation count must be explicit; no overflow
   and no unbounded allocation under native hard-real-time profiles.
3. Repeated await/resume with carrier migration, nested callbacks and
   structured task joins; preserve source origin and logical caller order.
4. Actor message + supervisor event, full user mailbox, parent restart and
   hot-reload generations; no cross-actor heap pointers or fabricated callers.
5. LIFO defer/finally, suppressed cleanup failures, deterministic precedence;
   cancellation/VM-fatal never silently become ordinary throws.
6. Rethrow/raise conversion carries original provenance and records the new
   escalation site distinctly. No frame splicing across independent actors.
7. Missing debug info, optimized/inlined functions, AOT tree shaking,
   relative paths, source-map lookup: gracefully degrade without leaking
   private absolute paths, values, credentials, or other actors' state.
8. Build `compact`, `full`, `off` presentation modes with bounded work.
   Static no-alloc trace buffers for bare-metal/hard-RT and emergency panic
   paths; optional host-symbolication only outside the critical failure path.

The current tests only prove the limited marker contract; none of these
unimplemented gates should be reported as passing.


## Actor/supervisor and heap/arena detachment — 2026-11-04 candidate

Draft Java host integration: `ActorFailureTrace` records bounded immutable causal
**association**, distinct from a synchronous call stack and from an actually
delivered supervisor CONTROL failure receipt. The public marker
`atto.actor-failure-causality.v1` is an internal host value contract, **not**
an approved replacement for the cross-backend error wire schema.

A failed actor now captures the first terminal cause's **trusted runtime
identities** before normal actor-memory retirement wherever possible:
- actor identity + incarnation generation (from ActorRef, never guest fields);
- nearest-first parent-actor lineage, up to 8 entries;
- nearest-first bound supervisor lineage, up to 8 entries;
- explicit `truncated` flag when either lineage exceeds the cap;
- cancellation vs unclassified failure. No claimed throw/raise/panic kind is
  manufactured by host Throwable heuristics.

Every snapshot is plain detached UUID string/scalar/immutable-list data. It
contains no original `Throwable`, guest message, arbitrary `toString()`,
`getMessage()`, stack array, guest `Error`, actor capability, pointer, native
region handle, arena loan, task callback, Java carrier stack or closure.
Capturing makes **no** guest call, and the snapshot remains valid after
child actor finalization and arena reclamation. The host-only accessor is
`ActorRef.failureTrace()` or `ActorControlHandle.failureTrace()`; actor code
does not gain supervisor authority by acquiring a ref. A missing supervisor
association is represented explicitly rather than guessed.

**Not covered by this PR:** source-level call/await stacks, actual supervisor
CONTROL delivery and acknowledgement, full cross-heap error transport, or
portable `throw`/`raise`/`panic` effect identity. A `SUPERVISOR_ASSOCIATION`
boundary is **not** an on-raise/on-panic callback. Existing host-compatible
`ActorRef.failure()`/`ActorHandle.failure()` raw-Throwable reads now reject
ordinary actor execution. `ActorHandle.failureTrace()` instead admits
only its parent actor's authenticated child handle and returns the detached
value snapshot. The actor lifecycle observers `ready()`, `readyRef()`,
and `done()` now publish an immutable, **stackless**
`ActorFailureTrace.ObservationFailure` on exceptional completion, rather
than retaining the raw child Throwable in their settled Future state.
`ActorTerminatedException` raised by closed-ref send/drain paths now carries
that detached observation as its cause, not the child Throwable.
Actor output-stream terminal errors are also closed with a detached
observation, so the output channel does not hold a raw child exception.
The observation has a host-issued actor identity/generation, a coarse
failure kind and a fixed message; it contains no supervisor/parent ancestry,
privileged causal snapshot, guest stack, guest exception message or raw
Throwable cause chain. The full causal snapshot remains restricted to
`failureTrace()` on the authenticated host/CONTROL/parent-child surfaces. It is diagnostic, not an
authenticated supervisor CONTROL receipt.

**Remaining P0 gaps:** Legacy host-only `ActorRef.failure()` continues to
hold the original raw Throwable after finalization for compatibility and may
retain guest-owned objects. Generic `OresFuture`, pending mailbox/drain error paths,
one-shot invocation bridges, and other
host interop completion paths can still retain raw Throwables; no universal
cross-heap Future sanitization is claimed. No implicit conversion from
`ObservationFailure` to guest `throw`/`raise`/`panic` is implemented.
Do not serialize host exceptions or use this observation as an authority
token. Complete those remaining paths and add authenticated CONTROL events
carrying a detached, descriptor-validated `GuestErrorWireCodec` compact
snapshot plus an immutable causal sidecar, while keeping raw guest failure
objects inside their owning execution domains.

The trace is deliberately not attempted after fatal JVM
`VirtualMachineError`, `ThreadDeath` or `LinkageError`; no host stack
overflow is an actor-recoverable event. Snapshot allocation is still best effort
under memory pressure and does not alter terminal failure semantics.

### Exact-head admission gates

1. JDK/GraalVM `ActorFailureTraceTest` (host integration), existing
   `ActorTraceContractIntegrationTest`, `ActorGroupSupervisorBindingTest`,
   `SourceBoundaryTruncationTest`, `TrapFailurePolicyTest`. Prove first-winner
   origin and cleanup/suppressed failure precedence.
2. Native/LLVM, .NET NativeAOT, Wasm, Forge: emit the same detached field
   structure without retaining an actor-heap reference or allocator-owned
   string, then independently test retirement and stale-generation rejection.
3. Source-compiled `.atto` multi-actor failure fixture:
   `A -> B -> C -> supervisor S -> parent S0`, asynchronous suspension
   and carrier migration, restart, hot-upgrade, failure during arena drop,
   cancellation vs panic, and supervisor CONTROL receipt **exactly once**.
4. Adversarial cases: fake supervisor IDs, forged source spans, arbitrary guest
   error getters, cyclic causes, large source/trace, control characters,
   failed serialization, actor/tenant swap, old-generation replay, concurrent
   failure versus shutdown, and OOM/stack-overflow emergency paths.
5. Capture exact head commit/tree/blob hashes, stepful workflow jobs, real
   compiler/guest execution and independent review. Zero-step CI is runner
   admission failure; `ATTO_GUEST_COMPILER_EXECUTION=NOT_EXECUTED` is not parity.

### Bounded host supervisor failure inbox — follow-up candidate

The optional `ActorSupervisor.enableHostFailureInbox(capacity)` wires a
supervisor-owned, bounded **admission ledger**, not executable guest supervisor
CONTROL. It is deliberately **off by default**, because an advisory
`ActorSupervisor` and installed `SupervisorCodeSlot` do not yet constitute
an authenticated source-level failure handler or guarantee of VM CONTROL
dispatch. Only host/supervisor callers can configure, claim, acknowledge, or
retry receipts. Ordinary actors and ActorGroup Mailman turns are denied.

For a supervisor with an enabled ledger, a first-winner actor failure attempts
to admit its immutable value-only causal snapshot before normal reclamation.
The ledger accepts the exact actor incarnation and **direct** supervisor
association, never an ancestor-only ID; queued and claimed-but-unacknowledged
entries both consume a bounded capacity of 1–256. A full inbox now sets a
**separate recoverable admission fence**: the stopped actor cannot finalize,
but its cleanup-corruption flag is not forged. After the host claims and
acknowledges an older receipt to release quota, the **matching** supervisor
may explicitly call `retryHostFailureAdmission(actorRef)`. This validates
runtime ownership, exact ActorRef identity/incarnation, terminal snapshot and
direct supervisor before admitting the original failure, clearing only the
inbox-specific fence, and resuming physical finalization. It never clears
genuine source-cleanup quarantine. Wrong-supervisor, pre-stop, stale-ref and
still-full retries fail closed.

Unrecoverable diagnostic corruption, a group-transfer race or failed
allocation/serialization may still quarantine the actor without automatic
recovery. Both the retry mechanism and host claim API are off by default.
Deployments enabling an inbox must provide a trusted host consumer and explicit
drain/retry lifecycle; there is **no automatic VM CONTROL policy handler**.

`pollHostFailureReceipt()` returns an immutable receipt with a stable sequence,
actor identity/generation, direct supervisor ID, and detached trace. Each
claim increments a `claimAttempt`; an acknowledgement must match sequence,
actor identity, actor generation **and the latest claim attempt**. An old
CONTROL coordinator claim cannot acknowledge a receipt retried after restart.
`requeueUnacknowledgedHostFailureReceipts()` keeps the sequence but increments
the attempt on a fresh claim. This is an **at-least-once host replay mechanism**;
neither a claim nor an acknowledgement proves the `.atto` supervisor policy
executed. Durable exactly-once guest policy execution, hot-upgrade fencing, automatic
VM CONTROL dispatch, automatic queue recovery and process-crash persistence
remain unverified. Inbox map+queue admission now rolls back partial writes
under a rejected/allocation-failed offer and consumes the sequence only after
the two stores are coherent.

Separately, terminal actor mailbox and continuation-channel closure and
previously pending `drain()` observers now propagate only detached host
failure causes; no child raw `Throwable` is placed into those channels.
The legacy host-only `ActorRef.failure()` and unrelated generic Future,
one-shot, and guest error paths still require source-local ownership audits.

Authored regression suite: `SupervisorFailureInboxTest` (including full-inbox
quarantine and exact-supervisor explicit retry) and expanded
`ActorFailureTraceTest` (including immutable-copy topology validation). The `SupervisorFailureInbox` and
`ActorFailureTrace` source value classes alone have been compiled against
exact Git blobs locally with OpenJDK 21 `javac --release 17 -Xlint:all -Werror`,
and an isolated concurrent host-ledger probe passed. These are **NOT**
full-head Maven/JUnit, GraalVM, real CONTROL, or compiler-backed `.atto`
execution results. Do not promote this PR until those checks actually run.

### Additional failure-inbox audit (2026-11-04)

- **Recoverable backpressure:** `supervisorFailureAdmissionBlocked` is
  separate from `sourceStopCleanupAborted`. On saturated admission, the
  stopped actor remains unfinalized. After an older receipt is acknowledged,
  `ActorSupervisor.retryHostFailureAdmission(actorRef)` retries that actor's
  **original** detached trace through the exact direct supervisor and checks
  the runtime, ActorRef identity, actor generation, and stopped state. The
  method does not execute a guest CONTROL handler or clear unrelated cleanup
  quarantine. No automatic retry is yet wired; a trusted host coordinator
  must perform it.
- **Atomic ledger admission:** `offerTrusted` now rolls back partial
  map/queue insertion if ordinary failure or OOM occurs between stores;
  receipt sequence numbers are committed only after both stores succeed.
- **Immutable-canonical diagnostic structure:** `Snapshot` validates a
  defensive immutable copy, rejects a duplicate terminal, repeated IDs,
  parents appearing after supervisors, more than 8 parent or supervisor
  hops, and a mismatch between the association flag and the actual path.
  This is data-shape validation, **not cryptographic provenance**; only the
  authenticated runtime owns admission authority.
- **Emergency resource distinction:** a secondary OOM/stack overflow while
  capturing optional diagnostics must not automatically retain unsupervised
  heaps forever. When an actor is bound to an opted-in supervisor failure
  inbox, failed or fatally skipped capture instead remains fail-closed.
  The latter case is a real availability/quarantine limitation until
  authenticated recovery is designed.
- **Authored tests (unexecuted):** `SupervisorFailureInboxTest` now includes
  full-inbox recovery, wrong-supervisor denial, retained arena prior to
  admission, and 24 simultaneous duplicate-failure publications.
  `ActorFailureTraceTest` includes malformed immutable topology rejection.
  Full Java compilation, Maven/JUnit, source-level `.atto` execution,
  supervisor CONTROL delivery and LLVM/.NET/Wasm/Forge parity are not proven.

**No merge until exact-head nonzero-step tests and independent safety review.**

### Group-close versus terminal failure publication — additional hardening

A closing `ActorGroup` removes itself from the live `actorGroups`
registry before every possible late actor cleanup/cancellation failure has
finished. Relying solely on `actorGroups.get(managedByGroup)` could lose an
already-assigned **direct** supervisor route, silently skipping a configured
failure inbox after group teardown. `ActorCell` now pins a runtime-owned
`ManagedSupervisorRoute(groupId, supervisor)` when lifecycle management is
accepted (including inherited group ownership). The route contains no guest
heap reference, private message, or original Throwable. Failure capture
resolves the live group first, falling back to this pinned, ID-checked route
when the group has retired. Releasing a worker clears only the old matching
pin; another supervisor's new ownership cannot be cleared by a racing
release from the previous group.

Authored tests assert that a released-and-readopted worker reports failure
**only** to its new supervisor and that group closure does not discard the
managed worker's cancellation receipt. These tests are not yet executed
with the full Java actor runtime.

The source organization still uses `ubuntu-latest` /
`ubuntu-24.04` hosted runners. Jobs showing `steps=null` fail before
a Java command executes. A single-job rerun was requested during this
audit, but no stepful receipt exists on the current PR head. No
cross-organization private source mirror or secret/token checkout was used.

### Exact-file no-Maven value probe (CI admission workaround, not guest proof)

Run `sh scripts/verify-supervisor-failure-values.sh` on a checked-out
**exact PR head** with JDK 17+ to compile the authoritative
`ActorFailureTrace.java`, `SupervisorFailureInbox.java`, and
`SupervisorFailureValueProbe.java` sources using
`javac --release 17 -Xlint:all -Werror`. No Maven, GraalVM, source mirror,
token, or guest compiler is required once a checkout is available.

The probe executes 100 rounds of 24 concurrent terminal receipt offers
(2,400 offers), at-least-once host claim/retry and generation/attempt fences,
1,000 oversized 100,000-boundary rejection attempts, malformed parent/
supervisor causality, direct-supervisor denial, and final quota accounting.
Snapshots are now capped **before any defensive copy allocation**, rather
than copying an arbitrarily large host `ArrayList` before rejecting it.
The Java source for the probe is checked into the draft PR for independent
reproduction. This probe is **host-value-only**: it does not validate the
`ActorRuntime` lifecycle wiring or any compiled `.atto` guest path.

An attempted rerun of the existing `fast-ci` job at an earlier head was
accepted by the GitHub API but returned a new job with `steps=null`, so
it is **runner admission failure**, not an executed test. The Actions
minute/billing/admin issue still requires an authorized runner owner.
