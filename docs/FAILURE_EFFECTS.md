# Failure effects

Functions and methods may list any combination of `throws`, `raises`, and
`panics` after their return type. Markers are optional, order independent, and
cannot repeat. Omission does not promise that the callable cannot fail.

```ores
fnc read() -> string throws raises {
  throw "not found";
}

define class Reader as
  pub read() -> string throws { throw "not found"; }
end
```

| Operation | Local catch | Trap | Deferred recover | Actor boundary |
| --- | --- | --- | --- | --- |
| `throw value;` | handles | converts to None | does not consume | unhandled failure retires actor |
| `raise value;` | bypasses | bypasses | consumes pending raise | unhandled failure retires actor |
| `panic value;` | bypasses | bypasses | does not consume | goes to runtime supervision |

Cleanup still runs in LIFO order when a failure bypasses a handler. `raise`
unwinds until a deferred recovery consumes it; a defer that does not recover
allows unwinding to continue. Catch/finally blocks do not turn it into an
ordinary throw. Actor panic cannot be converted into a local successful result.

```ores
fnc work() -> void raises {
  defer || -> {
    val recovered = recover();
    print(recovered.is_some());
  };
  raise "failed";
}
```

Failure payloads currently accept immutable scalar strings, integers, floats and
booleans. Object payloads are rejected so unwinding cannot expose actor-owned mutable
objects across an isolation boundary.

The initial Java/Graal recovery form is `recover()` directly inside a deferred
inline lambda. It returns an Option containing the pending raise payload, or
None when no raise is available. The payload has the static union type
`string | int | float | bool`; callers must narrow it before using a specific
scalar type. A raise can be consumed once. Recovery state is
scoped to that cleanup invocation and cannot be retained for later use. Recovery
ends unwinding of that block; execution does not resume at the raising statement.
Deferred recovery in a non-void callable is rejected until explicit recovery
results are modeled. It cannot synthesize a value for a required return.
A raise carrying suppressed panic, host failure, cancellation, permission denial,
timeout or process exit cannot be recovered. This rule applies in synchronous
and resumable cleanup, and cyclic or oversized failure graphs fail closed. Named deferred handlers, typed exception lists,
interface effect subtyping and payload-type inference remain follow-up work.

## Caller opt-out

The `unchecked` keyword applies to one function or method call:

```ores
fnc read() -> string throws { return "cached"; }

fnc use() -> void {
  val value = unchecked read();
}
```

`unchecked` tells the analyzer to ignore signature-only effect assumptions at
that call. It preserves known effects inferred from executable bodies and
unresolved-call notices. A normal call elsewhere still propagates its signature.
The callee's own signature continues to be checked. Markers remain advisory;
`unchecked` does not enable code that would otherwise fail type, argument,
ownership, capability or isolation checks.

Use `await unchecked fetch()` for an async call and `trap unchecked read()` to
combine the opt-out with ordinary throw handling. Arguments and receiver
expressions retain their own analysis; `unchecked outer(inner())` does not opt
out the call to `inner`. It does not catch, recover, or reroute runtime failures.
A panic in an unchecked actor call still goes to supervision.

The keyword must prefix a call, cannot repeat on the same call, and cannot
prefix compiler failure operations such as `recover()`.

## Advisory analysis

`oreslang-core --analyze file.ores` validates source without executing main and
reports inferred failure markers and unresolved calls. Normal compiler diagnostics
also suggest missing markers and warn when a partially marked signature omits a
known effect. There are no Java-style mandatory checked exceptions.

Analysis reaches a fixed point through recursive direct calls, uses name and
arity within each module, and keeps method symbols separate from free functions.
Declaration-level and expression-level trap, and catch, remove only ordinary
throws. Signature assumptions and inferred body effects are tracked separately
through recursive calls so caller opt-outs cannot erase known body effects. Closure bodies are conservatively
included; deferred recovery is not treated as proof that a raise cannot escape.
Unknown, imported, callback, spread and dynamic dispatch remain explicitly unknown.
This initial analysis works per source file; it does not certify whole-program
failure freedom, precise closure invocation, or actor mailbox failure delivery.

The formatter's `--analyze` mode delegates to this compiler analysis through
`ORESLANG_BIN`; it does not rewrite signatures. `oreslang test --analyze` runs the
same analysis and may be combined with `--race` or `--fuzz` as a preflight check.

## Implementation coverage

The executable failure statements, signature metadata and recovery described here
are implemented in the Java/Graal reference compiler. Its synchronous and resumable
source paths share the failure policy. The LLVM, Forge, .NET and Wasm backends must
retain their unsupported-feature admission checks until corresponding lowering
and conformance tests exist. Do not infer backend parity from formatter support.
