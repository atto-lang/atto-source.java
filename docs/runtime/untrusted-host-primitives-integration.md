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


## Monotonic admission timeout hardening (2026-10-18)

Audit finding: relying only on the `ScheduledThreadPoolExecutor` callback
to change an admission from RUNNING to EXPIRED allows a late
`commitIfLive()` to win while the single timer worker is delayed. The
updated `GuestAdmissionDeadline` checks `System.nanoTime()` elapsed time
*at commit*, and checks again after the state transition to reject delayed
publication; expiry invokes a separate virtual-thread revoker. Timeout
duration is strictly positive and capped at five minutes, keeping modular
nanosecond subtraction safe. The callback never dereferences the alarm
handle, because a near-immediate timeout can run before the constructor
assigns it.

**Local exact-blob execution:** `GuestAdmissionDeadline.java` blob
`f5d4f25551266298dafaa305990abab9df3cd789` compiled with OpenJDK 21
`javac -Xlint:all -Werror` and passed **5,205** deterministic host checks,
including 75 deliberate timer-worker starvation cases, 1,500 one-nanosecond
timeouts with exactly-once revocation, 1,500 successful admissions and
overlong-duration rejections. Authored `GuestAdmissionDeadlineTest` adds
JUnit regressions for these behaviors.

The earlier exact-blob offline source identity/cache/fence suite also reran:
2,081 identity + 7,213 cache + 5,524 Unicode + 24,400 fence assertions passed.
Total host-only checks for these four components: **44,423**. The cache
harness stubs Graal's `Source`; neither the new JUnit tests nor full Oreslang
native runtime were executed by this host verification.


## Unicode canonical-equivalence hardening (2026-10-18)

The shared untrusted code-image cache indexes immutable source with
`(codeUnitId, SHA-256(exact UTF-8 source))`. A malicious unit can otherwise
submit visually identical composed and decomposed Unicode names, consuming
separate image quotas and producing ambiguous import/diagnostic identities.

`UntrustedSourceIdentity.inspect()` now **rejects non-NFC code unit IDs**
rather than silently normalizing an authority-bearing name. The original
NFC spelling and exact source bytes are preserved. Valid Unicode module
identifiers (including supplementary symbols) remain accepted. Added JUnit
regressions cover Latin accent and Hangul canonical aliases, failed-cache
admission accounting, reuse of an identical canonical unit, and full lease
reclamation.

**Independent local strict-JDK proof:** exactly the production Git blob
`25090e51753cf27207f3be2fad4862836d0f7cae` compiled with
`javac -Xlint:all -Werror` on OpenJDK 21. The focused NFC test passed
11,344 checks, including 5,000 seeded Unicode combinations and 1,000
rejected attempts that must leave the cache accounting unchanged. Existing
host identity/cache, Unicode fuzz, close-fence and admission deadline
harnesses also passed in the same local audit, totaling **55,762 assertions
actually observed** in that execution. The cache tests use a minimal Graal
`Source` stub. This is not full Maven/JUnit or native-isolate evidence.

The canonical-name rule must also be preserved by future import/loader
resolution and compiler-owned code-image identity; otherwise caller
normalization could inadvertently bypass the fail-closed boundary.
