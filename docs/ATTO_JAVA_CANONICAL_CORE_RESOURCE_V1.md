# Java/GraalVM canonical Atto Core source migration

The **active** compiler-owned source resource is now
`src/main/resources/dev/atto/core/numeric.atto`, byte-identical with main's
current numeric module (Git blob `babf9cd1b3ffa1e6878315f2391f1b1ea6cdcc1a`, which
since #2641 includes the named Integer.rem/mod migration shims; the earlier
cross-backend blob `5ced991f1d49b3c2a60fdd72b9003c0a4d18cd72` predates them).
Legacy `dev/oreslang/core/numeric.ores` stays packaged as a byte-identical
compatibility snapshot; it is **not** the runtime module selected by
`CoreLibrarySources.oresCoreSource`.

Canonical `atto.core/numeric[.atto]` and legacy
`ores.core/numeric[.ores]` imports resolve to the same checked-in
`.atto` bytes and one historical `@ores.core/numeric.ores` unit ID
to avoid breaking initialization/linker identity. The source file name
reported to the parser is now `numeric.atto`.

The existing Java/Truffle JUnit guest in predecessor PR #2581 actually
parses/links/executes `.atto` source; the new JUnit verifies source bytes,
Git blob and alias identity independently. Exact-head CI must execute both
and archive both JUnit reports. A zero-step workflow is not executable
guest evidence. Future migration must build canonical Alloc and Std
sources, plus enforce capability-closure instead of reserving names only.
