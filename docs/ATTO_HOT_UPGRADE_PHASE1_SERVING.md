# AttoLang source hot-upgrade: phase 1 executable-serving publication

This current-main patch splits **staged** (legacy `active*`) from **successfully
started / serving** (`serving*`) source generations in `HotReloadManager`.

## Transaction boundary

1. Compiler/source-capability validation occurs before context staging.
2. `load()` stages a new context but leaves the existing serving generation
   untouched. `active()` is legacy stage metadata, **not** a serving route.
3. `Generation.start()` invokes real `.atto` guest execution **without**
   holding the manager monitor.
4. Only after `context.eval(source)` returns successfully does the manager
   check that the exact generation still belongs to this open manager, then
   publish it into the serving registry under one synchronized monitor.
5. Failure closes the candidate and keeps an existing serving generation.
6. Closing/retiring a serving generation removes only its own pointer; no
   implicit rollback to retired code after a successful publication.
7. Manager shutdown clears both staged and serving registries.

## Honest scope

The two example `.atto` actor programs execute through real Truffle guest
contexts and validate their *own* actor message results. They **do not** migrate
one physical actor across contexts. `serving()` is a code-unit context
registry, **not** a publication authority for `ActorRef` or mutable actor
state.

Until a single CONTROL-owned route owner is integrated with the real
`ActorRuntime` mailbox, outstanding Task/FFI/borrow/continuation pins,
state migration, deterministic supervisor handover, and physical teardown,
a `Generation.start()` success is **not** enough to change the route of a
currently running actor. A second-stage runtime PR must implement that
operation and test stable `ActorRef` identity with real mailbox receipts.

## Non-HTTP code-image retention admission (separate host foundation)

`HotCodeGenerationRetentionV1` now exposes a code-generation `CodeLease`
for trusted callers to pin a code image through suspended work, actor callbacks,
FFI and trace-code entries. The admission and cleanup decisions share one
monitor: physical cleanup fails while any explicit non-HTTP code lease remains,
even if the HTTP ledger is drained and a loader reports cleanup. Duplicate
lease close is idempotent; quarantined or retired images reject new code pins.
The image's charged byte budget remains held until the final physical cleanup
acknowledgement. Race tests exercise pin versus cleanup linearization.

**Not yet integrated into all runtime entry/continuation/FFI call sites.**
The lease object is a Java control-plane allocation, not a zero-allocation
per-call guest ABI. A zero ledger count does **not** prove all runtime pins
are absent; the loader's independently authenticated physical-quiescence
witness remains mandatory. This does not implement full guest AOT, JIT code
publication, compiled guest hot upgrades, zero guest tracing GC or OTLP.
Do not promote this host-only foundation as real guest execution evidence.

## Test

```sh
mvn -B -ntp -Dtest=HotReloadServingGuestCurrentMainTest -DfailIfNoTests=true test
```

When source Actions runners are unavailable, use a **private** alternate-org
runner only with full source/dependency provenance. Host-only mock/test mirrors
must not be reported as successful GraalVM `.atto` execution.
