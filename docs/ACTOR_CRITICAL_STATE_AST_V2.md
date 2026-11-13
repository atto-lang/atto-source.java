# Stateful Counter V2 — native AST and sealed-state MIR admission

## Canonical multi-file fixture

`contracts/declared-actor-stateful-counter-v2/` is one complete
manifest-owned Atto source package. Its nominal actor declaration,
`CounterContract`, mutable state schema, implementation method,
private `receive(ActorMail<int>)`, and caller are in separate files
in namespace `counter`.

The intended output is `1|3|6|10`, with all four mailbox replies
received **before** shutdown completes. This fixture is the future
identical-source Java/LLVM release gate.

## Typed syntax milestone (NOT runtime execution)

Java's lexer/parser now recognizes *only actor-local statement syntax*:

```atto
self.handle_state(critical-write |s| -> { s.total = s.total + mail.value; });
self.handle_state(critical-read |s| -> { self.send(s.total); });
```

The parser emits a dedicated `Ast.ActorStateAccessStmt` with a distinct
`CriticalStateMode.READ/WRITE`, a named scoped view and the body AST.
It cannot fall through to `CallExpr` or be represented as a
guest-authored `handle_state` method. Neither non-adjacent
`critical -write` nor arbitrary mode spellings receive permissions.

**Executable typechecking and both evaluation paths remain explicitly
fail-closed** with `ACTOR_CRITICAL_STATE_MIR_UNSUPPORTED` until the
following are implemented and tested:

1. Compiler-owned matching of the node to a manifest-authenticated
   `ActorStateAttachmentDecl` and actor receiver `self`.
2. Exact field layout, type and mutability checks. No direct or indirect
   field access outside a verified critical scope.
3. Typed state-access MIR with checked arithmetic, read/write
   acquisition, state-loan lifetime and effect checking.
4. Reject escaping views, nested incompatible loans, arbitrary host
   callbacks and `await`/yield across an exclusive loan.
5. Pin the actor lifetime/generation and unwind the loan on throw,
   cancellation and panic before supervisor CONTROL is delivered.
6. Execute one identical physical package on GraalVM JVM, GraalVM
   Native Image and LLVM AOT, assert four replies `1|3|6|10`.
7. Run native ASan/UBSan, negative parser/type/borrow cases and the
   canonical namespace regression suite.

The LLVM backend still rejects this declarative source form until its
multi-file source linker and matching MIR are implemented. Neither
the earlier Java stateless guest execution nor the older LLVM
legacy-field native Counter proves the V2 guest runnable.

## New manifest-bound typed MIR v1 (STILL NOT EXECUTABLE)

`PackageNamespaceBuild.lowerCriticalStateMirV1()` now snapshots the
complete canonical `.atto.pkg.toml` source set, derives the same nominal
actor/receiver/state witness as the existing audit, and produces a private-
constructor `ActorCriticalStateMirV1.Plan`. The plan retains package identity,
actor identity, source-unit locations, manifest SHA-256 and each source SHA-256.
It contains no guest-reachable mutable state pointer, capability or executor.

This first **narrow** typed instruction sequence is:

1. `CHECKED_ADD_MAIL_I64`: take the explicitly owned `ActorMail<int>`
   operand and update precisely one `let int` field under a write loan.
2. `COPY_I64_TO_ACTOR_OUTPUT`: copy that field under a read loan, then
   send the scalar output without exporting a state alias.
3. `STOP_ON_MAIL_I64`: evaluate the canonical literal equality and
   request only the current actor's `self.end()`.

It requires **exactly** two verified critical scopes in that order, private
`external mtd receive` ingress, one scalar state field, and one checked
stop branch. A literal write increment, extra callback, wrong field, mutable
read, extra statement, or alias shadowing the receiver/message is rejected
before any executable compiler path is available. This plan is not intended
to be extensible by mutation; it is a bounded front-end proof of source
operands and effect shape only.

**No actual critical loan, generation fence or JVM/GraalVM guest execution
has been granted by this change.** Ordinary `build()`, standalone
`compile(Map)` and `TypeChecker.check` remain fail-closed. The dedicated
`actor-critical-mir-junit.yml` workflow runs two targeted JDK25 JUnit
suites, requires positive test counts and zero skipped tests, and prints
`GUEST_NOT_EXECUTED=true`. Runner admission must be verified separately.

