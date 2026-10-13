# Typed exceptional-effect admission contract

**Status: Java grammar and nominal Error-operand check added on draft PR #811;
execution remains fail-closed. LLVM source lowering not yet admitted.**

Both production Oreslang lexers reserve `throw`, `raise`, `panic` and
`recover`. Java/GraalVM now parses the first three as dedicated
`ExceptionalEffectStmt` AST nodes and **validates the operand type before
blocking execution**. A typed, valid operand still receives an explicit
`source effect is not yet enabled` diagnostic. `recover` continues to
be parser-rejected pending supervisor authority lowering. Neither compiler
may treat these keywords as ordinary identifiers, functions or methods.
LLVM still rejects unsupported exception syntax before emitting LLVM IR.


## Mandatory nominal std/error.Error payload

All three effects require one **owned, non-null, statically proven**
`std/error.Error` value or nominal subclass. There are no zero-argument
or primitive payload variants:

```ores
import class Error from "std/error";
import class ActorAskTimeoutError from "std/error";

throw new Error();
raise new ActorAskTimeoutError();
panic new Error();
```

These examples satisfy the new **operand type contract**, but still fail the
separate runtime-admission gate until native/JVM unwind and actor CONTROL
semantics are implemented. They are **not runnable examples yet**.

The verifier requires an actual `import class Error from "std/error"`,
linking the stdlib's nominal class identity. It will not accept a local
unrelated `class Error`, a `Serializable` or `CodedError` implementation,
a structurally identical record/object, a Java `Throwable`, a string,
integer, bool, or a null/optional value. `Any`/`Unknown` values must
first be narrowed to a concrete proven Error type, not silently cast at the
effect site. A union is eligible only if **every** union alternative is a
nominal Error subtype. Borrowed `&Error`/`&mut Error` payloads are rejected:
exceptional effects can outlive the originating lexical borrow and are
potentially routed to a different actor/supervisor.

Compiler gates intentionally remain separate:

1. **Lexer/parser** recognize only effect **statements** with a required
   operand and a statement terminator, rather than treating the keyword as
   a general callable.
2. **Nominal type checker** rejects unproven or forged Error operands with
   a specific diagnostic.
3. **Effect admission** rejects even valid Error operands until `throw`,
   `raise`, `panic`, `defer`, `catch`, `trap`, `recover`, and actor
   supervisor CONTROL have demonstrably correct runtime behavior.

Java PR #811 provides gates (1)-(3) but no runtime effect lowering. LLVM
must provide equivalent static rejection and only lift the final gate after
exact-source executable conformance tests. Never lower an arbitrary host
exception to `std/error.Error` merely because it has a matching name.

**Trace invariants:** `new Error()` allocates no stack. Once effects are
enabled, `throw new Error()` captures the guest stack at the throw site
exactly once; subsequent rethrows preserve the original trace. `raise`
and `panic` also carry Error payloads without letting a failed actor
intercept its own panic or reclassifying forced cancellation/host fatal
conditions as ordinary Error values.

## Future source effects (not yet admitted)

- `throw e`: typed recoverable `Throw<E>`; only typed matching catch or trap
  may consume the effect.
- `raise e`: explicit escalation, not caught by ordinary catch/trap.
- `panic e`: invariant failure, not caught by ordinary catch/trap.
- `recover`: capability-checked supervisor recovery; cannot recover a VM-fatal
  condition, forge a notice, or silently erase diagnostics.

## First executable milestone

A shared source fixture with `defer`, `throw` and a matching catch must
compile with both production frontends and run through GraalVM and native
LLVM/Clang. Compare return value, cleanup order, failure category and
suppressed diagnostics. Reject suspension/borrow escape until continuation
frame ownership and cancellation cleanup have executable proofs.

Do not mark parity completed for token reservation, AST parsing alone, policy
helpers, or native C++ tests. Actual guest code must be executed on both
production backends and checked against the same source Git blob.

Tracks LLVM issue #143 and Java/LLVM existing supervision draft work.
