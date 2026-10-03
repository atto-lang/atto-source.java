# Reserved `trap` keyword and error-control contract

Status: **draft design / compiler contract**

This document specifies the proposed reserved `trap` modifier for Oreslang callables and the control-flow distinction between `throw`, `raise`, and `panic`.

The central guarantee is:

> A successfully compiled `trap` callable never lets an ordinary `throw` cross its call boundary. The compiler converts the uncaught throw into an explicit two-slot result. `raise` and `panic` are deliberately different control effects: they bypass `trap` and unwind to the nearest `recover` boundary.

This is a language and ABI contract, not merely parser sugar around a handwritten `try/catch`.

## Goals

- reserve `trap` as a first-class callable modifier;
- guarantee that uncaught `throw` cannot escape a `trap` invocation;
- make success and trapped failure explicit in the call-expression type;
- require callers to consume the trap result rather than silently discard errors;
- preserve a deliberate escape mechanism for exceptional control flow through `raise` and `panic`;
- make `throw`, `raise`, `panic`, `trap`, and `recover` distinct compiler effects so optimization, inlining, callbacks, interfaces, foreign code, actors, and AOT/JIT lowering cannot erase the boundary;
- integrate with Oreslang's existing one-return-value rule: tuples/arrays/records remain ordinary single values.

## Syntax

`trap` is globally reserved.

Canonical declarations:

```ores
pub trap fnc load_user(UserId id): User {
  return db_load(id);
}

pub trap routine initialize(): void {
  perform_initialization();
  return;
}
```

For instance methods, which intentionally omit `fnc`/`routine`:

```ores
define class Loader as

  pub trap load(UserId id): User {
    return self.lookup(id);
  }

end
```

Canonical modifier ordering:

```text
[visibility] [pure] trap fnc ...
[visibility] [pure] trap routine ...
[visibility] [pure] trap method(...)
```

The parser may accept equivalent modifier ordering during migration, but formatting and diagnostics should normalize to the canonical order.

## Declared return type versus call-expression type

The declared return type is the **success value**.

Example:

```ores
pub trap fnc load_user(UserId id): User {
  ...
}
```

The body type-checks as though successful `return` statements return `User`.

The call expression, however, has the trap ABI shape:

```text
[
  Option<[User]>,
  Option<TrapError>
]
```

Oreslang has one return value. The `[User]` above is the normalized success tuple used by the trap ABI, not language-level multiple return values.

For `void`:

```text
[
  Option<[]>,
  Option<TrapError>
]
```

A successful void trap returns `Some([])` in the success slot.

## Trap-result invariant

Exactly one side is populated.

Success:

```text
[Some([value]), None]
```

Failure:

```text
[None, Some(error)]
```

For `void` success:

```text
[Some([]), None]
```

These states are invalid and must never be constructible by compiler lowering:

```text
[None, None]
[Some(...), Some(...)]
```

The implementation may use a more compact internal tagged representation, but the observable type/semantics must preserve this invariant.

## Caller destructuring

The ordinary form keeps the success bundle intact:

```ores
const [results, err] = load_user(id);
```

with:

```text
results : Option<[User]>
err     : Option<TrapError>
```

Oreslang also supports Option-aware nested trap destructuring for the common single-success-value case:

```ores
[const [user], const err] = load_user(id);
```

with:

```text
user : Option<User>
err  : Option<TrapError>
```

On success:

```text
user = Some(User)
err  = None
```

On trapped failure:

```text
user = None
err  = Some(TrapError)
```

This syntax does **not** mean the function has multiple return values. It is compiler-supported destructuring/lifting of the optional normalized success tuple.

If the declared return type itself is a tuple, that tuple remains one success value. The trap layer does not flatten the user's returned tuple.

## `throw`: the trappable error channel

`throw` is the ordinary exception/error control effect.

Inside a `trap` invocation, any `throw` that is not handled by a nearer explicit `try/catch` is absorbed by the nearest dynamic trap boundary.

