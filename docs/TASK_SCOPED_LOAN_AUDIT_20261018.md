# Scoped Task loan audit — October 18, 2026

**Status:** compiler-analysis helper only. NOT guest Task syntax admission, Java/Graal runtime execution, or LLVM compiler parity. Draft PR #1448 stacks on Send/Sync PR #1439; shared semantics in common-source PR #90.

## Confirmed findings and mitigations

| Severity | Finding | Fix |
| --- | --- | --- |
| P0 | Public host class could look like a guest-usable permission API | Enclosing Java class is now package-private; guest Task borrowing remains disabled |
| P0 | READ/READ from unrelated trust domains could bypass exclusion | One scope domain; reject mixed-domain claims even for readonly access and even after prior child completion |
| P0 | Structural reallocation checked numeric ID only | Scan all live claims using conservative mayAlias, including uncertain roots with different IDs |
| P1 | Race on mutable admission/cancellation/retirement HashMaps | Synchronized ledger admission, cancellation, parent checks, quiescence and barriers |
| P1 | Parent permission queries accepted stale generations | Require known exact allocation generation, scope domain, and non-closed scope |
| P1 | Compiler memory retention/unbounded Tasks | Cap 4096 active children, 1024 claims per child and 65536 tracked allocations per scope; clear metadata on final close |
| P1 | Concurrency test used busy-spin | Latch-based eight-writer admission race keeps one successful writer live until all attempts finish |

## Remaining P0 integration gates

- Root IDs, allocation/trust-domain strings, generations and inline-disjointness flags are currently trusted *compiler inputs*. They are NOT unforgeable guest/runtime tokens or verified storage layout certificates. Compiler/MIR must mint proofs using real place and alias analysis; FFI and unknown layouts remain conservative.
- quiescent(ticket) models logical retirement, not an authenticated physical completion receipt. A future runtime caller releasing early would be unsound. Bind each Task to authenticated continuation/scope identity and wait for carrier unwind, native FFI/device pins, destructor/defer cleanup and code-version lease retirement before final loan release.
- A nominal Send/Sync callback is not a transport or permission grant. Validate transitive captures, interior mutability, lifetime, suspension safety and actor domain authority separately.
- The per-scope capacity is intentionally conservative. Future lowering may shard footprint graphs or retire provenance only with proven non-ABA lifecycle fencing.
- Compile-time synchronized metadata does not implement the runtime memory model. Real Task handoff needs acquire/release publication, scheduling and true join barriers; no guest Task source feature is enabled by this PR.

## Evidence and acceptance

- 26 JUnit regression methods committed; neither Maven/JUnit nor actual GraalVM guest compiler tests were executed in this audit.
- At the inspected 2026-10-19 03:10 UTC head, Actions fast-ci run 39193409339, concurrency-contract run 39193409592 and ci run 39193409489 all show sampled jobs failing with **zero executed steps**. This is not Java test evidence.
- Required before merge: JDK Maven exact-head TaskAccessVerifierTest and full relevant checks; positive/negative source compiler diagnostics; TaskScope cancellation vs physical quiescence; native LLVM and TSan/ASan where applicable; correct dependency revision.
- No private-source cross-organization mirror was created or authorized. Keep this PR DRAFT.
