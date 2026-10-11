# RFC: nominal enums, typed variants, actor-message domains, and portable identity

**Status:** proposed design only (2026-10-11). **No parser, type checker, evaluator, LLVM, serialization, or actor runtime support is asserted by this document.** Do not use a docs-only PR as evidence of feature parity.

## Design decision

Keep **values**, **types**, and **matching** separate:

1. `define pattern P as TypeA | TypeB | ... end` declares a **type-only**, transparent, statically-closed domain of allowed shapes. It is not an instance, constructor, enum, or enum namespace. The existing `type T = A | B | C;` remains the ordinary type-alias form; `define pattern` retains declaration-kind metadata for domain restrictions, diagnostics and actor-message admission.
2. `define enum E as ... end` declares a closed **nominal type and a namespace of value constructors/constants**. The enum declaration is not itself a runtime value; `E.Case` is a value (or a constructor of values). A variant may carry no payload or a statically typed payload.
3. Dependency is strictly **pattern -> enum**, never **enum -> pattern**. Patterns may contain enum *types*, not arbitrary evaluated expressions. Enum case payload types cannot reference any named `pattern` (including through type aliases, generic arguments or transitive dependencies). Reject direct/indirect cycles.
4. `match` inspects values using patterns. It is not a declaration kind. The result of a value-producing match is inferred or optionally constrained using `match<R>`; neither a named pattern nor individual match arms require return-type annotations. `do match` produces no value.

These choices are compatible with the direction of [TYPEOF_AND_TYPED_MATCH.md](TYPEOF_AND_TYPED_MATCH.md) and the planned canonical `match ... over / on ... / end` forms. Existing grammar/docs include older `match` spellings. **Reconcile the active match PRs and parser grammar before enabling new source syntax.** Do not create a parallel expression-match AST.

## Proposed surface syntax (NOT supported yet)

```ores
define enum Status as
  Pending = "pending"
  Running = "running"
  Complete = "complete"
end

define enum WorkerMessage as
  Start(job_id: string)
  Progress(done: int, total: int)
  Finished(result: string)
  Stop
end

define pattern Shapes as
    Tuple[bool, string]
  | string
  | FixedArray[string, string, string]
  | FixedList[bool, bool]
  | Status
end

// The enum type contributes all cases of Status to Shapes.
// Shapes.Status and Shapes("hello") are NOT constructors or values.

define actor Worker<messages=WorkerMessage> as
  receive(WorkerMessage m): void {
    do match m over
      on WorkerMessage.Start(job_id) -> { /* actor-owned state operation */ }
      on WorkerMessage.Progress(done, total) -> { /* actor-owned state operation */ }
      on WorkerMessage.Finished(result) -> { /* actor-owned state operation */ }
      on WorkerMessage.Stop -> { /* initiate stop */ }
    end
  }
end

worker.send(WorkerMessage.Start("job-123"));
worker.send(WorkerMessage.Stop);

// Another actor may choose the heterogeneous pattern domain.
// Its only external entrypoint is still send(Shapes), not arbitrary methods.
// define actor MixedWorker<messages=Shapes> as ... end
// mixed_worker.send(Status.Running);  // admissible by Shapes
// mixed_worker.send("hello");        // admissible by Shapes
// mixed_worker.send(42);             // type error

const string label = match<string> Status.Running over
  on Status.Pending -> { return "waiting"; }
  on Status.Running -> { return "busy"; }
  on Status.Complete -> { return "done"; }
end;
```

`Array[T]`/`List[T]` are variable-extent core collections. Exact 3-element or 2-element shapes use `FixedArray`/`FixedList` (or `Tuple` when tuple/product semantics are intended). `Shapes` is structurally distinguishable on kind and arity; if alternate arms overlap, use the existing explicit ordered matching contract once reconciled.

## Named pattern semantics and one-way dependency

A `define pattern` declaration is *type-only*: its constituent entries are type expressions, not objects created at declaration time. `pattern` is a declaration keyword; unlike an arm-level pattern, it cannot bind a variable, execute a guard, or evaluate arbitrary expressions. It has no identity-bearing runtime constructor, tag, UUID or Symbol. Compiler analysis may normalize it into a union type while retaining source declaration-kind metadata to diagnose illegal enum -> pattern references.