Example:

```ores
pub trap fnc foo(): String {
  throw new Error("bad");
}
```

Conceptual lowering:

```ores
fnc foo$trap_lowered(): [Option<[String]>, Option<TrapError>] {
  try {
    throw new Error("bad");
  } catch (Error err) {
    return [None, Some(TrapError.from(err))];
  }
}
```

A successful return:

```ores
return "ok";
```

conceptually becomes:

```text
[Some(["ok"]), None]
```

A `throw` in a nested non-trap helper also belongs to the dynamic trap boundary:

```ores
fnc helper(): User {
  throw new Error("missing");
}

pub trap fnc load(): User {
  return helper();
}
```

The caller of `load()` receives the error side. The exception does not escape `load`.

If no dynamic `trap` boundary exists, `throw` retains normal Oreslang `try/catch` / unhandled-exception behavior.

## `raise`: deliberate trap escape

`raise` is a distinct reserved control-flow keyword/effect.

A `raise`:

- is **not** converted into `TrapError`;
- is **not** absorbed by `trap`;
- unwinds across nested `trap` callables;
- continues until the nearest matching `recover` boundary;
- runs required `defer` / `finally` cleanup while unwinding;
- remains distinguishable from `panic`.

Example:

```ores
pub trap fnc parse_config(): Config {
  if unrecoverable_configuration_state {
    raise new ConfigAbort("cannot continue");
  }

  if malformed_user_value {
    throw new ParseError("bad value");
  }

  return config;
}
```

The malformed-value `throw` becomes:

```text
[None, Some(TrapError(...))]
```

The `raise` crosses the trap boundary and keeps unwinding toward `recover`.

This gives library/application code an explicit way to say:

> This failure must not be normalized into the local trap result.

## `panic`: invariant/runtime escape

`panic` is also a distinct reserved control-flow keyword/effect.

Like `raise`, `panic` bypasses `trap` and unwinds to the nearest `recover` boundary.

It exists for invariant violations, impossible states, compiler/runtime assertions exposed to guest semantics, and failures that should not be mistaken for an ordinary trappable application error.

Example:

```ores
pub trap fnc decode(Packet p): Message {
  if compiler_proven_impossible_state_became_reachable {
    panic new InvariantError("decoder invariant broken");
  }

  ...
}
```

A panic must never silently become the ordinary `err` slot of the trap result.

`raise` and `panic` may share an internal unwinding mechanism, but their kinds must remain distinct in diagnostics, metadata, tracing, and `recover` handling.

## `recover` boundary

`recover` is the dynamic boundary that can intercept `raise` and `panic`.

The trap design requires the compiler IR to represent a recover boundary explicitly so that optimizer/inlining transformations cannot accidentally turn a non-trappable unwind into a trapped `throw`.

The exact final surface syntax for typed/selective recovery may evolve independently, but these semantics are fixed:

1. `throw` does not skip a trap boundary.
2. `raise` skips every trap boundary until recover.
3. `panic` skips every trap boundary until recover.
4. `defer` / `finally` execute according to normal unwinding rules.
5. If no recover boundary exists, an unhandled `raise` or `panic` terminates the current top-level execution domain according to runtime policy (process, actor, isolate, or untrusted actor), rather than being fabricated into a trap result.

A future/selective `recover` syntax may choose to recover only `raise`, only `panic`, or specific payload types. The semantic distinction must already exist in compiler metadata.

## Interaction with explicit `try/catch`

Ordinary `try/catch` handles the `throw` channel.

It does not implicitly downgrade `raise` or `panic` into `throw`.

Therefore:

```text
try/catch  -> throw
trap       -> uncaught throw at callable boundary
recover    -> raise / panic
```

This separation prevents a broad local catch from accidentally swallowing a deliberate trap escape.

A future explicit syntax may allow a recover block to classify or rethrow a raised/panicked signal.

## Error type

The trap error should be structured, not reduced to a string:

