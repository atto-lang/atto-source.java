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

Java now recognizes the canonical **detached Copy scalar** expression:

```atto
self.handle_state(critical-write |s| -> { s.total = s.total + mail.value; });
val int snapshot = self.handle_state(critical-read |s| -> s.total);
self.send(snapshot);
```

The parser emits `Ast.ActorStateAccessStmt` for scoped writes and a
distinct `Ast.ActorStateReadExpr` for scalar reads. The read view never
becomes an ordinary closure or `CallExpr`; the manifest witness requires
an immutable-identity `val int` binding and exact `self.send(snapshot)`
**after** the reader loan ends. An old `self.send(s.total)` within a
`critical-read` block now fails semantic preflight. Neither non-adjacent
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
- `critical-read`: exactly `val int snapshot =
  self.handle_state(critical-read |view| -> view.int_field);` where
  `int_field` is a verified scalar state field and the only immediate
  following operation is `self.send(snapshot);`. The lexical loan is
  released before mailbox publication; a direct send inside the borrowed
  scope or mutable/aliased snapshot is never admitted.
- No view escaping into ordinary sends, direct `self.field` state access,
  nested critical scopes, reader writes, unknown fields, immutable writes,
  callback returns, suspension or arbitrary unverified effects.

These checks are **compiler-admission preflight**, not a substitute for
LLVM/Java typed state MIR or scheduler-issued state loans. All execution
paths are still blocked until the remainder of the stated release gates
pass. The raw `TypeChecker.check` and standalone compile(Map) entrypoints
do not gain authority from a caller-supplied preflight result.

## Canonical source identity

The seven units in the Java Counter V2 fixture are to be byte-identical
with `actor-counter-identical-source-v1` in the Java/LLVM/.NET parity
contract (including `external mtd receive`). This patch reconciles the
receive source with the canonical detached Copy form. Native execution is
still deliberately **NOT_EXECUTED** until manifest-bound MIR is implemented.
