# Native Task branch bootstrap

This focused prerequisite is stacked on PR #1468 head
`48dd11bc6f6d52ee093afdf2f679409ab163cdfa`, not a replacement for current main
or the independently developed #2748 TaskHandle retirement fix.

The evaluator could not compile because it called package-private
`OresFuture.whenCompleteRuntime` across packages. `TaskScope.afterPhysicalDrain`
keeps that callback private and exposes only a trusted retirement notification.
It observes both successful and failed drain, after physical quiescence, without
closing/cancelling the scope or executing guest code. The caller already owns
scope cancellation. A regression separates logical failure from physical drain
and checks a late notification. No new guest-visible cancellation authority exists.

Reuse the two-line `.atto` admission change from existing main commit
`b52f2b1092c2efdf8fe1341c9ac29b086f0de407`. The new .atto fixture and Java harness
exercise an actual suspension in guest cleanup before lexical scope retirement.
Java changes are compiler/runtime plumbing and test harness only; the behavior
is in `src/test/resources/atto/native-task-cleanup.atto`.

Validation: GraalVM 25.0.4+7-LTS-jvmci-b01, Maven, declared Truffle 25.3.4.1;
37 focused JUnit tests passed. This combination warns that JIT compilation is
unavailable due to version mismatch; execution uses Truffle's fallback interpreter.
No version check was disabled. This is JVM guest execution, not a native-image build.

PR #1468 is Phase 1: Task<T> has compiler admission/transfer checking but shares
OresFuture result storage with Future<T>. Task cancellation and individual physical
retirement observation are not implemented. Do not certify a complete independent
native Task engine or cancellation-safe resource reuse from these results.

Command:
```
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn -B -ntp -Dtest=NativeTaskAttoGuestTest,TaskValueReturnTest,TaskValueReturnHardeningTest,ScopedTaskTest,TaskScopeTest test
```
