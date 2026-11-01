# Oreslang native test harness v2 — design and admission contract

**Status:** proposed v2 interfaces, NOT an implemented async/actor/cluster runner. The
existing executable baseline is `stdlib/testing.ores` and the separate Rust
`oreslang-test.cli` launcher. No Java/JUnit, C#, JavaScript, Python, or
foreign-language test framework is the implementation of Oreslang's public API.

## Division of responsibility

| Layer | Owner | Contract |
| --- | --- | --- |
| Pure Oreslang stdlib (`std/test`, legacy `stdlib/testing.ores`) | Oreslang | Typed assertions, immutable suites, tables, expected failures, result semantics, fixtures and cancellation-safe lifecycle; no ambient global registry. |
| Compiler and link graph | Each Oreslang compiler | Discover explicit test descriptors at build time, emit static source location/test identity and reachability roots; **no runtime reflection**. Fail closed for unsupported test primitives. |
| Oreslang runtime | Each backend | Capability-restricted task/test actor scopes, structured cancellation, monotonic deadlines, isolated per-test resources, virtual time where supported. |
| `oreslang test` / `oreslang-test.cli` | Rust launcher | Discover tests, pin compiler/source identity, schedule files/processes, apply resource limits, gather typed results, render reporters and propagate exit status. |
| Conformance | Shared fixtures, independently executed | Cross-backend acceptance; reference models and host-only checks are not guest execution. |

Preserve current `TestCase`, `TestOutcome`, `TestSuite`, `TestOptions`,
`RunSummary`, `run_suite`, and the v1 CLI receipt format until a
versioned migration is implemented. Do not silently redefine the meaning of
old output. The existing `run_suite` is sequential, not scheduler-backed.

## Design sources and selected features

* Node `node:test`: nested tests, hooks/cleanup, explicit concurrency,
  mocking/timers, watch/filter/reporters, snapshots, coverage.
* Java JUnit Jupiter: strongly typed assertions, parameterized cases,
  lifecycle scopes, extensions, tags, timeout/isolation.
* Kotlin `kotlin.test` and coroutine test: platform-neutral assertions,
  scheduler-controlled virtual delays, single completion boundary.
* .NET xUnit/NUnit/MSTest: fixtures, theory/case-data, independently scoped
  async lifecycle and CI-compatible test result records.
* Rust Cargo/libtest: compile-time discovery, isolated integration targets,
  named filters, ignored tests and deterministic captured output.
* Go `testing`: subtests, table-driven checks, opt-in parallelism,
  scoped cleanup, benchmarks and replayable corpus fuzzing.
* Akka Typed TestKit: synchronous pure-behavior tests *versus* real
  scheduler-backed multi-actor tests, probes and controlled scheduler.
* Erlang EUnit/Common Test: suite/group/test fixtures, per-case process
  isolation, bounded timetraps, logs, distributed-node test orchestration.
* Microsoft Orleans: cheap pure-grain unit tests *versus* faithful in-process
  clusters and provider-backed integration scenarios.

## Public API target (illustrative; not yet implemented)

```ores
import module Test from "std/test";

@Test.case(name: "counter increments")
pub suspends fnc counter_increments(Test.Context t): void {
  val probe = t.actor_probe<int>();
  val counter = await t.spawn_test_actor<Counter>();
  await counter.send(Increment(1));
  await probe.expect_message(1, timeout_ms: 200);
  t.expect_equal(1, 1);
  return;
}
```

Syntax and annotation semantics in this sketch are *proposals*, not
evidence that any compiler accepts them. Actual registration must be
compile-time-only (hygienic metadata or explicit static declarations), not
runtime reflection, hidden class scanning or process-global mutation.
Oreslang's `rt borrow`, `rt share`, `rt take`, `rt copy` ownership
conventions apply; no C-style pointer or implicit conversion APIs.

### Core public modules

1. `std/test`: `Test.Context`, typed `expect_*` (collect failures),
   `require_*` (stop only current test), explicit `skip(reason)` and
   `todo(reason)`, `Test.Result`, static suites, subtests and tags.
2. `std/test/property`: seeded generators, `for_all`, replay ID, bounded
   shrinkers and checked coverage of input/corpus; zero generated cases
   is inconclusive, never green.
3. `std/test/clock`: monotonically bounded real deadlines; virtual-time
   scheduler advancing through scheduled events, not sleeping the host
   carrier. Timer callback must not execute guest code on the clock thread.
4. `std/test/actor`: actor probes, spawned isolated actors, controlled
   scheduler, captured message/turn receipts, deterministic supervision
   and cancellation; no privileged actor-state introspection.
5. `std/test/cluster`: local tenant-scoped IsolateVM test clusters and
   provider-backed integration harnesses as separately labeled modes.
6. `std/test/bench`: separate benchmark mode with warmup, samples and
   distributions; never count a benchmark as a functional test.
7. `std/test/snapshot`: canonical versioned serialization and diff;
   baseline updates require explicit review (default read-only).

### Lifecycle and failure taxonomy

For each case: `discover -> admit -> fixture -> execute -> cancel/drain ->
cleanup -> report`. Fixture teardown is reverse registration order,
runs once even on assertion failure or ordinary exception, and may be async.
Cancel children on parent failure/timeout; preserve actor ownership and
physical retirement before publishing a completed test. A timeout is a
failure only when a trusted monotonic deadline and cancellation/drain receipt
exist. Otherwise mark **INCONCLUSIVE** and quarantine resources.