```ores
struct TrapError {
  kind: TrapErrorKind,
  message: String,
  cause: Option<TrapError>,
  stack: Option<StackTrace>,
  source: Option<SourceLocation>
}
```

Initial trappable categories may include:

```text
Thrown
Assertion
Arithmetic
Bounds
ForeignException
Io
Runtime
Cancelled
```

`Raised` and `Panic` are intentionally **not** ordinary `TrapErrorKind` values because they do not travel through the trap error slot. Their payloads belong to the recover/unwind channel.

## Returned Error objects remain data

Only control flow determines whether a value is trapped.

Example:

```ores
pub trap fnc inspect(): Error {
  return new Error("this is data");
}
```

Result:

```text
[Some([Error("this is data")]), None]
```

Returning an `Error` value is not equivalent to `throw`.

## Trap results may not be silently discarded

A trap call used as a bare expression is a compile error:

```ores
load_user(id); // ERROR: trap result discarded
```

The caller must bind/consume the result:

```ores
const [results, err] = load_user(id);
```

or:

```ores
[const [user], const err] = load_user(id);
```

A future explicit discard operator may be introduced, but silent discard is forbidden.

## Callable types and ABI

`trap` is part of the callable contract.

These are not the same callable type:

```text
fnc(): User
trap fnc(): User
```

The latter has an explicit trap-result call ABI and a no-`throw`-escape guarantee.

The compiler must preserve this distinction through:

- function values;
- callbacks;
- generics;
- closures;
- interface slots;
- trait slots;
- virtual dispatch;
- imported/compiled package metadata;
- Java/JNI/native interop;
- actor messages/callbacks;
- JIT and AOT lowering.

Unknown metadata fails closed when an exact trap guarantee is required.

## Overrides and interfaces

A trap contract cannot be erased by implementation.

If an interface/base slot requires a trap callable, an override/implementation must preserve the trap guarantee.

An implementation may eventually be allowed to strengthen a non-trap slot into a trap slot only if callable variance/ABI rules make the conversion explicit and safe. V1 should require exact trap-effect compatibility for overrides and interface implementation.

## `pure trap`

`pure` and `trap` are orthogonal and may be combined:

```ores
pub pure trap fnc divide(int a, int b): int {
  return a / b;
}
```

A deterministic arithmetic failure may enter the trappable `throw` channel without making the computation impure.

Likewise, `raise` and `panic` are control effects rather than automatically external side effects. A callable may remain pure if constructing and propagating the signal performs no forbidden effect.

Therefore `pure` does not imply `nothrow`, `noraises`, or `nopanic`.

The internal effect system must track these properties independently.

## Internal effect model

The compiler effect model must distinguish at least:

| Effect | Meaning | Absorbed by `trap` |
| --- | --- | --- |
| `throw` | ordinary guest exception/error | yes, if uncaught before boundary |
| `raise` | deliberate non-trappable exceptional escape | no |
| `panic` | invariant/runtime non-trappable escape | no |
| `diverge` | possible nontermination | no |
| other effects | I/O, mutation, actor, scheduler, FFI, etc. | unrelated |

A single undifferentiated "exception" bit is insufficient.

Transitive effect inference must preserve whether a call may throw, raise, or panic.

Example:

```ores
fnc inner(): User {
  raise new Abort("stop");
}

pub trap fnc outer(): User {
  return inner();
}
```

`outer` has no escaping `throw` after trap lowering, but it **does** retain a transitive `raise` effect.

## Compiler IR requirements

Do not immediately desugar `trap` into arbitrary source-level `try/catch` and then lose its identity.

Recommended IR nodes/effects:

```text
TrapBoundary
Throw
Raise
Panic
RecoverBoundary
```

The trap boundary must survive long enough for:

- effect checking;
- control-flow analysis;
- cleanup-edge construction;
- inlining;
- exception-table generation;
- actor/isolate boundary lowering;
- JVM/Graal lowering;
- native/AOT lowering.

