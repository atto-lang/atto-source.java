# Portable Oreslang core errors — `std/error` v1

Status: source taxonomy, concrete Error root, guest message/trace fields and Java wire encoders are introduced in the draft branch. **Typed throw/catch/raise/panic integration, cause/suppressed graphs, async trace ancestry, LLVM source execution and cross-backend parity are NOT claimed.** These are gated language features, not a finished exception subsystem.

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

The type `Error` is a nominal **concrete** Oreslang class, never Java `Throwable`. Its `code(): int` returns 0 for a directly constructed `new Error()`; no trace is captured at construction. Nine families and `FileSystemError` are also abstract; every concrete leaf provides its own `code()` value. `FileSystemError` (3090) and `NetworkError` (4000) are descendants of `IOError`. The catalog has **88 concrete leaf classes, 1 concrete root and 10 abstract grouping classes**: **99 classes** total.

The machine-readable complete name/parent/code inventory is `contracts/v1/error-catalog.json`. Names and codes are permanently assigned. Deprecate old codes instead of changing their meaning; reserve new codes for new types. Never dispatch on code instead of guest nominal type metadata.

## Family layout

| Family | Code | Leaf range | Meaning |
| --- | ---: | ---: | --- |
| ArgumentError | 1000 | 1001–1008 | Inputs, arity, casts, bounds |
| StateError | 2000 | 2001–2013 | Lifecycle, iteration, mailboxes, detected deadlocks |
| IOError | 3000 | 3001–3008 | Filesystem, EOF, broken pipes |
| NetworkError : IOError | 4000 | 4001–4008 | DNS, connections, TLS, protocols |
| TimingError | 5000 | 5001–5008 | Timeouts, backpressure, retries |
| DataError | 6000 | 6001–6011 | Parse, map keys, formatting, schemas |
| SecurityError | 7000 | 7001–7008 | Authentication, capabilities, integrity |
| OperationError | 8000 | 8001–8010 | Numeric, unsupported, bounded resource admission |
| ActorError | 9000 | 9001–9014 | Actor API admission, routing, request/reply and lifecycle lookup (never CONTROL panic) |

`FileSystemError` has code 3090 and parents the first six `IOError` leaves. `EndOfFileError` and `BrokenPipeError` are direct `IOError` children.

## Composition: one class parent and nominal interfaces

Multiple class inheritance is allowed in Oreslang, but `std/error` deliberately
uses **at most one concrete/abstract class parent** per type. This prevents
ambiguous inherited state, constructor paths and name-plus-arity method slots.
The concrete root supplies `code(): int` and inherited serializers; other abstract
group classes implement their reserved codes, while the 88 leaf classes
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

## Cross-language extension: ten concrete errors

The preceding cross-language milestone had 74 concrete leaf classes and 10 abstract classes (84 classes total); the actor-focused milestone below extends that to 88 concrete leaves and 11 abstract classes (99 total).
The 74 original pre-cross-language classes retain their names, parents, codes and abstract flags. The subsequent actor expansion additively introduces the ActorApiError interface on two existing StateError types, without changing their class parents or numeric codes.

| Name | Parent | Code | Intended meaning |
| --- | --- | ---: | --- |
| KeyNotFoundError | DataError | 6009 | Required lookup of absent map key; optional lookup stays Option.None |
| DuplicateKeyError | DataError | 6010 | Uniqueness-enforcing insert rejects existing key; ordinary overwrite succeeds |
| InvalidFormatError | DataError | 6011 | Invalid formatting specifier/template, distinct from ParseError |
| EmptyCollectionError | StateError | 2009 | Remove from empty collection, distinct from index bounds |
| IteratorExhaustedError | StateError | 2010 | Advance exhausted iterator; normal exhaustion stays Option.None |
| DeadlockDetectedError | StateError | 2011 | Proven recoverable deadlock detection; not a timeout or cancellation |
| ProcessNotFoundError | StateError | 2012 | Explicit process/actor address lookup fails; not actor supervisor panic |
| MailboxFullError | StateError | 2013 | Nonblocking actor send admission reports bounded capacity full |
| NotImplementedError | OperationError | 8009 | Explicit placeholder operation, not missing abstract overrides or backend IR |
| ResourceExhaustedError | OperationError | 8010 | Recoverable quota/admission failure, never fatal OOM or sandbox termination |

Semantics and exclusions:

