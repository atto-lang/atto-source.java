# Actor hot-reload host execution-domain audit — October 19, 2026

Status: **DRAFT SHARED actor integration**. This is not production
hot-reload certification or protected PRIVATE/UNTRUSTED worker support.

## Concrete authority gaps closed

The runtime maintains independent, thread-local execution markers:
`CURRENT_ACTOR_EXECUTION`, `ACTOR_CARRIER`, ActorGroup Mailman CONTROL,
OresScheduler task/carrier, and AsyncRuntime carrier.

Previously the source-actor logical router and bounded proxy checked
the active actor turn and scheduler/async carriers, but omitted the
actor-only physical carrier marker and ActorGroup CONTROL marker.
A callback after actor turn teardown could have no active actor context
and still run on a physical actor carrier. Similarly a Mailman CONTROL
callback was not equivalent to an external supervisor. Such callbacks
must never enter blocking host migration, receipt-wait, freeze, or
physical generation-retirement code.

Both `LogicalSourceActorRouterV1.requireBlockingHostSurface` and
`LogicalSourceActorProxyBridgeV1.requireHost` now reject **all six**
categories: active actor turns, actor carrier, ActorGroup Mailman CONTROL,
active scheduler task, scheduler carrier and async carrier.

The router additionally checks this authority **before acquiring
routeLock** in its public logical-ref `address()`, `incarnation()` and
`routeEpoch()` methods; in `generation()` and `reapRetired()`; and in
its package-only proxy/physical diagnostic observers. These seemingly
read-only operations were not harmless: they may acquire a contended
monitor and/or run `reapRetiredLocked`, which can release old-code
generation leases and close other resources. The immutable logical
`id()` method remains directly readable because it does not lock,
wait, publish or retire work.

## Committed product regression tests

`ActorHotUpgradeHostCarrierIsolationTest` injects each carrier-only
ThreadLocal marker after CURRENT actor/task state is clear. It verifies
both central host boundaries reject the request. It also injects a
non-authoritative ActorGroup Mailman marker and proves that a Mailman
callback cannot borrow supervisor authority.

A real source-compiled SHARED actor/router fixture then checks that an
actor carrier or Mailman CONTROL callback cannot read logical
address/incarnation/epoch, inspect generation, reap retired code or
obtain physical actor diagnostic references. When the test restores
ordinary host context, legitimate reads and reaping remain available.

These are **committed JUnit sources**, not executed pass results.
Product JDK25/Maven/GraalVM execution and Native Image parity are still
unverified on this exact source head.

## Locally executed independent model

A separate **source-free** Java 21 host-guard concurrency oracle compiled
using `javac --release 21 -Xlint:all -Werror`, then executed
**53,600 / 53,600 checks PASS**. It exercised all six denial markers,
host-only reads/reaping, and 200 concurrent eight-contender rounds.
It does not instantiate the actual Oreslang ActorRuntime, router,
mailbox or GraalVM compiler and is not release evidence by itself.

## GitHub Actions and private source-transfer policy

The source-owned `fast-ci` run
https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39312523661
and `ci` run
https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39312524434
reported failed jobs with `steps: null`, not failing JVM assertions.
No exact-tree JDK25/GraalVM pass is currently confirmed.

`.github/private-ci-approved-repositories.txt` has **zero approved
destination repositories**, and security issue
https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/962
requires a protected independently approved source/destination policy,
immutable Git tree provenance and destination numeric ID continuity.
Available GitHub Actions minutes do not authorize transfer. No private
source Git tree was copied to an unrelated organization for this audit.

## Remaining release requirements

Restore authorized exact-head JDK25/Graal/JUnit/native CI; integrate the
compiler's immutable typed actor-message encoder beyond i64; preserve
message ABI identity, actor ownership and physical completion provenance;
prove single supervisor publication of physical route, code image,
Task generation leases and checkpoint; then test protected PRIVATE and
UNTRUSTED workers, timeouts, cancellation and failure recovery.
