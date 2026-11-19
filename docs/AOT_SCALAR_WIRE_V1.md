# AttoLang AOT reflection-derived JSON / MessagePack scalar wire v1

**Status:** source-local Java/GraalVM compiler-side prototype, **NOT full native AOT
or guest execution parity**. See draft PR #2473 and
[conformance draft #318](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/318).

The source-checked static type query derives a bounded descriptor from
`OresCompiler.parseAndTypeCheck` and the ATTOREF1 table encoder/reader.
`AotScalarWireV1` binds a typed decoder schema to that exact descriptor.

Example of the current compiler-side API:

```java
var schema = OresCompiler.compileAotScalarWireSchemaV1(
    attoSource, "pkg@revision1", "type_of<WirePayload>()");
var json = schema.decodeJsonUtf8(jsonBytes);
var msgpack = schema.decodeMessagePack(msgpackBytes);
var schemaDigest = schema.schemaSha256Hex();
schema.requireSchemaFingerprint(schemaDigest);
```

Both decoded outputs are immutable field-ordered scalar slots
(`Long`, `Boolean`, `String`), **not guest class instances**. To add real
deserialization to AttoLang, backends must generate owned constructors and
bind these slots to checked field IDs with rollback/tenant fences.

v1 supports at most 256 **required** public fields and exactly one root
JSON object or MessagePack map. Values are signed i64 (`int`/`i64`),
`bool`, and `string`. No implicit coercions, arbitrary methods, nested
objects, null, `Option`, floats, `uint64`, defaults, extension types,
user callbacks, custom setters or `@FromJson` aliases. Annotated public
fields now **fail closed** because the v1 descriptor format does not carry
wire-name mappings. A separately versioned descriptor must enable those.

All inputs are limited to 65,536 bytes, text values to 4,096 UTF-8 bytes;
JSON escaping/surrogates and MessagePack UTF-8 are validated. Duplicate,
unknown, missing, private or malformed fields fail without returning a
partially initialized guest object. No Java reflection/classpath scan,
runtime bytecode generation, runtime generic specialization or dependency
on a reflection-config.json registry is used by the codec.

**Schema compatibility:** the nominal type ID is *not* a field-layout digest.
The codec separately computes domain-separated SHA-256 of the full canonical
ATTOREF1 table and offers `decodeJsonWithFingerprint` and
`decodeMessagePackWithFingerprint` so network transports can fence an
unexpected schema **before** reading a payload. Hash equality does not
authenticate the source/package, tenant, or actor generation.

## Tests

```bash
mvn -q -Dtest=AotScalarWireV1Test,AotScalarWireV1MatrixTest test
bash scripts/verify-aot-scalar-wire-host-linkage.sh target/classes
bash scripts/run-aot-scalar-wire-native-image-smoke.sh
```

The two scalar-wire JUnit test classes now contain **49 authored tests**
(28 original + 21 matrix), including a deterministic 12,000-input
malformed JSON/MessagePack fuzz pass, complete 6-way field-order permutations,
signed i64 boundaries, UTF-8 and MessagePack framing mutations, schema
fingerprint fences, and concurrent readers. A **local JDK 21** isolated harness
compiled the exact committed `AotScalarWireV1.java` source (verified by its
Git SHA-1 blob) with stand-in compiler-query/descriptor-reader inputs:
12,143 assertions passed; seven compiled codec class files passed a
host-level forbidden-runtime-reflection linkage audit. **Neither proves
production Maven/JUnit, full descriptor integration, or native execution.**

The matrix additions were also exercised against the exact latest
committed codec source with stand-in schema inputs: **5,376 additional
isolated checks PASS**. Including the rerun original **12,143**, there are
**17,519 isolated codec checks PASS**. These are not Maven/JUnit runs of
the complete production repository; host/native/guest parity still needs
separate executed receipts. The source's rejected null scalar collection
now yields `IllegalArgumentException` rather than raw `NullPointerException`.

The platform conformance PR includes **23 portable golden wire vectors**
(8 positive and 15 negative) and 36 structural contract/fixture/text tests.
None of those fixtures is itself a cross-backend execution receipt.

A third JUnit class, `AotScalarWireV1GoldenVectorsTest`, adds
**12 authored methods**, bringing the focused scalar-wire suite to
**61 JUnit methods**. Its source-local text fixture has exactly the same
Git blob (`f8cbaa59d1095da52918c06c8acf520bd7202708`) as the
conformance repository's portable fixture. A dedicated
`.github/workflows/aot-scalar-wire-qualification.yml` executes all three
classes, the host linkage audit and a `--no-fallback` GraalVM Native Image
smoke once GitHub admits a runner. Its first checked runs
[41766398794](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41766398794)
and [41766390865](https://github.com/ores-truffle-oreslang/oreslang-source.java/actions/runs/41766390865)
failed with **zero executed steps**, so the 61 JUnit tests and native
build are **NOT_EXECUTED**, not failed tests.

The conformance draft now has **36 structural tests** (22 contract,
8 JSON fixtures, 6 portable text parity tests); these passed with a
V8 assertion shim, not hosted Node. The source organization has not
executed the production Maven or native build, and cross-organization
private source mirroring remains unapproved.

The native-image script is intentionally **fail closed** when Maven or GraalVM
Native Image are missing, insists on `--no-fallback`, records Git head/tree
and binary SHA-256, and prints **HOST** evidence only. Native-code generation
for an actual AttoLang `.atto` guest, linker retention and independent
LLVM/.NET/Wasm/Forge compiled guest runs are still required before
`aot.native_execution` or wire parity cases can be promoted.

Private-source test carriers remain governed by source `main` protection,
the approved carrier allowlist, and independent exact-tree transfer review
([security issue #2490](https://github.com/ores-truffle-oreslang/oreslang-source.java/issues/2490)).
No alternate-org private source transfer is authorized from a successful
source-free runner canary alone.
