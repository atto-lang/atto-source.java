# PR #522 semantic forward-reconciliation — native blocking I/O

Historical head: `3f6c544f97bddb64d561a24cec4cbf37c8fc6f21`. Integration baseline `1f3912a772f6aa46aacb8a745f4c3cba1015fb73`.

The historical runtime `_blocking` native I/O wrappers, nested synchronous-tail suspension, filesystem/network capability checking and strict CLI/source-compiler mode must not overwrite modern main's source-flow, isolate, ownership and native host admission. This branch's original `CooperativeBlockingIoTest` depends on APIs that are not present in current main as a complete executable feature and cannot be assumed to pass merely by recreating that test.

Current main already enforces a narrow invariant: a user routine or method with a `_blocking` name must declare `suspends`. This merge carries that rule forward without granting unsupported blocking native I/O APIs, and adds `BlockingSuffixAdmissionForwardPortTest` for the negative admission and ordinary-function compatibility cases.

Deferred contract: no first-class `fs.read_text_blocking` alias, no fake scheduler desuspension, no extra host carrier pool; restore the historical source-level blocking-IO tests only after a separately reviewed implementation meets real filesystem/network and actor carrier tests. The historical code remains in first-parent history.

Exact-head JUnit and Graal/native run evidence is still required. This is not a full #522 feature release or a CI pass.
