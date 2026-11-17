# Java/GraalVM transform pipe actor: compiler admission only

Status: **draft; do not merge or claim Java guest execution parity**.

The parser recognizes:

```ores
define transform pipe actor Stage<
    forward_messages=PipeForwardMessages,
    backward_messages=PipeBackwardMessages
> as
    receive_forward(PipeForwardMessages m): void { return; }
    receive_backward(PipeBackwardMessages m): void { return; }
end
```

A transform requires exactly one handler per direction, exact nominal
parameter spelling and void return, no async/static/public/abstract/generic
hook, no structural or inferred/borrrowed parameter, and no ordinary
`receive` fallback. Ordinary actors cannot impersonate directional hooks.
The declared role survives both `AnnotationExpander` and `TreeShaker`.
The same declaration is accepted at root and module scope.

**Fail-closed compiler/runtime boundary:** The current Java source mailbox
executes the traditional `receive(ActorMail<T>)`, without trusted
forward/backward ingress dispatch. The TypeChecker rejects transform roles
with an explicit missing-directional-DATA-dispatcher diagnostic, including
after annotation expansion. It also rejects forged ordinary actor ASTs with
directional hooks and invalid non-actor/proxy role metadata.

Source JUnit regression cases:
`src/test/java/dev/oreslang/TransformPipeActorRoleTest.java`.

Focused exact-source funded JDK25 validation:
https://github.com/zed-pkg-test/oreslang-private-pr-validation-20261012/actions/runs/38311417501

The funded runner verified 12 exact Java Git source blobs, compiled the
actual parser/AST/TypeChecker/OwnershipChecker/annotation expander/tree
shaker, and passed **23 focused positive and negative checks**.

New actor-state admission:
`state { value: int = 0; }` is parsed as `Ast.ActorStateSpec`
**independent** of ordinary `ClassDecl.fields`. Actor instance
`let`, `val`, and `const` are rejected. Duplicate state map declarations
and duplicate fixed keys are rejected. The state metadata survives
`AnnotationExpander` and `TreeShaker`. Actor classes may have methods
without a state map. The TypeChecker **fails closed** on any state declaration
because Java does not yet implement scoped state-loan execution.

This focused CI does **not** run the full Maven/JUnit suite, Native Image,
source-level Java actor DATA delivery, or a Java 15-actor guest roundtrip.
JUnit regression source is included, but not claimed executed in this gate.

The restricted LLVM compiler in PR #244 now **does** execute synchronous
fixed-key state callbacks in all three real 15-actor pipeline spellings:
https://github.com/zed-pkg-test/oreslang-private-pr-validation-20261012/actions/runs/38312289067
passed 26/26 focused native CTests. General async state, multiple concurrent
flows, Java runtime parity and full legacy regression migrations are
outstanding. See `docs/ACTOR_STATE_INVARIANT.md`.

## Canonical `self.handle_state` read and write design

The canonical actor-state API is `self.handle_state`:
`self.handle_state(|state| -> {...})` denotes a read-only loan;
`self.handle_state(critical |state| -> {...})` denotes an exclusive
mutable loan. The older `using state.read/write` native prototypes
are temporary aliases, not the preferred API.

Java's parser now recognizes and deliberately rejects both canonical
forms as reserved actor-state capabilities with an explicit
unimplemented-runtime diagnostic, rather than treating them as ordinary
method calls. Java continues to reject the older `using` aliases.

[Funded Java JDK25 CI #38314867754](https://github.com/zed-pkg-test/oreslang-private-pr-validation-20261012/actions/runs/38314867754)
passed **27** focused parser/compiler checks. LLVM's restricted
execution of the canonical forms passed
[57/57 native CTests](https://github.com/zed-pkg-test/oreslang-private-pr-validation-20261012/actions/runs/38314809579).
No executable Java state-loan, Native Image, or Java/LLVM guest parity
is claimed. See `docs/ACTOR_STATE_USING_CAPABILITIES.md`.
