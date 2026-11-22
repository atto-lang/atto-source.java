# Canonical Record.of and Tuple.of guest fixtures — Java/Graal draft

These are compiler/Truffle fixture sources for readonly Record, positional
Tuple, and typed-empty collection behavior after retiring tilde aggregate
constructors. They do **not** constitute an executed guest-test receipt.

- `records-guest-pass.atto`, `records-nested-pass.atto`,
  `records-explicit-empty-pass.atto`, and `records-readonly-reject.atto`
  exercise `Record.of(...)` and readonly field semantics.
- Duplicate field, mixed Map/Record entries, and unsupported spread fixtures
  must fail with the **relevant semantic diagnostic**, not merely a parser
  error because canonical `Record.of` lowering is absent.
- `tuples-empty-pass.atto` and `tuples-two-pass.atto` exercise `Tuple.of`.
- `typed-empty-{map,record,tuple}-parser-only.atto` use canonical factory
  spelling. The old `~<T>()` forms are removed, including generic and
  required-field variants, and remain negative syntax fixtures.
- `typed-empty-guest-pass.atto` compares the expected Truffle stdout
  from canonical empty Record and Tuple values.
- `unary-tilde-pass.atto` must retain the independent unary operator.
  Tilde structural typing and structural matching have separate conformance.
- `maps-*-not-enabled.atto` remain intentional deprecated-tilde negatives.
  Canonical Map.of positive, semantic-negative and duplicate-key fixtures
  are in `../tilde-map/` and `../typed-empty-map/`.

**Integration blocker:** Java #2735 supplies canonical Type.of guest lowering
on a separate stacked branch; PR #3140 must reconcile it before positive
Record/Map/Tuple guest cases can pass on the removal branch.

**Verification:** SOURCE AUTHORED / JUNIT NOT_EXECUTED / GUEST NOT_EXECUTED /
NATIVE AOT NOT_EXECUTED / MULTI-BACKEND PARITY NOT_CERTIFIED.
Zero-step GitHub Actions failures are runner-admission failures, not guest
compiler failures or successful tests.
