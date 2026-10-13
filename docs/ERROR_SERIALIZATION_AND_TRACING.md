# Error serialization and independent guest tracing

Status: Java/GraalVM implementation in draft PR #811. Automatic throw-site capture, cross-actor wire authorization, native LLVM parity and full executable conformance are not yet established.

## Construction

The root std/error.Error is now concrete. Construction never creates a stack trace. Its synthetic positional arguments, inherited by subclasses, are:

| Position | Field | Default | Meaning |
| --- | --- | --- | --- |
| 1 | bool full_trace | true | Include previously captured guest frames in rendering |
| 2 | bool json_full | true | Include message and trace in JSON; false emits schema, code and name |
| 3 | String message | "" | Diagnostic message |
| 4 | bool binary_full | true | Include message and trace in MessagePack and Protobuf |
| 5 | Array<String> stack | [] | Mutable guest-owned captured frames |

Examples: new Error() has code 0 and an empty stack; new ActorAskTimeoutError(false, false, "private", false) preserves the specific type and code, but produces compact JSON and binary output. The positional form is an interim ABI: explicit constructors on the abstract/error parents cannot be added until super-constructor chaining is implemented. A future named ErrorOptions value avoids a brittle five-argument call.

## Serializable interface and formats

The std/error.Serializable interface declares to_string(): String, to_json(): String, to_messagepack(): Array<u8>, and to_proto_buf(): Array<u8>. Error implements Serializable; descendant types inherit the implementations.

JSON schema 1 has schema, code, name, message, stack. With json_full=false, only schema/code/name are serialized; UTF-8 JSON string escaping handles backslash, quotes and control chars. MessagePack is a map (fixmap(5) when full, fixmap(3) when compact), with schema/code/name/message/stack. Protobuf has stable fields 1 schema, 2 code, 3 name, 4 message, 5 repeated stack (contracts/v1/guest-error.proto). Compact Protobuf omits 4 and 5.

Code and name are diagnostics only, not authority for actor-origin failure type checking. Serialization never automatically samples a stack; full_trace=true only includes frames already captured. Encoders bound frame counts to 64, per-frame characters to 256, and message length to 4096, rejecting overlong inputs.

Security: full disclosure is the default only for trusted local diagnostics. Remote actors, untrusted plug-ins, cross-tenant transport, public API responses and telemetry require an independently enforced disclosure policy. Current caller-controlled flags are NOT an authorization boundary. No host Java stack, raw Java Throwable, guest heap pointer, actor supervisor CONTROL envelope, executable closure or arbitrary object graph is serialized. Before released use, ensure data at rest and actor boundaries is redacted and that caller options cannot override enforced compact-mode rules.

## Actor-to-supervisor transport: methods and closures are never data

A live guest `Error` object may inherit `code()`, `to_json()`,
`to_messagepack()`, `to_proto_buf()`, other user-defined methods,
and subclass fields that reference callbacks, closures, actor handles,
object graphs or cycles. **None may be marshalled or even invoked by the
supervisor transport.** No `Object.toString()`, reflection, automatic
guest serializer dispatch, method invocation or general-purpose object
graph traversal is permitted while producing the control diagnostic.

`GuestErrorWireCodec.ErrorSnapshot` is a JVM immutable, strictly typed
data-only record (bounded name, integer code, String message and a copied
list of String frames, plus the disclosure flag). Its output helpers create
JSON, MessagePack and Protobuf without retaining the source Error or guest
callables. There are deliberately two distinct factories:

- `supervisorCompactSnapshot(trustedNominalName, trustedCatalogCode,
  ignoredGuestFields)`: the **default** actor -> supervisor diagnostic.
  It never inspects, enumerates or copies the field map. It always emits
  only `schema/code/name`, regardless of `json_full`, `binary_full`
  or `full_trace` choices made by guest code. Even self-referential,
  cyclic and callback-containing subclass state cannot be traversed.
- `trustedLocalSnapshot(trustedNominalName, trustedCatalogCode,
  guestFields)`: intended only for explicitly authorized **local**
  diagnostics. Reads exactly `message` and `stack`. Both must be
  ordinary String or Array<String> data; any method/function/capability
  in the trace is rejected, not stringified. Every frame is copied;
  returned records cannot alias live actor-owned memory. All unrelated
  subclass fields, functions and user-defined `to_json` overrides
  are ignored, not visited.

**Critical provenance rule:** name/code are display-only diagnostics and
MUST come from verified nominal metadata in the compiler/runtime, not
from serialized text, a guest's `code()` return value, or a caller-supplied
string. The factory itself is **not the actor-supervisor CONTROL channel**
and does not grant authority. Integration with runtime-owned non-forgeable
CONTROL notices, actor generation and supervisor policy is a separate
release gate. No guest can construct a supervisor notice by creating a
well-formed error snapshot.