For a pattern domain `P`, `P` can type actor message admissions, function parameters, or scrutinee values where that domain is required. `P.SomeCase`, `P(value)` and `new P` are invalid. For `E` an enum, `E` is usable in a type position and `E.Case` is usable in a value/pattern-match position; the bare enum declaration is not a first-class namespace object.

A pattern may include an enum **type**, e.g. `| Status`, which includes all of its variants; the enum may never include a named pattern as a payload type. Reject transitive violations through `type Alias = PatternName`, generic type parameters or imported aliases. Ordinary enum fields may still use other legal non-pattern types, including tuples and arrays. A bare enum-case value such as `Status.Running` is **not a type expression** in this v1 declaration grammar; match arms may still test that value as a constant pattern.

Use an explicit `|` between alternatives: source newlines are generally ignored by the existing lexer and must not be used as semantic union separators. A named pattern's *domain* is statically closed (no dynamic registered cases), although a member such as `string` may of course admit infinitely many runtime values. No additional implicit runtime discriminant appears when a value is assigned to a transparent pattern domain. The matcher must reject ambiguous/reified-erased shape tests, or use the chosen explicit ordered-match grammar; do not pretend shapes are disjoint when they overlap.

Example invalid dependency, including the indirect case:

```ores
define pattern Accepted as
  string | Status
end
type AliasToAccepted = Accepted;

// Invalid: enum payload refers to a pattern (directly or through an alias).
// define enum Bad as Data(payload: AliasToAccepted) end
```

## Match result semantics

An arm-level `on Status.Running` tests an enum **value**; `on is Status status` tests an enum **type** (with binding). A `match<R>` expression has optional overall result type `R`, independent of the scrutinee and of the declared pattern domain. Each value arm must produce a result compatible with `R` or with the inferred common result type. There is **no** per-case return type in `define pattern` and no required `-> R` after an arm. A `receive(Shapes m): void` declaration types the handler independently; `do match` remains statement-only.

## Enum type and runtime semantics

- `Status` is a nominal, closed, nonnullable type. `Status.Pending` is a value and a constant constructor with no payload. `WorkerMessage.Start("x")` is a constructor call whose result is a `WorkerMessage`. Variants are not independent types unless a later explicit type projection is designed.
- Qualifying with the declaring enum disambiguates same-name variants from other enums and from process-local Symbols. Two distinct enum types with identical labels are *not equal*.
- Payload-bearing variants are algebraic/sum cases, **not** PostgreSQL ENUM values themselves. Every payload field has a declared type. Require deterministic construction/representation; prohibit implicit host-language Java class identity.
- The compiler resolves constructor names, checks arity, types, ownership/moves, generic substitutions and nominal equality, and diagnoses duplicate case names/duplicate wire labels.
- `match` over a closed enum can prove exhaustiveness by checking all variants. Disjoint variant tags give exclusive branch predicates; repeated/shadowed arms are errors. Guards narrow, never expand, coverage. Binding a payload must respect actor isolation, move/borrow, and suspend/loan rules. A matcher must never reflect a raw untrusted string into an unchecked variant.
- `do match` has no result. Value-producing `match<R>` constrains its *whole expression result*, not each pattern; `R` is otherwise inferred from arm results. The planned arm-local `return` returns from that match arm's result scope, not from the containing function. No `-> R` requirement per arm. Preserve slim arrow `->` for executable arms; fat `=>` is for types.
- The proposed `<messages=WorkerMessage>` actor metadata constrains the one public `send` entrypoint (no arbitrary external actor method invocation), while `receive` stays in the actor's exclusive scheduler lane. Static and runtime admission both reject out-of-domain messages; no cast, dynamic import, serializer, FFI, or unsafe host value may bypass that contract. Message ownership transfers or copies according to explicit sendability rules.

## Mandatory `Serializable` actor message contract

**Normative decision:** *Every value supplied to the single public `ActorRef.send(value)` is recursively `Serializable`*, independent of SHARED/PRIVATE/UNTRUSTED placement, current process, deployment topology and whether the runtime elides a physical byte encoding on an optimized local path. **`Sendable`/frozen/read-only is necessary for isolation but is not a proof of portable serialization.** A host pointer, actor reference, lock, Future, channel, borrowed view or closure is not automatically a valid mailbox value. An actor reference can only be forwarded in the future through an explicitly schema-defined, authority-scoped, revocable capability descriptor with verified serialization semantics; no raw pointer or process-local token enters a data envelope.

