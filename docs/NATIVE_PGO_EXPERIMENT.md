# Native Image PGO experiment — draft / not a speedup claim

Tracking issue: [#1254](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/1254).
Graal Native Image AOT compiles the **host Java/Truffle interpreter** ahead of time.
It does **not** compile arbitrary Oreslang guest handlers into application-specific
native code. JVM JIT, native-aot, and native-hybrid remain distinct execution modes.

Preflight of the **exact executable** (absolute path mandatory):

```sh
bash scripts/native-pgo-preflight.sh "$JAVA_HOME/bin/native-image"
bash scripts/test-native-pgo-preflight.sh
```

The script returns 78 / UNSUPPORTED on unavailable help or PGO flags; on a
candidate it deliberately prints `CLI_CANDIDATE_ONLY`. CLI help is not proof
that the installed edition/license actually supports an instrument/train/rebuild
cycle, nor that a GraalVM dependency/JDK version matches this repository.

**Real experiment to implement:** pin GraalVM/Native Image build and CLI flags;
produce a native-aot **instrumented** host image; run a trusted deterministic
training corpus; record and hash a nonempty `.iprof` with source/toolchain SHA;
rebuild a separate profile-backed native-aot image; run held-out correctness,
isolation/admission-negative tests; measure 5+ repetitions and compare JVM JIT,
native-aot baseline, PGO-native-aot and native-hybrid separately (p50/p95/p99,
throughput, RSS, build time, executable size). Fail closed when unavailable.
Never silently change the build mode or call a mock probe a completed PGO run.
