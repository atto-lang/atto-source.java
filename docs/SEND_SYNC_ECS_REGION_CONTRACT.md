# Send / Sync worker transfer and SoA region access — compiler foundation

Status: **incremental implementation**. Java TypeChecker now uses compiler-known `Send` proof alongside the existing conservative async-boundary gate; this does **not** introduce user-defined `Send`/`Sync` implementations or source-language trait syntax.

## Marker rules (not user-forgeable)

- `Send<T>`: moving **owned** T between M:N scheduler workers is safe.
- `Sync<T>`: moving shared `&T` across workers is safe. A shared borrow is Send iff its target is Sync; a mutable exclusive borrow is Send iff its target is Send.
- Primitive scalars, records, tuples, lists and Option/Result derive markers **only when every included type does**.
- `Channel<T>` is Send and Sync if T: Send because its runtime transport serializes ownership transfer (this does not make T itself Sync).
- Unknown generics, unknown FFI/native handles, functions, thread-affine handles, ActorRef, mutable interior handles, Future, Task and allocator-domain `Shared<T>` fail closed until separately proven. No `unsafe impl` escape hatch.
- Marker proof does **not** allow borrowed memory to escape async or outlive a scope. Existing lifetime/ownership verifier must continue checking capture, move, await, exceptions and cancellation.
- Actor memory domains are **distinct** from OS thread markers. `Send` never authorizes an actor-private pointer to escape its arena or a trusted value to cross to an untrusted isolate.

## Current compiler integration

`TypeChecker.validateAsyncBoundaryType` requires a Send proof and retains the prior stronger owned/shared-safe check. Existing async function generic restrictions remain. Compiler-verified nominal layout proofs are passed by `TypeChecker`; standalone tests exercise fail-closed unknown/negative types and the difference between T: Send and T: Sync. This is conservative, not a complete trait solver.

## Proposed zero-copy ECS path

`RegionAccessPlanner` is a deterministic, side-effect-free planning substrate:
- SoA column `(allocation, component)` and half-open row interval `[from, until)`.
- Read/read concurrency is allowed; overlapping read/write or write/write serializes. Separate columns and proven disjoint row chunks can execute in parallel.
- The planner groups systems into dependency-preserving stages, with no copying and no per-entity actor.
- **Warning**: compiler region IDs are symbolic, not unforgeable runtime permits; the planner does **not** authorize a worker to dereference a real allocation. Actual `InvertedArray<T>`/ECS execution requires nonforgable lifetime/domain tokens, alias canonicalization, scoped Task joining, cancellation fencing and worker scheduling.
- Actor can own the ECS world and supervise ticks, but ordinary Tasks may process disjoint chunks; actors are not mandatory for ECS.

## Remaining implementation order

1. Define source-language marker trait bound syntax and coherent generic requirements, with no user declarations that bypass compiler-known safety.
2. Apply Send to every Task capture/move and M:N scheduler transfer; add compile-fail tests for borrow escapes across await and cancellation.
3. Implement Arc-like, domain-authorized immutable `Shared<T>` with correct last-owner reclamation and a verified Sync proof; do not enable its marker merely from its payload type.
4. Prove real disjoint array region identities and scoped borrows; bind planner-issued permits to allocation epochs and lifetime scopes.
5. Lower ECS system access sets, feed stages to the Task scheduler, benchmark SoA vs AoS cache misses and measure false-sharing.
6. Cross-check GraalVM/LLVM code generation, negative tests, sanitizers and real concurrent execution. The LLVM header/test currently checks marker *contract parity only*, not source lowering.

Freestanding `core` owns the type/borrow semantics; `alloc` will own reference-counted storage and allocator-domain policy; optional libraries provide ECS and host scheduling.
