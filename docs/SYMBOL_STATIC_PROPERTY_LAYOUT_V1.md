# First-class Symbol property keys: staged implementation contract

**Status:** Java internal tagged-key identity + immutable static layout foundation only.
This branch deliberately does **not** admit new guest record/struct/class/actor syntax,
change the existing string-keyed aggregate ABI, implement guest typed Maps,
or assert LLVM/Native Image parity.

Normative language contract: `oreslang-common-source/docs/SYMBOLS.md` (merged common PR #22).
Tracked feature work: Java #867 (typed Maps), #868 (records/structs), #869 (classes/actors),
#1104 (forward-ported Map schemas), #1515 (checked dynamic reads), LLVM #339.

## The semantic distinction

`StringKey("status") != SymbolKey(PROCESS,"status")`.
`SymbolKey(PROCESS,name) != SymbolKey(STABLE,name)`.
Symbol identity is logical `(kind, NFC(name))`, not an intern pointer, arena
address, actor, Java classloader, or host hash code. String field keys retain
their existing spelling and equality without implicit NFC normalization.
No String/Symbol coercion may occur through indexing, structural matching,
serialization, spread, destructuring, method dispatch, or FFI.

An Oreslang Symbol is an immutable name, **not** an access capability.
Known/guessed names confer no access to private fields.

## Proposed opt-in source grammar (not implemented by this PR)

```ores
let s = infer struct{status: 1, [#status]: 2};
let r = record{status: 1, [#status]: 2};
let t = struct{status: int, [#status]: int}{status: 1, [#status]: 2};

define class Worker as
  final int status: 1;
  final int [#status]: 2;
end

let mut m = new Map<Symbol | String, int>();
m[#status] = 2;
m["status"] = 1;
```

`s.status` and `s[#status]` refer to disjoint fields. A bare `#status`
in an ordinary initializer is a **value**, not a field-name declaration.
For closed aggregates, the new `[#literal]` grammar accepts only a static
PROCESS Symbol literal (including quoted literal spellings); arbitrary
computed Symbol expressions, mutable key expressions and inferred dynamic
field insertion remain forbidden. Other Symbol kinds require an explicitly
reviewed grammar extension.

## Compiler migration requirements

1. **Parser/AST:** introduce a tagged `MemberKey`/equivalent instead of
   coercing Symbol literal keys to `String`. Keep ordinary `field` names
   and `.field` access unchanged. Parse `[#foo]` only in approved field
   declaration/shape positions. Reject ambiguous forms explicitly.
2. **Types:** store tagged keys in closed struct/record shape identities.
   Preserve width subtyping, field mutability, partial initialization,
   generic substitutions, field-sensitive borrows, and duplicate-after-NFC
   checking. `record{status:int}` and `record{[#status]:int}` are different.
3. **Classes/actors:** lower Symbol-key fields to typed static slots in
   inheritance-aware layouts. Require normal visibility, override,
   constructor definite-initialization, field finality, and teardown checks.
   Never treat Symbol data fields as methods, extension members, reflection
   names or automatic overload dispatch. Keep well-known `Symbol.index`
   protocol names separate from arbitrary data-property keys.
4. **Index access:** static `receiver[#foo]` resolves a declared slot.
   An unknown runtime Symbol on a closed structural/class receiver must fail
   closed unless a proof-bound checked-index feature explicitly admits it.
   Existing checked string indexing (#1515) does not establish Symbol access
   authority. No private field or narrowed-view leaks.
5. **Layouts:** use one authoritative physical AoS/SoA/AoSoA representation,
   never synchronized String and Symbol copies. `OresPropertyLayout.declared`
   retains nominal declaration order; `canonical` gives deterministic order
   for structural ABI materialization. Do not change legacy layouts silently.
6. **Dynamic Maps:** use the dedicated guest `Map<K,V>` implementation, not
   a Java host Map or reinterpreted closed-aggregate backing storage. Ensure
   `Map<String,V>` rejects Symbols, `Map<Symbol,V>` rejects Strings and
   explicit unions preserve both. Respect ownership, actor isolation,
   allocated-byte quotas and presence-aware get/take semantics.
7. **Serialization:** tagged String/PROCESS/STABLE domains and UTF-8 logical
   identity must survive wire encoding, with duplicate logical key rejection.
   Do not encode Symbols by pretending `"#status"` is an ordinary field.
   Old readers fail closed on required unknown tagged keys. Native targets
   match portable codec vectors before guest transport is enabled.
8. **Security:** recursively validate exact immutable snapshots at actor
   ingress/egress. Exported userland Symbols use receiver-authenticated
   tenant/endpoint/protocol-scoped allowlists; guessing a Symbol is not
   permission. Do not create global untrusted interning tables.

## What this PR actually supplies

`OresPropertyKey` is an immutable sealed tagged key with `StringKey`
and `SymbolKey`, a strict String/Symbol guest-value bridge, disjoint
logical equality, v1-compatible domain discriminants and unsigned UTF-8
canonical ordering.

`OresPropertyLayout` provides fixed key-to-slot indexing, duplicate
detection, declared-order or canonical-order construction and immutable
snapshot storage. It does not carry runtime field values, mutate aggregates,
use reflection or allocate actor-owned heaps.

### Bounded admission for new static tagged-key metadata

Initial v1 limits (the same framing budgets as the opt-in tagged-key codec):
- At most **4,096 statically declared fields per layout**.
- At most **16,384 UTF-8 bytes per String property key**.
- At most **1,048,576 aggregate encoded key bytes**, counting the six-byte v1
  framing overhead of each key, even though this layout does not serialize them.
- Existing Symbol validation already bounds Symbol names to **256 UTF-8 bytes**.

The Java layout bounds key count and byte totals **while iterating** the supplied
collection, rather than trusting `Collection.size()` or accumulating unbounded
input before validation. The native host-only counterpart bounds vector count and
aggregate key bytes prior to canonical sort/slot allocation. String keys retain
exact spelling and reject malformed UTF-16/UTF-8. This quota applies only to
**new tagged-key metadata**: before rolling out to legacy string-named fields,
review source compatibility, format and built-in core schemas separately.

These limits prevent extreme metadata allocation but do not themselves prove
safe actor transport, runtime borrow/move semantics or a compiler ABI.

Dedicated JUnit source tests exercise key collisions, Symbol NFC duplicate
identity, stable/process kind separation, canonical ordering, immutable
layouts and Map-key separation. The existing compiler rejects unsupported
Symbol indexing of closed String-keyed aggregates; this PR does not weaken it.

## Release gates before enabling source syntax

- Exact-head Java parser/typechecker/ownership/evaluator JUnit and source
  execution on JDK 25, including continuation-based access and optimizer
  AST cloning.
- Runtime source programs for record/struct/class fields and negative tests
  for undeclared Symbol keys, duplicate normalized names, field privacy,
  constructor partially initialized state, superclass/narrowed views,
  false String aliasing, and mutability bypasses.
- AOT/native-image and LLVM executable conformance against the same fixtures
  and portable tagged-key wire vectors.
- Formatter parse-format-parse identity and tree-shaking/AOT layout stability.
- Actor ingress isolation, sender mutation/TOCTOU tests and tenant-scoped
  untrusted denial tests.
- No claiming CI success for GitHub Actions workflows that allocate no runner
  or execute zero steps. Cross-organization private source mirroring requires
  the repository's reviewed destination allowlist and verified private/private
  provenance; available Actions minutes alone are not authorization.
