# AttoLang Java/GraalVM and .NET: static host imports, native binaries, and actor arenas

**Normative direction; exact-head guest parity remains unverified.** New shared
language behavior belongs in portable `.atto` source. Platform-specific calls
must be explicit, capability checked and linked before native publication.

## Separate the two import categories

| Target | Host import form | Build tool | Native executable |
| --- | --- | --- | --- |
| Java / GraalVM | `import class Math as JMath from "java:java.lang.Math";` (existing trusted Java host import syntax) | Precompiled Java JARs/Maven with registered reachability | `mvn -Pnative-aot -DskipTests package` |
| .NET / NativeAOT | `import dotnet "System.Math" as Math;` (experimental `Abs/Min/Max(long)` signatures on the structured CIL path) | Compile-time `System.Reflection.Metadata` resolution, pinned .NET 10 | `dotnet publish` NativeAOT |

Do not equate importing Java code on the JVM with Native Image supporting
runtime Java source compilation. The Java mixed-source `java { ... }` island
uses `javax.tools.JavaCompiler`, which is JIT/source-mode only today.
`JavaPrecompiledHostImportAotPolicyTest` now exercises an exact allowlisted
`java.lang.Long.sum(long,long)` JDK host call using the same `.atto` source
under JVM JIT and AOT-policy execution profiles, and asserts denied lookup
without an allowlist. **It is not a GraalVM Native Image executable proof.**
A full Native Image import and member-level capability proof is tracked in
[issue #2678](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/2678).

### New explicit Java member grant (manifest v0)

For the precompiled JDK `Long.sum(long,long)` fixture, the Java native
workflow uses both `--allow=JAVA_INTEROP` and the **method-scoped**
`--allow-host-method='java.lang.Long#sum(long,long)'` flag. Source syntax:

```atto
import class Long as JLong from "java:java.lang.Long";
pub fnc main(): void {
  stdio.println(JLong.sum(40, 2));
  return;
}
```

The v0 `SafeHostMemberManifest` admits only this exact JDK method and
signature. It refuses forged overloads and class-wide + method-scoped grants
for the same class. The class remains visible for explicitly permitted
lookup, but only the narrow method is exposed through Graal HostAccess.
This is **not a general Java/JAR method linker**. The existing
`--allow-host-class` remains trusted legacy class-wide interoperability;
it is broader and must not be silently treated as equivalent to a method
grant. No guest-directed runtime reflection, arbitrary package discovery or
constructor access is authorized by the v0 method grant. JNI/native access
and actor cross-heap ownership require separate reviews and permissions.
Native-image execution and reachability still require stepful CI receipts.
Native Image requires precompiled, statically reachable classes and explicit
registration. Classpath scanning, `URLClassLoader` plugin loading and arbitrary
reflection cannot be silently re-enabled. In the .NET backend, arbitrary
NuGet/assembly imports still require a trusted manifest and binding engine;
the `System.Math.Abs/Min/Max(Int64)` milestone is not a general library importer.

## NativeAOT does not mean zero GC

- A Graal Native Image contains compiled Java host/runtime code; its built-in
  managed Java heap and Truffle guest interpreter can still allocate. The
  normal `native-aot` profile is an AOT **host**, not proof that arbitrary
  `.atto` programs have been directly AOT-lowered.
- .NET NativeAOT produces native code but retains the .NET managed GC.
- AttoLang guest-owned mutable data should reside in **separate, bounded
  actor-native arenas** using unsigned 32-bit **actor-relative offsets** even
  on ARM64/x64 hosts. An offset is not a Java reference, CLR object reference,
  raw machine address, permission token or cross-actor pointer.
- The Java `ActorNativeArena.reserveActorOffset` now checks the aligned
  exclusive end against `2^32` **before** advancing its monotonic cursor.
  Host-only JUnit checks the last valid byte, wrap rejection, alignment failure,
  cursor rollback, and neighboring actor independence.
- Borrow checker `rt borrow`, `rt mut borrow`, `rt take` and `rt share`
  must enforce ownership at the typed guest boundary and across suspension.
  Neither Java nor .NET host references become Sendable merely by being
  obtained from an imported library. Lifetime, capabilities, actor generations,
  nonreentrancy and shutdown drain still apply.
- `critical` controls AttoLang state synchronization; it must never
  unconditionally authorize an arbitrary Java/.NET library to execute or share
  thread-unsafe mutable resources.

## Required independent release evidence

1. Real `.atto` parse/typecheck/borrow-check and compiler-generated guest
   execution, with negative moves, dangling loans, escaped host handles and
   forbidden dynamic imports rejected before emitted artifacts.
2. Exact source HEAD and Git tree, target OS/RID, toolchain version,
   **nonzero executed job steps**, hashes for generated native executables,
   stdout/stderr and exit codes. `steps=[]` or `null` means no evidence.
3. Native image/app executed on Linux and Windows (macOS when target toolchain
   available), with no managed/JIT fallback. Native Image `--build-info`
   proves host AOT artifact provenance, **not** compiled guest-code parity.
4. Actor restart/overflow/termination tests that show no cross-actor
   heap access, stale offset or outstanding lease, with accurate native byte
   accounting and cleanup.
5. Per-actor allocation and collection metrics for real guest workloads:
   native arena charge/refund; CLR/JVM managed allocated bytes, GC event
   counters, RSS and terminal cleanup. Count unknown GC counters as unknown.
   Epsilon GC can have **zero collections but positive growing allocations**;
   it cannot prove zero GC or leak freedom alone.

Useful existing Java evidence: `docs/GRAAL_EPSILON_NATIVE_ARENA_PROOF.md`
documents a previously executed **host-only** native Epsilon soak, explicitly
not full guest execution. `docs/NATIVE_DEPLOYMENT.md` documents the native
build flags and remaining dynamic-Java limitations. The .NET implementation is
tracked in `docs/DOTNET_STATIC_IMPORT_V0.md` on its own compiler branch.

## Immediate parity queue

After the .NET `System.Math.Abs(Int64)` path has executed in true NativeAOT:
pin a manifest of permitted Java/.NET static signatures; verify numeric and
exception behavior on both platforms; then add read-only primitives and
owned-foreign-resource handle lifetime tests. Delay allocations, reflection,
host callbacks, NuGet plugin discovery, async bridge and actor messaging until
per-method effect/ownership/capability contracts exist.
