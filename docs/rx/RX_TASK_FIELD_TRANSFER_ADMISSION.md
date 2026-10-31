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