After semantics are fixed, a backend may implement the trap boundary with exception tables, tagged returns, CPS, setjmp-like native machinery, or another representation, provided observable behavior is identical.

## Optimizer rules

Inlining may not erase or move a trap/recover boundary in a way that changes which signal catches which failure.

In particular:

- a `throw` from an inlined callee must still be absorbed by the same nearest trap boundary;
- a `raise` from an inlined callee must still bypass trap;
- a `panic` from an inlined callee must still bypass trap;
- cleanup ordering must remain unchanged;
- dead-code elimination may not assume `trap` means the call cannot `raise`, `panic`, or diverge;
- pure-call optimizations must continue to respect throw/raise/panic/divergence ordering.

## Java/JNI/native/host interop

Foreign failures require explicit classification.

Default fail-closed behavior:

- an ordinary mapped foreign exception may enter the `throw` channel and therefore be trapped;
- a foreign fatal/invariant condition must not automatically be mislabeled as a trappable throw;
- unknown host failures are not silently swallowed;
- process-kill, VM corruption, hardware failure, or OS termination cannot be promised recoverable merely because source contains `trap`.

Compiler/runtime-owned adapters must classify foreign failures into the correct Oreslang control effect.

Raw native crashes must never be described as ordinary `TrapError` unless the runtime actually isolated and converted them safely.

## Actors, isolates, and untrusted actors

The same semantic channels apply inside actor execution.

A `throw` leaving a trap actor helper/entrypoint becomes its trap error result.

A `raise` or `panic` bypasses trap and unwinds to the nearest recover boundary in that execution domain.

If unrecovered:

- a normal/shared actor follows actor-supervision failure policy;
- an isolated actor terminates its isolated execution domain safely;
- an untrusted actor is terminated and its confined memory/capabilities are reclaimed according to sandbox policy.

The runtime must not forge a successful trap result after an unrecovered raise/panic merely to keep the caller alive. Actor supervision may separately report actor termination as a transport/supervision outcome.

## Diagnostics

Proposed stable diagnostic families:

- `E-TRAP-001`: invalid `trap` modifier placement;
- `E-TRAP-002`: trap result discarded;
- `E-TRAP-003`: invalid trap-result destructuring;
- `E-TRAP-004`: trap callable override/interface mismatch;
- `E-TRAP-005`: incompatible trap callable value/callback assignment;
- `E-TRAP-006`: compiler cannot classify foreign failure semantics;
- `E-TRAP-007`: invalid trap result state exposed by compiler/runtime lowering;
- `E-TRAP-008`: illegal attempt to treat `raise`/`panic` as ordinary trappable throw;
- `E-TRAP-009`: missing/invalid recover metadata for a construct that requires it.

Diagnostics for propagated effects should identify the shortest useful path.

Example:

```text
error[E-TRAP-008]: 'raise' bypasses trap and cannot be converted to TrapError

  load_user
  └── calls validate_session
      └── calls abort_session
          └── effect: raise SessionAbort
```

## Required compiler work

1. reserve `trap`, `raise`, `panic`, and the recover boundary keyword/symbol;
2. parse `trap` on `fnc`, `routine`, and instance methods;
3. record trap contract in callable AST/symbol/type metadata;
4. split compiler exception effects into `throw`, `raise`, and `panic`;
5. represent `TrapBoundary` and `RecoverBoundary` explicitly in IR;
6. type a trap call as the normalized two-slot Option result;
7. implement Option-aware nested trap destructuring;
8. reject silently discarded trap results;
9. lower ordinary successful returns into the success slot;
10. lower uncaught `throw` at the trap boundary into `TrapError`;
11. ensure `raise` and `panic` bypass trap;
12. preserve `defer` / `finally` cleanup on every unwind path;
13. enforce callable/override/interface/callback trap compatibility;
14. serialize trap and throw/raise/panic effect metadata across compiled units;
15. classify Java/JNI/native failure mappings explicitly;
16. harden optimizer/inliner/AOT/JIT passes against boundary erasure;
17. integrate unrecovered raise/panic with actor/isolate/untrusted-actor termination/supervision policy.

