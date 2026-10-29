# Actor-owned Step Functions coordinator — Java/Graal candidate (2026-10-29)

**Status: source and JUnit acceptance gate committed; real Graal/Oreslang guest NOT_EXECUTED.**

This is a narrowly scoped next step beyond the host-only transition oracle in
[platform-conformance PR #221](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/221).
The new `src/test/resources/parity/actor-coordinator-fork-join.ores` runs a
`WorkflowCoordinator` **actor** as the sole authority over the workflow phase
and two-child join state, rather than letting `async main` make all workflow
graph decisions.

## Executable guest acceptance contract

- Admit one fixed demonstration `tenant-a:run-1` and reject foreign-run
  and pre-start completion envelopes.
- On `start`, coordinator sends `dispatch-validation`. The outer executor
  may spawn the validation actor **only after** that command.
- On validated reply, coordinator sends `dispatch-parallel`. The executor
  spawns two independent child actors before waiting for either output.
- The coordinator requires **both** the inventory and payment completion
  messages to finish. First completion produces `pending`; replay of the
  same inventory completion produces `duplicate-receipt`; the second unique
  completion emits `complete`.
- After actor termination, the Java/Graal guest must print exactly
  `workflow:actor-fork-join` and `Context.eval` must return normally.
- Any unexpected output, exception or JUnit timeout fails the test.

Git blob SHA-1 of exact new `.ores` fixture:
`80b8da1ba193b30137efa122d94f30b52dae7d46`.

`SourceActorStepWorkflowExecutionTest` now pins the blobs for all **4**
baseline fixtures and asserts actual guest stdout via Graal `Context.eval`.
The other **13** canonical native demo cases remain separately pinned in
`SourceActorDemoWorkflowExecutionTest`. CI requires **17** real executed
non-skipped JUnit test cases and exact checked-out repository commit identity.

```sh
mvn -B -ntp -Dtest=SourceActorStepWorkflowExecutionTest,SourceActorDemoWorkflowExecutionTest test
```

## Deliberate non-goals and security boundaries

This fixture uses compatibility actor fields, **not** compiler-sealed actor
state with `handle_state(critical-read/critical-write)` verified loans.
The outer `async main` is a non-durable executor of coordinator commands;
the coordinator does **not yet** own runtime child actor handles, persistent
transaction boundaries, supervisor CONTROL routing, or restart semantics.
Messages contain fixed **untrusted string labels**, not signed/verified
identity/capability receipts; a malicious sender could forge them. Thus the
fixture demonstrates ordering and guarded join logic only and is **not an
authenticated multi-tenant workflow engine**.

There is no durable inbox/outbox, storage fencing, timer, retry lease, snapshot,
external side-effect idempotency, cancellation drain, or cross-backend run.
A duplicate message is suppressed **locally in one actor incarnation only**;
this does not imply durable or exactly-once delivery.

## Evidence and next gate

Local: Java 21 `javac --release 21` accepted the updated JUnit test using
minimal polyglot/JUnit **stubs only**, and the source fixture passed simple
structural checks. Neither executes the Oreslang compiler or runtime.

The first exact-head source-org native workflow attempt after the changes,
[run 40902970116](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40902970116),
reported a failed `real-guest-execution` job with `steps=null`: classify
this as **pre-execution CI infrastructure**, not a guest test failure.

Next: use an **authorized** real source runner, examine raw compiler/runtime
diagnostics, then implement the typed `Workflow<In,Out>` and
`WorkflowStep<In,Out,State>` library, sealed coordinator state, controlled
worker dispatch, supervisor receipts and durable transactional recovery.

Do not move private Oreslang source across GitHub organizations without the
independent security authorization required by
[platform-conformance issue #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22).

## Draft 14-case sealed-state expansion (2026-10-29)

A separately stacked PR carries the exact 14-case demo fixture set including
`effect-receipt-ledger.ores` and the **sealed `state` / `handle_state(critical-write ...)`**
coordinator source. The original 13 demo-case / 4 baseline-case candidate
remains historical; the expanded suite requires **14 + 4 = 18** actual Java/Graal
`Context.eval` JUnit executions and two source-matched Git blobs:
`56535ad1df72c1eaf2493e3e97e9d562c611c666` for the effect ledger
and `3e22866b88f6319b919849b4427dbb7df38c0adb` for the coordinator.

Neither the proposed `define isolated actor` nor `critical-write` spelling is
assumed to compile. A real compiler diagnostic is expected and must be
remediated at the compiler/parser/type/effect layer; substituting compatibility
fields would invalidate the contract. No native guest run, heap isolation,
durability, authenticated receipt, or crash recovery is claimed here.

### Native coordinator actor-kind consistency

The source-pinned coordinator resource is now `define isolated actor WorkflowCoordinator as`. Its exact Git blob is `3e22866b88f6319b919849b4427dbb7df38c0adb`, corresponding to Step Functions PR #11 at `0f2e9cf276a8bbad9089b258e4d33df9e80e6365`. As before, this only makes Java/Graal admission require the requested isolated semantics: **no physical heap isolation or Oreslang guest execution has been verified**.
