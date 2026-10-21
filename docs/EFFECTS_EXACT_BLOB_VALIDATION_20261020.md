# Effects audit: exact-source local verification and private CI gate (2026-10-20)

## Results — verified executable production blobs, not a whole-repo checkout

The following Git objects were fetched from the Java or LLVM GitHub PR head
using the authenticated repository connector. Their contents were copied into
an isolated local JDK/C++ harness. Each copied source was checked with
`git hash-object` and matched the upstream SHA before compilation.

| Source file | Exact Git blob SHA |
| --- | --- |
| Java `EffectContracts.java` | `9a0b6ffddf65ad0308a3e6b409b3093f4c2584a8` |
| Java `EffectAbiV1.java` | `dfbdea04b6a691189c4cc2868bf039400517d38d` |
| Java `LatentEffectRows.java` (after loan-escape hardening) | `ea7c0f757354056f3ad0f30050883af53c5b97d2` |
| Java `VerifyEffectClosure.java` | `1cb55763c1cdbfdc869ce6eb9078e00439f1b1fc` |
| LLVM `FunctionEffects.hpp` | `c0f75dae6908ed1f844c780dcb7df2915149b249` |
| LLVM `tests/function_effects_test.cpp` | `0d137f6adac9a14b4996faac0b6dbbee4d0974d3` |

Local execution evidence:

- JDK 21 compiled the four exact Java production classes. The production
  `VerifyEffectClosure` standalone main passed **98,304 root cases** and
  trap/FFI/dynamic-dispatch/ABI negative controls.
- An independently written Java reachability oracle exercised **20,000
  four-node randomized graphs × four roots = 80,000** passing comparisons,
  including recursive SCCs, forged unresolved label collisions,
  THROW/RAISE/PANIC/CANCELLATION and trapped-callee boundaries. A smaller
  **2,000-iteration** deterministic JUnit form is committed on this draft.
- The exact Java `EffectAbiV1` compiled and passed **1,050,649**
  assertions over the reviewed 1,024 native masks, permission-subset pairs,
  forbidden unmappable Java effects and unknown native bits.
- The exact revised Java `LatentEffectRows` compiled and passed **9,497**
  assertions across deferred kinds, ownership captures, allowed effects,
  composition and rejection of unverified operations. This standalone
  harness exercised a **minimal stub only for its nested Effect enum**;
  cross-component source-tree conformance is not implied.
- The exact LLVM header and actual `function_effects_test.cpp` compiled
  and passed in four configurations: **GCC 14 and Clang 17**, each with
  `-O0 -g` and `-O2 -DNDEBUG`, all with
  `-std=c++20 -Wall -Wextra -Werror`. The native test covers
  **1,048,576 valid-row pairs**, unresolved-call bit denial and malformed
  spellings.

**Proof boundaries:** these are source-blob SHA-matched local compiles, not
an exact *whole Git tree*, GitHub Actions, full Maven, GraalVM Native Image,
LLVM codegen, actual Oreslang guest execution or authenticated HIR effects.
They must not be represented as any of those.

## A blocking source-transfer policy exists — do not bypass it

The Java source main and this branch currently contain a checked-in
`.github/private-ci-approved-repositories.txt` file with **no approved
destinations** (only comments). Source-side
`scripts/check-private-ci-destination.sh` and
`scripts/publish-private-ci-source.py` require a reviewed, exact private
destination and an attested protected source main branch with CODEOWNER
approval before copying any private source to another organization.

A GitHub App read of the branch-protection settings returned HTTP 403.
That does not prove branch protection is absent, but it means the required
approval cannot be attested with the available permissions. Accordingly,
**do not perform a new cross-org private-source transfer**, despite
successful GitHub Actions runs on some *public* test repositories.

The previously created `zed-pkg-test/java-app#225` focused-blob mirror
was flagged `BLOCKED` for maintainer review. Its private contents
should not be extended or treated as approved exact-source CI evidence.

## Actions recovery / approved test route

1. Source administrator resolves Actions billing/runner scheduling and
   verifies destination private Actions budget with a **real job that
   receives a runner and executes steps**, not a zero-step failed run.