- KeyNotFoundError is for required lookup, while a normal optional lookup returns Option.None; DuplicateKeyError does not describe an ordinary overwriting map update.
- IteratorExhaustedError is not the ordinary end of an iterator. EmptyCollectionError denotes empty-container removal. Neither should make normal iteration exceptional.
- InvalidFormatError concerns a formatting specification; ParseError concerns malformed external data. No new parser effect is admitted implicitly.
- DeadlockDetectedError requires concrete detection, not a timeout guess. ProcessNotFoundError applies to an explicit lookup and not an actor supervisor notice.
- MailboxFullError is an explicit nonblocking-send admission outcome and can be carried in Result.Err; the presence of a type alone does not make mailbox send throw.
- NotImplementedError must never replace compiler failures for missing abstract methods or unsupported backend lowering.
- ResourceExhaustedError is for recoverable guest quota/admission outcomes only; host-fatal OOM, sandbox kills, forced termination and cancellation do not become ordinary errors.
- None of these new classes implements RetryAdvisory. PatternMatchFail/badmatch remain compile-time exhaustiveness failures under the Oreslang matching contract wherever proof is required.

References: .NET KeyNotFoundException, FormatException and NotImplementedException; Java/Kotlin NoSuchElementException; Haskell Control.Exception; Erlang maps and process errors.


## Actor-first error model and supervisor CONTROL separation

Oreslang has **one general-purpose concurrency identity: the actor**.
Therefore actor transport/admission and request/reply diagnostics are
first-class stdlib errors, not borrowed Java exceptions.

The nominal `abstract class ActorError extends Error implements ActorApiError`
is reserved code **9000**. Its 14 concrete leaves are:

| Guest type | Code | Source of design | Precise admission contract |
| --- | ---: | --- | --- |
| `ActorSpawnRejectedError` | 9001 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Spawn request rejected before actor identity is admitted |
| `ActorInitializationError` | 9002 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Caller-facing readiness/startup result failure; supervisor still receives actual actor failure |
| `ActorTerminatedError` | 9003 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | New actor operation targets an already-terminated actor; not a termination signal |
| `ActorMailboxClosedError` | 9004 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Send attempts after the destination inbox has been sealed |
| `ActorMessageRejectedError` | 9005 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Transport admission rejects payload ownership/sendability/capability |
| `ActorProtocolMismatchError` | 9006 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Dynamically negotiated/remote actor message protocol mismatch, never compiler-accepted static mismatch |
| `ActorAskTimeoutError` | 9007 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Request/reply deadline reached; remote side might have processed request |
| `ActorReplyUnavailableError` | 9008 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Response channel or reply recipient becomes unavailable |
| `ActorReferenceExpiredError` | 9009 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Generation-fenced stale actor reference, not authority to reuse identity |
| `ActorNodeUnreachableError` | 9010 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Remote actor node unavailable; no implicit actor crash inference |
| `ActorNameConflictError` | 9011 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Actor registry registration collides with a registered live identity |
| `ActorSpawnQuotaExceededError` | 9012 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Guest actor-count or memory reservation for spawn denied, recoverable |
| `ActorSupervisorUnavailableError` | 9013 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Ordinary supervisor API/registry lookup unavailable, not a supervisor-control panic |
| `ActorOutputClosedError` | 9014 | Erlang/OTP, Akka/Pekko, Ractor or Actix error categories | Caller writes/subscribes to already-closed parent-facing actor output stream |

These types are intended for **Result.Err**, rejected actor
ready/done or ask futures, and other ordinary API/guest error values where
the signature defines an error channel. Declaring a type **does not**
automatically make the runtime convert a host exception into that type.
No actor control effect lowering is implemented by this catalog.

**Cross-family matching:** the nominal `ActorApiError extends CodedError`
interface has no extra methods and is explicitly implemented by the
abstract `ActorError`, plus the previously released
`ProcessNotFoundError : StateError` (2012) and
`MailboxFullError : StateError` (2013). Their existing class parents
and codes are unchanged, so both match `ActorApiError` without being
`ActorError` class descendants. An arbitrary `Error` with a matching
`code()` method is not an `ActorApiError`. The interface is a **guest
API failure** taxonomy, not a marker that an actor panicked.

**CONTROL is not an Error subtype.** Runtime actor `panic`,
escalated `raise`/`throw`, failure of `on_start`, unhandled
`receive`, restart-intensity exhaustion, abnormal actor termination,
structured/force-isolated cancellation, and VM-fatal conditions must follow
the established actor lifecycle and supervisor CONTROL pipeline. Never
offer these as ordinary catchable `ActorError` values inside the failed
actor; a failed actor must not swallow its own panic. The supervisor should
receive a typed, immutable control-envelope **reason** (proposed separately)
with actor ID, generation, failure kind, supervision escalation policy,
and bounded/redacted diagnostic metadata. Such an envelope is not
`std/error`, not constructible as a normal guest class, and cannot be
forged by user messages.

Error distinctions that must remain sharp:

- `ActorAskTimeoutError`: no reply by deadline; delivery or processing
  may already have occurred. Do **not** blindly retry non-idempotent asks,
  and never treat a timeout as proof the actor died. Use correlation tokens
  or process aliases so late replies cannot be mistaken for new work.
