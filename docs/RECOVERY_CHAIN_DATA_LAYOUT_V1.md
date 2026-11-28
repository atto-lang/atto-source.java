# Recovery chains, explicit capture and data layout (draft, 2026-10-22)

## Status and boundaries

This patch introduces an isolated Java 21 **host prototype** of recovery-chain dispatch,
focused JUnit tests, and an exploratory layout microbenchmark. It does not enable
guest-source `recover |err, c| -> ...`, typed `raise`, the curried registration
syntax, deferred cleanup interoperation, actor supervisor delivery, LLVM lowering,
or continuation-safe `await`. Those gates remain fail-closed. The experimental
recover example in oreslang-demo#9 used a different and now **rejected** contract
where panic could be locally recovered. We must not resurrect that behavior.

The controlling safety rule from oreslang-common-source#128 is: typed ordinary
`throw` is locally catchable; `raise` bypasses catch/trap but may be handled by
an eligible direct-defer recovery boundary; `panic` cannot be handled locally and
must reach supervisor CONTROL, after safe non-consuming cleanup. Cancellation,
revocation and host-fatal signals must not be recovered.

## Proposed source (not implemented)

```ores
fnc process(string x): int {
    recover (|string context| -> |err, c| -> {
        if can_recover(err, context); do
            return 7;
        fi
        c.next();
    })(x);

    defer || -> { release_resource(); };

    work_that_may_raise();
    return 42;
}
```

The factory is evaluated **once at registration**. The resulting handler carries
an ownership-checked capture environment, and it is called only while unwinding
a compatible `throw` or `raise`. A successful callable call must not invoke
any recover callbacks. `|context: string|` may later be considered as an
alias for current type-first `|string context|` lambda parameters; do not add
an ad hoc recover-only lambda parser. The two `c` parameters from the originally
suggested example must not shadow each other: use `context` for the binding
and `c` for the runtime continuation.

### Delegation and result semantics

- Registration order is source order; recovery runs in reverse order (LIFO).
- Compiler-lowered `return value` from a handler sets a `Recovered<T>` outcome,
  which the caller receives as the failed invocation's result. It does **not**
  restart execution after the failing statement.
- `c.next()` is a terminal `Never` effect in **guest source**, moving the
  same pending failure to the next earlier eligible recover registration.
  Delegation is iterative and bounded; it never recursively calls Java handlers.
- With no earlier handler, propagation crosses to the caller's failure context,
  preserving original error identity and effect kind; the caller may return a
  different type. No implicit fallthrough or swallow is legal.
- An eligible handler must terminate in either a compatible return or `c.next()`.
  This needs definite-control-flow analysis for branches and match cases.
- No handler may consume panic/cancellation/host-fatal outcomes, independently of
  whether it declares a generic Error parameter.
- `c` is compiler-issued, non-copyable, non-storable, non-sendable, non-serializable,
  and one-shot. It cannot be placed in persistent actor `state { ... }` or
  retained past the callback. The host prototype invalidates each capability
  even on exceptional exit; the real compiler must additionally reject escape.
- Cleanup `defer`/finally execution precedes recovery selection under the
  chosen boundary and runs at most once, including upon failure and cancellation.
  Cleanup failures preserve higher-priority signals and suppressed diagnostics.
- Raising a new failure inside a handler cannot recursively re-enter the same
  handler. A compiler/runtime unwind guard must route it only outward.
- A handler may only handle values that satisfy its checked `throws`/`raises`
  effect signature; it may not invent an effect or cast an arbitrary host Throwable
  into a guest error.

### Capture and ownership rules

Factory args evaluate once, left-to-right. Ordinary lexical closures can capture
immutable/Copy values, but non-Copy captures move explicitly under `rt take`.
Do not permit an ordinary mutable loan, actor arena reference, socket, Task
capability, or recovery cursor to outlive the lexical activation without a
proven region. A captured read-only view is not automatically `Send`, `Sync`
or portable across actors. `nlex` disables ambient activation capture but can
allow explicitly owned parameters when the checker proves their origin and
lifetime. Reuse the canonical pointerless ownership/actor isolation engine;
do not introduce a second permissive capture checker for recover.

