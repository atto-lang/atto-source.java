# Java tenant runtime host: first source-guest integration slice

**Canonical naming (2026-11-11): TenantVM** denotes the per-tenant execution compartment under OresVM; `TenantIsolateManager` is the existing Java trusted-context host implementation name, **not** a proven untrusted TenantVM. See [TenantVM naming](../TENANT_VM_NAMING_V1.md); no API or sandbox guarantee changes here.


**Status: draft implementation / execution not yet verified.**

This is a stacked implementation on Java PR #2278 (fail-closed physical
tenant-isolation admission). It consumes the OresCluster design in platform
conformance PRs #227 and #260, including the following hierarchy:

```text
Host OS process (future single OresVM silo)
  tenant A guest Context (logical isolation, not verified native VM-isolate)
    OresContext / OresVM root / ActorRuntime / multiple guest actors
  tenant B guest Context (distinct logical context and actor runtime)
    OresContext / OresVM root / ActorRuntime / multiple guest actors
```

## Implemented Java foundation

`TenantIsolateManager` is a trusted embedding-host helper with bounded
registration and lifecycle. Each admitted tenant gets its own real Oreslang
polyglot Context; source evaluation creates the actual `OresContext`,
`OresVM`, and `ActorRuntime` and runs compiled/interpreted `.ores` guest
code, including source actor mailboxes and async lifecycle.

- Registration requires an explicit canonical tenant ID and host-supplied
  `IsolatePolicy`. No tenant identity is accepted from guest source.
- Opaque, manager-bound `TenantHandle` objects prevent name-based lookups
  and mistaken handle reuse after tenant close/reopen. Such handles are **host
  authority**; never provide them to a guest or allow arbitrary network
  callers to invoke registration/evaluation.
- One tenant's source eval and close are serialized using its private
  tenant monitor. Other tenants are independently runnable.
- Each tenant has a bounded output buffer and the registry caps tenants.
  These are **not** complete CPU, memory, async task or mailbox accounting
  limits. Existing guest policy limits still apply where enforced.
- Context close delegates to OresContext's actor/native-I/O quiescence
  barrier. If close fails, the tenant remains strongly reachable and
  quarantined in the registry for host inspection/retry. No forceful
  best-effort deallocation is introduced.
- Only trusted, in-process policies are admitted. Requests for unsupported
  protected VM-isolate, OS sandbox and untrusted actor hosting are
  rejected rather than downgraded. Unsupported modes cannot be enabled
  by claiming an actor arena is a physical protection boundary.
- The interface returns copied textual output, **not** raw guest Values,
  mutable host objects or raw actor heap references.

## Verification requirements

```sh
mvn -Dtest=TenantIsolateManagerTest test
mvn test
```

The JUnit file `TenantIsolateManagerTest` uses real `.ores` source
including two `Counter` actors per tenant, their `on_start`,
`receive`, mailbox output and `get_done` lifecycle. Other cases exercise
independent tenant close, old/foreign handle refusal, tenant quota,
invalid Unicode IDs, reject protected/untrusted policies, and reject
foreign-language Source objects.

**These source tests have been committed but are NOT_EXECUTED.** Source-org
GitHub Actions currently suffers runner admission failures; a failed
zero-step job is not implementation or test evidence. No private code may
be mirrored to an unapproved other organization.

## Explicit blockers to real OresVM tenant-isolates

This helper currently creates one **OresVM root per guest Context**, not
one centralized OresVM-wide actor dispatcher shared across tenant isolates.
That is intentional for this narrow safe vertical slice, but is not the
final cluster hierarchy. Graal Contexts are not independently protected
native VM-isolate heaps or adversarial OS sandboxes. Trusted Java statics
and shared JVM runtime facilities are not strictly physically isolated.

Next work must integrate a **process-level OresVM silo owner**, with a
verified backend tenant VM-isolate handle, authenticated tenant identities,
per-tenant/actor resource ledger, generation-fenced actor roots and memory
arenas, safe root-turn admission and native sandbox evidence. Concurrency
must remain bounded; no per-actor scheduler pool. A tenant may span OresVM
nodes, but may never share mutable execution state with another tenant.

P0 before promotion: resolve Java actor memory issue #1566 and verify
physically quiescent source Tasks/descendants before actor arena/isolate
teardown. For cluster failover, an in-memory epoch is not sufficient:
durable provider-side epoch CAS and effect sink fencing are mandatory.

Do not claim Java/LLVM/.NET/Wasm/Forge parity or hard real-time from this
host integration. Only executable exact-head guest and native isolation
evidence can advance those states.

## Lifecycle hardening and shared contract update (2026-10-30)

- The host publishes `RETIRING` **before waiting** for any active guest
  evaluation monitor; no new guest eval can overtake a tenant retirement.
  Whole-host close seals **every** tenant first, before closing the first.
- A failed context close becomes `QUARANTINED` and remains strongly held;
  never count a close exception as physical retirement. A successful close
  removes the tenant handle, and concurrent trusted close attempts avoid
  double-closing the same Context.
- `tenant_state` is a **host-only** diagnostic, not an actor API or a
  security sandbox proof.
- Tenant names now conform to the shared tenant-isolation-v1 ASCII
  `[A-Za-z0-9_.-]` alphabet (1–128 bytes), with `.` and `..` reserved.
  Reject Unicode, slashes, whitespace and path component aliases so a
  future backing store never diverges from other backends.
- `TenantIsolateRetirementRaceTest` uses a deterministic monitor barrier
  to test the retirement/evaluation admission race and that host-wide
  shutdown seals unrelated tenants before a blocked tenant completes.
  This test is source-committed but **NOT_EXECUTED** until Maven actually
  runs against the exact PR head.

These are trusted-context lifecycle protections. The final process-level
OresVM root, physical VM-isolate/sandbox implementation, complete tenant
resource accounting, compiled guest execution proof and Java issue #1566
physical Task quiescence evidence remain separate blockers.
