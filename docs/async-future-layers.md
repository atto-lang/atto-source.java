# Explicit async Future layers

An `async fnc` declaration names the value returned by its body. Calling an
async function adds exactly one `Future` around that declared type. Each
explicit `await` consumes one layer. A returned Future remains a value: the
runtime does not adopt or flatten it.

| Declaration | Call type | Return expression |
| --- | --- | --- |
| `async fnc launch(): int` | `Future<int>` | `await spawned.result` |
| `async fnc launch(): Future<int>` | `Future<Future<int>>` | `spawned.result` |
| `fnc launch(): Future<int>` | `Future<int>` | `spawned.result` |

`async fnc launch(): int { return spawned.result; }` is a type error.

Source async bodies execute as resumable scheduler tasks. Await saves the next
operation and releases the control carrier, then resumes through the existing
bounded VM control dispatcher. Returning an inner Future can complete the
outer Future immediately while the actor producing the inner value is still
running. The task has a stable ownership domain across carrier migration.

Run the complete example (Java 25):

```sh
mvn -q test dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt
java --enable-native-access=ALL-UNNAMED \
  -Dpolyglot.engine.WarnInterpreterOnly=false \
  -cp "target/classes:$(cat target/runtime-classpath.txt)" \
  dev.oreslang.launcher.OresMain examples/actors/three-shared-async.ores
```

Expected output: `10`, `20`, `30`, `40`, each on its own line.

`AsyncFutureLayerTest` covers explicit and nested returns, call aliases, module
and static calls, async main, deeper nesting, loop side effects, and exception
cleanup. `AsyncCarrierProgressTest` holds an actor producer pending and proves
peer progress with exactly one control carrier and no compensation threads.

Current scope: trusted root async functions and static functions, with owned
plain-data values and Future handles. Async instance methods and starting root
async tasks from actor/mailman turns are rejected; actor-owned resumable work
needs its own policy-aware lowering. This change does not integrate the separate
channel/select compiler stack. Cancellation cleanup and guest finally execution
on scheduler cancellation are not established by these tests.