Suspended/source Tasks need a heap-backed **logical** unwind frame owned by
the task/actor, not the carrier OS thread. Physical quiescence must gate actor
arena retirement. Guest callback delivery must remain on the actor's single
serial execution lane; no host callback may run on a producer/IO thread.

## Data-oriented layout investigation

A recovery list is an **ordered LIFO chain**, not a bag of unordered ECS
systems. Typical registrations are small; failures are rare; registering a
handler should add almost no overhead to successful execution. Avoid generic
hash maps and linked lists in the hot path.

| Layout | Proposed representation | Strength | Tradeoff |
|---|---|---|---|
| AoS | `Entry { mask, functionRef, environmentRef, scope }` array | One conceptual object/entry; useful when all fields are touched | Extra allocation/pointer chasing in Java |
| SoA | compact `byte[] masks` + `Handler[] refs`; future `int[] captureOffsets` | Linear reverse scan, dense filter metadata, fast selective skipping | Extra arrays; capture resolution still indirect |
| SoAoS | hot masks SoA + arrays of small payload records | Hot/cold segregation without full flattening | Record allocations remain |
| AoSoA | fixed 16/32/64-element chunks, each with parallel hot arrays | Chunk reuse, bounded locality, useful for large continuation stacks/NUMA domains | Chunk transitions and branch overhead at short depth |
| ECS | dense entity index into pooled registered recovery components | Potential reuse for large fleets of actor-owned descriptors | Indirection and generation/authority validation, poor fit to tiny lexical chains |

**Chosen v1 substrate:** SoA immutable snapshots of handler refs and effect masks,
iterative reverse scan, fixed 1024-registration cap, and a distinct one-shot
continuation for each visited handler. Snapshot ownership and capture validation
remain work for compiler/runtime integration. Do not pool/recycle a continuation
object in a way that lets a retained stale reference act on a new handler.

For a mature runtime, separate **cold immutable code descriptors** (function ID,
effect mask, provenance, source span) from **per-invocation capture environments**
(logical actor arena and owned slots). Compiler-generated small-stack inline slots
can avoid allocating snapshots for 1-4 handlers. An optional AoSoA chunked
representation can be evaluated when profiling shows deep, numerous chains.
ECS should be restricted to a distinct actor/supervisor registry use case, not
adopted as the default for lexical recovery.

Consider compressed 32-bit *indexes* for descriptors/capture offsets where the
region has verified bounds, never raw guest-forgeable 32-bit pointers. Pinned
native arena lifetime, generation checking, and hot-reload code-epoch identity
are mandatory before indices can be reused.

## Exploratory Java layout benchmark

Run the checked-in standalone metadata benchmark after compiling the project:

```sh
java -cp target/test-classes:target/classes dev.oreslang.runtime.RecoveryLayoutBenchmark
```

One local OpenJDK 21 scan (not an authoritative statistical performance result)
gave approximately the following nanoseconds per **metadata walk**; no guest
callback invocation, recovery allocation, or production JIT/AOT was included.

| depth | AoS | SoA | AoSoA-16 | SoAoS | ECS-indexed |
|---|---:|---:|---:|---:|---:|
| 8 | 8.22 | 10.00 | 14.78 | 8.45 | 16.06 |
| 64 | 35.75 | 38.81 | 63.82 | 35.54 | 39.69 |
| 256 | 143.03 | 136.85 | 194.78 | 130.88 | 177.91 |

These numbers are noisy and implementation-dependent, do not establish a
universal winner, and **must not** be presented as Oreslang guest performance.
AoS was competitive for small stacks; SoA/SoAoS became competitive for deeper
scans. Future evaluations must use randomized harness order, JMH/forked JVMs,
confidence intervals, allocation rate/GC, p95/p99, PMU L1/L2 misses, JDK25
GraalVM AOT/hybrid, LLVM native and actor/carrier stress, while preserving
identical logical traces. Benchmark real registration/freeze and successful-call
cost too, since a handler is registered much more often than it executes.

## Release gates (not yet passed)

1. Merge/disambiguate existing draft recover contracts (#1316 vs #128 and prior
   demo), selecting panic-never-recovered semantics across grammar and tests.
