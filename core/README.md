# AttoLang core Option/Result (Java source candidate)

**Status:** independent `.atto` library source, **not** an installed or verified
standard library. No claim of successful compiler admission, guest execution,
native image build, or cross-backend parity is made merely by adding these files.

## Built-in foundation (not redefined here)

`Option<T>` is a sealed `Some(T) | None` sum; `Result<T,E>` is a
sealed `Ok(T) | Err(E)` sum. These tags are **not** pointers, nullable
sentinels, exceptions, or dynamically forged structural types.

Existing built-in method contracts must remain authoritative:
- `Option`: `is_some`, `is_none`, `unwrap`, `unwrap_or`,
  `unwrap_or_else`, `or_else`, `ok_or`; `map<U>`, `and_then<U>`,
  `flat_map<U>` require compiler support and a callback ownership/effects audit.
- `Result`: `is_ok`, `is_err`, `unwrap`, `unwrap_or`,
  `unwrap_or_else`, `or_else`; `map<U>`, `and_then<U>`,
  `flat_map<U>`, `map_err<F>` require compiler support.
- `?` performs typed early return of `None` or `Err` only when the
  enclosing callable has a compatible carrier. No implicit
  `Option -> Result` conversion and no catchable throw on `Err`.
- `trap` is an independent effect boundary, not an alias for `Err`/`None`.

## Additional source functions

| `core/option.atto` | Meaning |
| --- | --- |
| `flatten<T>` | `Option<Option<T>> -> Option<T>` |
| `to_result<T,E>` | `Option<T> + eager E -> Result<T,E>` |
| `from_result<T,E>` | `Result<T,E> -> Option<T>`; drops error |
| `transpose<T,E>` | `Option<Result<T,E>> -> Result<Option<T>,E>` |

| `core/result.atto` | Meaning |
| --- | --- |
| `flatten<T,E>` | `Result<Result<T,E>,E> -> Result<T,E>` |
| `to_option<T,E>` | Keep Ok; drop Err |
| `err_option<T,E>` | Keep Err; drop Ok |
| `transpose<T,E>` | `Result<Option<T>,E> -> Option<Result<T,E>>` |
| `merge<T>` | `Result<T,T> -> T` |

All functions are synchronous and require no scheduler, heap, OS, global
state, callback, or interop. They **consume** sum operands. The compiler
must support generic ADT pattern extraction and active-variant destruction
before these source bodies can be enabled for affine values. No silent
fallback to host `Optional`/`Either`.

## Required implementation & verification gates

1. Parse and typecheck both files; validate generic `T/E` inference,
   exhaustive patterns, result-arm unification, and nested match syntax.
2. Link/import the source modules and run the guest fixtures in
   `tests/core_option_result/`. Current directory placement is an
   intentional target; package resolution/install integration is pending.
3. Check `Some(None)`, `Ok(None)`, `Err`, `None` and nested
   `transpose` success/failure without confusing tags.
4. Prove ownership: success and failure consume exactly one owner; inactive
   variant members are never read; payload is dropped exactly once;
   moving a value prevents reuse; borrowed values cannot outlive their owner.
5. Prove `map`/`and_then`/`map_err` callback skipped on wrong tags,
   invoked exactly once on matching tags; effect and panic propagation
   unchanged; `?` early returns after exact cleanup.
6. Verify actual guest tests (Java/JDK25, GraalVM Native Image AOT), with
   separate backend-local tests in Rust, LLVM and other Atto compilers.
   Zero-step CI is NOT_EXECUTED, not a pass.

This source is **not** the canonical source to be copied into other
`atto-source.*` repositories. Each backend can implement these functions
independently; only public behavior and conformance cases should match.