2. Source security owners select and merge a **named private destination**
   in the protected source allowlist with mandatory CODEOWNER reviews,
   stale-review dismissal and no unauthorized bypass.
3. Obtain permission to read source branch-protection settings, or have
   an authorized independent checker issue a reviewable attestation.
4. Use the GitHub connector to copy *all* files from the pinned source
   commit: Java **761 blobs**, preserving the **seven executable bits**;
   LLVM **805 blobs** must be authorized and copied independently.
   Reconstruct directories in the destination and verify the **whole
   mirrored source subtree SHA** matches the corresponding source commit
   `tree.sha` (not merely a sample of blob hashes).
5. Run JDK25/Maven/JUnit/GraalVM Native Image and native CMake/CTest on
   the exact mirrored trees, capture immutable source tree SHA and
   workflow run, and reconcile all failures before changing draft state.

The preferred source CI tracker is
`ores-truffle-oreslang/oreslang-source.java#963`.


## Additional effect hardening and JDK21 revalidation (later 2026-10-20)

New static audit corrections are included in the current draft:

- Syntactic aggregate construction (list, tuple, object/record) carries
  `ALLOC` unless verified lowerings establish otherwise.
- String/large-number literals carry possible allocation; unknown synthetic
  literal payloads carry `UNKNOWN_CALL` instead of gaining silent purity.
- Structural, `nlex`, and generic callable declarations cannot be classified
  effect-free without typed instantiation and capture proofs.
- Async, suspending, and generator declarations conservatively include both
  `SCHEDULER` and canonical `SUSPEND`.
- Source analyzer AST-shape regressions added in
  `SourceEffectAuditShapeTest.java`. The standalone JDK formal checker now
  also executes `VerifyLatentEffectRows.java` for deferred IO/Future/Stream,
  effect-row composition, and actor loan escape.

**Executed locally with JDK 21, exact Git SHA-matched production sources:**

| Production source | Git blob SHA |
| --- | --- |
| `EffectContracts.java` | `42aedab716c37110e96c6666a82cc098415975c0` |
| `EffectAbiV1.java` | `dfbdea04b6a691189c4cc2868bf039400517d38d` |
| `LatentEffectRows.java` | `ea7c0f757354056f3ad0f30050883af53c5b97d2` |
| `SourceEffectAudit.java` | `818de2ac5e939cbe8f5831292dd7c42c10a47a03` |
| `VerifyEffectClosure.java` | `c17720eb6c3ed789dd5b842c26765457118880b8` |
| `VerifyLatentEffectRows.java` | `0ec4cae0ce9dc9973f844f943606e0f718d187ef` |

The production graph, ABI, and latent descriptor classes compiled together.
The **exact committed standalone oracles** passed:
- **98,304** graph-closure root comparisons, plus negative controls for
  trapped failures, unresolved callbacks, unknown FFI, closed-world denial,
  and invalid native mask decoding.
- **8,226** latent-effect assertions, including all 1,024 canonical ABI rows,
  conversion/bind effects and capture-denial checks.

The **exact production `SourceEffectAudit.java`** additionally compiled
against small local `Ast`/`OresCompiler` interface stubs and passed
**24** direct AST classification checks, including aggregate allocation,
nested callback discovery, dynamic select, async/suspend, and unknown
non-scalar literals. These stubs are **not** the real compiler or
typechecker; this is not Maven/JUnit or source-to-HIR certification.
`SourceEffectAuditShapeTest.java` in the actual repository still awaits
execution against the production AST and parser in full CI.

The source repository's private-CI destination allowlist remains empty.
Do not copy full private Git trees to a public or unapproved carrier.
The existing private GitHub Actions capacity probes failed or did not
start, so **no full exact-tree CI success** is claimed. The new Java
PR remains **draft**.


## Round 5: enforce source-report identity and deny unproven direct call certificates

Previous exact-blob Java/LLVM model results above are valid for their
*listed earlier blob SHAs only*. The new provenance edits change
`EffectContracts.java` and `SourceEffectAudit.java`, so none of the
earlier exact-blob execution results can be represented as passing on the
new PR head without re-executing the corresponding tests.

