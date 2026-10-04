# `trap` keyword and error-control contract

Status: **normative compiler contract**

`trap` is a reserved callable modifier that installs a guaranteed outermost ordinary-error boundary around a callable invocation.

The declared return type remains the successful body return type. At the call site, the result is always a two-slot tuple containing a possible success value and a possible ordinary error.

## Core guarantee

For:

```ores
trap fnc load(int id): User {
  return lookup(id);
}
```

the function body type-checks as returning `User`, while a call has the effective result shape:

```text
[
  Option<User>,
  Option<Exception>
]
```

Success:

```text
[Some(value), None]
```

Ordinary thrown failure:

```text
[None, Some(error)]
```

Exactly one side is populated by the runtime boundary.

For `void`, the successful value is the unit/empty-tuple value:

```text
[Some([]), None]
```

Oreslang still has one return value; this pair is one tuple value.

## Syntax

`trap` is globally reserved.

```ores
pub trap fnc load_user(int id): User {
  return lookup(id);
}

pure trap routine calculate(): int {
  return 42;
}
```

V1 restricts `trap` to synchronous top-level/module `fnc` and `routine` declarations. Actor entry points and async functions are rejected until their suspension/supervision semantics can preserve the same boundary exactly.

## Body type versus call type

Inside a trapped callable, ordinary `return` uses the declared success type:

```ores
trap fnc answer(): int {
  return 42; // int, not a tuple
}
```

The caller receives the wrapped tuple:

```ores
[const value, const err] = answer();
```

This distinction is required so implementation code does not manually construct or propagate the trap pair.

## `throw` versus `raise` / `panic`

The control channels are distinct:

- **throw**: ordinary trappable error; caught by the nearest `trap` boundary if not already handled by ordinary `try/catch`;
- **raise**: non-trappable control unwind; bypasses `trap` and travels to the nearest `rt recover` boundary;
- **panic**: non-trappable invariant/runtime unwind; bypasses `trap` and travels to the nearest `rt recover` boundary.

Therefore a trap boundary must never be implemented as a catch-all over every VM/runtime termination signal.

The current interpreter already has an internal panic signal used by operations such as failing `unwrap`; trap explicitly rethrows that signal rather than turning it into the error slot. First-class source `throw`, `raise`, `panic`, and `rt recover` lowering must preserve this same separation.

## `rt recover`

`recover` belongs to the compiler-owned runtime namespace:

```ores
rt recover(...)
```

It is not a shadowable ordinary function. Its final handler syntax is reserved but remains fail-closed on the current compiler head until first-class `raise`/`panic` control signals are lowered. No implementation may emulate recovery by accidentally catching ordinary exceptions or host failures.

## `try/catch`

Ordinary `try/catch` handles the ordinary throw channel. It does not consume `raise` or `panic`.

The intended nesting model is:

```text
try/catch  -> ordinary throw
trap       -> uncaught ordinary throw crossing a callable boundary
rt recover -> raise / panic
```

## Cleanup

`finally` and `rt defer` execute during normal and exceptional unwinding according to lexical LIFO cleanup rules.

A deferred callback is registered as a callable value at the `rt defer` statement, not re-evaluated later:

```ores
rt defer(|| -> {
  cleanup();
  return;
});
```

The single-callback sugar is equivalent:

```ores
rt defer || -> {
  cleanup();
  return;
};
```

If cleanup itself produces a stronger non-trappable control signal, the runtime must preserve that signal rather than silently converting it to the ordinary trapped error channel.

## Optimization and AOT/JIT

`trap` is part of callable semantics. Inlining, tree shaking, AOT compilation, JIT compilation, tail-call transforms, and interop lowering must preserve the trap boundary and its external tuple result.

Compiler passes that reconstruct a callable AST/IR node must retain the `trap` bit. Dropping it is a semantic and safety bug.
