# Spin borrow/NIO integration

This branch combines the borrow audit (`59c26b78b`) and current Java main with
the existing HTTP NIO/performance branch (`30dd05604`). Pinning the audit branch
alone would lose native asynchronous HTTP client completion and the private
mailbox/global-select separation that the framework already depends on.

The semantic merge preserves main's atomic actor-local mutex admission and
physical execution-segment fencing. It also preserves the performance branch's
live guard resource accounting: the segment-owned constructor increments once,
only a successful owner release decrements, and rejected/double releases cannot
change the count. A regression checks failed recursive/try admission, a wrong-
domain release, successful release and idempotence. Existing stale-segment tests
remain active. No concurrency lane is silently enabled by this merge.

Local validation initially passed 291 tests in 31 suites, covering borrow and
escape checks, native async completion, actor runtime/heap placement/upgrade
and mutex/mailbox accounting. Further HTTP wire and source compatibility checks
are recorded with delivery evidence. This Java integration does not change the
LLVM zero-GC/full-AOT contract. Legacy synchronous APIs are not reclassified as
NIO by this merge; the earlier uncommitted TCP work is deliberately preserved in
its original checkout and is not silently included in this source pin.

Final local integration evidence: 317 tests across 38 suites passed, including
NIO HTTP transport, native HTTP wire/stdlib and mailbox execution boundaries.
The updated Spin source with router construction in its no-argument constructor
passed 55 real HTTP checks against this combined runtime. Pin-based package
checks and exact-tree remote evidence are recorded separately by their heads.
