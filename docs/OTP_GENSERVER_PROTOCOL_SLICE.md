# OTP GenServer typed protocol — first executable source slice

This branch adds `stdlib/otp/gen_server.ores` as an executable Oreslang-owned
protocol module. It is intentionally smaller than the full GenServer behavior
tracked in issue #1205 and common-source RFC #65.

## Implemented in Oreslang source

- Generic `InitResult<State>`.
- Generic `CallResult<State, Reply>` with explicit next state, typed reply, and
  graceful-stop flag.
- Generic `CastResult<State>` and `InfoResult<State>`.
- Pure constructors for continue/stop callback outcomes.
- A bounded pure pending-capacity predicate for policy code.
- Closed stdlib resolver registration under `std/otp/gen_server`.
- Guest execution and negative type tests.

None of this imports Java, Linux, POSIX, process APIs, networking, filesystem,
stdio, an ambient clock, threads, or allocator APIs.

## Deliberately not implemented yet

There is no public `call_async` in this slice. A correct call path requires a
runtime-minted opaque one-shot reply capability bound to runtime, actor
identity, actor incarnation, and request correlation. It must settle an
Oreslang Future exactly once and race safely against timeout/cancellation.
Using the actor output stream as a reply mechanism would be incorrect with
multiple clients and is therefore not implemented.

Also pending: compiler-enforced handler ABI, automatic actor dispatch,
self-call refusal, deadline scheduling, CONTROL failure/restart integration,
hot-code generation fencing, and LLVM/native parity.

The host-only correlation ledger in PR #1210 is related safety groundwork but
is not imported or exposed by this module.

## Intended eventual handler contract

The future behavior layer should enforce the semantic equivalent of:

- `init(args) -> Result<State, InitError>`
- `handle_call(Call, ReplyCapability<Reply>, State) -> CallResult<State, Reply>`
- `handle_cast(Cast, State) -> CastResult<State>`
- `handle_info(Info, State) -> InfoResult<State>`
- `tear_down()`

Exact callback syntax remains gated on compiler support. GenServer state must
remain serialized by default even if ordinary Oreslang actors opt into a
larger execution-thread maximum.

## Focused gate

```sh
mvn -q -Dtest=NativeOtpGenServerProtocolTest,NativeOtpSupervisorPolicyTest test
```

A workflow trigger or static source inspection is not execution evidence. Keep
the PR draft until an exact-head runner actually records the test steps.
