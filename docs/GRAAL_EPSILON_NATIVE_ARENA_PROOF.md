# GraalVM Native Image Epsilon actor arena test — experimental

AttoLang/Oreslang requires full application-specific AOT on GraalVM and LLVM,
and a zero-collection Graal native deployment mode. **This is a narrower,
independently executed host-native memory test, not those finished requirements.**

## Actual independently executed evidence

- Oracle GraalVM Native Image **25.0.4**, JDK25 on Ubuntu x86-64.
- [Private exact-source Git blob run 42319638862](https://github.com/atto-lang/atto-common-source/actions/runs/42319638862) **SUCCESS**.
- Seven exact production/test source blobs from Java PR #2606 head
  `1147aed592f545c1ebe6e1f0f34f0480f96c2dae` were checked before compilation.
- Compiler output identified **Epsilon GC** and produced an approximately 14.29MiB Linux native executable, with no runtime JIT.
- Executed Java/C JNI **host** actor-arena workload:
  **102,400** native-backed state operations across **128** actor-arena
  retirements, zero outstanding native budget after retirement, stale
  region rejection, outstanding read-lease protection and surviving-neighbor
  state.
- Runtime collector beans observable; **0** GC collections in this run.
- Sampled resident memory: **11036 KiB start**, **13884 KiB sampled peak**,
  **13916 KiB end**; sampled peak below the 256MiB test budget.
  An end sample above the sampled peak reflects sampling intervals, not proof
  of a leak; these counters do not provide a process lifetime bound.

## What is not proven

Epsilon **does not reclaim managed Java heap allocations**. Zero observed
collections is not zero allocations or proof of indefinitely stable memory.
The test uses a small native arena/actor facade workload, not the complete
Truffle/AttoLang runtime. The compiler's **build-time** GC counters are not
runtime GC events. No actual `.atto` source was statically lowered into
application-specific Graal Native Image machine code.

Independent tenant security isolation, complete actor supervision, formal
ownership/typecheck coverage, 100,000 tenant restarts, hard real-time claims,
and five-backend parity remain **NOT_EXECUTED** here. The established Graal/Truffle
SDK pin **25.3.4.1** has not been revalidated by this specific 25.0.4 host test.

The test's runtime invokes a JNI load from an unnamed module. GraalVM JDK25
currently emits a restricted-method warning. The verifier accepts *only the
exact expected warning text* (and a bounded number of terminal blank lines),
and fails for unrelated stderr. This is a test harness exception, not production
authorization of dynamically loaded libraries.

## Run

Install GraalVM JDK25 with `native-image`, a C compiler and `file`.
Checkout the exact Git commit to test, then:

```sh
export JAVA_HOME=/path/to/graalvm-25
export ATTO_EXPECTED_SOURCE_HEAD_SHA="$(git rev-parse HEAD)"
bash scripts/verify-graal-epsilon-native-arena.sh
```

CI validates the PR's exact Git source head, then builds/runs a real native
executable with `--no-fallback --gc=epsilon`. This profile is an **opt-in
experimental host test**; no default production GC setting is changed.
