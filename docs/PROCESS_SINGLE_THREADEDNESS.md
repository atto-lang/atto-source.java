# ROOT / CONTROL reconciliation

The historical single-flight-root-process-turns branch is reconciled against the current VM ownership work (#617). Ordinary ROOT turns have one bounded carrier. CONTROL and Mailman work has an independent scheduler and bounded CONTROL pool. Strict isolates retain the externally supplied caller-thread admission queue for both facades; no new guest carrier is created for that profile.

VM bootstrap and shutdown retain current carrier quotas, actor retirement receipts, native I/O fencing and physical STOP ordering. Regression tests exercise a blocked ROOT, an independently completing CONTROL turn, a queued second ROOT turn, and strict caller admission.

This is a scoped reconciliation, not a claim that all historical 78-path changes were replayed. The previous alternate receive-concurrency defaults, independent supervisor process scheduler, process timer/tracing changes, and historical compiler ownership rewrites are intentionally not imported over current implementations. They need separate semantic forward ports and qualification. Existing exact-head CI and independent review requirements remain in force.
