# Local actor-mail nominal enum symbols — v1 foundation

Status: **Java host-runtime foundation only**, not a language/compiler backend
parity claim. The `define enum Light as ... end` parser, nominal case
construction, typechecking, and guest execution are in the stacked Java draft
PRs #1598 and #2847, **not on main**. This PR intentionally avoids copying
their overlapping AST/compiler logic. Wiring the existing guest
`NominalEnumValue` representation to this checked scalar is a follow-up
integration gate.

## Observable contract

- `Light.Red` is a value of the closed nominal type `Light`, not the
  integer `0`, not `"Red"`, and not the open symbol `#Red`.
- Nullary enum cases are immutable, copyable, and do not contain actor-arena
  pointers. The host `OresEnumSymbol` is a value with logical identity and
  does not require a global process interner.
- Identity is **(authenticated defining source-unit identity, nominal enum
  declaration name, closed variant-name schema, variant name)**. Ordinals and
  host object address do not participate. A variant-set change is
  deliberately incompatible until an explicit versioned compatibility
  relation exists.
- The compiler, **not untrusted message data**, must prove that an enum
  declaration exists, that the variant belongs to it, and that the receiver
  expects this nominal type. A host factory does not grant authority.
- Runtime shared/private actor mail can retain the immutable scalar directly
  while copying or moving mutable *containers* according to ownership policy.
  The scalar itself has no outstanding loan and is safe when its sender actor
  is reclaimed. Same-process transport does **not** encode or deserialize it.
- If a variant carries a payload, the variant tag alone is shareable;
  a payload-bearing enum is **not** automatically a scalar. The payload
  independently requires a borrow/copy/move proof.
- Untrusted ingress currently rejects nominal enum symbols, including
  nested keys/values, until a receiver-authenticated export manifest is
  specified. No string matching or type name alone grants visibility.
- Message quota accounting remains mandatory even for zero-serialization
  scalar delivery.
- Cross-process / isolated VM / separate Wasm linear-memory communication
  must use explicit bounded, versioned symbolic encoding and type validation.
  A raw reference, tag or arbitrary string must never be treated as an
  authenticated identity or capability.

## Checks in this PR

`OresEnumSymbolActorMailTest` covers: distinct nominal declarations, same
schema with reordered cases, changed schema, unknown/duplicate/invalid cases,
no integer/string/open-Symbol coercion, read-only freezing, detached mutable
containers, actual host PRIVATE and SHARED actor mailbox delivery, and
untrusted nested ingress rejection.

These are authored tests only until an **exact-head** Maven execution with
nonzero runner steps and JUnit XML evidence exists.

## Guest compiler, actor, and backend gates still required

1. Integrate `OresEnumSymbol` with the actual nominal nullary guest
   constructor and `Light.Red` expression on top of the stacked enum PR.
   Preserve source-unit resolution/import authorization and reject payload
   variants when used as nullary values.
2. Compile and execute real `.atto` actor mail source fixtures for PRIVATE,
   SHARED, and UNTRUSTED actor kinds, including stale-revision and STOP
   reclamation tests. Test that mutable payloads never bypass ownership.
3. Verify Java, LLVM, .NET NativeAOT, Wasm, and Forge use the same nominal
   semantics with distinct physical representations; O0/O2 and AOT/JIT
   results must agree. Host-only tests are not compiler parity evidence.
4. Keep local `Sendable` separate from network `Serializable<Format>`.
   Ensure remote bridges encode stable schema-qualified IDs, reject unknown
   versions, and authorize receivers independently.
5. Acquire independent review; do not merge until actual executed CI and
   negative tests prove the exact PR head. Zero-step runner admission is
   `NOT_EXECUTED`, not success.

## Quota hardening and additional executable gates

Each nullary symbol references the complete immutable closed enum declaration,
not only its selected case string. Mailbox accounting charges the declaration's
*entire bounded case schema* for each admitted symbol, using a cached logical
byte count computed once during validated declaration construction. This makes
each send's scalar budget check O(1), avoids per-send 4096-case traversal, and
does not give a large retained declaration dictionary an uncharged admission
path. Quotas are intentionally conservative (logical retention), not byte
measurements of Java or native object layouts.

Additional JUnit probes cover a full 4096-case declaration and a real UNTRUSTED
actor's rejection of both direct and nested unexported enum symbols. Existing
positive PRIVATE/SHARED mailbox tests still assert the same immutable object
arrives without serialization.

The CI gate on this branch is
`.github/workflows/nominal-enum-local-mail-v1.yml`. It runs
`mvn -B -ntp -Dtest=OresEnumSymbolActorMailTest -DfailIfNoTests=true test`
and retains Surefire output when a runner actually executes. No successful
exact-head CI or compiled `.atto` guest evidence is implied merely by writing
this workflow.
