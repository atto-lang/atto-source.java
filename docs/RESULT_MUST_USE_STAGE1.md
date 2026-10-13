# Result<void,E> must-use admission (Java stage 1)

**Status:** Java source-level conservative checker, not generic Result<T,E>
ownership/escape/liveness parity and not LLVM guest Result execution.

## Rules currently enforced

- Every local binding statically typed `Result<void,E>`, including an
  inferred `Ok()`, is an obligation until explicitly handled.
- A bare Result-valued statement is a compile-time error.
- A direct `return result` discharges the obligation through propagation,
  provided the declared return type preserves `Result<void,E>`.
- A statically exhaustive `match result over` discharges it; the ordinary
  type checker must separately prove arm types and exhaustive coverage.
- Calling `result.unwrap()`, `result.expect(...)` or
  `result.unwrap_or(...)` as a checked extraction discharges it. The
  underlying type checker still validates argument types/arity.
- Reading, printing, copying a name, invoking `is_ok()` or `is_err()`,
  or merely mentioning the local **does not** discharge it.
- If/else and ordered match paths are joined conservatively. An early
  return with a pending Result fails; final fallthrough with a pending
  Result fails.
- Function and concrete class-method parameters of type `Result<void,E>`
  must also be discharged. A wildcard-only or Ok-plus-wildcard match
  is **not** a discharge proof: an unguarded explicit Ok arm and Err
  arm are required, as well as the normal exhaustive-match proof.
- Fresh `Result<void,E>` temporaries may not be passed into untyped
  call sinks; references to named Results may be inspected without
  losing the originating pending obligation.
- Nested Result obligations inside container/Option/tuple/record/union
  types are rejected until recursive tracking exists. Class/actor
  fields and module bindings storing Result obligations are likewise
  rejected until storage lifetime obligations are modeled.
- A new binding or match pattern shadowing a pending Result is rejected
  rather than allowing false handling of the unrelated inner value.
- Direct binding/return erasure from `Result<void,E>` to an unrelated
  destination type fails even if a dynamic type would otherwise accept it.
- Rebinding `Result<void,E>` storage is currently rejected, even after
  extraction: every write would create a new obligation and needs an SSA/CFG
  per-version proof before it can safely be allowed.

## Deliberate limitations

This is *not* yet a whole-program affine must-use proof:
- Local and direct parameter `Result<void,E>` obligations only.
  Non-void `Result<T,E>` must-use, globals, class/actor fields and
  nested generic payloads are not yet supported and require their
  own tracking (untracked void-Result storage now rejects).
- Indirect propagation through aliases, function arguments, destructuring,
  callbacks and complicated expression trees has not been certified.
- Unsupported control-flow shapes fail closed with an explicit diagnostic
  when a pending Result would cross them. This may reject safe programs,
  until branch-aware lowering and precise CFG dataflow are ready.
- This checker does not make invalid native ABI codes recoverable, validate
  actor serializers, or implement `Array<int>.try_add` in guest code.

## Merge gate

Run new `ResultMustUseProofTest`, existing `ResultVoidSemanticsTest`
and `OptionResultSemanticsTest`, complete JVM/Graal/AOT suites, and
the same-source LLVM matrix before treating typed Result as a public API.

Do not loosen the proof to a textual variable-reference count: probing and
logging are not handling. Do not silently widen a must-use Result to
`Any` or `Unknown` to avoid checking its status.
