# HTTP Router generation authority — trusted-runtime stage 1

**Status:** draft, host-only admission primitive. NOT connected to the native Atto
router, Spin, `NativeHttp`, actual supervisor actor lifecycle, or cross-isolate
transport. Do NOT merge/ship as if issue
[oreslang-http-routing #52](https://github.com/ores-truffle-oreslang/oreslang-http-routing/issues/52)
were closed.

## Threat model and invariants

- A publicly constructible `http_routing.Decision` is metadata, never an
  authorization capability. Route-template equality, handler IDs, endpoint
  positions, and digests are non-authoritative.
- The trusted supervisor/HTTP host creates an `Owner` identity (tenant,
  supervisor ID, actor ID, incarnation) and retains its object reference.
  Textual identities are *diagnostics only*. Equal fields on another `Owner`
  object do not grant access.
- The immutable `Snapshot` and once-only `Ticket` have private constructors
  and never cross into guest state, actor mailbox messages, source-created
  callbacks, serialization, logging, or HTTP response metadata. Java's
  package-private implementation is not an OS sandbox.
- `admit`, `prepare`, and `publish` serialize under one gate. A listener
  integration must select the route snapshot and reserve the ticket atomically
  at its own dispatch boundary. Current implementation deliberately does **not**
  claim to enforce that integration.
- `claimResponse` requires the exact original owner + snapshot + ticket and
  succeeds at most once. There is no token-based lookup into an unbounded
  registry, no public counter, static secret or hash granting authority.
- Preparing freezes new admission; publication atomically begins draining
  the old generation and installs the new one. Existing same-owner requests
  can finish on the old generation. Owner replacement revokes their future
  response claims (in-flight work must subsequently abort and still clean up).
- Cancellation or Future settlement NEVER implies physical cleanup. The
  `HttpGenerationDrainV1` exchange remains live until handler and transport
  physical completion; suspended tasks, HTTP streams, callbacks, FFI and shared
  regions hold separately bounded pins.
- Retained generation capacity is bounded and fails closed under pin leaks;
  epoch exhaustion fails closed. Closing the gate revokes authority but does
  not lie about outstanding physical resource completion.

## Executable tests

`HttpRouterGenerationAuthorityTest` exercises identical tenant/route IDs
across two gates, forged/stale issuer and snapshot identity, once-only
claims, cancellation, same-owner upgrade and different-owner revocation,
retained native pins, capacity exhaustion, retirement and concurrent
admission/upgrade linearization.

Run against the exact Java candidate source:

```sh
mvn -B -ntp -Dtest=HttpRouterGenerationAuthorityTest -DfailIfNoTests=true test
```

Do not report a test as executed unless a real test XML report contains
nonzero tests with zero failures, errors and skips.

## Next integration gates (NOT IMPLEMENTED)

1. Attach authority to an actual `NativeHttp.Server` (or common runtime ABI
   primitive) at the request selection and admission linearization point.
2. Pass *only* runtime-owned ticket handles through transport-owned slots;
   compiler/admission must reject access from source Atto actor mail and host
   interop. The Router `Decision` remains public data.
3. Integrate supervisor restart and hot-upgrade callbacks with
   `ActorUpgradeEpochGate`, including actor incarnation replacement,
   quiescent continuation cleanup and streamed-response drain/abort.
4. Add real native `.atto` + Spin HTTP guest and mutation tests, verifying
   the 124 existing live checks and new scope/replay/revocation cases.
5. Repeat executable conformance for LLVM, .NET, WASM and Forge backends
   when backend ABI support is actually present. Same-host JVM tests are not
   cross-backend authorization or full AOT evidence.

No broad security release claim is authorized by stage 1 alone.