`Serializable` is a built-in, compiler-verified type **constraint** with a versioned codec descriptor, not a mutable interface method that arbitrary guest code can claim to implement. Proposed `@Serializable` derives this descriptor and the encode/decode implementation for eligible user-defined structs/classes/enums; it must not mean Java `java.io.Serializable`, Graal host reflection, JSON field accessors alone, or a promise that an arbitrary pointer is copyable.

```ores
// Desired semantics; derivation and enum declarations are still proposals.
const a = await spawn WorkerActor();
a.send("foo");  // Valid if WorkerActor's declared message domain includes string.

// Automatically serializable: scalar string/bool/int/float, fixed tuples and
// finite arrays, homogeneous collections and readonly/infer structs whose
// elements are all Serializable; enums when all case payloads are Serializable.
define pattern Messages as
    string | Status | Tuple[bool, string]
end

@Serializable // PENDING: must generate an executable, versioned codec first.
define struct JobData as
    id: string
    retries: int
end

// A source actor's only public ingress is a.send(value); no a.receive(...).
```

**Admission algorithm** for `ActorRef<T>.send(value)`:
1. Resolve the actor's canonical declared message domain (currently a typed `receive(ActorMail<T>)` on Java; proposed `<messages=...>` syntax must lower to the *same* domain).
2. Prove that `typeof(value)` is assignable to that domain. This is independent of serializability.
3. Prove recursively that the **complete static message type** has an Oreslang `Serializable` codec. For a named `pattern` domain, every alternative must be serializable; for an `enum`, every variant's payload must be serializable (even a branch not taken by this particular send).
4. Validate the dynamic value against the exact schema descriptor (important at `Any`, erased generic, deserialization, host FFI or unsafe boundaries); reject unknown constructors, extra/missing fields, wrong types, stale versions, cycles and unsupported capabilities before mailbox publication.
5. Serialize to an immutable bounded envelope or produce a proven-equivalent isolated immutable value on a **local-only optimized fast path**, with a real codec available for relocation; enforce max byte size, node/depth limits, quota reservation and cancellation-aware cleanup. Success acknowledges bounded mailbox **admission**, never execution or durability. Failed encode/admission has no delivered partial message.
6. Decoder reconstructs a fresh value with no shared writable alias into the sender's actor-owned heap. It validates schema ID/version/variant, sender isolation and destination generation and cannot instantiate host objects or execute arbitrary user methods as a side effect.

`Serializable` is **transitive**: `List<Serializable>` is not a magical dynamic back door; a concrete `List<T>` qualifies only when `T` does. An `Option<T>`/`Result<T,E>`, tuple, finite array, map, inferred struct, or pattern domain qualifies only if *all* component types have codecs. The result type of `match<R>` is unrelated to this constraint, and a named pattern remains type-only, never a runtime constructor/tag. A class explicitly annotated `@Serializable` requires generated field codecs and a safe construction path; simply having the annotation is not enough.

**Format:** use a portable length-delimited tagged encoding with a versioned schema identity and stable variant labels. The precise binary format and canonical scalar encodings require separate cross-implementation vectors; neither JVM identity, `ObjectOutputStream`, `Symbol` interning, nor PostgreSQL-local OIDs are wire contracts. Text/JSON may be a diagnostic/debug encoding. Unknown/malformed or oversized messages fail closed, and no decoder dynamically interns attacker-controlled symbols.

**Current implementation gap (as of this RFC):** Java source currently has `ActorMail<T>` typing and frozen/isolated-copy runtime transport, and formerly uses a broader `sendable` check that can admit explicitly scoped capabilities; the parser does **not** yet generate `@Serializable` codecs or named enums/patterns. This PR adds a *first strict source-level admission pass* for primitive and structural built-in types and rejects `@Serializable` until derivation exists; it **does not yet certify a portable binary codec or change every host-privileged transport path**. LLVM still lacks full source-level actor/send/receive lowering. Migrating previously permitted host capability mail must use a separate audited control path or a genuinely serializable capability type; do not silently weaken the new public `send` contract.

