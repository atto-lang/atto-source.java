# Actor native logical-offset rollback hardening — 2026-11-10

**Status: DRAFT / HOST_ONLY / NOT_EXECUTED_AS_ATTO_COMPILED_GUEST / NOT_CERTIFIED.**

## Audited failure

An actor-native slab previously reserved a monotonic actor-relative logical
offset **before** attempting the JNI allocation. If that attempt failed, the
catch path released the native budget but did **not** restore
`nextActorOffset`. This could cause repeated failed admission attempts to
consume logical address space despite zero successful native payload bytes.
Native regions also needed explicit rollback for failures after offset
reservation but before publication into the arena's region registry.

## Proposed repair

- Retain the existing monotonic offset policy for **successful, published**
  allocations. This is intentionally different from the LLVM dead-slot reuse
  design; never recycle Java guest-visible object addresses merely because
  an untyped native hole exists.
- Snapshot the actor-relative offset at each never-published slab/region
  admission. The actor arena monitor serializes allocation.
- On failed slab admission, remove any partially staged slab/bases/release
  entries, reset `nextActorOffset`, free any staged native memory, and issue
  the existing retryable failed-admission refund.
- On failed region publication, remove any staged region, restore
  `nextActorOffset`, free native allocation if present and refund budget.
- A package-private, **fail-only** admission probe makes OOM and RuntimeException
  fault injection deterministic *before* invoking JNI. It cannot supply a
  potentially mismatched direct ByteBuffer for the JNI releaser. Normal
  production construction uses a no-op admission probe and the original JNI
  allocator/releaser.
- New JUnit class
  `ActorNativeArenaOffsetRollbackTest` repeats 1,024 failing slab admissions,
  32 injected runtime-error admissions and 128 region failures, checking
  offset, committed bytes and budget refund remain neutral.
- Exact-head `.github/workflows/actor-native-offset-rollback.yml` checks Git
  source/head/blob identity and requires three executed JUnit test cases.

## Evidence boundary

Locally, an **independent source-free Java 21 algorithmic model** with 20,000
injected native admission failures compiled and passed. This is **not**
execution of the exact private Java runtime source or its new JUnit tests.
Source-org runner admission still needs a real assigned runner and stepful
source-build receipts. JDK25 JUnit, GraalVM Native Image, compiler-produced
AttoLang actor guest tests, actor-heap isolation, OS RSS and GC claims remain
**NOT_EXECUTED** here.

## Additional review gates

Have a reviewer verify that all affected methods hold the arena monitor while
rolling back; that a partially published native slab/region cannot escape to
the guest; that release callbacks remain retryable; and that JNI allocation
and native freeing maintain the same provenance. Add failure injection for
post-native metadata publication when a safe native releaser test seam exists.

Do not mirror private source to alternate organizations without the protected
source-branch approval and independent incident signoff required by
[platform conformance issue #22](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/issues/22).
