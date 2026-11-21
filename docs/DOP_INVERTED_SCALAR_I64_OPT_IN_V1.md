# AttoLang inverted struct: bounded compiler-verified i64 source slice

**Status:** compiler-verified Java **host-reference executor only**. The ordinary
Java/GraalVM guest compiler remains fail-closed. This is not a Truffle call
target, GraalVM native-image, LLVM output, JVM bytecode generation, actor
ownership proof or SIMD/AoSoA/SoAoS backend.

## Restricted source and admission

The production parser has an explicit opt-in entry point
`Parser.parseDopInvertedScalarV1`. It parses the canonical
`InvertedArray<Row>.with_capacity(N)` factory into a dedicated typed AST shape.
The only consumer is `OresCompiler.compileDopInvertedScalarI64(source)`, which
constructs a closed `DopInvertedScalarCompilerV1.Plan`. Ordinary
`Parser.parse`, `OresCompiler.parseAndTypeCheck`, `TypeChecker`, and
`LinkedProgramRunner` continue to reject unsupported inverted execution.

Accepted source is exactly one nominal two-`int` inverted struct and one
synchronous `fnc main(): int` with no inputs, generics, imports, user effects,
extra declarations, actor lifecycle or annotations. The body must start with
`let rows = InvertedArray<Row>.with_capacity(N);`, with `1 <= N <= 256`,
then contain no more than 1024 owner operations (including the terminal
return), consisting solely of:

- `rows.push(i64Literal, i64Literal);`
- `rows.set(denseIndex, mutableFieldName, i64Literal);`
- `rows.erase_swap(denseIndex);`
- a terminal `return rows.get(denseIndex, fieldName);`

All operands must be static signed i64 literals or declared static field names.
No arbitrary functions, callbacks, host reflection, source imports, aliasing,
operator overloads, suspension, layout conversion or silent backend fallback.
Writes to fields lacking `let` fail during plan construction.

This **shared source parity envelope** is intentionally narrower than the
underlying Java-only `InvertedSchemaStorageBridge` host primitive:
the production LLVM inverted verifier currently admits row capacity
`[1, 256]` and operation count `[1, 1024]`. The opt-in Java compiler
therefore rejects capacity `0`, `257` and higher, and operation stream
length `1025` or higher at compilation. Broader Java host backing stores
must never be described as compiled Java/LLVM parity.

The plan has immutable typed operations and a private constructor. Its
`executeHostReference()` creates a new bounded
`InvertedSchemaStorageBridge`/private `InvertedColumnStore`, then executes
only checked operations and closes the owner in a finally-equivalent scope.
Physical columns are independent byte-backed arrays, **not AOT/zero-GC
proof**. Dense indices are logical positions; they are **not** stable
generation-fenced row handles. The store-issued handles internally enforce
generation fencing across erase-swap and recycling.

## Cross-backend exact source fixtures

The following source paths are committed byte-identically in the Java and
LLVM repositories:

| Source | Git blob SHA | Intended behavior |
| --- | --- | --- |
| `contracts/dop-inverted-v1/column-write-42.atto` | `766b8ef482b5d18da33e13a37ce0225f3fc0d744` | Return/exit `42` |
| `contracts/dop-inverted-v1/immutable-write-rejected.atto` | `8a316fd438fdfed1631e11c5f2741d3fba578285` | Reject immutable field write before execution |

The older byte-identical `swap-reuse.atto` and `removed-index-trap.atto`
fixtures are also exercised. LLVM's native script now **requires**, but has
not yet executed, 12 positive O0/O2 native returns, four real bounds-signal
checks, and six source negatives. Java has authored JUnit host-reference
plan/bridge tests but no executed native or guest parity receipts yet.

## Experimental isolated Truffle RootCallTarget (not general guest admission)

The narrow source compiler now provides the explicit API
`OresCompiler.compileDopInvertedScalarI64TruffleRoot(source)`. It parses
using `Parser.parseDopInvertedScalarV1` and the **same closed verified plan**
before creating `DopInvertedScalarTruffleRootV1`. The returned
`RootCallTarget` has four explicit typed operation-node classes
(`PushNode`, `WriteNode`, `EraseSwapNode`, `ReadNode`); no source text,
arbitrary expressions, lexical closures, function values, external type
dispatch or unchecked ASTs are accepted by those children.

Each `call()` owns a fresh `InvertedSchemaStorageBridge` (separate two-i64
SoA columns and storage-issued generation-fenced handles). That owner is
closed on both successful returns and errors. The call target takes **zero**
arguments; no actor, channel, coroutine, borrow across suspension or foreign
source namespace can be injected. The same target may be called twice, but
the underlying mutable owner is never reused between calls.

