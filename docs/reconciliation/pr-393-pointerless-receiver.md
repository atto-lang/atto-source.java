# PR #393 — pointerless mutable self receiver reconciliation

Historical head `718b411164946eefdaac844688655bb3c81abac5`; current Java main `d63f2611fb77fdda361807ab608da6f6656d1e8e`.

Current main carries more recent Actor/Task suspension, moved-owned borrows, nested callable and type/effect checks. Never copy the historical 128-KiB `OwnershipChecker`, old `TypeChecker` or parser over their much larger new implementations. Preserve canonical `mut self` receiver grammar with no `&` or pointer dereference spellings in source. A mutable receiver must borrow the original owned instance for the duration of the invocation, not consume it; overlapping borrowed/actor-private or cross-Task captures remain rejected.

Reintroduce the old `PointerlessMutableReceiverTest` unchanged as an additional compiler/guest compatibility oracle (three source tests). No compiler code is replaced. It may need updating if the current public source grammar/host launcher differs; that is a test validation task, not permission to remove new ownership safety.

Release gate: compile all three cases at exact head, including repeated mutation, scope isolation and denial behavior; execute against GraalVM and verify ownership/Actor/Task regressions. No claim of a passing source compiler from Git mergeability.
