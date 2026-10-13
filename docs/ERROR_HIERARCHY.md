# Portable Oreslang core errors — `std/error` v1

Status: source taxonomy and importable nominal codes are introduced on this branch. **Typed throw/catch/raise/panic integration, message and cause payloads, LLVM source execution, and cross-backend parity are NOT claimed.** This is a first gated foundation, not a finished exception subsystem.

## Package and representation

`stdlib/error.ores` is packaged as `std/error` by Maven's existing std resource copier. The closed source allowlist in `CoreLibrarySources` must contain `error.ores`. Guest code must explicitly import the exported classes:

```ores
import class Error from "std/error";
import class FileNotFoundError from "std/error";

pub fnc category(Error error): int {
  match error over
    on FileNotFoundError missing -> { return missing.code(); }
    on Error other -> { return 0; }
  end
}
```

The type `Error` is a nominal **abstract** Oreslang class, never Java `Throwable`. Its `code(): int` is a required abstract slot (code 0 is reserved for this root in the catalog, not produced by an instance). Eight families and `FileSystemError` are also abstract; every concrete leaf provides its own `code()` value. `FileSystemError` (3090) and `NetworkError` (4000) are descendants of `IOError`. The catalog has **64 concrete leaf classes and 10 abstract group/root classes**: **74 classes** total.

The machine-readable complete name/parent/code inventory is `contracts/v1/error-catalog.json`. Names and codes are permanently assigned. Deprecate old codes instead of changing their meaning; reserve new codes for new types. Never dispatch on code instead of guest nominal type metadata.

## Family layout

| Family | Code | Leaf range | Meaning |
| --- | ---: | ---: | --- |
| ArgumentError | 1000 | 1001–1008 | Inputs, arity, casts, bounds |
| StateError | 2000 | 2001–2008 | Lifecycle, mutation, stale handles |
| IOError | 3000 | 3001–3008 | Filesystem, EOF, broken pipes |
| NetworkError : IOError | 4000 | 4001–4008 | DNS, connections, TLS, protocols |
| TimingError | 5000 | 5001–5008 | Timeouts, backpressure, retries |
| DataError | 6000 | 6001–6008 | Parse, encode/decode, schemas |
| SecurityError | 7000 | 7001–7008 | Authentication, capabilities, integrity |
| OperationError | 8000 | 8001–8008 | Numeric, unsupported, version, dependency |

`FileSystemError` has code 3090 and parents the first six `IOError` leaves. `EndOfFileError` and `BrokenPipeError` are direct `IOError` children.

## Composition: one class parent and nominal interfaces

Multiple class inheritance is allowed in Oreslang, but `std/error` deliberately
uses **at most one concrete/abstract class parent** per type. This prevents
ambiguous inherited state, constructor paths and name-plus-arity method slots.
The abstract root has a **pure virtual** `code(): int` slot; other abstract
group classes implement their reserved codes, while the 64 leaf classes
override `code()` with stable, unique core codes.

`CodedError` is an exported interface with `fnc code() => int`.
`Error` implements it, so its subclasses inherit that nominal membership.
`RetryAdvisory extends CodedError` additionally requires
`fnc retry_hint() => bool`. Only **five** explicitly opted-in error types
(`ConnectionResetError`, `TimeoutError`, `WouldBlockError`,
`BackpressureError`, `RateLimitedError`) implement it.
This says that a retry may be considered, **not** that any operation is
idempotent, safe to repeat, authorized, or guaranteed to succeed.
In particular, no failure handling mechanism may automatically retry solely
because an error implements this interface.

Canonical usage:

```ores
import class Error from "std/error";
import class RateLimitedError from "std/error";
import interface RetryAdvisory from "std/error";

fnc choose(Error failure): int {
  match failure over
    on RateLimitedError rate -> { return rate.code(); }
    on RetryAdvisory candidate -> { return candidate.code(); }
    on Error other -> { return -1; }
  end
}
```