## Binary wire v1: MessagePack by default, copying only as a proven fast path

**Decision:** use standard **MessagePack** for actor message payloads, in a schema-guided Oreslang envelope. Avoid JSON/text in the hot path. ProtoBuf/FlatBuffers can be revisited for extremely large or specialized workloads without changing language semantics; CBOR is a valid alternative but would not remove the need for Oreslang type schemas. MessagePack's binary format is simple to implement across Java/Graal and native C++ and provides native compact arrays, strings, scalar numbers, booleans, maps and extension types. Do not confuse built-in MessagePack arrays with the five distinct Oreslang collection types.

Initial frame for the isolated scalar/sequence codec:

```text
MessagePack array(3):
  [1, "fully.qualified.Schema.v1", payload]
payload:
  Tuple[T1,...Tn]         -> MessagePack array(n) [element_1,...element_n]
  FixedArray[T1,...Tn]    -> MessagePack array(n), enforce exact length
  FixedList[T1,...Tn]     -> MessagePack array(n), enforce exact length
  Array[T] / List[T]      -> MessagePack array(n), all items recursively T
  string / bool / i64 / f64 -> native MessagePack scalars
```

The **compiler-owned schema descriptor** determines whether an array payload is a tuple, fixed array, fixed list, dynamic Array or dynamic List. These kinds are *not* inferred from MessagePack bytes and must not be silently interconverted by a mismatched shape; the decoder checks the expected schema ID/version and correct type/arity. A future variant/pattern union must carry a stable resolved alternative tag for ambiguous overlapping types; do not decode it by guessing. Source-level `byte[]` versus `List[int]` and UTF-8 `str` versus binary `bin` remain distinct. Do not serialize a JVM `OresObject` or a native struct by reflecting or copying its physical memory layout.

A Java **standalone reference subset** is now provided by `MessagePackCollectionCodec`, with the same six golden vectors in both repositories' `contracts/v1/design/messagepack_collection_vectors.json`: primitive UTF-8 string/bool/signed i64/finite f64 and nested homogeneous/different-typed list, array and tuple shapes. It checks schema ID, version, fixed arity, UTF-8 validity, numeric range/type, trailing bytes, graph cycles, byte/node/depth/length quotas and disallows arbitrary host objects. This is *not yet the ActorRuntime.send transport*, nor a complete `Serializable` implementation for structs, maps, enums, options, decimals, complex numbers or all signed/unsigned widths.

**Default send:** immediately encode/snapshot before publishing a message to a mailbox, reserve bytes/quota, and deliver a recipient-owned decoded value. Serialization semantics must apply to SHARED, PRIVATE and UNTRUSTED actors alike. For `SHARED` local actors, `rt copy` **may** later replace the byte encoding only when a compiler/runtime proof establishes recursive serializability, identical semantics, ownership-safe deep copy and exactly the same failure/quota behavior. Even a `SHARED` actor cannot inspect or mutate the sender's live heap by retaining a pointer. Absolutely never use a faster alias-sharing path merely because the actors happen to be in the same process.

**Benchmark admission:** compare `serialize + decode`, `rt copy`, and (where proven safe) immutable ownership transfer across 8B, 64B, 1KB, 64KB and 1MB payloads, plus nested tuples/lists. Measure wall latency p50/p95/p99, throughput, allocations, retained bytes and cache pressure under SHARED/PRIVATE/UNTRUSTED placement and contention. Enable a fast path only on a reproducible performance win *and* identical semantics. Keep it internal; neither a sender's source syntax nor observable value semantics may depend on placement.

## Cross-runtime identity and durability

Separate three layers:

| Layer | Identity | Persistence |
| --- | --- | --- |
| In-process fast representation | compiler-chosen integer/discriminant, interned Symbol, or pointer as optimization | never exposed as portable identity |
| Oreslang nominal type and constructor | fully qualified package/module type name, declared contract major version, variant's stable wire label | portable across Java/GraalVM, LLVM, and processes |
| Persisted/wire value | explicit codec schema, version, stable string tag, typed payload | durable across deployments |

**Do not assign an implicit UUID per enum constant.** UUID literals are optional application data, not the default representation. Process-local `Symbol` or Erlang-like atoms are not durable identifiers and must not be deserialized by unrestricted interning from untrusted traffic. A deterministic internal hash may optimize dispatch but must not become the wire contract.

