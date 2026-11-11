# Stateless `declare actor` opt-in prototype — Java/GraalVM

**Release status: draft / not promoted / test execution pending.**

This is an intentionally narrow first **potentially executable** path for
out-of-line lifecycle roles. It does not replace the canonical actor contract,
grant general receiver methods, permit mutable state, or establish all-backend
conformance.

## Example source

```atto
namespace standalone;

declare actor Worker;

define external mtd (Worker w) init(): void {
    return;
}

define external mtd (Worker w) receive(ActorMail<String> mail): void {
    self.send(mail.value);
    self.end();
    return;
}

pub async routine main(): void {
    val worker = spawn Worker();
    await worker.get_ready();
    worker.send("delivered");
    val output = (await worker.outputs.next()).value.unwrap();
    stdio.stdout.write(output.value);
    await worker.get_done();
    return;
}
```

## Explicit compiler API (not the default)

```java
Ast.Program checked =
        OresCompiler.parseAndTypeCheckStatelessDeclaredActors(sourceText);
```

Only a trusted embedding host may install that **already-checked** program
using the existing single-use, source-and-code-unit-bound
`OresLanguage.openPrecheckedProgram` lease before invoking the JVM guest
evaluator. Ordinary `OresCompiler.parseAndTypeCheck`, `TypeChecker.check`
and normal `Context.eval(source)` continue to reject canonical out-of-line
methods until general actor-state and cross-file lowering are proven.

The opt-in lowerer first runs the production nominal receiver binder and then
requires **all** of the following:
- A canonical `declare actor Worker;` in the same source unit and module as
  its external methods. Legacy `define actor ... as name;` is not admitted.
- Exactly one private, explicitly marked `external init(): void` whose entire
  body is a single inert `return;` statement and whose argument list is empty.
- Exactly one private, explicitly marked
  `external receive(ActorMail<T>): void`. Its body is subject to the normal
  actor type and ownership checker. Direct/captured actor `receive` remains
  forbidden by the integrated ingress guards.
- No arbitrary out-of-line `mtd` attachments or alternative runtime roles.
  No actor state, state-builder return, inheritance, generic witness, indexed
  loans, cross-file executable linking or broader actor init effects.

For this strictly stateless case, the compiler emits a **fully populated
compiler-checked internal actor class** with a private inert `on_start` and
the typed private `receive`. This is not a fabricated empty class and must
never be done for an unbound declaration. It reuses the existing
`OresEvalRootNode` and actor `ActorRuntime.ActorCodeExecutor` dispatch,
including the actor-cell-owned `ActorSourceLifecycleGate` that now enforces
one-shot init, owned state, recipient identity, terminal teardown and physical
retirement.

The opt-in API deliberately uses the existing actor backend instead of
introducing an unverified second mailbox executor or a source-callable actor
context capability.

## Executed proof still required

`StatelessDeclaredActorExecutionTest` contains 14 authored JUnit cases.
One is a real GraalVM guest execution test with the existing
single-use prechecked-program lease and expected `delivered` output.
Other tests deny default compilation, missing or unmarked lifecycle
roles, side-effecting init, wrong mailbox types, arbitrary methods,
duplicate startup, unknown receivers and legacy receiver aliases.

The exact-head source-org JDK25 workflow runs this alongside 10 other suites,
requiring at least **176** executed, unskipped cases (per-suite counts checked). At the time of writing,
GitHub has been failing before runner admission. No successful JVM guest,
JUnit, no-fallback Native Image or five-backend result is claimed.

## Explicit next increment

After exact-head JDK25 tests genuinely execute and pass, implement a
compiler-owned sealed state schema and state builder; authenticate
package-relative cross-file actor attachments; prove affine actor state
loans through critical read/write and suspension; and carry runtime entrypoint
role, actor owner, activation generation and supervisor CONTROL policy through
typed HIR/MIR to all five backends. Do not lift this opt-in stateless subset
into normal compilation until the security review and conformance evidence
cover those features.