**Evidence classification:** this is real isolated Truffle
`RootCallTarget` execution of a **production-parsed restricted verified
plan**, *backed by Java host storage*. It is **not**
`OresLanguage.parse(ParsingRequest)` or the full
`OresEvalRootNode` guest pipeline, is not permitted through the ordinary
`TypeChecker`/language parser, and is not Graal native-image, zero-GC,
tenant-safe, actor-safe, AOT, or general compiler parity. Do **not** use this
as a guest-parity receipt. The original host-reference `Plan.executeHostReference`
remains for differential checks. The dedicated JUnit class
`DopInvertedScalarTruffleRootV1Test` has eight authored cases (including 64 independently compiled roots and
320 repeated root calls) and is
included in the exact-head CI gate; no hosted execution pass is yet proven.

## Host-authorized one-shot `OresLanguage` Polyglot integration

An **explicit trusted Java host** may now enter
`OresLanguage.openVerifiedDopSource(codeUnitId, exactSourceText)`, then
synchronously evaluate or parse the corresponding source through the ordinary
registered `ores` language ID. The scope validates and freezes the restricted
two-i64 compiler plan **before** publishing a private thread-local token;
`OresLanguage.parse` consumes that token once, checks the normalized code
unit identity and raw source bytes, and constructs the typed Truffle root
through `DopInvertedScalarTruffleRootV1.lowerForVerifiedSource`.
Without the scope, ordinary `Context.eval` still rejects these inverted
programs through the existing parser and TypeChecker.

The Truffle execution root **revalidates the live private token on every
invocation**, including calls through a cached source or a retained
`Context.parse` result. `OresLanguage.parse` resolves the active
`OresContext` and binds its immutable UUID to the new typed root **during
parsing, before the root is published**. No first caller is allowed to choose
the root's context identity. Reusing the same compiled root after the scope
closes, borrowing its root under a new lease, invoking it on a different host
thread, or crossing to another polyglot context are all denied.
Adversarial-isolate admission is rejected at parse time and checked again
at each execution. Mismatched
source or source name consumes the token permanently. Nested/interleaved
`PrecheckedProgramScope` and DOP leases are denied in both directions.
Adversarial/untrusted tenant execution remains denied, even with a trusted
host token. Actor turns, continuations, cancellation, generation-crossing
state and scheduler handoff do not gain authority from this pathway.

Example trusted-host pattern, using a newly built source to avoid a stale
Truffle parsed-source cache hit:

```java
try (var permit = OresLanguage.openVerifiedDopSource(
        "verified.atto", trustedSourceText)) {
    var source = org.graalvm.polyglot.Source
            .newBuilder(OresLanguage.ID, trustedSourceText, "verified.atto")
            .mimeType(OresLanguage.MIME_TYPE)
            .cached(false)
            .build();
    long result = polyglotContext.eval(source).asLong();
}
```

**Evidence limitations:** this is an opt-in, trusted-host, same-thread,
single-parse source lease for an existing restricted `int/i64` language
subset; it does **not** enable arbitrary user-provided inverted source,
general `OresLanguage` AST/ownership analysis, an actor runtime, native AOT,
an untrusted tenant, or Java/LLVM guest parity. No compiler-backed
GraalVM/polyglot JUnit run is claimed until actual source-org CI executes.
The dedicated `OresLanguageVerifiedDopLeaseTest` contains thirteen
regression methods, including 32 unique source leases, 256 additional
signed-i64 sources with 1,024 repeated cached `Context.eval` invocations,
post-close rejection for each source, actual polyglot source-name mismatch,
retained-value cache replay, thread ownership, lease coexistence and
fail-closed admission; they are authored but not executed.

## Promotion gates

1. Run the exact-head Java JUnit suite and LLVM production compiler/CTest
   with nonzero executed GitHub Actions job steps and independently verified
   immutable HEAD/tree identities. Zero-step workflows are NOT_EXECUTED.
2. Independently review static effect/ownership admission, integer overflow,
   row lifetime, store capacity, failure cleanup and LLVM/Java shared blobs.
3. Integrate verified operations into **real Truffle/Graal guest lowering**
   through a typed compiler-owned MIR/ABI. Do not simply dispatch host
   source snippets or relax ordinary TypeChecker ahead of this step.
4. Prove backend-native physical columns, negative mutation behavior and
   generation fencing against the same source oracle.
5. Add separate support for general field kinds, actor-owned regions,
   structured concurrency, AOT, AoSoA/SoAoS and SIMD only after the small
   executable source slice is independently qualified.
