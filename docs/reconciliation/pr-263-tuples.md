# PR #263 semantic reconciliation — Tuple / FixedArray / spread

Original historical head: `5911d5623d00a770ab11b104a2c18a39642d2852` (768 changed paths in an obsolete snapshot). Current-main baseline: `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

## Resolution

- **Preserve the modern tuple/FixedArray and argument-spread implementation** already forward-ported to main through PR #279, including the newer AST/parser, typed ownership/borrow rules, incremental ABI, actor isolation, tree-shaker, and continuation changes. Do not replace hundreds of modern files with the historical PR snapshot.
- Retain current-main `ArgumentsTupleSpreadTest` and later compiler tests rather than overwrite newer coverage.
- Bring back the original 11 test-method `TupleFixedArraySpreadTest` as an **additional regression oracle** at its exact historical Git blob `17e1b18d79ff78f20166394cec92e0eaede17aa0`; let the exact-head suite detect any now-invalid legacy spellings or semantic regressions.
- Maintain the intended distinctions: positional heterogeneous tuples, uniform fixed-size arrays (union element for heterogeneity), statically safe spread to fixed arity, dynamic Array/List spread rejection, ownership transfer/evaluation exactly once.

## Modern runtime oracle hardening (2026-10-25)

- The original runtime test printed with legacy `stdio.println` inside a `define module app` wrapper and accepted *any* stdout containing `42` while separately counting occurrences of a marker. That could accept unrelated output and was not aligned with the current-main `ArgumentsTupleSpreadTest` fixture.
- The new runtime test uses a top-level `pub routine main()`, `stdio.stdout.write`, an explicitly typed/ownership-checked program, and **exact stdout equality** to `pair-evaluated:42`. Extra diagnostics, duplicate evaluation, wrong answers, missing execution, and an unexpected trailing newline are rejected instead of silently tolerated.
- Source-free local checks: 9/9 expected-output classifications; Java 21 syntax compilation of the isolated method against temporary test doubles plus **5/5** mock assertion classifications (including unexpected stderr rejection). The runtime fixture captures `.err(diagnostics)` and requires empty guest stderr as well as exact stdout. **These are not actual Oreslang parser/guest/compiler execution, JUnit or full CI.** They only demonstrate the tighter test assertion and Java host syntax.
- Source-org exact-head GitHub Actions are failing at runner admission: synthetic-smoke rerun of workflow #40248933950, latest job #121336566555, ended in failure with **zero steps**. Alternate private `cliptown/cliptown-e2e` synthetic Native Image workflow #40137488274 latest job #121335758789 succeeded with **7 steps**. This alternative run proves capacity for synthetic source-free builds, **not private-source authorization**; no source was mirrored.
- Remaining 10 historical test methods still need current-main syntax/typechecker adaptation and actual executed CI. Do not promote or merge this draft on the strength of local test-double checks.

## Review hold

This reconciliation resolves the historical Git integration boundary without asserting that the old feature branch is independently correct or runnable today. The forwarded test class may require syntax/AST adaptation to match modern compiler contracts. Its presence is **not** a passed test. Run `TupleFixedArraySpreadTest`, `ArgumentsTupleSpreadTest`, parser/type/ownership/AOT and cross-backend fixtures before merge; reconcile any failures explicitly, without reverting #279.
