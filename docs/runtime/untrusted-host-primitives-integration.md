# Isolated source-image host primitives — integration checkpoint

This integration branch contains only the independently testable **host-side**
primitives and their JUnit tests from draft PR #1275. It is based on current
source-repository `main`, not on the stale #558 actor-runtime branch.

## Included
- Strict UTF-8 source identity and bounded module identifiers
- Engine-local immutable Source image cache with context-owned leases and quotas
- Exactly-once close fence with sanitized ordinary failures and fatal-VM priority
- Source parse/link/start admission deadline with asynchronous Context revocation
- Four host-side JUnit test classes

## Excluded, intentionally
- Creating a Graal Engine, spawning real untrusted actors, linking code images
  into the compiler/import graph or activating a guest language-isolate
- Full actor heap/arena/GC changes and scheduler policy (#558/#569/#1275)
- Supervisor-scoped permission grants and code-domain approvals (#1289)
- Live actor hot reload, migration or state rollback (#1270)
- Cross-organization private-source CI mirroring

These helpers alone do not prove physical isolation, compiled-code page reuse,
per-actor GC independence, or safe adversarial guest execution. Do not expose
the Source cache to trusted/untrusted cross-domain callers without a separate
capability-checked admission API.

## Required acceptance
Run Java 25 Maven JUnit host tests on **this exact commit/tree**, requiring
all four imported test classes to execute without failures; follow with
GraalVM Native Image isolate E2E on the later integrated actor-runtime branch.
Existing local host-only stress evidence for the imported production blobs is
39,218 assertions, including a minimal Graal Source stub; it is not a Maven,
JUnit, Native Image, or compiler correctness result.

Cross-org source export remains denied by protected
`.github/private-ci-approved-repositories.txt` until independently authorized.
