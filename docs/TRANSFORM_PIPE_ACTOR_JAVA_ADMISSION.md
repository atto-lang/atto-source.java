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
https://github.com/zed-pkg-test/oreslang-private-pr-validation-20261012/actions/runs/38300778417

This verified 12 source blobs, javac compilation of the actual parser,
AST, checker, ownership checker, annotation expander and tree-shaker,
and 14 parser/compiler smoke cases. The final funded rerun also verified the stricter forged-AST and type-checker guards. This gate does **not** execute the
source JUnit suite, full Maven, Native Image, source-level actor DATA
delivery, or a Java 15-actor round trip.

LLVM supports an executable *restricted scalar-state* transform profile in
its separate PR #244. The canonical fixed-key `state { value: int = 0; }`
and scoped `handle_state(critical ...)` implementation is not yet
combined with native pipeline codegen, so design examples must remain marked
non-executable on both backends.
