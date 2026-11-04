# Truffle dispatch and allocation optimization audit

Status: **draft measurement and correctness contract**, not evidence that devirtualization or scalar replacement currently occurs.

## Scope
Focus on the actual hot code paths: Oreslang method invocations, actor mailbox sends, message conversion, class/record temporary values and HTTP routing. Measure each path before changing dispatch specialization. Closed-world Native Image reachability **does not guarantee guest method calls become statically devirtualized**.

## Baselines and profiles
- Capture JVM/JIT, native AOT-interpreter and hybrid modes as separate environments with pinned source and toolchains.
- Measure warm vs steady-state throughput, p50/p95/p99, JIT/deopt counts if exposed, allocation bytes/op, RSS, and time in GC.
- Characterize monomorphic, 2- and 4-way polymorphic and megamorphic call sites. Use the same fixture inputs across modes.
- Capture actual Graal/Truffle inlining and escape decisions; avoid inferring from source structure alone.

## Semantics before performance
1. PIC/inline-cache keys must include exact class/shape identity and code-generation lifetime. New or revoked hot-reload generations must never use stale inline caches.
2. Actor or tenant-owned state must remain isolated. A scalar-replaced object cannot leak a mutable alias, invalidate a borrow, or remove required drop/cleanup operations.
3. Preserve exactly-once send/ack, exception ordering, sync boundaries, ownership checks, capability guards and host integration.
4. Perform no speculative optimization that elides runtime policy checks or changes observability of a constructor, destructor or trap.

## Proposed negative cases
- Two unrelated classes sharing a method name; same-name classes after hot reload.
- Alternating receiver shapes through mono -> poly -> mega transitions, and deoptimization to interpreter.
- Allocations escaping via callback, return value, actor message, shared region and thrown error.
- Async suspension and concurrent actor interactions; back-to-back reload invalidation.

## Exit criteria
- [ ] Reproducible instrumented microbenchmarks with uncontaminated warmup
- [ ] Truffle/Graal diagnostics prove chosen optimization actually triggered
- [ ] Before/after repeated measurements with variance and correctness outputs
- [ ] Concurrency/hot-reload/borrow/capability regression suite
- [ ] Remove or disable any speculative optimization with inconclusive evidence

Tracking: https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1255
