> Historical audit: its opt-in/default policy is superseded by [mandatory nonblocking I/O](MANDATORY_NONBLOCKING_IO_2026-10-15.md). Blocking compatibility is now forbidden.

# Native async I/O and ownership/keyword audit — 2026-10-13

## Result and scope

The organization inventory is complete: **37 repositories**, **22 fetched existing
checkouts**, **15 new clones**, no fetch/clone failures. `oreslang-db` is empty
upstream. Clean main branches were fast-forwarded; the documentation feature
branch and the two checkouts containing local untracked work were preserved.
All three still have freshly fetched remote refs. Inventory and operation
receipts are beside this report in JSON.

Another active chat changed both primary compiler checkouts during the audit.
Implementation and verification therefore use isolated local Git worktrees:

- Java: `audit-worktrees/java-native-async`, branch
  `hardening/native-async-io-keyword-audit-20261013`, base `a0b5d9d32`.
- LLVM: `audit-worktrees/llvm-native-async`, branch
  `hardening/async-io-keyword-boundaries-20261013`, base `8607da45`.

**The complete native-only/zero-blocking migration is NOT finished.** This
change establishes a reviewed executable slice, rejects unsafe fallbacks in a
selected strict policy, and inventories remaining release blockers. It does
not certify production HTTP, native filesystem I/O, actor networking, Native
Image/AOT, or full cross-backend keyword execution.

## Native Oreslang versus Java

On audited Java main, packaged `stdlib/` had only `core.ores`, `assert.ores` and
`testing.ores`. The public fs/net/http semantics and transport still went through
`OresEvalRootNode`, `NativeIo`, `NativeHttp` and `AsyncNativeIo` in Java. Names
containing “native” or “async” are not evidence of Oreslang implementation or
nonblocking OS behavior.

This branch reuses and hardens the small existing network draft
`feat/native-nio-tcp-selector-20261012` at
`b39414d3539e9cd7905577f10b21acf503e581b5`, rather than rewriting it independently:

- `stdlib/net.ores` owns the loopback TCP source facade.
- `stdlib/http.ores` owns byte matching, incremental request-head framing,
  route classification, keep-alive decisions and response bytes.
- The closed compiler-owned stdlib registry packages both; core source imports
  continue to deny Java islands, Java imports, and non-stdlib dependencies.
- `NativeNioFutureReactor` is a **Java NIO kernel adapter**, using nonblocking
  sockets and runtime-owned Futures. A dedicated reactor may idle in selector
  readiness; guest callbacks do not run on that thread. This is NOT the desired
  final C/JNI OS ABI and not a claim of Java-free process execution.
- The unused duplicate `NativeNioTcpKernel` prototype was deliberately excluded.

Java remains necessary in the current Truffle frontend, evaluator, embedding,
capability checks and result adapters. Moving library policy into Oreslang does
not remove those backend components. The next step is a narrow native reactor
ABI, preserving the same Oreslang modules and capability/ownership contracts.

## Blocking I/O audit and strict gate

| Surface | Actual audited behavior | Hardening / remaining requirement |
| --- | --- | --- |
| Filesystem | `Files.readString`, writes, directory and metadata calls, real-path resolution | Strict policy rejects before even canonicalization. No native async filesystem implementation exists. |
| Legacy TCP | `Socket.connect`, buffered stream reads/writes | Strict policy rejects direct and worker entrypoints; new loopback byte-stream slice uses nonblocking channels. |
| Legacy HTTP client | Java `HttpClient.send` | Strict policy rejects it, including `_async` aliases offloaded to workers. |
| Legacy HTTP server | Java `HttpServer`, stream transport and virtual-thread workers | Strict policy rejects it; Oreslang HTTP wire module is a separate restricted GET/HEAD bootstrap. |
| Stdio | Java input reader/output writer | Strict policy rejects before touching those streams. An async descriptor sink is still required. |
| Java interop | Optional host classes and mixed source | Strict policy rejects host authority and mixed Java source before execution/compilation. |
| LLVM guest I/O | No supported fs/net/http/stdio transport or continuation lowering | Unsupported calls/imports/aliases/await fail without emitting LLVM IR. |

Activate the Java runtime gate with:

```text
--core-io=native-async-only
```

Embedders can pass `CoreIoPolicy.NATIVE_ASYNC_ONLY` to the new
`LinkedProgramRunner.runWithExitStatus` overload, or append
`--ores-core-io=native-async-only` to Oreslang context application arguments.
Policy is immutable per context and independent of permission grants.
Alias calls reach the same runtime boundary. Duplicate/unknown policy values
are rejected. File creation and network authority do not authorize a fallback.

**Compatibility remains the default** (`legacy-blocking`) for this unversioned
pre-release implementation. It still permits blocking host calls. The strict
gate is runtime enforcement, not a static proof supplied by `--check`, and
covers guest core I/O rather than compiler/source loading or host diagnostics.
Default cutover requires a versioned consumer migration. Do not advertise all
current Oreslang execution as async-only.

The new loopback slice still has synchronous listener setup/port metadata, no
TLS, no general connect/DNS, and no actor-transfer support. Accept/read/write
and close expose Futures; reads/accepts can cancel before kernel consumption;
writes promise no rollback. Listener setup must move to an async native ABI
before claiming **every** source I/O operation is asynchronous.

## Concrete hardening

### Oreslang HTTP and transport

- Byte matching uses subtraction-based bounds, avoiding integer overflow.
- The 8,192-byte limit applies to the current request head; buffered pipeline
  bytes do not incorrectly reject a short first request.
