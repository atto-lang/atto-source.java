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

## Follow-up: owner-identity collision (P0), path budgets (P1)

**P0 actor/tenant isolation:** The first verifier version modeled trust domains only by
category string (for example `ACTOR_PRIVATE`). That is not an actor identity:
two separately owned heaps can both be actor-private. A helper that admits
READ/READ from those heaps into one scope violates the isolation boundary even
when individual allocations are disjoint.

**Fix in this draft:** `Root` now carries a distinct `ownerId` alongside
the category, allocation ID, and generation. Every alias query, conflict check,
allocation-generation key, scope admission, parent access, and structural
barrier uses both category **and** owner identity. The four-argument
compatibility constructor is restricted to process-local provenance; non-process
roots require explicit owner identity. Tests exercise independent actor owners,
owner restarts/epochs, same-owner distinct allocations, untrusted-vs-actor and
process-vs-actor rejection. This prevents accidental category-based authority
confusion in the compiler helper; it is **not an unforgeable runtime token**.

**P1 bounded input:** Limit owner/domain labels and inline field names to 256
UTF-16 code units and projection path depth to 64 before copying. These are
compiler-analysis resource bounds, not runtime ECS limits.

**Residual P0:** Only the compiler's trusted place/alias/type engine may mint
`ownerId`, allocation IDs, generations, `uniqueAllocationProven`, and
layout-disjointness proofs. Actor lifecycle epochs and tenant boundaries must be
bound to those proofs, including when code hot reloads. Public string constructors
by themselves do not authenticate owners; source Task borrowing stays fail-closed.
The typed claim's referent type is also not yet checked against an authoritative
MIR place type. `quiescent(ticket)` is not authenticated to a physical Task join,
so the real runtime must not invoke it merely on cancellation or result settle.

**New evidence:** 32 committed JUnit methods (including six additional owner,
epoch, and resource-bound regressions), not run under Maven/GraalVM. The
source-org fast-ci run [39194705587](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39194705587)
and concurrency-contract run [39194705537](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/39194705537)
reported failures with **zero recorded steps** and no runner assigned on
the inspected intermediate head. No compiler/JUnit pass is claimed, and
no private Git tree was exported to another organization.