2. Lower curried and direct handler registration to a typed `RecoveryHandler<T>`
   without leaking the continuation, reusing the ordinary lambda parser and
   checked capture/ownership engine.
3. Prove handler control flow (`return T` or terminal `c.next()`), forbid
   double use, callback escaping, stale scope, shadowing and invalid error effect.
4. Integrate with real `throw`/`raise` unwind, defer/finally LIFO, suppression,
   cancellation, after-`await` continuations and physical actor quiescence.
5. Confirm source-level panic bypass and exactly-once immutable supervisor
   CONTROL notification when the normal actor mailbox is full.
6. Run exact-head JDK25 Maven/JUnit, GraalVM JIT/AOT/hybrid and LLVM native
   parity suites, including fuzz/property tests and real actor memory pressure.
7. Treat JDK21 standalone class compilation and microbenchmark as **local
   preliminary checks only**. No claim of exact-head source/CI test passing.

## Post-review security and performance hardening (2026-10-22)

The first review found and corrected several specific flaws in this experimental
runtime substrate:

- **Untrusted kind/source mismatch:** a caller could label a wrapped
  `CancellationException`, `TimeoutException`, `SecurityException` or host
  `Error` as `THROW`/`RAISE` and have a handler turn it into a success.
  The dispatcher now fails closed on those terminal host signals, even when
  they appear in the bounded cause or suppressed graph. A cycle/over-budget
  graph also fails closed. This is defense in depth, NOT guest provenance:
  the final compiler bridge MUST mint an unforgeable typed effect carrier.
- **Unbounded worklist breadth:** causal checking formerly enqueued every
  suppressed failure before enforcing the 64-node bound. It now bounds
  insertion before the queue grows. `getSuppressed()` may itself return
  an already-large array, so untrusted host exceptions must still be sealed
  or rejected at the guest adapter boundary.
- **Implicit swallowed raise:** a callback that simply returned without
  calling `resolve` or `next` previously threw a new host exception,
  risking loss of the pending failure. It now returns a `HandlerFailure`
  containing both the pending effect/error and callback error. Double-decision
  and callback-exception tests verify identity.
- **Leaked or off-lane cursor:** every visited callback receives a distinct
  one-shot cursor, invalidated in `finally`. A synchronous foreign carrier
  thread is refused. This thread check is temporary: suspended guest callbacks
  must instead be checked against the logical task/actor admission token.
- **Overexposed host dispatch:** `dispatch` is package-private until a
  trusted guest-effect bridge exists. The standalone helper still accepts
  raw `Throwable` internally; it must never be exported as a guest ABI.
- **Unnecessary allocation/scans:** a common ordinary leaf error now avoids
  constructing the causal graph worklist, and a chain with no mask-compatible
  handler skips causal graph traversal altogether.
- **Stale assertion:** the double-decision regression expected a thrown host
  `IllegalStateException` even after the policy changed to
  failure-preserving `HandlerFailure`. The test now asserts the stronger
  contract. A seeded 400-scenario differential reference-model check covers
  effect masks and LIFO short-circuit delegation.

Local supplementary execution: an independently reconstructed copy of the
core helper compiled with `javac --release 21 -Xlint:all`, and 35 smoke
assertions plus 400 deterministic differential scenarios passed on OpenJDK21.
This is **not** an exact-source commit or repository JUnit run. The committed
18 JUnit methods remain unexecuted on an approved JDK25 runner at the time of
this audit.

The checked-in metadata-layout benchmark now randomizes interleaved execution
order, reports medians across five trials, includes four-handler cases, and
checks full-run checksums outside timed sections. It is **still not JMH** and
does not establish recovery-chain allocation or latency characteristics.
The historical table above is illustrative only and must not be treated as
validated current-branch measurements.

### Additional hard gates before production integration

1. Define a compiler/VM-sealed **guest-originated exceptional signal** with
   nominal error identity and `throws`/`raises` proof. Do not map arbitrary
   Java `Throwable` or user-controlled `FailureKind` labels into it.
