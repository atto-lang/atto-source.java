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
