# Symbol identity, registry and static typing audit (2026-10-19)

Status: review-stage audit accompanying a narrow Java/main hardening PR. This is **not** a declaration that all Symbol APIs, guest typed Maps, cross-process transport, scoped exports, or LLVM parity work.

## Decision: do not require a process-global runtime Symbol registry

The merged Java/main `OresSymbol` currently models an immutable value identified by **(kind, NFC-normalized name)**. `OresSymbol.process("ready")` constructs a fresh object; `#ready` parses to the same logical value. Equality and hashing, NOT Java pointer equality, provide the desired get-or-create result:

- `#ready == Symbol.process("ready")` is the intended source-language identity contract; codegen/runtime end-to-end tests remain a separate gate.
- Two `process("ready")` results can be different allocations while equal as keys; a map lookup does not need to find a canonical pointer.
- PROCESS and STABLE keys are distinct even with the same text. STABLE requires a canonical lowercase UUID, not `new Symbol(...)`.
- Symbols are not object references, private capabilities, security principals or unforgeable tokens.
- An implementation MAY add an **optional, bounded, weak/evictable engine-local interning cache** for memory/performance, with identical behavior when entries are evicted. Never make pointer identity, insertion order, registry index or cache survival observable.
- Do not use a permanent global `computeIfAbsent` on arbitrary guest or wire-controlled text: it offers memory-exhaustion and tenant-leakage surfaces and complicates actor lifecycle/native zero-GC.

Recommended `process(name)` behavior:
1. Validate Unicode scalar sequence and name-byte quota, NFC-normalize, then validate canonical result.
2. Construct immutable `Symbol(PROCESS, canonicalName)` (or return an optional cache hit).
3. Type-check the result as nominal `Symbol`, NOT String; no global insertion is required.
4. For wire decoding, require a versioned kind+canonical-name envelope; reject noncanonical or unauthorized input **before** emitting an immutable value. Never intern as a side effect of decoding.

A genuine **unique fresh** identity has different semantics: use a separately specified actor-owned `UniqueToken`/reference, not a guessable `Symbol.process` spelling. There is no public `Symbol.local` kind in the current portable v1 contract.

## Separate the compiler's symbol directory from runtime values

A compiler needs its existing lexical/type symbol table and an **exported Symbol declaration manifest**, but that is *not* a global heap registry.

In a future incremental/interface pass, collect explicitly exported immutable constant declarations (example: `define pub const Symbol Ready = #ready;`) into a deterministic table:

`(provider interface ID, public declaration name) -> (kind, NFC canonical name, visibility, source origin, policy/export metadata)`

This supports import linking, spelling/typo checks for **declared protocol vocabularies**, value-sensitive interface invalidation, AOT constant pooling and language-server completions. Registry construction must be transactional: no leaked declaration or visibility entry on a failed compile, cyclic import, cancelled build, or failed validation. Reproducibility depends on canonical serialized bytes and a provider-bound content digest rather than process pointer IDs. Hot reload must publish a validated manifest atomically and invalidate stale import consumers by version/digest.

This proposed table does **not** globally reserve every runtime Symbol name: ordinary user code may construct `#ready` wherever policy allows, and different modules may intentionally use the same logical Symbol key. Where collision-free protocol identity is required, define a namespace convention or distinct STABLE UUID, and check uniqueness in that protocol declaration scope after NFC normalization. Do not promise cryptographic authenticity merely because a key is Symbol-typed.

## What static typing can and cannot prove today

Already on Java/main:
- Literal `#ready` is a nominal `Symbol` (internally `$builtin$Symbol`), not String/int.
- Literal use in return values and match patterns is parseable; existing string-named struct members remain string-named.
- `Symbol<...>` is not a supported generic type today. No singleton/literal subtype is implemented.
- `new Symbol(...)` is forbidden: STABLE and PROCESS are deliberately not implicit constructor overloads.
- Untrusted source references to hidden symbols are rejected for literal paths (broader initializer/runtime parity remains a draft hardening gate).

