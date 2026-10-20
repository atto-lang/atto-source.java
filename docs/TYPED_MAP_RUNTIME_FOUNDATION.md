# Typed Map runtime foundation

Status: **runtime foundation only**. This branch does not expose a new guest
`Map<K,V>` surface and does not implement `Map.from_entries`.

## Purpose

The guest Map implementation must not reuse `java.util.Map` as its semantic
boundary. `OresKeyedMap<K,V>` is a dedicated runtime container with explicit
key/value domains, bounded storage, transactional bulk admission and no mutable
host-map views.

## Key identity

Supported runtime key domains are:

- String;
- nominal `OresSymbol`;
- String-or-Symbol.

String and Symbol namespaces are distinct. PROCESS and STABLE Symbols remain
nominally distinct. Symbol names are NFC-normalized by `OresSymbol`; the
tagged key codec preserves the nominal kind and rejects noncanonical Symbol
wire encodings instead of silently normalizing hostile input.

Ordinary String keys keep ordinary String equality. They are not silently
Unicode-normalized into a different key.

## Runtime safety

- maximum 4,096 entries;
- bounded individual text-key bytes and aggregate encoded-key bytes;
- runtime key and value-domain validation;
- atomic `putAll`: the entire candidate batch is validated and detached
  before publication;
- duplicate logical keys in hostile/identity-backed host batches are rejected;
- lookup and take represent absence explicitly and do not flatten a stored
  Option-like value;
- exported snapshots are detached and unmodifiable;
- entry validators are runtime-package-private compiler policy hooks, cannot be installed by arbitrary external hosts, cannot re-enter mutation of the Map being validated, and are required to be deterministic/side-effect free;
- Symbol names reject control, bidi-format, Unicode line-separator and paragraph-separator characters so diagnostic/source rendering cannot become multiline or directionally ambiguous;
- `take` validates its key-byte refund before mutating live storage, so accounting corruption fails closed;
- mutable typed Maps fail closed at actor and untrusted boundaries.

The runtime intentionally does not make nested values immutable merely because
the outer Map is bounded.

## Actor boundary

Typed Maps are mutable runtime objects. They are not Sendable, Shareable or
untrusted-message values in this slice. Actor transport remains denied until a
separate design proves ownership transfer/copying, graph quotas, capability
walking and private/shared/untrusted semantics.

## Forward-port order

1. Land and execute this runtime foundation on current main.
2. Forward-port the compiler type/ownership surface against the then-current
   AST and evaluator instead of merging the stale #1104 compiler files.
3. Reconcile with nominal `Entry<K,V>` from #1668/#1688 after those semantics
   stabilize.
4. Implement `Map.from_entries` only after typed guest Map construction exists.
5. Keep duplicate-key policy explicit and fail closed; never silently
   last-write-wins.
6. Add Java/Graal exact-head evidence and LLVM parity or an explicit LLVM
   unsupported diagnostic before exposing the API as portable.
