# RFC: nominal enums, typed variants, actor-message domains, and portable identity

**Status:** proposed design only (2026-10-11). **No parser, type checker, evaluator, LLVM, serialization, or actor runtime support is asserted by this document.** Do not use a docs-only PR as evidence of feature parity.

## Design decision

Keep **values**, **types**, and **matching** separate:

1. `define enum E as ... end` declares a closed *nominal* sum/variant type. A variant may carry no payload or a statically typed payload.
2. `type T = A | B | C;` is the existing transparent *union type* alias, not a special `type match` declaration.
3. `match` inspects a value. It is not a declaration kind. The return type of a value-producing match is contextually inferred or optionally constrained with `match<R>`; arms do not need individual return type annotations.

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

type Shapes =
    Tuple[bool, string]
  | string
  | FixedArray[string, string, string]
  | FixedList[bool, bool];

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

const string label = match<string> Status.Running over
  on Status.Pending -> { return "waiting"; }
  on Status.Running -> { return "busy"; }
  on Status.Complete -> { return "done"; }
end;
```

`Array[T]`/`List[T]` are variable-extent core collections. Exact 3-element or 2-element shapes use `FixedArray`/`FixedList` (or `Tuple` when tuple/product semantics are intended). `Shapes` is structurally distinguishable on kind and arity; if alternate arms overlap, use the existing explicit ordered matching contract once reconciled.

## Enum type and runtime semantics

- `Status` is a nominal, closed, nonnullable type. `Status.Pending` is a value and a constant constructor with no payload. `WorkerMessage.Start("x")` is a constructor call whose result is a `WorkerMessage`. Variants are not independent types unless a later explicit type projection is designed.
- Qualifying with the declaring enum disambiguates same-name variants from other enums and from process-local Symbols. Two distinct enum types with identical labels are *not equal*.
- Payload-bearing variants are algebraic/sum cases, **not** PostgreSQL ENUM values themselves. Every payload field has a declared type. Require deterministic construction/representation; prohibit implicit host-language Java class identity.
- The compiler resolves constructor names, checks arity, types, ownership/moves, generic substitutions and nominal equality, and diagnoses duplicate case names/duplicate wire labels.
- `match` over a closed enum can prove exhaustiveness by checking all variants. Disjoint variant tags give exclusive branch predicates; repeated/shadowed arms are errors. Guards narrow, never expand, coverage. Binding a payload must respect actor isolation, move/borrow, and suspend/loan rules. A matcher must never reflect a raw untrusted string into an unchecked variant.
- `do match` has no result. Value-producing `match<R>` constrains its *whole expression result*, not each pattern; `R` is otherwise inferred from arm results. The planned arm-local `return` returns from that match arm's result scope, not from the containing function. No `-> R` requirement per arm. Preserve slim arrow `->` for executable arms; fat `=>` is for types.
- The proposed `<messages=WorkerMessage>` actor metadata constrains the one public `send` entrypoint (no arbitrary external actor method invocation), while `receive` stays in the actor's exclusive scheduler lane. Static and runtime admission both reject out-of-domain messages; no cast, dynamic import, serializer, FFI, or unsafe host value may bypass that contract. Message ownership transfers or copies according to explicit sendability rules.

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
enum_variant  = IDENT [ "(" [ named_type_param { "," named_type_param } ] ")" ]
                [ "=" STRING_LITERAL ] [ ";" ] ;
named_type_param = IDENT ":" type_ref ;
```

Do not reserve `match` as a declaration modifier. Add `enum` to named-type imports (or include it under the existing `import types` selector) and exports with normal nominal type resolution. Specify whether enum declarations can occur at module scope in the actual grammar, consistent with other type declarations.

Implementation phases:
1. **Shared spec and fixtures:** positive and negative syntax, typed constructors, duplicate tag errors, import/alias behavior, exhaustive and nonexhaustive matching, exact collection shapes, `match<R>` vs `do match`.
2. **Java/Graal:** lexer/AST/compiler typed descriptors; constructors, runtime representation, source-level matching and fail-closed actor `send` admission; interpreted/JIT/native-image tests.
3. **LLVM:** equivalent front-end syntax, typed sum discriminant/payload ABI, ownership-safe destruction and constructor lowering; source-compiled execution tests (parsing only or rejection is NOT support).
4. **Protocol/database:** explicit stable codec with conformance fixtures, round trips across backends, unknown tags, changed schema versions, collision/rename migration behavior, PostgreSQL schema/migration tests.
5. **Release:** same input corpus and outputs on exact-source Java/LLVM revisions, malformed payload and cross-actor injection negative tests, runtime lifecycle and cancellation stress where actor messages are involved.

## Open questions for review

- Whether the public syntax should name payload-bearing declarations `enum` (one construct) or `variant`/`data` (two constructs). Favor one construct initially for less complexity, while keeping payload distinction in ABI.
- Whether persisted/public enums require explicit labels instead of merely recommending them.
- The exact source grammar of `receive` and `<messages=...>` after integration with the current ActorGroup/actor single-send design. This RFC does **not** claim these source forms currently parse.