Proposed label rules:

- Local-only cases default the wire label to their case spelling. Persisted/public cases SHOULD explicitly set a stable string, e.g. `Running = "running"`, to make refactors deliberate.
- For payload cases, a future spelling such as `Started(job: string) = "started"` gives an explicit **tag**; the payload still serializes separately.
- Renaming source identifiers must not silently change persisted labels. Reusing or retargeting a persisted label is a breaking schema change. Define a separate explicit migration/alias mechanism rather than accepting multiple canonical labels implicitly.
- Tag and type-version are checked by decoding. Unknown tags yield a typed decode error (or a separately declared Unknown case), not an invented enum value that defeats exhaustive `match`.
- Wire example: `{"type":"company.workers.WorkerMessage","version":1,"tag":"Start","payload":{"job_id":"job-123"}}`. A `Status` value can use `"running"` in a column whose schema already fixes enum type. Default JSON encoding policy must be agreed across both backends before declaring compatibility.

## PostgreSQL mapping

Plain fieldless enums may map to native PostgreSQL ENUM labels (e.g. `CREATE TYPE worker_status AS ENUM ('pending', 'running', 'complete')`). PostgreSQL ENUM values are type-specific, ordered by declaration, and represented internally by database-local catalog OIDs; **those OIDs are not cross-runtime identity**. Dropping values and changing order require care/migrations. Never use PostgreSQL's internal enum OID as the portable variant ID.

Payload-bearing variants generally map to `tag TEXT` + validated JSONB, discriminated structured columns, or normalized child tables, not a native PostgreSQL enum holding the payload. If the set changes frequently, a CHECK-constrained TEXT column or reference table may be easier to migrate than PostgreSQL ENUM. The DB codec preserves explicit labels, types, and versioned schema.

## Grammar / implementation sketch

```ebnf
enum_decl     = visibility "define" "enum" IDENT [ generic_params ] "as" { enum_variant } "end" ;
pattern_decl  = visibility "define" "pattern" IDENT "as" type_ref "end" ;
                (* type_ref admits explicit | union alternatives; pattern declarations are nonconstructible *)
enum_variant  = IDENT [ "(" [ named_type_param { "," named_type_param } ] ")" ]
                [ "=" STRING_LITERAL ] [ ";" ] ;
named_type_param = IDENT ":" type_ref ;
```

Do not reserve `match` as a declaration modifier. Add `enum` to named-type imports (or include it under the existing `import types` selector) and exports with normal nominal type resolution. Specify whether enum declarations can occur at module scope in the actual grammar, consistent with other type declarations.

Implementation phases:
1. **Shared spec and fixtures:** distinct named `pattern` versus `enum` declarations, no pattern constructors, pattern includes enum type, reject direct/transitive enum -> pattern payload dependencies and cycles, positive and negative syntax, typed constructors, duplicate tags, import/alias behavior, exhaustive and nonexhaustive matching, exact collection shapes, `match<R>` vs `do match`.
2. **Java/Graal:** lexer/AST/compiler typed descriptors; constructors, runtime representation, source-level matching and fail-closed actor `send` admission; interpreted/JIT/native-image tests.
3. **LLVM:** equivalent front-end syntax, typed sum discriminant/payload ABI, ownership-safe destruction and constructor lowering; source-compiled execution tests (parsing only or rejection is NOT support).
4. **Protocol/database:** explicit stable codec with conformance fixtures, round trips across backends, unknown tags, changed schema versions, collision/rename migration behavior, PostgreSQL schema/migration tests.
5. **Release:** same input corpus and outputs on exact-source Java/LLVM revisions, malformed payload and cross-actor injection negative tests, runtime lifecycle and cancellation stress where actor messages are involved.

## Open questions for review

- Whether the public syntax should name payload-bearing declarations `enum` (one construct) or `variant`/`data` (two constructs). Favor one construct initially for less complexity, while keeping payload distinction in ABI. A separate `define pattern` remains necessary because it declares a non-constructible structural domain, not a value family.
- Whether persisted/public enums require explicit labels instead of merely recommending them.
- The exact source grammar of `receive` and `<messages=...>` after integration with the current ActorGroup/actor single-send design. This RFC does **not** claim these source forms currently parse.
