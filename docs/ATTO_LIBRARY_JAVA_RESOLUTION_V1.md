# Canonical AttoLang Java library admission (v1 bridge)

Current main already contains layered core/alloc/std registries and packaged
canonical .atto modules. Preserve those registries and their existing resource
and unit identities. The optional atto.runtime.actor namespace remains reserved
against ambient fallback; this is not an actor runtime implementation.

This change preserves the existing explicit suffix policy: migrated core and
std modules share their registered .atto/.ores identities, while testing remains
a legacy .ores packaged source with an extensionless selector. No broad suffix
probing or new alias is introduced. Empty path segments, including trailing
separators before a suffix or at the end, are rejected rather than normalized
away.

The JUnit lane checks the real linked numeric guest (expected stdout 42),
registered alias equality and negative namespace/path admission. Workflow actions
are pinned, and the required Surefire report must be nonempty and unskipped.
Full compiler execution remains unverified until the exact head runs. No
NativeAOT, freestanding, actor runtime or cross-backend parity claim.
