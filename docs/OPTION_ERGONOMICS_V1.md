# Option ergonomics and independent question-mark positions

**Status:** Java/GraalVM compiler and interpreter implementation staged on a draft
branch; no executed JDK25/GraalVM, LLVM, JS, .NET or native-image parity evidence.

## Basic extraction and recovery

Existing operations: `unwrap()`, `expect(message)`, `unwrap_safe()`,
`unwrap_or(default)`, `is_some()`, `is_none()`, `map`, `and_then`,
`filter`, `ok_or_else`, `transpose`, and strictly typed nested extraction.

Additional source methods in this draft:

| Receiver | Method | Result | Invocation policy |
| --- | --- | --- | --- |
| `Option<T>` | `unwrap_or_else(f: () -> T)` | `T` | Invoke `f` exactly once for `None`; never for `Some` |
| `Option<T>` | `or_else(f: () -> Option<T>)` | `Option<T>` | Invoke `f` exactly once for `None` |
| `Option<T>` | `ok_or(error: E)` | `Result<T,E>` | Evaluate `error` eagerly, including on `Some` |
| `Result<T,E>` | `unwrap_or_else(f: (E) -> T)` | `T` | Invoke `f` exactly once for `Err` |

Callback methods are direct-call-only, synchronous, non-suspending and
non-mutating at this stage. No borrowed or affine payload should move through
new callback methods until branch-specific ownership and lexical-drop proofs
are complete. The current first slice therefore rejects non-Copy payloads and
(non-Copy) `Result` error types; `ok_or` also rejects non-Copy eager errors.

```ores
val Option<int> present = Some(3);
val n = present.unwrap_or_else(() -> 8080); // 3

val Option<int> missing = None;
val other = missing.or_else(() -> Some(8080)); // Some(8080)

val result = missing.ok_or("missing port"); // Err("missing port")
```

`unwrap()` and `expect()` still **panic** on absent/error variants.
They are not ordinary `throws` and a `trap` cannot absorb those panics.
When checked `panics` signatures ship, these existing panicking methods
will need corresponding effect typing or compiler-backed nonempty proofs.

## Three non-interchangeable question-mark positions

| Source | Meaning |
| --- | --- |
| `bool? a` | Type sugar for `Option<bool> a`; the argument remains required |
| `string b?` | Omission marker on formal parameter `b`, not an optional type |
| `bool? a?` | Optional-typed parameter and independently omittable formal |
| `value?` | Postfix early-return extraction, only inside an eligible callable |

For the fully admitted form of defaults, a non-optional omittable formal must
have a well-typed default; absence is never implicit `""`, `0` or other
fabricated values. An omittable `Option<T>` may synthesize `None` only as
an explicit future admission rule backed by compiler type/default proofs.
Illustrative future syntax:

```ores
pub fnc foo(bool? a, string b? = "default"): void {
  // a is Option<bool> and required at the call site.
  // b may be omitted, then gets "default".
}
```

Parameter-position `?` is separately staged in Java draft #1519, which
currently rejects unlowered parameter defaults and omission. Postfix `?`
propagation is a separate blocked Java draft #1020: it returns `None` or
`Err(e)` from the nearest callable and never raises, throws or panics.
Maintain ternary `condition ? yes : no` unchanged; parse based on lexical
position and grammar, not by global token replacement.

No source-level feature is advertised as executable across all backends until
the respective parser/typechecker/ownership/runtime and source-ABI conformance
fixtures execute against an exact Git head. Never mirror private compiler code
to an unapproved organization just to acquire Actions minutes.

Related: common-source issue #129; Java drafts #1519 and #1020; merged Java
#63 and #693; common-source checked-effects draft #128.