## Required tests

### Compile-pass / runtime-pass

- `trap fnc` success;
- `trap routine` success;
- trap instance method success;
- void success produces `Some([])`;
- one value produces `Some([value])`;
- ordinary `throw` becomes `TrapError`;
- throw from nested non-trap helper is trapped;
- inner explicit `try/catch` may handle throw before trap;
- returned `Error` object remains ordinary success data;
- `[const results, const err]` destructuring;
- `[const [result], const err]` Option-aware destructuring;
- `pure trap fnc`;
- trap callable through function value with compatible metadata;
- interface/override preserving trap contract;
- deterministic mapped foreign exception becomes trapped throw;
- defer/finally executes before trapped throw result is produced.

### Raise/panic escape

- `raise` directly inside trap bypasses trap;
- `panic` directly inside trap bypasses trap;
- raise from nested helper bypasses outer trap;
- panic from nested helper bypasses outer trap;
- raise/panic cross several nested trap callables;
- nearest recover boundary receives the correct signal;
- raise remains distinguishable from panic;
- defer/finally runs while raise/panic unwinds;
- optimizer/inlining does not change nearest trap/recover boundary;
- JIT and AOT behavior match;
- actor/isolate execution does not rewrite unrecovered raise/panic into the trap error slot.

### Compile-fail

- use `trap` as an identifier;
- invalid modifier target;
- discard trap result;
- destructure trap result with invalid arity/shape;
- assign trap callable to incompatible non-trap callable type without explicit supported adaptation;
- implement trap interface slot with incompatible non-trap callable;
- attempt compiler lowering that routes raise/panic into `TrapError`;
- foreign call whose failure classification is required but unknown.

### Adversarial

- throw hidden behind recursive `fnc` SCC;
- raise hidden behind recursive `fnc` SCC;
- panic hidden behind callback;
- throw/raise/panic through generic callable parameters;
- dynamic dispatch with mixed control effects;
- imported compiled unit with stale/missing effect metadata;
- exception from Java callback invoked under trap;
- nested trap inside recover;
- recover inside trap;
- trap inside trap;
- finally block that itself throws;
- finally block that raises;
- finally block that panics;
- untrusted actor raise/panic during HTTP response ownership;
- AOT exception-table optimization preserves exact boundary semantics.

## Implementation staging

A safe implementation sequence is:

1. documentation + reserved-word/parser tests;
2. AST/callable metadata;
3. effect split (`throw` / `raise` / `panic`);
4. explicit trap/recover IR boundaries;
5. trap call-result typing;
6. destructuring/lifting rules;
7. throw-to-result lowering;
8. raise/panic unwind lowering;
9. cleanup-edge hardening;
10. callable/interface/callback compatibility;
11. foreign failure classification;
12. actor/isolate integration;
13. optimizer/JIT/AOT hardening;
14. full adversarial matrix.

Do not enable the surface keyword while any backend can still route `raise` or `panic` through the ordinary trap error slot.

## Non-goals

This contract does not promise that `trap` can recover from:

- OS `SIGKILL`;
- process termination;
- hardware faults;
- corrupted native memory;
- VM failure;
- host failures that prevent the runtime from executing the trap boundary.

The source-level guarantee is specifically about Oreslang's classified `throw` channel.

`raise` and `panic` deliberately escape that guarantee.

## Core invariant

The implementation must preserve this distinction everywhere:

```text
throw  -> nearest explicit catch, otherwise nearest trap -> TrapError result
raise  -> bypass catch/trap -> nearest recover
panic  -> bypass catch/trap -> nearest recover
```

No parser rewrite, optimizer pass, foreign adapter, actor boundary, JIT optimization, or native backend may collapse these three channels into one.