The next executable step is lowerer-generated, actor-owned state layout and
authenticated scope acquisition/release inside the **existing** runtime
`ActorCodeExecutor` mailbox turn. It must not use a hand-written Java
Counter handler, a trusted host replay loop, or a synthetic expected-output
oracle to stand in for `.atto` execution.

## Experimental compiled receive on real actor turns (V1, limited)

The manifest-owned builder now has a deliberately **opt-in** entrypoint,
`compileCriticalStateActorGuestV1(SharedCodeImageStore)`, which consumes one
closed snapshot and calls `ActorCriticalStateMirV1.lower` again. It installs
an immutable **whole-source-set** `CodeImage` whose identity includes the
manifest SHA-256 and every physical source-unit digest. The compiler creates
an `ActorCodeExecutor` from the validated MIR, not a user-authored Java
Counter handler. The caller may only attach that executor through the
existing `ActorRuntime.spawnCodeActor(PRIVATE, ..., "Counter")` API.

On a genuine actor startup turn, `initializeActor` allocates an eight-byte
persistent `ActorMemorySlice.PrivateMemoryBlock` and writes the default
declared by the attached `.atto` state field. State holds its runtime, actor
ID, generation and exact CodeImage identity. On each runtime-minted mailbox
turn, `receiveActor` checks those identities *and* the exact currently
admitted `ActorInboxMail`, and decodes one owned Java `Long` (Atto `int`).
It interprets the three **compiler-authenticated** MIR opcodes: checked
`Math.addExact` update under a nonparking exclusive scope, copied read under
a separate scope, a real `emitCurrentActorOutput` call, and finally
`endCurrentActor` when the source-derived stop predicate is true. No
state pointer/view appears in mailbox output. The state storage is closed
under the actor's cooperative teardown and invalidated with the actor's
memory slice on physical retirement. Overflow throws before persistence.

`ActorCriticalStateGuestV1Test` drives real runtime mailbox messages, checks
the computed `1|3|6|10` outputs, two actors' independent state, checked
overflow with sibling survival, invalid message types, and an altered
**physical Atto package** (renamed field, initial state 17, stop on 2)
which must produce `24|26`. This proves the executor is source-derived,
not a four-constant output oracle, *if and only if the tests actually run*.

**Important evidence boundary:** this first slice compiles the Atto actor's
`receive` method only, and the test driver sends actual mailbox DATA from
Java. It does **not** compile or execute `main.atto`, nor permit arbitrary
external methods, effects, untrusted actors, general concurrent critical
readers, suspension inside loans, or all-backend parity. Ordinary `build()`
and `TypeChecker.check` remain deliberately fail-closed on unsupported
declarations. The current JVM direct-memory block is a bounded owner-checked
scalar region, **not** proof of the complete 32-bit arena-relative heap ABI.

The dedicated JDK25 CI workflow compiles the complete module and executes
all three JUnit classes, with zero skips required, before claiming the
`SOURCE_MIR_MAILBOX_EXECUTED=true, FULL_ATTO_MAIN_EXECUTED=false` receipt.
The source GitHub organization has recently failed runner admission before
any steps; until this workflow has stepfully run, this new runtime behavior is
**committed but unverified**.

## Manifest-owned semantic preflight

The `PackageNamespaceBuild.auditDeclaredActors()` pipeline now runs
`ActorCriticalStatePreflight.verify(...)` only on the canonical, complete,
manifest-owned source set, after the nominal actor contract witness and
receiver index have been constructed. It emits immutable
`CriticalOperation` proof descriptors identifying the exact source unit,
namespace, actor, method, read/write mode, field and scalar type.

The present intentionally **narrow** operation subset is:

- `critical-write`: exactly `view.int_field = view.int_field + int_mail.value`
  or a signed-i64 literal increment, requiring a mutable `let int` field
  in the verified attached state schema.
- `critical-read`: exactly `self.send(view.int_field)` of a copied
  scalar field with a verified declaration and type.
- No view escaping into ordinary sends, direct `self.field` state access,
  nested critical scopes, reader writes, unknown fields, immutable writes,
  callback returns, suspension or arbitrary unverified effects.

These checks are **compiler-admission preflight**, not a substitute for
LLVM/Java typed state MIR or scheduler-issued state loans. All execution
paths are still blocked until the remainder of the stated release gates
pass. The raw `TypeChecker.check` and standalone compile(Map) entrypoints
do not gain authority from a caller-supplied preflight result.
