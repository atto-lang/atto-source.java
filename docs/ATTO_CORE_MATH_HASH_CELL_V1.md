# Backend-owned Atto core math/hash/cell — Java v1

Freestanding `.atto` modules are registered at `ores.core/math`,
`ores.core/hash` and `ores.core/cell`, without `.ores` aliases.

- **math**: signed min/max, signum, closed-interval admission and
  `Result<int,String>` checked clamp. Float transcendentals and signed
  abs(minimum) need explicit intrinsics/overflow semantics.
- **hash**: seed=17; octet step `(state*31+octet) mod 1000000007`,
  implemented without overflow, modulo or wrapped arithmetic.
  Invalid inputs return -1. This is a stable noncryptographic byte fold,
  **not** a cryptographic hash, hash-flood defense or complete nominal
  Hash-trait implementation for whole Atto values.
- **cell**: *policy helpers only*. No cell storage, real borrow tokens,
  synchronization or interior-mutability authority is implemented.
  A real `Cell<T>` / `RefCell<T>` requires compiler ownership/loan
  checking, nonescaping lifetimes, and approved runtime storage.

The JUnit is intended to compile and execute the actual linked Atto guest
with exit status 42. It has **not executed** until exact-head CI shows
real runner steps. Java/GraalVM native-image and other backends remain
unverified. Adjacent/independent work: Java numeric #1329/#1736, LLVM
Math #547/#548, Atto spec numeric #209 and existing Hash trait dispatch.
