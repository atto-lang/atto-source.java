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


## Java frontend exact routine binding (candidate, not instrumented execution)

`dev.oreslang.compiler.SourceRoutineBindingV1` now accepts previously checked
routine-ID entries and validates them against the actual Java compiler's
`Ast.Program` / module declarations. It returns an immutable
`IdentityHashMap`-backed mapping from each parsed `Ast.FunctionDecl` to its
static source ID; comparing AST objects by identity prevents accidentally
collapsing structurally identical independent declarations. It denies stale
selectors, duplicate source IDs, unsupported actor/generator/async/generic
coverage and missing routines.

`SourceRoutineBindingV1Test` invokes the real Java `Parser.parse` on a
minimal `.atto`-style function body, tests exact frontend binding, and rejects
wrong-kind, stale and duplicate entries. **Execution remains pending Maven CI.**
This binding is not yet called by the production parser-to-evaluator pipeline
and cannot claim `log.info` events, Truffle tags, recompiled guest machine code,
stable post-optimization debug info or an authenticated deployed artifact.
Linking that immutable mapping to actual guest log-expression AST nodes and
the bounded recorder is the next independently tested Java compiler gate.


## Literal log-site AST admission candidate (2026-11-14)

`SourceLogCallsiteBindingV1` works with actual Java `Ast.Program` /
`Ast.CallExpr` nodes rather than parsing text a second time. It assigns
registry-checked `rid_` and `sid_` values to the **identity of the original
parsed callsite AST node**, only for direct
`log.info("literal")` expression statements in currently supported
synchronous functions and non-suspending `using telemetry.span` scopes.

The strict v1 binder denies dynamic arguments, duplicate same-literal
sites, unknown/missing IDs, stale rows, async/actor/generator statements
and unsafe Unicode/control text. Valid UTF-16 surrogate pairs represent
UTF-8 messages and are accepted; malformed pairs are rejected. The companion
`SourceLogCallsiteBindingV1Test` exercises the real Java parser and source
binding with positive and negative cases.

**This is still a compiler admission candidate, NOT emitted/observed Java
guest telemetry.** The source TypeChecker and production evaluator do not yet
consume the site binding. The existing `StaticSiteProbeV1` ring was separately
compiled and tested locally, but no parsed `log.info` statement has yet
executed through that ring. Maven/JUnit and guest Truffle node instrumentation
remain pending a functional, authorized runner. No site metadata is promoted
to runtime authority merely because the parser can produce an AST.



### Exact-function identity admission hardening

The log-site binder now requires the separate `SourceRoutineBindingV1.bind`
table for the **same AST object identities**: matching a routine ID only
against the site manifest is not enough. A foreign or structurally equivalent
`FunctionDecl` object, forged site-to-routine correspondence, surplus
verified routine entry, unsupported dynamic nested return expression, or
missing function proof is rejected before any immutable site table is
returned. Corresponding JUnit regressions were committed.

These new Java AST binder changes are **unexecuted**: the local host
`StaticSiteProbeV1` smoke test does not exercise the actual parser,
typechecker or newly changed binder. Require JDK21 Maven/JUnit and the
full guest logging fixture before promoting this PR.


### Guest-owned logging name shadowing denied

The strict Java frontend site binder now denies a verified function that has
a parameter named `log` or a `using telemetry.span` alias named `log`. Without
that gate, a guest-owned name could be confused with the compiler-intrinsic
`log.info` surface, and a static site manifest could be incorrectly attached.
Companion JUnit negative fixtures are committed but have **not** been executed
under the full Maven/Java compiler in the currently available environment.
No guest logging side effect or Truffle probe is claimed.


### Exact production Java binder cold-path compilation (local host evidence)

The exact Git blobs `5eb0724239b4027d76a4a6a82336f21f033c6ceb`
(`SourceRoutineBindingV1.java`),
`bdd3bec3f0e65344b4e0e3deee7cbc114255f998`
(`SourceLogCallsiteBindingV1.java`) and
`fc2c579220437d5ab0c409be266123eb550a939f`
(`SourceIdentityV1.java`) were compiled under JDK21
`javac --release 21 -Xlint:all -Werror` against a **minimal synthetic
AST API fixture**, then executed using a positive two-site/one-routine
case and eight fail-closed cases. Result:
`JAVA_PRODUCTION_STATIC_SITE_BINDER_HOST_PASS:negatives=8`.

This proves the committed production Java binder source compiles and its
source-identity admission logic executes in a bounded host-level fixture.
**The AST fixture is synthetic**, not the repository's actual Java
`Parser.parse` AST or Maven/JUnit execution. No Truffle guest logging node
or GraalVM AOT/JIT probe has been run. The exact project parser and
`SourceLogCallsiteBindingV1Test` must still pass on an approved real runner.

### Follow-up audit: public routine-ID proof boundary

The `SourceLogCallsiteBindingV1.bind` **public API** now requires
`List<SourceRoutineBindingV1.Entry>` and invokes the independent
`SourceRoutineBindingV1.bind` itself. It no longer accepts an arbitrary
caller-constructed `Map<Ast.FunctionDecl, Id>` as evidence of verified
routine identity. Internally, the binder uses the resulting unmodifiable
`IdentityHashMap` for O(1) exact AST-reference lookup.

The **exact Git blob** `97ee5aa236eda10f62e095ea15098ba8f8c9b8b1`
for the revised site binder, plus exact routine binder
`5eb0724239b4027d76a4a6a82336f21f033c6ceb` and source ID parser
`fc2c579220437d5ab0c409be266123eb550a939f`,
were compiled with `javac --release 21 -Xlint:all -Werror`. A
synthetic-AST host harness then passed a two-site positive fixture and
**16** rejection checks (`JAVA_EXACT_GIT_BLOBS_BINDER_HOST_PASS:negative=16`).
The actual repository Parser, Maven/JUnit, Truffle guest-node and JIT
tests have **not** executed and remain mandatory before merge.


### 2026-11-14 continued audit: span names, alias scope and Java-only 10k proof

`SourceLogCallsiteBindingV1` now denies oversized (greater than 256
UTF-8 bytes), C0/C1-control or malformed-surrogate lexical span names,
active nested span alias reuse, and span aliases shadowing formal
parameters. The corresponding Java JUnit negative fixtures are
committed but the real parser/JUnit pipeline has **not executed**.

The independent Java production validator
`SourceTelemetryLiteralV1.java` (exact Git blob
`876a7489e14f5dbe63c8a7a3b2c2f73dd9edf2bd`) and its
standalone JDK21 10,000-input test (exact Git blob
`0cc067349a0699ec65f888de1b3d506300cef017`) passed locally
with `javac --release 21 -Xlint:all -Werror`. This covers
96-byte log / 256-byte span limits, Unicode C1 controls, valid emoji,
invalid UTF-16 surrogates and deterministic random UTF-16 input.
`SourceTelemetryLiteralV1Test` invokes that standalone test from
JUnit once a real Maven runner is available.

The host-level literal test does not prove that Java's actual
`Parser.parse`, Truffle guest nodes, JIT, NativeAOT or actor-owned
heap/stack tracing executed.
