# Java/Graal `with` canonical conformance bridge

This PR is **stacked on Java draft #2832**. It mirrors the exact
10 canonical AttoLang `.atto` fixtures from platform-conformance
draft #693 under `fixtures/with-ownership-v1/`, with identical Git
blob hashes (verified during creation) and a JUnit real guest harness.

The two tests execute five positive `.atto` cases and typecheck five
negative `.atto` cases via the actual Java compiler. Parsing alone
does not count. The source fixture contains `self`, not `this`,
except for a deliberate negative case.

**NOT EXECUTED** on this new PR head: canonical JUnit tests, complete
Java source suite, GraalVM Native Image full AOT, LLVM/Wasm/Forge/.NET
guest execution. Prior Java PR #2832 passed 19/19 *different* selected
guest tests on a 348-blob private source subset. Do not reuse those
receipts as proof of this new matrix.

Other backend source mirrors must match these exact Git blob hashes.
No cross-organization source copy is authorized by this test bridge.
