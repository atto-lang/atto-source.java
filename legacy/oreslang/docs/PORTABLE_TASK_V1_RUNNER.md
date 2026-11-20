# Portable Task v1 — Java/GraalVM guest execution adapter

This runner integrates the 14 proposed shared `.atto` fixtures of [conformance PR #465](https://github.com/ores-truffle-oreslang/oreslang-platform-conformance/pull/465) using the real JVM launcher. It invokes `dev.oreslang.launcher.OresMain --check` for compile admission, then the same launcher without `--check` to execute guest `main()`. It uses no Python interpreter/probe, and does not modify or export repository sources.

```bash
export ATTO_TASK_SUITE_ROOT=/absolute/path/to/authorized/oreslang-platform-conformance
export ATTO_TASK_SUITE_SHA="$(git -C "$ATTO_TASK_SUITE_ROOT" rev-parse HEAD)"
mvn -B -ntp -DskipTests package
mvn -B -ntp -q dependency:build-classpath -Dmdep.outputFile=target/conformance-classpath.txt
bash scripts/run-portable-task-v1-guest.sh
```

The launcher preserves the JVMCI, native-access, and Unsafe compatibility flags of the older JVM guest probe. Its `OresMain.class` SHA-256 is an **entrypoint identity**, not compiled Native Image guest evidence. The wrapper fails closed on unavailable suite, SHA mismatch, missing compiled launcher, missing Maven classpath or an unsupported Task fixture. Candidate pass status always requires actual guest exit and trace; independent runner-log verification is still necessary for release qualification.

**Executed on this branch:** NOT EXECUTED (source-local CI jobs have reported no actual steps). Cross-org private-source mirrors are not authorized by this integration. Reconcile generic `HasFuture<T>`, identity-preserving `await ActorRef<T>`, and Task cancellation before requesting review approval.
