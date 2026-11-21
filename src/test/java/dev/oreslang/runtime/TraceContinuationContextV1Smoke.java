package dev.oreslang.runtime;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Host-only trace-context carrier smoke, not a guest compiler execution. */
public final class TraceContinuationContextV1Smoke {
    private static int assertions;

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        assertions++;
    }

    private static TraceContextV1 ctx(long id) {
        return new TraceContextV1(1, 2, id, 1);
    }

    /**
     * A minimal task-turn adapter following SourceTask.resume's installed
     * context, snapshot-and-restore protocol. The production SourceTask is
     * separately guarded by a source-blob admission test.
     */
    private static final class TaskTurn {
        private TraceContextV1 snapshot =
                TraceContinuationContextV1.current();

        <T> T run(Callable<T> action) throws Exception {
            TraceContextV1 parent =
                    TraceContinuationContextV1.install(snapshot);
            try {
                return action.call();
            } finally {
                try {
                    snapshot = TraceContinuationContextV1.current();
                } finally {
                    TraceContinuationContextV1.install(parent);
                }
            }
        }

        TraceContextV1 snapshot() {
            return snapshot;
        }
    }

    public static void main(String[] unused) throws Exception {
        TraceContinuationContextV1.install(null);
        check(TraceContinuationContextV1.current() == null,
                "initial carrier cache empty");
        TraceContextV1 outer = ctx(11);
        check(TraceContinuationContextV1.install(outer) == null,
                "first install remembers empty carrier");
        check(TraceContinuationContextV1.current() == outer,
                "parent installed");
        TaskTurn task = new TaskTurn();
        check(task.snapshot() == outer, "child snapshots immutable context");

        TraceContextV1 replacement = ctx(12);
        TraceContextV1 previous = task.run(() -> {
            check(TraceContinuationContextV1.current() == outer,
                    "first task turn inherits parent");
            TraceContextV1 before =
                    TraceContinuationContextV1.install(replacement);
            check(before == outer, "scope update reports previous");
            check(TraceContinuationContextV1.current() == replacement,
                    "span parent update visible inside task");
            return before;
        });
        check(previous == outer, "caller value observed");
        check(task.snapshot() == replacement,
                "new immutable value carried on heap-owned task");
        check(TraceContinuationContextV1.current() == outer,
                "parent carrier restored exactly once");

        try (var carriers = Executors.newFixedThreadPool(2)) {
            var first = carriers.submit(() -> task.run(() -> {
                check(TraceContinuationContextV1.current() == replacement,
                        "resumed task preserves parent across carrier migration");
                return TraceContinuationContextV1.current();
            }));
            check(first.get(5, TimeUnit.SECONDS) == replacement,
                    "resumed context returned");
            var second = carriers.submit(() ->
                    TraceContinuationContextV1.current());
            check(second.get(5, TimeUnit.SECONDS) == null,
                    "carrier context cleared after foreign task");

            TaskTurn sibling = new TaskTurn();
            var one = carriers.submit(() -> sibling.run(() -> {
                check(TraceContinuationContextV1.current() == outer,
                        "sibling captured original parent");
                return 1;
            }));
            check(one.get(5, TimeUnit.SECONDS) == 1, "sibling result");
            check(sibling.snapshot() == outer, "sibling does not inherit child update");

            var fail = carriers.submit(() -> {
                try {
                    task.run(() -> {
                        check(TraceContinuationContextV1.current() == replacement,
                                "failing resumed turn gets own context");
                        throw new IllegalStateException("cancelled");
                    });
                    return false;
                } catch (IllegalStateException expected) {
                    check("cancelled".equals(expected.getMessage()),
                            "failure is not swallowed");
                    return TraceContinuationContextV1.current() == null;
                }
            });
            check(fail.get(5, TimeUnit.SECONDS), "failed turn still cleans carrier");
        }
        check(TraceContinuationContextV1.current() == outer,
                "originating caller remains untouched");
        check(task.snapshot() == replacement,
                "task snapshot survives failed execution");

        // A host-only parent restore may intentionally clear the current value.
        check(TraceContinuationContextV1.install(null) == outer,
                "clear returns outgoing value");
        check(TraceContinuationContextV1.current() == null, "no stale carrier context");
        TraceContextV1 serialized = TraceContextV1.parseTraceparent(
                replacement.traceparent());
        check(serialized.equals(replacement), "transport snapshot round trips");

        System.out.println("ATTO_TRACE_TASK_CONTEXT_PASS assertions=" + assertions);
    }
}