This round changes the security boundary deliberately:

- `SourceEffectAudit.Report` is now a final class with a **private**
  constructor and immutable contents. A report obtained via
  `SourceEffectAudit.inspect(source)` carries SHA-256 of the exact source
  that completed parsing, typing and ownership checking. An internal
  AST-only inspection is explicitly *not* a source-attested report.
- The digest encoder rejects malformed UTF-16 (including lone surrogate
  code units) instead of silently substituting UTF-8 replacement bytes.
  A stand-alone Java 21 smoke of the exact digest method logic passed
  8 local checks on valid/invalid encoding cases. This **does not**
  prove compilation or execution of the full current production file.
- `EffectContracts.Graph.validateClosedWorld()` now **rejects
  caller-supplied `DIRECT` edges and self-declared trap-handler facts**
  because the actual typed-HIR callsite/handler proof producer does not
  exist yet. `infer()` and `validate()` continue to analyze such edges
  as *hypotheses* for formal testing. A public graph with no direct edges
  may pass this model gate, but that still is not a runtime authorization
  capability or a compiler-derived effect certificate.
- The standalone closure oracle and JUnit tests were updated to
  distinguish model-only direct-edge propagation from proven closed-world
  admission; additional JUnit tests check non-forgeable reports and exact
  text fingerprints, including malformed Unicode denial.

**Pending gates**: re-run the updated production blobs under JDK21+ and
the new JUnit tests on the full compiler, then exact-tree Maven/JDK25,
Native Image, native LLVM guest conformance. Local source-org Actions
runs `39525247018` (synthetic runner smoke) and `39525247019`
(fast-ci) failed before useful execution; the GitHub job responses
contain no executed test steps. Private CI destination approval
policy remains empty.

Provenance/HIR implementation and accept/reject conformance matrix are
tracked in [Java issue #1793](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1793).


The opt-in AST auditor also treats `Ast.TrapExpr` as
`UNKNOWN_CALL + ALLOC` until the compiler proves actual checked-handler
and Option construction semantics. Merely writing `trap` never discharges
the underlying THROW effect in this conservative path. A synthetic AST
JUnit regression checks the denial; it is **not yet executed** against
the full Java compiler.


## Checker-scoped identity evidence: narrow positive static-call path

The current stacked draft now also contains a narrow implementation in
`TypeChecker` and `OresCompiler.parseAndTypeCheckForEffectAudit`:
after overload selection, generic argument typing, actor boundary checks
and suspension checks succeed, the type checker records the *exact*
`Ast.CallExpr` node identity to its resolved `Ast.FunctionDecl`.
The result uses an immutable `IdentityHashMap`, rather than a
structurally keyed record map. `SourceEffectAudit.inspect(source)`
consumes those checker-produced identities, builds conservative static
call edges and computes transitive effects. Checked same-unit calls to
literal-only functions can be reported effect-free, while a callback
unbound by the checker stays `UNKNOWN_CALL`.

This checker-scoped AST fact is **not** a general typed-HIR ABI witness,
not a runtime authority token and not a public assertion that
`Call.direct("text")` is trusted. Arbitrary public
`TypeChecker.TypeCheckResult` records and `EffectContracts.Call`
values remain constructible, so no production runtime may treat those
API values as authenticated capabilities. Thus
`Graph.validateClosedWorld()` intentionally rejects *unbound public*
DIRECT edges; the source-backed auditor builds its model via
`Graph.infer()` after obtaining its own checked callsite map.

A new `CheckedEffectCallBindingsTest` regression verifies that a
checker-bound local target cannot erase an unverified surrounding
arithmetic operator. Direct local-call proof coverage and remaining
producer hardening are tracked in [Java #1793](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1793).

**Verification status:** these updated full-compiler JUnit tests
and recently edited `EffectContracts`, `TypeChecker`, and
`SourceEffectAudit` integrations have **not** passed an exact-head
Maven or GitHub Actions execution. The earlier JDK component results
apply only to the exact older blobs noted in preceding sections.
