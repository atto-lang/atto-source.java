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