2. Treat `HandlerFailure` as a **non-success** whose strongest underlying
   runtime signal continues to propagate. Host fatal Error and cancellation
   must not be downgraded by the callback-error wrapper.
3. Verify direct `c.next(): Never` lowerings have no subsequent guest
   effects, including in branch/match edges; the host `next(): void` method
   is only a simulation, not evidence of terminal guest behavior.
4. Prove that `defer` executes once, in LIFO order, **before** recovery where
   specified, and that a new throw/raise in a handler cannot re-enter itself.
5. Bind any suspended recovery cursor to the authenticated logical Task/actor
   incarnation and arena; carrier-thread identity is insufficient after
   `await`. Forbid capture or persistence of the cursor.
6. Measure successful-call registration and cleanup overhead, fresh-failure
   allocation (cursor + preflight + outcome), and deep-chain p99 alongside
   metadata scans. Evaluate compiler-inlined 0-4-handler storage against
   SoA/SoAoS/AoSoA/SoAoS/ECS on each target; do not use simulated ECS
   metadata walk as a proof of actor scheduler suitability.
7. Obtain real exact-head Maven/JDK25/JUnit, GraalVM AOT/hybrid and LLVM
   source-level conformance evidence, with security-approved CI placement.

## Adversarial callback and mutable-failure follow-up (2026-10-22)

The host-side dispatcher now checks whether a pending failure graph became
terminal **after** each callback makes a decision, before it can yield
`Recovered<T>` or delegate to an earlier handler. This closes a demonstrable
prototype gap: a recovery callback can attach a `SecurityException` or
cancellation marker to its mutable `Throwable` and otherwise resolve or
delegate past it. Inspection failure remains fail-closed and preserves the
original pending failure identity.

Terminal host `RuntimeException` values thrown by the recovery callback
(including nested cancellation, rejected execution, authority denial and
timeouts) have a distinct `TerminalCallbackFailure<T>` outcome carrying both
the original pending failure and the terminal callback fault. They must not
be treated as ordinary recoverable callback failures by a future bridge.
Host `Error` still escapes immediately, but the current invocation invalidates
its one-shot cursor in `finally`.

This remains an **incomplete** security boundary: mutable host `Throwable`
graphs can race with the final read. Raw kind/Throwable dispatch has been
removed and replaced by the compiler/runtime-originated `GuestFailure`
envelope, but its host payload is still mutable and its code epoch is only
locally scoped until compiler/hot-reload integration supplies a canonical
code-image identity. No trusted
authentication, actor-lane dispatch, suspend-frame safety or supervisor
notification is provided by this host prototype.

### Executed exact-blob focused evidence

Java source revision: `ca6c26e644eadedbc180c62a63bbae56e7b18f5a`.
Private isolated CI runner:
https://github.com/agent-pontifex-test/agent-pontifex-e2e-tests/actions/runs/39886905556

The private CI run's verified source and committed-test blob hashes:
- `RecoveryHandlerChain.java`: `a84a158feac3d7287d41103fa62334ed1873f3e2`
- `RecoveryHandlerChainTest.java`: `20661067ecfdbd97fc5947edeca0d6182e96eab7`

The run used OpenJDK 25.0.4.1, compiled the isolated two-file
Maven/JUnit harness at Java release 21, and reported **24 tests,
0 failures, 0 errors, 0 skipped, BUILD SUCCESS**. The temporary target
branch contains precisely mirrored source and test files, a small Maven
harness and its existing test workflow. Source-org CI runs with zero
executed job steps are not presented as source test failures.

This evidence validates only the committed dispatcher and JUnit fixtures.
It is **not** an exact-full-tree build of the Oreslang source repository
or proof of GraalVM JIT/AOT/hybrid, LLVM, guest syntax, static capture
ownership, task unwind/defer or actor/supervisor parity. Keep this PR
DRAFT until those independent acceptance gates execute.

## Compiler-owned guest failure envelope (2026-10-23)

The recovery dispatcher no longer accepts a raw `(FailureKind, Throwable)`
pair. Its only admission method now accepts a package-private `GuestFailure`
whose constructor is private and whose compiler/runtime issuer is package-private.
The envelope's provenance is immutable; a private lifecycle ledger records
one-unwind chain visitation and terminal consumption.
The envelope can be minted only for `THROW` or `RAISE`; attempts to mint
`PANIC`, cancellation or host-fatal as a locally recoverable effect fail before
the handler chain is entered.

