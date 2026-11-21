# Host class imports: inert linking and execution-time resolution (batch 13)

Status: **draft implementation, not yet verified by exact-head JDK25/Graal CI**.

A `java:` import may name an allowlisted JVM class, but parsing,
indexing, source linking and cyclic source-graph construction must not perform
`OresContext.lookupHostSymbol` or enumerate host member invocability.
Even a lookup nominally intended to be harmless can have provider- or
version-dependent Java class-initialization side effects.

The evaluator now records only a class name and a context-local lazy holder.
The first explicit call, static field/member read or constructor creation
checks `JAVA_INTEROP` and resolves its host symbol once per evaluator.
`import fnc f` defers `isMemberInvocable` until the invocation, then fails
closed when `f` does not exist. An imported function value alone grants no
permission to resolve the host class. Namespace alias façades share a lazy
holder and never imply constructor capability.

The host class name remains validated and class access remains constrained by
`IsolatePolicy.restrictedContextBuilder(..., allowedHostClasses)`.
Resolution never grants an untrusted actor host permissions; the runtime
capability check remains in the lazy resolver even if another call site skips
the usual upfront check.

**Important boundary:** This does not establish that GraalVM
`lookupHostSymbol` itself is initialization-free; instead it no longer runs
during inert linking. Exact-head Graal tests must verify host static
initialization counters, class-path import cycles and denied capabilities.
Cross-context races, unexpected host reflection side effects and Java Native
Image/AOT embedding require additional validation.
