# Lexically scoped transport borrowing: using resource.borrow() as view

Status: parser/AST admission only. This is NOT an enabled guest runtime feature.

The Java/GraalVM parser now recognizes:

```ores
using request.borrow() as view {
    const method = view.method();
    const path = view.path();
}
```

It creates a distinct UsingBorrowStmt(resource, binding, body). This AST node
must never be desugared into a generic binding plus BlockStmt because that
could skip cleanup, copy host-owned buffer pointers, or permit an asynchronous
continuation to outlive the borrow.

The resource expression must syntactically be a zero-argument .borrow() call.
The alias is scoped by the braces. A user function named using remains callable
with using(...), without turning using into a global reserved word.

The TypeChecker currently fails closed on every UsingBorrowStmt. This is
intentional: a parser acceptance test is NOT proof of runtime eligibility.

## Mandatory semantic gates

- The source type checker must prove receiver is an authenticated, runtime-
  minted non-Sendable RequestHandle (or future opted-in scoped resource).
- The binding is read-only and cannot be assigned to actor state {}, copied,
  stored, returned, captured by escaping lambdas, sent as ActorMail or exported
  through a borrowed field/view alias.
- Direct and transitive call effects must be analyzed: using an async function,
  blocking readch/select, rt cooperate, or a suspends callback while a view is
  live is prohibited until a separately verified pinned-loan suspension model.
- Scope cleanup must run exactly once on normal fallthrough, return,
  exception, raise, panic, cancellation, and actor teardown. The transport
  table must remain alive until all existing read leases have quiesced.
- Nested scopes are allowed only where the borrow checker proves alias and
  mutable/shared loan compatibility. A generation-fenced request handle can
  survive suspend in continuation storage; the borrowed view cannot.
- The runtime must derive actor identity and incarnation from trusted execution
  state. The compact slot identifier by itself is not authentication.
- LLVM parser/AST/ownership/codegen must implement the same shape and run the
  same negative/positive fixtures. Untrusted and isolated actors require
  separate IPC/broker validation, not raw cross-process pointers.

Related native transport substrate:
https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/876

## Existing regression tests

UsingRequestBorrowScopeParserTest validates AST shape, nested syntax,
malformed borrow calls/aliases, compatibility with using(...) function calls,
and explicit typechecker rejection. The tests do NOT claim that Oreslang actors
can execute scoped borrowing or that zero-guest-allocation source benchmarks
have passed.
