# Native certification reconciliation — 2026-10-17

This branch started from integration head `128b164256aa9f7d4ac906b20a3c4c2566013b6a` and reconciles security-parity head `11ed8fdf39eba91bb120258739e83df76484bdb2`.

The shared source-free gate now includes the 91-case exact-run provenance suite, the hardened persistent-data reader, three additional workflow pin regressions for committed mode/type and symlink denial (47 persistent-data tests total, three strict rounds = 141 executions), and the actor-arena lifecycle contract.

This branch is a certification candidate only. It is not proof of Java/GraalVM runtime correctness and does not authorize private-source export. Native evidence must bind the exact branch head commit and root tree on an authorized source-local runner.
