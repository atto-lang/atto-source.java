# Native build capabilities

The deployment choice is made at build time:

| Build | Command | Default execution policy |
| --- | --- | --- |
| JVM | `mvn package` | `jit` |
| AOT-only native | `mvn -Pnative-aot -DskipTests package` | `aot` |
| Hybrid native | `mvn -Pnative-hybrid -DskipTests package` | `hybrid` |

Native builds require JAVA_HOME pointing to a GraalVM toolchain matching the
pinned Graal/Truffle SDK, plus the platform C toolchain. Temurin/OpenJDK alone do
not include native-image. The outputs are target/ores-aot and target/ores-hybrid.
Use `--build-info` to inspect the capability embedded in the executable.

`NativeBuildMode` is initialized while Native Image builds the executable. The
image-generation property `ores.native.build-mode=aot|hybrid` is mandatory. It
cannot be changed by setting a system property after compilation. An AOT image
rejects `--mode=jit` and `--mode=hybrid`; a hybrid image accepts `--mode=hybrid` or
`--mode=aot`. AOT execution policy disables engine compilation when the engine
supports it. JVM execution profiles remain available for policy testing, but do
not turn the JVM process into a native executable.

AOT-only uses Truffle's fallback runtime: the interpreter and reachable Java
runtime libraries are native code, without a guest JIT. **This does not lower
all Oreslang handlers to application-specific machine code at build time.**
Guest source is still parsed/typechecked and interpreted. Hybrid includes the
Truffle optimizing runtime; its throughput must be measured rather than assumed.

Native binaries need neither a JDK nor a JVM installation on their target. They
still depend on platform OS libraries. Our pthread carrier library is currently
shipped alongside the executable (`liboresthread.dylib` or `liboresthread.so`),
not statically linked. Pass `-Dores.thread.native.path=/absolute/path/to/library`
and `-Dores.runtime.carriers=native` to require that backend rather than permit
the development fallback. JNI reachability metadata is included in the runtime.

A distribution must bundle its source import graph or embed it as resources,
pin native libraries, include license notices, and be built per OS/architecture.
The REST demo supplies an application-specific native launcher that embeds the
fixed Ores route graph and extracts it to a private temporary directory. No
Python, Git, Maven, source checkout, or network dependency resolution is required
to run that distribution. Its runtime port/data-directory settings do not change
the build's compilation capability.

Java source islands require JVM mode and cannot be dynamically compiled in an
AOT/hybrid executable. Precompiled, explicitly registered interop needs its own
reachability metadata. A native build succeeding is not evidence that every
interop extension or untrusted isolate path works: run those deployment-specific
tests separately.

## Reproducible Native Image build and smoke

From the repository root, install Maven and a **GraalVM JDK 25 with Native Image**
matching the pinned Graal/Truffle dependencies (and the native platform C
compiler). Point `JAVA_HOME` at that GraalVM JDK; a stock OpenJDK cannot
produce the binary. Build and verify with:

```sh
sh scripts/build-and-verify-native-image.sh aot compressed32
sh scripts/build-and-verify-native-image.sh hybrid compressed32
# Optional, on a supported Native Image platform:
sh scripts/build-and-verify-native-image.sh aot native64
```

The script invokes the Maven native profile with `--no-fallback`, requires a
real OS-native executable at `target/ores-aot` or `target/ores-hybrid`,
checks the embedded build mode and managed reference layout, runs native CLI
source validation, executes `examples/together.ores` and the packaged stdlib
fixture `examples/native-core-library.ores` with Java build variables unset,
and checks that JVM JIT mode is rejected with exit code 2. A failed compilation
or fixture fails the command; a successful JVM `mvn package` alone does not count.
The main `ci.yml` workflow runs this exact gate for AOT compressed32, AOT
native64, and hybrid compressed32 before its larger source-level test matrix.

**Evidence requirements:** Treat Native Image support as *verified* only when
the exact source commit has a completed build **and** successful native binary
execution on a real runner. A queued, skipped, or rejected GitHub Actions job
(including jobs with zero executed steps) does not establish compatibility.
Native images are platform-specific (Linux, macOS, Windows); a successful Linux
run must not be represented as proof of a Windows or macOS artifact. Native
Image compilation of the Oreslang *host* is also not proof that every Oreslang
source feature has application-specific AOT codegen or that isolate/shared
library deployment has passed its separate runtime contract.
