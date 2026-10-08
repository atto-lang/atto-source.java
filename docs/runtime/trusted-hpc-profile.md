# Trusted HPC/HFT execution profile (RFC — WIP / DO NOT MERGE)

Status: **design + inert planning utilities only**. Tracking: #469.
This is not an operational kernel-bypass NIC, hard real-time system, lock-free
mailbox replacement, LLVM compiler backend, or garbage-collector-free runtime.

## Goals and hard boundaries

Oreslang should eventually support dedicated-host HPC and market-data/order-path
workloads with explicit memory layout, bounded allocation, cache-line and NUMA
locality, predictable code generation and native NIC queues. These are exceptional
**trusted supervisor-deployed** workloads, not the default language semantics.

Security invariant: a guest annotation, user-controlled profile flag, actor kind,
module import, FFI call or configuration file must NEVER grant privileged access.
Only a host/supervisor with authority over the dedicated deployment can grant
specific physical resource leases, and only after verifying executable identity,
tenant identity, host policy and hardware capabilities. No ambient capability
inheritance across actor/spawn, import, future, IPC or serialization.

| Workload | Ordinary actor state | HPC physical allocations | NIC DMA / device lease |
| --- | --- | --- | --- |
| Trusted actor in dedicated Linux HPC process | Actor-local | Future opt-in | Future supervisor-granted |
| Shared/trusted actor in multi-tenant hosting | Actor-local | Denied by default | Denied |
| Untrusted/isolated actor | Confined | Denied | Denied |
| Mobile runtime / browser / FaaS | Confined/ordinary | Denied | Denied |
| Supervisor/host control plane | Separate privileged plane | May provision | May bind/unbind and revoke |

An actor's logical mailbox serialization remains mandatory. Hardware queues can
feed a trusted actor through a **single-consumer bounded bridge**; they must not
invoke guest actor methods concurrently on NIC poller threads. Driver work must
not borrow mutable actor state without a validated ownership transfer.

Existing runtime placement and physical isolation: see
[actor physical boundaries](actor-physical-boundaries.md). Related: #211
(data-oriented programming), #236 (transferable Regions), #289 (actor-local
heaps), #303 (core/LLC/NUMA placement) and #304 (borrow/take/copy/share).

## Target deployment modes

- DEFAULT: existing interpreter/JVM/hybrid sandbox model; unchanged.
- TRUSTED_HPC_EXPERIMENTAL: dedicated Linux host, explicit signed deployment
  manifest and supervisor authority, runtime control/telemetry plane separate
  from the bounded real-time data plane; **not available in this PR**.
- TRUSTED_HPC_STRICT (future): native ahead-of-time compiled guest binary,
  mechanically checked ownership and bounds, fixed memory budget; no runtime
  guest compilation or moving/reachable managed objects on its hot path.
  Admission fails unless a backend proves all requested properties.

Fail closed on unsupported OS, missing NUMA, wrong CPU cpuset, lack of NIC
IOMMU/queue ownership, insufficient huge pages or locked-memory budget, inability
to pre-fault, lost affinity or driver lease, or unsupported compiler profile.
Explicit best-effort alternatives must be separate and never silently downgrade
strict requirements.

### What JVM/GraalVM currently does NOT guarantee

- Rust-like source-level moves and borrow checks alone do **not** eliminate JVM
  GC or host-side managed objects. JDK heap, Truffle, interpreter and agent work
  can still allocate and trigger GC. Strict *no-GC-pauses* requires measurement
  and eventually a target with no runtime GC in the data path.
- GraalVM Native Image AOT-compiles the **host runtime**, but the existing
  Oreslang AOT/interpreter profile is not yet native machine-code compilation of
  Oreslang guest functions. Hybrid modes may still compile guest code at runtime.
- Source annotations cannot generally command a JIT to never OSR,
  invalidate assumptions or recompile a method. Truffle partial evaluation and
  JVM compilation controls are not a substitute for a static-code property.
- No blanket microsecond or worst-case latency promise is possible on a general
  purpose Linux OS: firmware, SMIs, PCIe, power transitions, IRQs, and contention
  still cause jitter even with kernel-bypass packet I/O.

## Region and arena memory semantics

Proposed language-facing constructs (illustrative **non-compiling** syntax):

