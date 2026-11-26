# AttoNATS actor scalar critical MIR v1 — opt-in executable slice

**Status: DRAFT.** This is a source-AST-derived scalar actor compiler stage,
not the general AttoLang `state{}`/`critical` implementation, and it has no
admitted exact-head JUnit/guest CI receipt yet.

## Supported source subset

`OresCompiler.compileSealedActorScalarI64(source)` parses an Atto source
actor and only lowers the following closed-world shape:

```atto
define actor Counter as
  state { let int count = 0; }
  critical increment(): void {
    self.handle_state(|mut s| -> {
      s.count = s.count + 1;
    });
    return;
  }
  receive(ActorMail<int> mail): void {
    self.increment();
    return;
  }
end
```

This is not text matching. The lowerer checks the parser's AST method effect
flag, typed actor/mailbox and state declarations, exact mutable lexical
lambda, one state field, one checked scalar addition, the complete receive
body, scalar init type, and no imports, extra declarations, macro effects,
async, suspension, or parallel execution. Other bodies/aliases fail closed.

Its private, constructor-sealed MIR plan binds to the **identical**
`SharedCodeImageStore.CodeImage.program()` object. Only the validated plan
can mint a `ActorRuntime.ActorCodeExecutor`. Startup allocates the
existing `SealedActorStateRegion.allocateFromSealedDeclaration` on the
actor's real runtime execution domain, not in a caller-supplied Java
counter. Mail is validated against actor ID/recipient/type, then a short,
non-suspending write loan reads one integer, performs
`Math.addExact(value, 1)`, and writes back. The runtime already fences
views after lexical scope exit and closes sealed source regions after
physically quiescent source actor finalization.

The optional host `LongConsumer` is a **test-only post-loan observer**
rather than a guest stdout print or authorization capability. Its callback
runs after the region loan is released.

## What tests need to execute

`SealedActorScalarMirV1Test` checks three sequential real
`ActorRuntime` mailbox turns accumulating 1, 2, 3; independent
program/code-image identity mismatch; malformed/forged critical state
methods and lambda mutation; no ordinary compiler admission; and signed
64-bit overflow causing actor failure rather than wrapping state.

Run:
```sh
mvn -B -ntp -Dtest=SealedActorStateAdmissionTest,SealedActorStateRegionTest,SealedActorScalarMirV1Test test
```

If the upstream source organization is runner-starved, a CI mirror MUST use
the entire exact source commit tree (including this class and tests), not
only matching filenames. Require exact HEAD and tree, nonzero executed
steps, independent source/blob identity, and a recorded compiler binary
hash before declaring anything guest-backed. A failed zero-step job is not
a test result.

## Missing before AttoNATS guest or production

This narrow lowerer does not execute source `main()`, additional methods,
read-only critical loans, general typed actor messages, source-level
stop/control handlers, `async`/`suspends`, multiple state fields,
all types/ADTs, binary sockets, revocation, or Core NATS routing.
The general TypeChecker and OresEvalRootNode **continue to reject**
unlowered critical methods. The broker's exact
`tests/guest/actor_state_critical.atto` with `increment`, `has_one`,
stdio marker and supervised join remains **NOT_EXECUTED**.

Next: compile the full source actor methods through a checked
state-loan/effect MIR with byte-I/O and runtime capability provenance,
then execute the original AttoNATS guest and two-client probe.
