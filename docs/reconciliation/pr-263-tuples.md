# PR #263 semantic reconciliation — Tuple / FixedArray / spread

Original historical head: `5911d5623d00a770ab11b104a2c18a39642d2852` (768 changed paths in an obsolete snapshot). Current-main baseline: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

## Resolution

- **Preserve the modern tuple/FixedArray and argument-spread implementation** already forward-ported to main through PR #279, including the newer AST/parser, typed ownership/borrow rules, incremental ABI, actor isolation, tree-shaker, and continuation changes. Do not replace hundreds of modern files with the historical PR snapshot.
- Retain current-main `ArgumentsTupleSpreadTest` and later compiler tests rather than overwrite newer coverage.
- Bring back the original 11 test-method `TupleFixedArraySpreadTest` as an **additional regression oracle** at its exact historical Git blob `17e1b18d79ff78f20166394cec92e0eaede17aa0`; let the exact-head suite detect any now-invalid legacy spellings or semantic regressions.
- Maintain the intended distinctions: positional heterogeneous tuples, uniform fixed-size arrays (union element for heterogeneity), statically safe spread to fixed arity, dynamic Array/List spread rejection, ownership transfer/evaluation exactly once.

## Review hold

This reconciliation resolves the historical Git integration boundary without asserting that the old feature branch is independently correct or runnable today. The forwarded test class may require syntax/AST adaptation to match modern compiler contracts. Its presence is **not** a passed test. Run `TupleFixedArraySpreadTest`, `ArgumentsTupleSpreadTest`, parser/type/ownership/AOT and cross-backend fixtures before merge; reconcile any failures explicitly, without reverting #279.