Each envelope snapshots the stable logical `ExecutionContext.ExecutionId`,
generation-fenced allocation domain, parent execution, continuation handle, and
an opaque positive compiler source-provenance token. Outcomes retain the exact
same envelope, so propagation cannot silently reconstruct a differently labeled
failure or lose its logical provenance. The dispatch surface has a regression
that asserts there is no raw kind/Throwable overload.

This is a meaningful provenance boundary but not yet the final guest error ABI:
the prototype envelope still carries a Java `Throwable` as its payload identity.
That host graph is mutable, so cause/suppressed inspection remains fail-closed
defense in depth before and after callbacks. Source lowering should ultimately
place immutable typed guest error data in the envelope rather than expose an
arbitrary host exception object. Likewise, the current package boundary protects
ordinary guest/FFI surfaces; it is not a claim that hostile reflective Java code
inside the trusted VM process is sandboxed.

Guest `throw`, `raise` and `recover` parsing/lowering remain disabled. The
next compiler gate is to mint this envelope only from checked exceptional-effect
lowering and then give `c.next()` terminal `Never` control-flow semantics.

## Recovery envelope replay and logical-owner hardening (2026-10-23)

A valid compiler-issued failure capability must not be reusable merely because it
cannot be forged. Recovery chains are now bound at construction to the complete
logical execution identity they were registered for: `ExecutionId`,
generation-fenced `AllocationDomain`, parent execution and continuation handle.
A failure from another Task, actor invocation, actor generation or logical
continuation is returned unhandled without running a callback.

`GuestFailure` keeps its effect kind, host payload identity and provenance
immutable, but now owns a private runtime lifecycle ledger. Distinct lexical
chains may see an active unhandled failure while it propagates outward; the same
chain cannot execute twice for the same unwind. Successful recovery,
`HandlerFailure`, terminal callback failure and reentrancy abort seal the
failure capability so it cannot be dispatched again. Both the nested failure
that triggers a recursion abort and the outer failure whose callback catches
that abort are sealed. This prevents a retained host reference from replaying a
completed exceptional effect.

Only one recovery dispatch may be active for a given `GuestFailure` at a time.
Sequential outward propagation through distinct chains remains legal after an
`Unhandled` result, but concurrent or recursive redispatch of the same envelope
fails closed before a second callback runs. A fatal host escape clears the
in-flight lease while permanently terminating the envelope.

The identity-based visited-chain ledger is bounded to 1024 entries. Exceeding the
quota seals the failure and fails loudly rather than retaining an unbounded
number of recovery-chain objects. The bound is a host-prototype safety limit;
source lowering should statically bound lexical unwind metadata where practical.

These checks use logical execution identity, not carrier-thread identity, and
therefore remain valid across carrier migration. Recovery registrations and
failure envelopes now carry the same opaque runtime-issued `CodeImageIdentity`.
Authentication uses object identity, not `codeUnitId` strings or numeric
generation equality, so two independent hot-reload managers may both allocate
generation `1` without their recovery capabilities colliding.

`HotReloadManager.Generation` and `SharedCodeImageStore.CodeImage` now both
use this canonical identity type. Retirement/close revokes the identity before
tearing down executable context/store state, preventing future recovery
admission. A failure presented to a replacement code image remains unhandled and
unconsumed, allowing its still-live old generation to drain and handle it once.
After the old image is revoked, matching recovery admission fails closed and the
pending failure capability is sealed.

This is still a host-prototype lifetime fence, not complete hot-reload
quiescence. An already-entered synchronous callback is allowed to finish if the
identity is revoked concurrently. Production generation retirement must
therefore wait for authenticated Task/actor/code-generation pins and physical
quiescence before destroying executable code, continuation frames or actor
state. Cross-process identity transport is intentionally unsupported: a
`CodeImageIdentity` is a process/runtime capability, not a serializable wire
identifier.

## Host-cause accessor admission and public API containment (2026-10-23)

