# Closed namespace actor scalar turn lowering — phase 1

This is an **internal compiler-owned** execution slice on top of the
`declare actor`, sealed attached state, private `external mtd` and lexical
`critical mtd` parser/binder. It is **not** a general namespace actor
language feature, broker backend, or claim that public guest compilation is enabled.

## Exactly admitted source

```atto
use namespace rmq_test;
declare actor Counter;
define state on actor Counter as { let int count : 0; }
external mtd (Counter c) init(): void { return; }
external mtd (Counter c) receive(ActorMail<int> mail): void {
    c.bump();
    return;
}
critical mtd (Counter c) bump(): void {
    using state.write(critical |s| -> {
        s.count = s.count + 1;
    });
    return;
}
```

The package-private `NamespaceActorScalarTurnCompiler.compile(CodeImage)`:
- consumes **exactly one trusted, immutable source code image** with one
  explicit namespace, no imports, one declared shared actor, one state field
  initialized by a typed signed-int64 literal, and one init/receive/critical
  declaration each; rejects all extra declarations or bodies;
- reuses the existing nominal actor receiver and sealed-state binders;
- validates the complete receive AST as exactly one self-call to its private
  zero-argument critical method followed by `return;`;
- validates the *entire* token-preserving critical body against a single
  lexical, nonescaping `state.write` region assigning `s.count + 1`;
- emits an immutable shared execution plan and allocates a **new private
  scalar state instance** inside each real `ActorRuntime` initialization turn;
- validates the current runtime, actor identity, CodeImage, mailbox recipient,
  typed `ActorMail<int>`, and physical receive envelope on every turn;
- evaluates the typed increment using checked signed-int64 arithmetic, where
  overflow terminates only the owning actor rather than silently wrapping;
- does not offer any source-accessible method reference, state pointer,
  callback, host broker implementation, or public invocation escape.

## Exact boundary

This v1 slice relies on the existing Java ActorRuntime's serialized
nonreentrant actor mail turns for its one state write. It does **not** yet
integrate the physical fair read/write `ActorStateRwLoans` machinery from
the separate runtime integration draft #2796. That reconciliation is
mandatory before adding read loans, multi-write bodies, suspension,
cross-file packages, composite state, channel/connection AttoRMQ actors,
cancel/restart migration or a general-purpose critical MIR interpreter.

The normal `TypeChecker` continues to reject namespace actor source as
`*_LOWERING_UNSUPPORTED`. Only the explicitly trusted package-private
bridge can create the closed-slice executor; this draft does not turn
`Context.eval` into executable namespace actors.

## Regression contract

`NamespaceActorScalarTurnCompilerTest` contains real actor runtime
spawns using the same compiled CodeImage, independent state/checked
overflow outcomes, forged ingress and wrong-mail denial, lexical
negative mutations, unverified AST provenance denial, and normal
public compiler fail-closed behavior.

CI must run the suite at the PR exact HEAD with **nonzero executed
steps**, non-skipped Surefire XML and an immutable source/tree receipt.
A source-free host oracle, parser-only test or runner failure with
`steps=[]` is **not** guest evidence. Do not merge or enable on
AttoRMQ until the runtime state-loan integration, complete guest
semantics, cancellation/STOP, AOT proof and independent review are ready.