Traits and interfaces share `InterfaceDecl` semantics in the present Java
frontend; the `interface` spelling is used for exported cross-unit contracts
until the linker separately tracks trait-vs-interface identity. The runtime
uses declared nominal interface ancestry, not the presence of a similarly
named `retry_hint()` method. A structurally similar class that does not
implement `RetryAdvisory` must not match it.

The v1 manifest now explicitly records each class's `abstract` flag,
direct `interfaces`, and the versioned interface parent/method contracts.
Codes are guaranteed unique **only inside the versioned core catalog**:
third-party error subtypes require their own application-domain code registry.
Do not use codes for secure type identity or exception catch admission.

## Pattern-matching hardening

1. **Nominal identity only**: use guest type IDs and transitive Oreslang parent edges. Never classify by Java `instanceof`, Java exception name, error string, code alone, or structural field coincidence.
2. **Most specific first**: `FileNotFoundError` before `FileSystemError` before `IOError` before `Error`. Parent-first subtype arms must fail as dead/unreachable (under the current ordered implementation) or be rejected as overlapping (under a future disjoint-match form).
3. **Keep language forms distinct**: canonical `match x over` is ordered and requires exhaustive coverage; legacy non-`over` match has disjoint-proof semantics. Both are now documented in `docs/PATTERN_MATCHING.md`. Preserve that distinction when typed catches are implemented. Until then, regression coverage locks down existing runtime behavior and tests must not assert an unimplemented form as supported.
4. For a future `catch` statement, **catch only a `Throw<Error subtype>` effect**, match typed subclasses by guest nominal identity, preserve the original error on rethrow, and reject forged type tags. `raise`/`panic`/cancellation/termination do not downgrade to catchable `Error` objects merely because their diagnostic has a matching code.
5. `trap` converts only an uncaught *ordinary throw* to `None` (losing detail). Returning `Error` or `Result.Err` is ordinary data and remains unchanged. Never convert permission-denied control, host-fatal OOM, or forced actor termination into `None`.
6. An actor's unhandled ordinary throw/raise must ultimately reach supervisor PANIC if no handler/recover consumes it, carrying original type identity and bounded diagnostics. `panic` bypasses actor-local handlers and reaches the supervisor CONTROL path. Non-actor unhandled failures need a deterministic top-level exit contract with full cleanup first.
7. Borrowed match refinements alias the original ownership place; they must not clone move-only error payloads. Eventually permit causes and suppressed errors only with cycle bounds, deterministic ordering, bounded serialization, redaction of sensitive paths/tokens, and cross-actor ownership rules.
8. Subtype exhaustiveness is **open-world** unless sealed/closed domains prove otherwise. `match Error` needs a fallback for new third-party subclasses. Catch-all must never swallow panic/cancellation.

## Deliberate exclusions / follow-up gates

- **No source-level `throw`, `raise`, `panic`, or `recover` yet**: both compilers' existing fail-closed tests are the current boundary. A native C++ ExceptionPolicy test is not guest source lowering.
- **No per-instance message/cause/metadata fields yet**: this v1 file does not fabricate a usable structured constructor contract. Add immutable `message`, `cause`, optional context and sanitized metadata after inheritance constructor chaining, ownership, sendability and native layout are verified.
- **No implicit mapping of host exceptions** to these classes. Explicit, allowlisted, auditable backend adapters must map known guest-origin failures; unknown/host-fatal failures stay outside ordinary throws.
- **No claim of LLVM parity** until imported source classes, inheritance dispatch, match refinement and emitted guest type tags execute in native/wasm paths.
- **No auto-merge** before the exact-commit CI test matrix: Maven parser/typechecker; import/runtime match; source-order and unreachable arms; optimizer/Native Image; LLVM/Clang ASan/UBSan guest tests; negative syntax/effect policy tests; catalog drift/collision/security tests.

See `docs/EXCEPTION_EFFECT_ADMISSION.md`, `docs/TRAP.md`, `docs/PATTERN_MATCHING.md`.
