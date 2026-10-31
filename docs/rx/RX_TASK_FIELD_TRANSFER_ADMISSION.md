# RX Task field-transfer compiler admission

Stacked onto Java Task source PR #1468 at frozen source parent
`48dd11bc6f6d52ee093afdf2f679409ab163cdfa`.
Companion draft: https://github.com/ores-truffle-oreslang/rx-ores-tasks/pull/1

The Java OwnershipChecker's `rt take` branch intentionally rejects
`Ast.MemberExpr` when the class slot has `bindingKind != LET`.

The initial Task Rx prototype mistakenly declared:
`val Task<T> producer;`
and then attempted:
`rt take self.producer;`

That is a **deterministic source conflict**, not a backend race.
The RX branch changes the source slot to `let Task<T> producer;`.

This stack adds real JUnit compiler/guest checks for the exact behavior:
1. Immutable Task class-field take must fail with the explicit error.
2. Mutable Task class-field transfer must pass parser, type-checker and
   ownership checker.
3. A transferred Task must yield 42 on an actual polyglot guest scheduler.
4. Reusing the same affine local Task source in two subscriptions must fail
   before scheduling.

These are **authored tests, not executed proof**, until a verified job
actually builds and runs them on this exact Java branch. They are not a
substitute for TaskScope physical quiescence or a proof that
`holder.take_task()` cannot be called twice on the same object:
projected class-field transfer-state is still an explicit outstanding
proof obligation. The RX adapter's demand bit is only a single-owner
runtime gate; it must not be advertised as an atomic cross-carrier gate.

No private source export is authorized by this change. Tests run in
the source org only unless the security approval gate is met.


## Runtime ownership fix proposed on this draft branch

`OresEvalRootNode.Evaluator` previously evaluated the `Ast.MemberExpr`
and then invoked `applyRuntimeOwnership("take", value)`, an identity
operation. For a mutable class slot this returned the same move-only
Task again on a second call; the lexical ownership checker alone
could not prevent repeated `holder.take_task()` calls.

The patch adds an evaluator-private `MOVED_FIELD` sentinel and a
single shared `takeClassField` path. Both synchronous and suspendable
`rt take` evaluate the receiver once, verify class-field visibility
and `LET` binding, then consume the slot under the field-map lock.
A second move or read of a moved slot now throws
`IllegalStateException: ... was already moved`. Reassignment through
the ordinary mutable field path can reinitialize the slot.

The regular member getter and the invocation-field read reject moved
fields. Reads and mutable writes participating in these paths are
synchronized on the same backing field map. No guest invokable is
called while holding the field-map lock.

Two additional real polyglot JUnit tests demand:
- a second `holder.take_task()` fail closed;
- a read of the field through another class method after `take_task()`
  fail closed, either by checked ownership rejection or the runtime sentinel.

**Limitations:** No exact-head JUnit, Maven, ASan/TSan, GraalVM native
image or full compiler guest-suite run has executed yet. This narrow
class-field guard is not a generic per-object borrow checker or a
proof that every collection/struct projection has equivalent move
semantics. Concurrency and actor-scope retirement still need separate
admission. The patch is review-only and must remain draft.

## Runner evidence and private source-transfer policy

The repository's `.github/private-ci-approved-repositories.txt`
at source main had zero approved destinations on 2026-10-31 and
requires independent `@ORESoftware` review for carrier export.

The separate `zed-pkg-test/java-app` source-free admission probe
[PR #239](https://github.com/zed-pkg-test/java-app/pull/239)
transferred **no private source**. It produced zero workflow runs
from the API-created branch/PR event and was closed unmerged. An
historically stepful October 16 Actions run in that carrier
is not evidence of current runner availability.

Do not mirror this private Java branch to an unapproved org. The next
necessary executable gate is a source-side runner that can execute
`mvn -B -ntp -P '!native-thread-unix' -Dtest=RxTaskFieldTransferAdmissionTest test`
at the exact tested head; keep test admission `NOT_EXECUTED`
until real JUnit steps and output are retrieved.

## Focused source-side execution gate (added 2026-10-31)

`.github/workflows/rx-task-field-admission.yml` is an exact-source-
head read-only workflow for this stacked Java draft PR. It uses the
repository's existing pinned checkout and GraalVM 25.3 actions, keeps
contents read-only and disables checkout credential persistence.
For PR events, the checkout is `github.event.pull_request.head.sha`,
not a potentially misleading synthetic merge ref. It prints the
checked commit and Git tree and fails if they differ from the requested
head. The Maven step requires an actual surefire report proving exactly
**8 tests run, 0 failed, 0 errors, 0 skipped**.

A successfully scheduled workflow would prove only those eight
Java compiler/JVM guest tests on that exact HEAD. It would not prove
GraalVM Native Image, runtime sanitizer or multi-backend parity.
The workflow has no cross-organization clone or private-source
export. Zero recorded steps remain **NOT_EXECUTED**.


## Mutable field snapshot consistency (2026-10-31)

The same evaluator also contained unsynchronized iterations of an
`OresObject`'s live mutable `fields` map during async argument
detachment, live MutexGuard discovery/release, shared-state validation
and display rendering. A concurrent `rt take`/mutable assignment
could invalidate the iteration, produce a torn traversal or expose
the internal `MOVED_FIELD` marker as Java object identity.

The draft now adds `OresObject.fieldSnapshot()`, which creates a
stable `LinkedHashMap` copy under the same monitor as class-field
moves and writes. All five traversal paths and `sharedStateChildren`
use the snapshot. The display prints `<moved>` rather than revealing
an opaque sentinel. Recursive traversal and cleanup occur **after**
the lock is released, avoiding user-code reentrancy under the monitor.

This guarantees a consistent **shallow field-map snapshot**, not a
deep transactional snapshot or proof of owner/lifetime safety for
nested values. The Java compiler/runtime JUnit, actual multithreaded
stress tests, and Graal native executions are still **NOT_EXECUTED**
until source-side or independently authorized CI steps run.


## Testable host snapshot seam (2026-10-31 follow-up)

`OresFieldSnapshots.copy(Map)` is now the package-private Java-only
boundary used by `OresObject.fieldSnapshot()`. The production
implementation is a synchronized `LinkedHashMap` shallow copy.
`OresFieldSnapshotsTest` adds three JUnit host tests for field
order/null/sentinel preservation, release of the monitor after
copying, and concurrent atomic two-field updates against three
simultaneous snapshot readers (20,000 writes and 60,000 reads).

The focused workflow requires **both** exact Surefire reports:
eight Oreslang compiler/Graal guest tests and three host Java
snapshot tests, all zero failures/errors/skips. The host helper is
JDK 17-compatible and can also be smoke-tested with a standalone
JDK 21 compiler without importing the private full compiler tree.
That smoke result **does not** replace the exact-head workflow.

A shallow snapshot does not guarantee deep ownership safety, handle
affinity or complete actor/TaskScope retirement. Those remain
separate runtime/conformance gates.