Every result is exactly one of `PASS`, `FAIL`, `SKIP`, `TODO`, or
`INCONCLUSIVE`, with reason, source position, duration, backend,
test identity and evidence class. `TODO` must not hide a failing case:
unexpected success is a policy decision, not automatic credit. Retries must
preserve and disclose initial failure and seed; retries never erase
flakiness. No executed cases, all skipped, all filtered, incomplete discovery
or missing reporter evidence -> nonzero/inconclusive, never pass.

### Scheduling, isolation and clocks

Test case source order is deterministic; concurrent cases must opt in and
be bounded by a configured cap and isolated task/actor scopes. No shared
mutable fixtures are implicitly parallel. Test attempts get a deterministic
seed, separate actor-owned memory and capabilities, bounded mailboxes,
fresh timer state, independent logs and explicit cleanup. `await` in a
test suspends the Oreslang task; no carrier thread parking. Non-reentrant
actor turns remain non-reentrant across suspension.

Virtual time has a shared scheduler clock per isolated test scope; only
advancing the clock dispatches eligible timers/continuations. It never
claims to simulate OS scheduling, network I/O or arbitrary external time.
Expose both controlled deterministic behavior tests and real async runtime
tests; they are different evidence types.

Actor probes observe messages via ordinary authorized ports/mailboxes, not
supervisor CONTROL messages or private heap inspection. `expect_no_message`
requires a virtual-time, quiescence or bounded-real-deadline proof; an
immediate empty receive is not sufficient. For actor faults, verify
restart-generation fences, retired ActorRefs, mail order/duplicate
suppression, cancellation race, mailbox quotas, and physical cleanup.
Orleans-like cluster mode must say whether providers are fakes or real.

### Reporting and security

Versioned typed events must record source commit/tree, compiler binary hash,
target/backend and runner identity. Human/spec, TAP, JSONL, JUnit XML and
IDE reporters are projections of **one** typed event stream, not separate
test engines. Preserve provenance per test. Capture stdout/stderr with
explicit per-case byte limits and quarantine overflow.

The current `ORES_TEST|PASS/FAIL/SKIP` v1 stdout format accepts
untrusted case names/messages and is not cryptographically authenticated.
Do not treat a plausible child-printed receipt as trusted compiler execution.
Before any v2 promotion, define a length-delimited escaped event protocol,
bound all fields, reject duplicate identifiers/control characters,
verify monotonic event sequences and typed summary arithmetic, and bind
receipts to an authenticated runner/runtime channel. Never parse arbitrary
untrusted stdout into privileged test results. Maintain a strict v1
compatibility adapter, marked as guest-reported evidence only.

`oreslang test` exit policy: 0 only for a complete suite with at least one
actually executed passing case, no failures and no inconclusive cases;
1 for executed failures; 2 for invalid suite, no execution, missing
backend, incomplete/unsupported coverage, or missing attestations. Keep
runner admission, Rust host tests, Oreslang guest tests, actor runtime,
multi-backend parity, and hardware/security attestations distinct.

## Minimum executable conformance matrix

| Area | Mandatory positive | Mandatory negative |
| --- | --- | --- |
| Assertion | bool/int/string/float equality, grouped failures | mismatch, invalid tolerance, NaN and overflow edges |
| Case accounting | one PASS, mixed PASS+SKIP, filtered subset | empty, all SKIP, all filtered, duplicate case ID |
| Property | fixed seed replay, nonempty table | empty corpus, shrinking budget exhausted, bogus PASS |
| Lifecycle | setup, async cleanup, nested cleanup LIFO | setup failure, timeout, cancel/cleanup fault |
| Scheduler | ordered steps and controlled virtual time | stray child, unbounded wait, no-message false positive |
| Actor | non-reentrant turns, probe inbox, one-winner select | stale generation, mailbox flood, cancellation races |
| Tenant | two isolated heaps, owned clocks and ports | cross-tenant ActorRef or leaked resource |
| Reporting | intact typed event + exact summary | forged stdout, newline/delimiter injection, duplicate IDs, truncated event |
| Runner | exact-head pinned real guest/compiler steps | zero-step CI, wrong compiler SHA, missing guest receipt |
| Backends | Java/Graal, LLVM, .NET, Wasm, Forge separately | unsupported feature must reject; host oracle is not parity |

## Delivery plan

**P0:** keep v1 source API stable; fix vacuous property/suite success; add
typed assertions and guest regressions; document v1 receipt limits. Do not
merge without compiler-backed exact-head steps and independent review.

**P1:** Rust runner/stdlib shared v2 typed event protocol and static
discovery, named filters/tags and lifecycle cleanup; two-stage negative
tests for forged receipts and no-execution. Implement source-only guest
integration first; no process-global test registry.

**P2:** structured async/actor TestKit with clock/probe/fixture semantics;
in-process cluster separate from real provider integration; verify
physical actor retirement and tenant isolation.

**P3:** snapshot/corpus fuzz/shrinking/coverage/benchmark modes and 5-backend
parity; protected run evidence and hardware profiles where applicable.

Keep any older draft runner stacks and `oreslang-test.cli` integrated,
not duplicated. No cross-org private source transfer is authorized by
this document.