All four local `Error.to_*` implementations also now require a constant
non-negative numeric `code()` method body and read it without executing
any guest method or getter. A subclass that computes `code()` or
overrides it with side effects must fail closed at serialization until
an explicitly declarative error code field/annotation and portable ABI
are available. This rule is not permission to dispatch `code()` later
during cross-actor supervision.

Cross-tenant or external error rendering requires separately enforced
redaction, quotas and authorization before details can leave the actor
isolation boundary. Ordinary `to_json` return values are **not**
safe CONTROL messages.

### Additional serializer attack-surface hardening

The JVM wire encoder now caps nominal display names at **128 ASCII
characters**. As before, messages are capped at 4,096 UTF-16 code units;
traces are capped at 64 frames and 256 code units per frame.

Before even calling a list's `size()`, `get()` or `iterator()`, an
`ErrorSnapshot` verifies that the frame list uses the runtime-owned
`ArrayList` or JDK immutable-list representation. For trusted local
snapshot capture, the source object fields must be exactly a JVM
`HashMap` or `LinkedHashMap` (the production OresObject representation),
not a subclass or arbitrary `Map` implementation. This prevents
polymorphic `get()`/`entrySet()`/`size()` implementations from
executing guest or host callbacks during serialization. Other collection
implementations **fail closed**, even if they would be harmless.

Text diagnostics escape guest-supplied newlines, carriage returns, tabs,
ISO control characters, and Unicode bidirectional override/isolate
characters. Actual line separators between host-controlled stack frames
remain explicit. The JSON and binary formats retain their existing
version-1 schemas; these changes do not add serialized fields.

The actor-to-supervisor compact factory must still never read the
guest-owned `Map`, regardless of its implementation. The source
runtime-to-supervisor CONTROL integration remains gated pending a
trusted nominal metadata descriptor, actor-generation fencing,
supervision provenance, and executable conformance tests.

### Nominal serialization provenance and immutable input snapshots

The Java evaluator now verifies Error ancestry through linked code-unit
identities all the way back to the canonical packaged
`CoreLibrarySources.unitId("std/error")`, rather than accepting any local
class named `Error`. It recursively resolves explicitly imported parent
classes, and rejects unrelated Error-shaped classes without calling
any guest methods.

The codec detaches frame arrays **before validating them**, eliminating
the gap where a guest could mutate a live `ArrayList` between validation
and copying. Trusted-local capture likewise snapshots its allowlisted
source list first and rejects malformed/null frames before serialization.
This does not authorize source guest effects, supervisor CONTROL delivery,
or serialization of any arbitrary subclass fields.

## Stack trace as a value

std/tracing exports capture_trace_string(): String and capture_trace_frames(): Array<String>. Both snapshot only active Oreslang callable frames and never allocate Error. Current JVM runtime records synchronous guest call names using a bounded scope stack and owns the returned snapshot. It is independent of Throwable.getStackTrace, does not include Java carrier frames or raw guest receivers, and clears per-thread state when unwinding.

Error.capture_stack() explicitly captures the current guest frame list and stores it in the Error.stack field. new Error() leaves stack=[], while a later capture_stack() populates it. Calling any serializer does not capture a trace.

The overloaded capture_trace(SimpleTrace)/capture_trace(ArrayTrace) syntax is reserved: current Oreslang overload identity is (name, arity), so both one-argument overloads conflict. The two named functions are the executable spelling until true type-select overload resolution is available. For future enum syntax, use SimpleTrace and ArrayTrace only after nominal selector ADTs are admitted.

## Missing release gates

- throw new Error() must capture at the THROW boundary before stack unwinding, not during construction. An explicit rethrow must preserve the previous capture unless deliberately refreshed. Source throw, raise, panic and recover are still reserved and fail-closed.
- Async await/suspend source frames live in heap-owned continuation state; the current synchronous thread-local call-frame collector does NOT capture suspended ancestral frames. Never fall back to Java Thread.getStackTrace or claim actor causal trace parity.
- A successful serializer is not a supervisor CONTROL event. Unhandled actor failures still require immutable priority CONTROL envelopes and no catch of actor panic/cancellation.
- Exact source tests must pass across Java/GraalVM, Native Image and LLVM before claiming cross-backend parity. Protobuf and MessagePack need independent decoder interoperability checks and schema evolution gates.
- Message causes/suppressed failures, safe redaction and bounded cycle handling require separate additive versioned wire fields; no ad-hoc recursive object serialization.

Related: docs/ERROR_HIERARCHY.md, docs/EXCEPTION_EFFECT_ADMISSION.md, docs/TRAP.md, LLVM issue #313 and Java supervision issue #641.

## Required typed effect operands (implementation gate)

`throw`, `raise`, and `panic` take exactly one canonical std/error.Error or nominal descendant, never string, primitive, Any/Unknown, borrowed reference or a structural approximation. Their dedicated AST and static nominal check are staged, but all effects still fail closed at the runtime-admission boundary. Future throw-site capture must not alter ordinary new Error() allocation or allow supervisor panic to be intercepted. See docs/EXCEPTION_EFFECT_ADMISSION.md and LLVM issue #143.