- `ActorMailboxClosedError` vs `MailboxFullError`: a sealed inbox
  vs bounded admission rejection; neither implies that an earlier message
  was not processed. `ActorTerminatedError` is a guest-facing follow-up
  operation error, never a substitute for the supervisor's notice.
- `ActorInitializationError`: a caller-facing startup/readiness
  failure result; the actual actor failure STILL emits an independently
  reliable supervisor notice. It may not disguise panic or actor cleanup.
- `ActorProtocolMismatchError`: only runtime dynamic/remote protocol
  negotiation. Statically invalid `ActorMail<T>` is a compiler error.
  `ActorMessageRejectedError` is an explicit sendability/capability
  admission failure, not a general `raise` signal.
- `ActorNodeUnreachableError` vs `ActorReferenceExpiredError`:
  unavailable routing does not prove actor death, and a stale actor
  generation must never be silently retargeted to a replacement actor.
- `ActorSpawnQuotaExceededError` is a bounded guest spawn-quota
  rejection only. Fatal host OOM, forced cancellation or sandbox
  enforcement must remain uncatchable.
- `ActorSupervisorUnavailableError` is an ordinary lookup/management
  API error; an actual supervisor crash or exceeded restart intensity
  is propagated through the supervisor tree, not converted into this
  error. `ActorOutputClosedError` is an outbound stream API error, not
  permission to drop unsent outputs.

Review sources and distinctions:

- Erlang/OTP `gen_server:call`: `noproc`, `timeout`, `nodedown`;
  Erlang supervisor restart-intensity escalation.
- Scala Akka Typed / Apache Pekko: ask timeout vs actor lifecycle signals,
  `Terminated`/death pact and supervision.
- Actix `MailboxError`: `Closed`, `Timeout`.
- Ractor `MessagingErr`, `SpawnErr`, `ActorErr`: process/messaging
  failures, channel closure and typed message send mismatch.

Full references:
https://www.erlang.org/doc/apps/stdlib/gen_server.html
https://www.erlang.org/doc/system/sup_princ.html
https://doc.akka.io/libraries/akka-core/current/typed/interaction-patterns.html
https://pekko.apache.org/docs/pekko/current/supervision-classic.html
https://docs.rs/actix/latest/actix/enum.MailboxError.html
https://docs.rs/ractor/latest/ractor/errors/enum.MessagingErr.html
https://docs.rs/ractor/latest/ractor/errors/index.html

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
- **Message and stack are now explicit per-instance fields, but cause/suppressed/context remain unsupported**. The concrete Error root inherits synthetic positional initializer defaults; see ERROR_SERIALIZATION_AND_TRACING.md. Future immutable cause graphs require ownership, sendability, bounded cycles, redaction and native layout tests; no constructor super-chaining is claimed.
- **No implicit mapping of host exceptions** to these classes. Explicit, allowlisted, auditable backend adapters must map known guest-origin failures; unknown/host-fatal failures stay outside ordinary throws.
- **No claim of LLVM parity** until imported source classes, inheritance dispatch, match refinement and emitted guest type tags execute in native/wasm paths.
- **No auto-merge** before the exact-commit CI test matrix: Maven parser/typechecker; import/runtime match; source-order and unreachable arms; optimizer/Native Image; LLVM/Clang ASan/UBSan guest tests; negative syntax/effect policy tests; catalog drift/collision/security tests.

See `docs/EXCEPTION_EFFECT_ADMISSION.md`, `docs/TRAP.md`, `docs/PATTERN_MATCHING.md`.

## Trace and serialization extension

The concrete Error root implements Serializable with to_string(), to_json(), to_messagepack(), and to_proto_buf(). New Error() is trace-free, and explicit capture_stack() populates a bounded guest trace. Its inherited synthetic constructor fields configure full_trace, json_full, message, binary_full and stack; see docs/ERROR_SERIALIZATION_AND_TRACING.md for wire formats, compact JSON and binary output, trust-boundary redaction and the not-yet-enabled automatic throw-site capture. Current Java trace capture handles synchronous guest callable frames only; LLVM/async parity remains a release gate.

## Exceptional effect payload type admission

Every source `throw e`, `raise e`, and `panic e` must carry an owned nominal subclass of the canonical `std/error.Error` class (including the root itself). Nominal evidence must be verified by the compiler and cannot be satisfied by duck typing, `Any`/`Unknown`, copied code/name fields or an unrelated class named `Error`. The Java AST/type checker on PR #811 recognizes these statements and diagnoses invalid operands before failing closed for valid operands; guest exceptional effect execution is still not admitted. The actor supervisor CONTROL flow for unhandled failures remains a distinct, unforgeable runtime channel. Full contract: docs/EXCEPTION_EFFECT_ADMISSION.md.
