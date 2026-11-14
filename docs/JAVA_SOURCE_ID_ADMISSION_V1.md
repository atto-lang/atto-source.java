# Java/Truffle source identity — independent admission v1

**HOST-JAVA METADATA ONLY. No claim of Atto guest instrumentation, Truffle tagged-node attachment, Native Image full guest AOT, or OTel export.**

The standalone Java `SourceIdentityV1` is purposely different from native LLVM's C++ table and the independent Rust/Cranelift compiler's registry admission. It uses Java 21 records and two `long` lanes for fixed-size 128-bit source identities. This is a cold-path verifier, not a heap-object builder for each log.

- Accepted: `rid_` / `sid_` + exactly 32 lowercase hexadecimal digits, nonzero, correct identity kind.
- Rejected: runtime OTel trace IDs substituted for source IDs; wrong source kind; malformed/uppercase/zero values; duplicated site identity or per-artifact descriptor index.
- `freezeValidated` publishes an immutable, bounded list; source locations cannot be inferred from mutable runtime thread state.
- This does not add frontend parsing of `@Tracing.RoutineId`, log-site registry matching, AST tags or Truffle `Instrumenter` event probes.

### Local test commands

~~~sh
javac --release 21 -Xlint:all -Werror src/main/java/dev/oreslang/runtime/SourceIdentityV1.java
mvn -B -ntp -Dtest=SourceIdentityV1Test test
~~~

The standalone class and a smoke harness compiled and ran under local `javac --release 21 -Xlint:all -Werror` with assertion checks and printed `JAVA_SOURCE_ID_HOST_METADATA_PASS`. The JUnit suite itself still needs independent Maven execution; compiler guest instrumentation tests are separate.

### Independent compiler implementation gates

1. Annotated source identifiers must bind to Java's independently resolved guest callable and logged expression, not raw source token hashes or JIT-generated IDs.
2. Tag per-callable Truffle AST/bytecode nodes with independent `SourceSection` provenance and logical site descriptors; maintain generation-safe mappings under hot load, inlining and deoptimization.
3. Compile static AOT probes into genuine guest code and prove runtime OFF→ON→OFF→ON activation; do not mislabel dormant probes as newly generated JIT machine code.
4. For late instrumentation, actually attach a trusted Truffle `Instrumenter` event listener to tagged guest nodes and show deopt/recompilation plus later entry, with bounded authorization and safe retirement.
5. Keep trace context in logical task/actor envelopes, not only OS TLS. Scope close/cancellation semantics remain separate gates.
6. Use OpenTelemetry fields for runtime `trace_id`/`span_id` and dedicated namespaced attributes for static `routine_id`/`site_id`.

See [AttoLang source-ID proposal #62](https://github.com/ores-truffle-oreslang/attolang-language-spec.md/pull/62), [common independent compiler contract #287](https://github.com/ores-truffle-oreslang/oreslang-common-source/pull/287), and existing [Java adaptive control plane #3035](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/3035).

## New bounded Java recording substrate (not guest compiler lowering)

`StaticSiteProbeV1` is a Java-owned fixed-capacity atomic receipt ring for
previously admitted `SourceIdentityV1.Site` descriptors, with an opt-in
package-private recording toggle. The disabled path returns before allocation
or context validation. Enabled events contain only static routine/site IDs and
validated 128-bit trace + 64-bit span identifiers (or both absent); arbitrary
guest log payloads are **not retained**. Eight concurrent publishers exercised
12,000 events into a 32-slot ring without blocking, with explicit bounded drop
accounting. The recorder performs no network I/O or exporter work.

The committed production class was compiled under JDK 21
`javac --release 21 -Xlint:all -Werror` and a standalone smoke harness printed
`JAVA_STATIC_SITE_PROBE_HOST_PASS: threads=8 events=12000`. This is
**host Java execution**, not a JUnit test invocation or a Truffle compiled
guest probe. A dedicated JUnit `StaticSiteProbeV1Test` is now included, with
enabled/disabled checks, invalid W3C context rejection, concurrent publishers
and receipt-drain tests. The JUnit suite remains pending exact-head Maven CI.

The remaining integration gate is to attach *the very same validated static
site* to an actual parsed, typechecked guest logging expression and invoke
the recorder from a compiler-lowered guest callsite. Do not install this
prototype as a guest-callable Java-interop API. The current parser does not
implement the proposed `log.info(...)` source annotation/automatic site-ID
lowering; no complete guest instrumentation is claimed.

