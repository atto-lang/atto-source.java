# AttoLang M3.3-prep — Env snapshot owner identity and retired-frame denial

**Status: authored source changes, not executed Java/JUnit/guest evidence.**
This is a narrowly scoped child of
[Java #2662](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2662);
parent and other agents' branches remain unchanged.

## Audited gap and repair

The M3.2 `Evaluator.Env` routes supported scalar lexical reads/writes into
an actor-owned `SourceTask` frame. `Env.snapshot()` retains aliases of
`Slot` objects used in lambdas and asynchronous callbacks. Each frame-backed
`Slot` previously stored a scope epoch but not the **specific scheduler
execution identity** that owned it.

An unrelated SourceTask running on the same actor could therefore reach the
shared arena with a captured `Slot`, possibly attempting to access its own
frame using another task's scope name. This should be **rejected before
touching any current actor frame**, rather than poisoning a sibling task or
rehydrating the JVM compatibility shadow.

The compiler now stores the immutable, identity-compared
`ExecutionContext` that admitted each managed scalar slot. `lookup`,
`assign`, and initialization demand the exact same active SourceTask
identity before calling the frame bridge. `Env.snapshot()` validates
all its managed scalar aliases before copying them and recursively checks
its ancestors. A foreign host, other SourceTask, stale callback after owner
completion/cancellation, or old generation receives `SecurityException`
without reading the shadow or touching another task's frame. Legitimate
same-task snapshots and scalar mutation across `await` remain admitted.

This intentionally **does not copy/reconstitute** arbitrary lexical
references, closures or mutable object roots. `Slot.value` still exists
as a compatibility shadow, but a managed slot's value is read only via
its authenticated actor frame, not this JVM shadow. Direct detached
Task COPY/MOVE remains denied by Java #2662.

## Test repair

`ActorLexicalTaskCaptureFenceTest` previously used the nonexistent
three-argument reflective `Env.define` signature. The actual method
requires a fourth `Ast.ClassDecl` argument, so those tests would fail
before reaching their intended capture admission checks. This patch
corrects the method lookup/invocation and retains the existing three
COPY/MOVE positive/negative test cases.

## New JUnit host acceptance models

`ActorLexicalEnvSnapshotExecutionFenceTest` adds three host-runtime tests:

1. A managed scalar snapshot remains readable/updatable by its **own**
   stable execution context after `await`; it is denied from the host
   during the suspension and after the owning frame has been reclaimed.
2. A second SourceTask inside the **same actor** cannot snapshot, read,
   or reassign the first task's slot while it is waiting; denial does not
   poison their shared actor arena. The original task resumes unchanged.
3. A deliberately nulled owner token causes lookup/snapshot denial
   without actor poisoning; restoring the correct owner permits the
   original task to read its scalar.

All are **AUTHORED_NOT_EXECUTED**. Their host reflection reads private
compiler Env structures to verify the real bound-slot path, but is not
a substitute for compiled canonical `.atto` acceptance tests. Runtime
JDK 21 exists locally; Maven and the full dependency tree are unavailable,
and hosted GitHub Actions jobs have previously had `steps=null` and
`runner_id=null`. There is **no claimed Java compilation or JUnit pass**.

Required exact-head command on an admitted Java/GraalVM runner:

```sh
mvn -B -ntp -Dtest=ActorLexicalEnvSnapshotExecutionFenceTest,ActorLexicalTaskCaptureFenceTest,ActorSourceTaskLexicalFrameTest,ActorGuestReturnRecordTest,ActorLexicalScalarFrameLanguageTest,SourceTaskScalarParameterBindingTest,ActorSourceScalarParameterLanguageTest,ActorScalarSourceTaskIntegrationTest,ActorSourceTaskFrameTest test
```

Capture exact commit/tree/blob identities, test reports, toolchain versions,
runner and workflow attempt, nonzero executed steps, and independent review.
Zero-step CI is runner admission failure, never guest execution proof.

## Remaining blockers

Async completion callbacks and lambda creation call `Env.snapshot()`.
The runtime now denies their cross-task access to managed scalar slots,
but the compiler still needs real positive/negative `.atto` tests proving
legal same-task callback delivery, rejected cross-task capture, cancellation,
supervisor restart and nonreentrant source actor mailboxes.

Return-PC record ABI is still not wired to an iterative guest dispatcher;
non-tail guest recursion still grows JVM call frames. Native physical actor
stack containment, reference roots, cleanup/exception continuation frames,
and exact-head five-backend parity remain **NOT_EXECUTED**. No merge or
promotion to stackless guest status is justified by these host changes.
