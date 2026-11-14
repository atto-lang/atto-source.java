# Scoped live runtime observations

`ActorRuntime.grantObservation(supervisor, ttlMillis)` is a trusted embedding API.
It supplies only a runtime-local object-identity grant; it is not a guest builtin,
ActorRef, string bearer token, or CONTROL message. `observe(grant, maxNodes)` copies
allowlisted records from the real runtime registry, mailbox accounting and
lifecycle locks. No actor behavior, guest heap, payload, failure, credentials or
mutable runtime handle is returned. The existing devtools actor descriptor path
remains compatible; this adds the scoped ownership, termination and grant fences
needed by the runtime UI.

Guest implementation: `oreslang-runtime-ui/integration/inspector.atto` implements
the demonstrated actor startup, mailbox processing, nested actor creation and
termination. No new guest observation API is proposed here: granting supervisor
observation and reading runtime-owned mailbox counters require privileged host
security enforcement, so the minimal runtime primitives and their adversarial
JUnit cases are Java. The UI host embeds the real compiler, adds only local HTTP
I/O and a controlled stdout test barrier, and uses the existing revocable broker.

Boundary: host/supervisor-only entry, exact runtime ownership, supervisor subtree,
60-second maximum lifetime, revocation, bounded records, final disclosure recheck,
monotonic expiry and observation-epoch fencing. Out-of-scope ancestry is redacted
and marks the result partial. Actor generation is the real ActorRef incarnation;
observation generation is the independently revocable host observation epoch.
Neither is a certification of hot-loaded code generation.

Records are individually consistent; the collection is not a global atomic
snapshot. Missing/truncated data is never a healthy-state claim. Unmanaged actors
are explicitly marked supervision unknown even when displayed in the root
registry bucket. Up to 512 recent terminated descriptors are retained; this is
not a durable lifecycle event log.

Execution: see the inspector task receipt for exact compiler commit/tree, clean
Maven build, RuntimeObservationTest, current actor guest regressions, real guest
socket/UI integration, raw logs and browser screenshots. Local Java execution is
the proved backend. Distributed reserved CONTROL fanout, native AOT and LLVM,
Wasm, Forge and .NET parity are follow-up work, not extrapolated from this run.