- CR/LF, backslash and fragment markers inside request targets are rejected.
- An unknown route cannot advertise a persistent connection while the emitted
  error response closes it.
- Reactor command turns are bounded to 64 submissions before readiness service;
  queued commands use immediate selection, preventing the batch bound from
  delaying remaining commands until another wakeup.
- Maximum connections is bounded at 65,536. Existing listener/future/byte caps,
  context-owner checks, no actor sendability, cancellation fencing, and close
  settlement remain enforced. Aggregate native-byte quota accounting remains
  a release gate.

### Critical, read/write, using, with and ownership

Java main did not implement these keyword contracts: `critical`, `using`,
`with`, `owns` and `owner` could still be ordinary identifiers. They now fail
closed as reserved unsupported syntax throughout the compilation unit.
`critical-read`/`critical-write` receive exact hyphenated lexical recognition;
longer identifier suffixes are not swallowed. `owned` and ordinary prefix names
remain legal. A reserved keyword grants **no** ownership or scheduler authority.
Old ownership fixtures using `owner` as a variable/parameter were renamed so
negative borrow checks still reach the borrow checker.

LLVM reserves the same identifiers/hyphenated forms and adds 14 rejection
fixtures plus real positive native execution of prefix names/subtraction.
Existing legacy `using` effect rows remain a prototype in LLVM main; they are
not canonical scoped acquisition and must be reconciled with `with` before a
language release.

The native host state-loan slice reuses six files from the existing canonical
read/write policy draft `feat/critical-rw-using-parity-20261012` at
`b52cbaef6a267f5ae5184c11c758770fa58b8845`, with additional strict-block hardening:

- An explicit validated Read/Write mode is required; missing or forged modes
  fail. All invalid 8-bit mode values are covered.
- Read mode cannot mutate even under a broad effect upper bound or a verified
  serial actor. Serial admission never upgrades an unmarked callback to Write.
- Writes require actual write authority and inferred write effects; runtime
  `ActorOwnedScalarState.Access` independently enforces the mode.
- Live state loans cannot escape or suspend. Generation-fenced continuation
  metadata cannot make the synchronous loan bridge resumable.
- A declared `block` upper bound cannot authorize blocking inside state loans.
- Wrong actor/continuation, stale token, nested fencing and exception cleanup
  retain the native generation/lifetime checks.
- Async completion accepts only the five valid terminal outcomes. Invalid enum
  values cannot consume the terminal CAS; zero/stale/wrong operation identities
  remain rejected. Completion/cancel/timeout races have a single winner.

These are compiler reservations and **host runtime policy tests**, not evidence
that general guest actor programs using these keywords execute in either
backend. Actual `owns`/`owner` declaration semantics are still unspecified on
main; current real ownership syntax is implicit by-value ownership plus
`rt borrow`, `rt borrow mut`, `rt take`, `rt copy` and `rt share`.

Existing draft branches requiring reconciliation include Java PRs #789/#765/
#760 (canonical with/using), #788/#784 (RW scheduler admission), #740
(ownership suspension), #790 (NIO), #584/#522 (legacy async-backed transport).
LLVM PRs #264/#259/#257/#255 cover related source/policy work. None was merged
or treated as production-ready merely because a focused draft test passed.

## Verification

- Java/Truffle on Temurin JDK 25: full Maven suite **1,895 tests, zero failures,
  zero errors, one existing skip**; packaging succeeded.
- Three additional real CLI policy tests verify strict file-mutation denial,
  Oreslang loopback setup/async close, and invalid/duplicate flags.
- Actual Oreslang HTTP tests exercise fragmentation, pipelining, CL/TE/duplicate
  Host rejection, malformed targets, overflow and head-size boundaries.
- LLVM 20 Release: **64 selected CTests passed**, including compiled native
  prefix fixtures, borrow/ownership gates, 24 unsupported I/O boundaries,
  state-loan policy and completion races.
- Four focused native tests passed ASan/UBSan using Apple Clang (completion, critical policy, scoped loan, typed scalar state). LLVM 20's sanitizer runtime spun during macOS startup before `main`; those processes were stopped and are not counted as passes.

Full LLVM build remains blocked on macOS by pre-existing `<threads.h>` test
requirements (`native_actor_arena_test.c`, `actor_owned_heap_lifecycle_test.c`).
The broader WASM ownership test additionally requires `wasm-ld`, unavailable
here; correcting Apple Clang selection exposed that missing linker. These
checks were not counted as passes. No GraalVM Native Image executable is
installed; JIT/AOT/native-image certification and performance claims remain
unverified. Test logs and source revisions are retained with the audit.

## Remaining release blockers, in order

1. Reconcile canonical critical/with/using AST, transitive effects, source
   lowering and single-flight runtime admission on current Java main. Reject
   read-to-write upgrades, unknown effect summaries and loan escape at every
   imported/aliased/callback boundary. Specify owns/owner before enabling them.
2. Replace NIO/Java transport plumbing with generation-safe native reactor
   handles, actor/task buffer leases, deadlines and exactly-once completion.
   Make listener creation asynchronous and prove one-carrier actor progress.
3. Implement capability-checked genuine async filesystem operations per target;
   unsupported metadata/regular-file operations must reject, never fall back to
   worker threads. Oreslang source owns path/error/API policy.
4. Replace legacy HTTP/stdio and remove the Java semantic duplicates only after
   native modules execute equivalent security, cancellation, saturation and
   teardown tests. TLS/DNS require their own nonblocking boundary proof.
5. Implement LLVM typed I/O registry and heap-safe continuation lowering before
   admitting transport APIs. Default to strict policy only after versioned
   migration; verify Native Image, actor isolation and both backend runtimes.
