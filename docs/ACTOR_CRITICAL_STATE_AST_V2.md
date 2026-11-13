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
