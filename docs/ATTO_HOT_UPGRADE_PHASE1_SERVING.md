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

## Test

```sh
mvn -B -ntp -Dtest=HotReloadServingGuestCurrentMainTest -DfailIfNoTests=true test
```

When source Actions runners are unavailable, use a **private** alternate-org
runner only with full source/dependency provenance. Host-only mock/test mirrors
must not be reported as successful GraalVM `.atto` execution.