A foreign Java `Throwable` may override `getCause()` with an accessor that
alternates values or exposes different causes on successive reads. Earlier
preflight only guarded against an accessor *throwing*, not one lying. The
isolated dispatcher now fails closed for classes whose public `getCause`
is not the standard inherited `Throwable` implementation, including nested
causes. Class metadata is cached with `ClassValue`. That intentionally
rejects some otherwise legitimate foreign wrappers pending a trusted mapping.
The root ordinary-error fast path reads the root cause and suppressed list
once per scan. Three focused tests cover alternating hidden cancellation,
nested hidden security denial, and normal inherited-cause success.

The host dispatcher class is also package-private. No public guest/Java
recovery API is enabled by this experimental substrate, and a regression test
asserts the restricted visibility.

**Latest isolated executable evidence:** upstream source revision
`4ecc382231e085400c97b45e391dbaff8a294e44`,
verified mirrored source blob
`e084b886d2d9d2a814ef96a247cf0300ce30d30f` and test blob
`001c258190c4fe9e5473a373d79bca87ab6e9f14`,
private Actions run
https://github.com/agent-pontifex-test/agent-pontifex-e2e-tests/actions/runs/39903552289 .
Logs confirm OpenJDK 25.0.4.1, `javac --release 21`, and
**28 tests passed, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**.
This is an exact-blob isolated two-file Maven/JUnit harness, not complete
source-tree compilation or language-level recover acceptance.

**Still not proven:** immutable trusted guest error admission;
logical-task/suspend-aware continuation authority; ownership-checked
`rt borrow/take/share/copy` capture binding; correct panic/cancellation
bypass through native/JIT/AOT; unwind, defer and supervisor parity; and
LLVM lowering. Java host `Throwable` subclasses with inherited accessors
are still mutable and potentially subject to concurrent changes, so no
immutable guest-provenance guarantee is claimed. Keep PR DRAFT.

## Synchronous reentrant recovery guard (2026-10-23)

A host recovery callback could previously invoke `dispatch` on its own
`RecoveryHandlerChain`, including indirectly through a second chain.
Despite iterative delegation **within** a chain, recursive **invocation**
could grow the Java stack without a bound or re-enter the same handler.
The host prototype now maintains an identity-based thread-local dispatch
scope with a cap of 64 concurrently nested distinct chains. A repeated
chain or over-depth request records an unmaskable-in-scope `DispatchAbort`
host `Error`, preserving the original nested failure as its cause. Even if
a Java callback catches and ignores the abort, the scope remains poisoned
and will refuse to return a recovered result. The scope is removed on
unwind, and every active one-shot cursor is invalidated. Independent,
shallow nested chains remain valid.

This is a synchronous **host prototype** safety fence, not a complete guest
semantics implementation. Guest lowering must propagate a newly raised
error **outward to eligible lexical handlers** rather than substitute an
unconditional host fatal signal. The eventual actor implementation must
track logical Task/actor identity across suspension, migration, and
shutdown; thread-local identity alone is insufficient.

Five new adversarial JUnit tests cover direct recursion, an A->B->A
cycle, an intentionally swallowed abort, legitimate nesting, and 65
nested distinct chains.

**Executed exact-source-blob evidence:** private CI
https://github.com/agent-pontifex-test/agent-pontifex-e2e-tests/actions/runs/39906864297
tested Java source commit `db7a92b561ac0085d9dd120d499fa7e4bb9279b9`
with exact mirrored blobs:
- `RecoveryHandlerChain.java` `8f5473f74cd64644cb789be5646b7455b08cff66`
- `RecoveryHandlerChainTest.java` `39f4370955086a1517ce64be73fc91f28951f0bb`

The log reports OpenJDK 25.0.4.1, Java release 21 compilation,
**33 tests passed, 0 errors/failures/skips, BUILD SUCCESS**.
Only this isolated dispatcher/JUnit two-file slice was verified;
full-tree Java, GraalVM AOT/hybrid, LLVM native, typed guest recovery
semantics, actor-lane and supervisor parity remain **unverified**.
The source org's preceding workflows failed before job steps.
Keep PR DRAFT.
