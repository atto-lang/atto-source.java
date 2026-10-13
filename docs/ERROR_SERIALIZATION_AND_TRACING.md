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