\`\`\`oreslang
@TrustedHpc
@NoHeapAlloc
@Layout(kind: "SoA", align: 64, endian: "little")
define struct MarketBatch as
    prices: FixedArray<int64, 1024>;
    quantities: FixedArray<int32, 1024>;
end

// Every operation is bound to a supervisor-provisioned arena lease.
// Allocation happens at bootstrap, not during the hot loop.
val arena = HpcArena.from_lease(lease);
val quotes = arena.alloc<MarketBatch>();
defer quotes.close();
\`\`\`

Required compiler/runtime invariants:

1. Memory regions are arena-owned and borrow-checked. A value/borrow cannot
   outlive the region; no stale generational handle may be used after release.
   No raw address escapes to actor messages, stream subscriptions, closures,
   reflection, FFI or another privilege domain.
2. Arena creation reserves a hard budget and pre-allocates all pages during
   initialization. Hot operations use preallocated cells only. Close/drop
   deterministically releases leases, deregisters DMA and frees memory after
   outstanding users complete. Cancellation and crashes must not cause reuse
   while DMA is live.
3. NUMA affinity is **physical**, not just an actor scheduling lane. Discover
   allowed CPUs/nodes, bind thread and page allocation, fault/touch every page,
   verify actual placement; fail closed on denied/offline nodes and migration.
   Do not infer actual NUMA locality from an integer request alone.
4. Memory layout is explicit for FFI/on-wire data: fixed width, padding, array
   length, representation (AoS/SoA), alignment and endianness, with compiler
   generated size/offset assertions. Misaligned packed loads need explicit
   handling. No references to JVM objects in DMA buffers.
5. Cache-line size must be probed or specified with evidence; 64 B is only a
   common default. Independent producer/consumer counters occupy separate
   cache lines. Avoid false sharing of per-queue sequence state.
6. Existing actor-local Ref<T>/WeakRef<T> is **not** a DMA, shared-memory,
   atomic, off-heap or trans-actor capability.

Native backing should favor a Rust library with safe owning leases over
unrestricted raw pointer exposure. Placement mechanisms may include
\`mbind\`/\`set_mempolicy\`/libnuma, huge pages, \`mlock\` and pre-touch, but
only after platform-specific validation; allocations must not silently migrate
or fall back to a different node. The present Java planning class computes
offsets and rejects overflow; it does **not** allocate aligned memory.

## Native bounded rings and scheduler coupling

Implement and prove independently:

- SPSC: one producer and one consumer, wrap-safe bounded head/tail indices,
  acquire/release publication, separate cache-line padded counters.
- MPSC: multiple producers reserve distinct slots (CAS or tickets), publish
  independently with sequence state, one consumer, bounded backpressure.
- MPMC: only if real demand justifies additional complexity; per-slot sequence
  numbers, memory reclamation/ABA/wrap tests and documented progress guarantees.

A successful try_push/try_pop must not allocate, block or enter GC on the native
profile. Provide distinct error/status results for FULL, EMPTY, CLOSED and
LOST_LEASE; no blocking, hidden spin forever, or drop-on-full. Spinning must
consume an explicit budget; on budget expiry either propagate backpressure or
schedule off the critical path. Set a practical upper bound on queue capacity.

Every element must be a verified trivial fixed-layout value, or an opaque
owner/lease handle with tested lifetime and generation semantics. Do not place
Java object references, runtime callbacks, borrowed pointers, actor Ref<T>, or
GC-managed graphs into a raw ring.

**Important:** the current OresChannel select implementation uses a coordinator
lock and ArrayDeque; atomic selection semantics do not imply a lock-free ring.
This profile must not globally replace channels or actor mailboxes, because
select, wait registration, cancellation and fairness require separate protocol.
The fast path may coexist with a slow control path, after tests.

## NIC / packet path: two different kinds of "bypass"

- **AF_XDP (Linux):** accelerated packet data path via XDP and UMEM; the Linux
  kernel still sets up and mediates resources, depending on driver and mode.
  Confirm zero-copy support instead of assuming it.
- **DPDK + VFIO / poll-mode driver:** user-space NIC queue access after
  privileged setup and driver binding; grant only specific PCI functions /
  queues, with IOMMU, memory pinning, physical isolation, DMA bounds, exclusive
  ownership, reset/recovery and tenant restrictions. Never bind a shared
  production NIC on behalf of guest code.
- **RDMA verbs:** NIC-assisted zero-copy for appropriate transports, with
  registered memory and rkey/lkey revocation; not equivalent to arbitrary raw
  Ethernet packet access.
- Ordinary sockets and io_uring: valid throughput baselines, **not** direct
  kernel bypass.

The supervisor allocates all buffer memory and grants a generation-scoped
\`NicQueueLease\` to the trusted data plane; no actor may open VFIO or raw NIC
resources from user source. The control plane must revoke leases, quiesce RX/TX,
fence DMA, reclaim huge pages and enforce crash recovery before reassigning
queues. Separate secrets, management network and telemetry from polling.
NIC descriptor rings have alignment and device-specific memory-order contracts:
CPU-only Java atomics are not enough to prove DMA coherency.

## Compile-time control and reproducibility

Proposed annotations (syntax and exact names subject to language review):

- \`@TrustedHpc\`: compile-time request only; no authorization grant.
- \`@NoHeapAlloc\`: compiler-verified transitive hot-path property, including
  callees, allocation through generics, exceptions, logging and async closures.
- \`@NoRuntimeCodegen\`: reject builds with guest JIT, OSR, deoptimization
  recompilation or dynamic code loading in that path; cannot be guaranteed by
  an unverified Truffle/JVM annotation.
- \`@Layout(...)\` / \`@Align(...)\`: explicit ABI layout, checked and manifested.
- \`@PinnedCore(...)\` / \`@NumaNode(...)\`: deployment requests verified by
  the host; no implicit scheduler identity or portability assumption.

Stages:

1. Today: keep Graal/JVM profiles for semantics and integration tests, measure
   hot allocation, JIT/OSR and GC. The existing native-image \`-Pnative-aot\`
   target AOT-compiles the host but does not by itself satisfy strict guest AOT.
2. Native host experiment: isolate a Rust-owned off-heap data plane while
   Truffle remains on a separate management/control path. Strict latency
   guarantees still require proof that no managed callbacks or runtime JIT
   execute on the data plane.
3. Future LLVM lowering of a constrained Oreslang subset: verified MIR,
   ownership/region passes, frozen optimized IR, static machine code, stable
   symbol ABI, deterministic linked libraries. Reject unsupported reflection,
   hot reload and dynamic dispatch rather than silently interpreting/JITing.
4. Keep hot reload and dynamic optimization available in non-HPC profiles;
   trusted HPC is an explicitly different executable/deployment contract.

A provenance manifest should record source/compiler/LLVM backend hashes,
target architecture, codegen options, CPU features, layout schema hashes,
static binary hash and required hardware/OS controls. Hash identity, not
annotations, is the basis for code freeze verification.

## Validation matrix / merge blockers

The PR is WIP until the following independent proof gates are green:

- **Security:** untrusted guest/source annotations/actor kind/FFI/import cannot
  obtain native regions, NUMA control, DMA leases or driver file descriptors;
  denied mobile/FaaS/other-tenant cases; hostile cancellation and revocation.
- **Memory:** compiler rejects use-after-free, escaping borrows and
  reference-bearing DMA records; ASan/Miri where supported; double release,
  shutdown while DMA outstanding, page accounting and reservation overflow.
- **Concurrency:** randomized interleavings; long-running SPSC/MPSC wraparound;
  cross-core acquire/release litmus and TSAN where applicable; producer crash
  after reservation; full/empty/close/ABA/backpressure behavior.
- **Placement:** verify actual bound core and NUMA page placement under
  cpuset/cgroup restrictions, IRQ noise, unplug/hotplug, migration and budget
  exhaustion.
- **NIC:** dedicated hardware with VFIO/IOMMU or AF_XDP capability checks;
  verified zero copy, RX/TX correctness, packet drops, DMA fault/revoke paths,
  exclusive queue ownership, no multi-tenant NIC sharing.
- **Codegen:** disassemble hot paths, monitor allocations/GC/JIT/OSR,
  reproducible binary hashes and no runtime guest compilation in strict mode.
  Bare metal latency baselines (median, p99, p99.9, p99.99/max) must include
  CPU model, frequency/power mode, NIC model, PCIe, OS/kernel, topology,
  traffic mix, message size and run length.
- **Compatibility:** JVM/Truffle reference, native-image AOT/interpreter,
  hybrid, sandboxed private/untrusted actors, FAAS and mobile stay intact.

### PR deliverables in this first slice

- This RFC.
- An *inert* Java planner for cache-line/arena offsets and restrictive
  preflight diagnostics; it grants **no** capabilities and performs no NIC I/O.
- JUnit tests for invalid profiles, alignment, capacity and integer overflow.

No existing production path calls the planner. Do not advertise this PR as
shipping zero-GC execution, hard real-time, direct NIC I/O, NUMA binding,
guest LLVM-AOT, or a lock-free queue.