This PR tightens:
- Reject nominal builtin `Symbol` shadowing by class, interface, type alias, and generic declarations. A class named Symbol can otherwise make `new Symbol(...)` ambiguous.
- For static switch duplicate detection, key literal cases by (runtime nominal class, literal value), **not display text**; `"#foo"` and `#foo` must coexist, while `#ready` and `#"ready"` are duplicates.
- Render PROCESS names longer than the lexer's 128-character bare-identifier bound using quoted syntax, permitting parse/render/parse equivalence.

Three type-checking tiers should remain distinct:

| Tier | Static contract | Runtime checks |
|---|---|---|
| Open Symbol values | `Symbol`, `Map<Symbol,V>` where implemented | validate kind/name/quota; match logical identity |
| Protocol symbol vocabulary | module-exported `const Symbol` plus **future** explicitly declared finite literal set or refined type | verify imported manifest/digest and matching schema |
| Security authorization | *never* inferred from a `Symbol` type or recognizable name | authenticated recipient policy, detached snapshot, scoped export verification |

For fully exhaustive control flow or compile-time restricted cases, use a nominal `enum`/type match today where supported. A future finite `SymbolSet` or literal-refinement type could carry exact allowed cases, but it must be compiler-enforced at all call, pattern, import and deserialization boundaries and must not be silently treated as plain `Symbol`. Do not advertise `Symbol<"#ready">` as implemented.

## Security and ABI merge gates (separate from this PR)

1. Check `Symbol.process(name)` / `Symbol.stable(uuid)` actual **guest** resolution and runtime behavior: Java host factories alone are insufficient; literal and dynamically computed names differ in what the checker can prove.
2. Ensure the source compiler, imports, overloads, closed struct/record/class slots, dynamic indexing and normal/continuation evaluators preserve String/Symbol distinction, with duplicate canonical key rejection. `Map<Symbol,V>` and Symbol-keyed object fields are NOT present on current Java/main.
3. Prove no shadowing of trusted builtin Symbol constructors through value bindings, aliases, or linked class metadata. This PR fixes the local nominal declaration/generic paths; imported and dynamically bound namespaces require separate audited resolution.
4. Untrusted actors: check **every nested key and value in a fully detached message** (plus field initializers, actor outputs and snapshots). Validate against receiver-authenticated tenant, endpoint and protocol; never trust a sender-supplied allowlist/scope. STABLE stays rejected at the v1 untrusted ingress gate. The hardcoded `publicToUntrustedCoreV1` names are compatibility admission tags, **not authority**.
5. Wire codec: distinct tagged String, PROCESS, STABLE types, strict canonical NFC, byte and entry budgets, duplicate detection after normalization, and fail-closed unknown versions. Decoding must not grow a permanent interner. Old JSON String keys must not be silently rewritten to Symbol keys.
6. JVM/Graal, Native Image, LLVM, formatter and documentation must pass the *same* golden vectors. LLVM guest Symbol support and hosted codec foundations are not the same thing.
7. Run exact-head Maven/JUnit and guest tests; prove compiler checking of disjoint String/Symbol switch cases and same-key duplicate rejection, lexer/render roundtrip for 128/129/256 byte names, classes/generics attempting Symbol shadowing, and adversarial nested untrusted transport. Do not assert runtime correctness from standalone Java host-unit or source inspection alone.

## Related work and integration warning

- Merged #864: first-stage Java nominal Symbols and hash literals.
- Draft #877: broader actor isolation, switch identity, type-name and renderer fixes (overlapping with this narrow main-based PR).
- Draft #897: dedicated typed guest Map, with intentionally restricted mutation/aliasing.
- Draft #893: tagged-key wire codec, not yet end-to-end transport.
- Draft #903: composite integration of typed Maps, codec and actor checks, **not compiler-certified**.
- Portable specification: `oreslang-common-source/docs/SYMBOLS.md`.

Rebase/cherry-pick the overlapping fixes rather than merging parallel draft branches blindly. Preserve existing String field/record ABI. This audit does not introduce a global registry or bless an unverified API surface.
