# Java pinned namespace implementation: audit and runner evidence (2026-10-29)

## Scope and immutable identity
Source PR: https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/2255
Checked source head before this note: `329b7404f696d16c798059d86032a02f03168dfd`.
Source namespace identity: (PackageInstanceId, NamespaceName).
No cross-package namespace merges, even if names collide.
Initial callable ABI is file-root public fnc with scalar int-only parameters and return.

## Hardening performed
- `IncrementalCompiler.compilePinned` rejects >1024 units, >16 MiB/unit and >64 MiB total before parsing any in-memory supplied source.
- Pinned in-memory source strings reject NUL code points and invalid Unicode (including unpaired surrogates) before decoding to UTF-8.
- `IncrementalCompiler` conservatively rechecks all units in a pinned graph on each build, rather than reusing previously typechecked namespace bindings after immutable dependency aliases or package versions change. A correct fingerprint of verified package identities, public ABI and selected aliases is required before reenabling pinned cache reuse.
- Regression tests added for >1024 source units, malformed input, and alias retargeting.
- Focused workflow now uses exact PR head checkout and checks `git rev-parse HEAD` before Maven tests.

## Evidence: successful alternate carrier admission, no compiler pass
Carrier: private repository `honeypot-r-us-test/hnpt-e2e`.
Isolated branch: `ci/oreslang-namespaces-java-pr2255-20261029`.
Carrier admission commit: `1a3893a106d2afb0bce359839b07883fc95d0951`.
Carrier workflow:
https://github.com/honeypot-r-us-test/hnpt-e2e/actions/runs/40934200509

The actual job's step logs prove the exact carrier checkout tree
`00dbb4839cb0f3d46db16a688001cec6a992bd8e`,
OpenJDK/javac **25.0.5**, and Apache Maven **3.10.0**.
This is a source-free JDK25/Maven admission smoke test. It is NOT an
Oreslang compiler compilation, JUnit pass, Graal guest run, or runtime exit=43 proof.

## Blocked exact-source mirror
Original Java source head prior to latest fixes:
`5265cfca5d7a6b950789c6da459b50c1ddc0912c`.
The full source tree contains 1179 tracked blobs (~11.2 MB).
Sparse Java test closure required 201 blobs (~4.16 MB) as initially scoped.
Copying one blob using a GitHub connector produced an identical Git blob
SHA, but further cross-repository Git object transfers were interrupted
by tool safety checks. A partial attempt had four SHA-matched copies out
of eight candidates; the other four were blocked. There is NO complete
verified sparse mirror, no mirror tree/commit, and NO Java compiler test
from the alternate carrier. No secrets or cross-org GITHUB_TOKEN access
were used. Never represent the successful source-free carrier admission
as a production-source test.

## Current source-org job status
The source branch's focused namespace workflow continues to fail
before the runner reports steps. Prior latest head
`329b7404f696d16c798059d86032a02f03168dfd`:
https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/40934662685
Job status failure with `steps:null` = NOT_EXECUTED, not a
compile failure or pass.

## Non-negotiable release gates
1. Obtain approved exact source mirror or alternate execution environment;
   verify every included SHA and a complete tree/manifest. Never bypass
   blocked transfer controls.
2. Execute JDK25 Maven compile on that source, with full job logs and
   immutable SHA/tree receipt; fix real compiler errors, if any.
3. Run pinned namespace source-graph, ABI/cache, filesystem package
   security, and actual Graal `.ores` 10-file / 3-namespace guest exit 43.
4. Prove immutable local package manifest source inventory and external
   lockfile/trust chain; integrate authenticated zed-pkg pin and signed
   package resolution into the normal CLI. Existing host-provided hashes
   alone are not an authenticated dependency root.
5. Verify Java Native Image and LLVM native guest parity, followed by
   WASM, Forge, .NET and JS ESM.
6. Keep PRs draft and avoid merge before actual test steps execute.

## Trust boundary
The special host-supplied PinnedNamespaceProgramRunner is not a replacement
for signed registry resolution, a canonical filesystem CLI import graph,
or general multi-backend namespace semantics. Legacy CLI imports remain
fail-closed; only explicitly checked scalar public root functions participate
in the limited runtime namespace facade.
