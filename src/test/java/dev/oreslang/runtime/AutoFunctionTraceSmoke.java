package dev.oreslang.runtime;

import java.util.concurrent.atomic.AtomicReference;

/** Exact-source JDK-only slice: not a full guest or Native Image test. */
public final class AutoFunctionTraceSmoke {
    private static int assertions;
    private static void check(boolean yes, String message) {
        assertions++;
        if (!yes) throw new AssertionError(message);
    }
    private static void rejects(Class<? extends Throwable> type, Runnable task) {
        assertions++;
        try { task.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("wrong failure type", failure);
        }
        throw new AssertionError("missing expected failure: " + type.getSimpleName());
    }
    private static AutoFunctionTraceV1 tracing(String... args) {
        return AutoFunctionTraceV1.fromApplicationArguments(args, false);
    }
    public static void main(String[] args) throws InterruptedException {
        AutoFunctionTraceV1 off = tracing();
        check(!off.enabled(), "default disabled");
        check(off.begin(1, null) == null, "disabled scope is null");
        check(off.counts().completed() == 0, "disabled has no completion");
        rejects(IllegalArgumentException.class,
                () -> tracing("--instrument=none", "--ores-trace-calls=true"));
        rejects(IllegalArgumentException.class,
                () -> tracing("--instrument=boundaries", "--ores-trace-calls=true"));
        rejects(IllegalArgumentException.class,
                () -> tracing("--instrument=adaptive", "--ores-trace-calls=true"));
        rejects(IllegalArgumentException.class,
                () -> tracing("--instrument=calls", "--ores-trace-calls=maybe"));
        rejects(IllegalArgumentException.class, () -> tracing("--instrument=invalid"));
        rejects(SecurityException.class,
                () -> AutoFunctionTraceV1.fromApplicationArguments(
                        new String[]{"--instrument=calls", "--ores-trace-calls=true"}, true));

        AutoFunctionTraceV1 on = tracing("--instrument=calls", "--ores-trace-calls=true");
        check(on.enabled(), "enabled in calls build");
        check(AutoFunctionTraceV1.sourceSiteId("unit", "fn", 1)
                == AutoFunctionTraceV1.sourceSiteId("unit", "fn", 1), "stable site hash");
        check(AutoFunctionTraceV1.sourceSiteId("unit", "fn", 1)
                != AutoFunctionTraceV1.sourceSiteId("unit", "fn", 2), "arity distinguishes sites");
        rejects(IllegalArgumentException.class, () -> AutoFunctionTraceV1.sourceSiteId("x", "f", -1));
        rejects(IllegalArgumentException.class, () -> on.begin(0, null));
        AutoFunctionTraceV1.Scope root = on.begin(9, null);
        check(root != null, "root scope enabled");
        check(root.context().traceHigh() != 0, "fresh trace identifier");
        check(root.context().spanId() != 0, "fresh span identifier");
        check(root.parent() == null, "no inherited root");
        check(root.context().traceparent().length() == 55, "W3C v00");
        check(TraceContextV1.parseTraceparent(root.context().traceparent())
                .equals(root.context()), "W3C roundtrip");
        AutoFunctionTraceV1.Scope child = on.begin(10, root.context());
        check(child.context().traceHigh() == root.context().traceHigh(), "child high trace ID");
        check(child.context().traceLow() == root.context().traceLow(), "child low trace ID");
        check(child.context().spanId() != root.context().spanId(), "fresh child span");
        check(child.parent() == root.context(), "parent remains immutable snapshot");

        on.suspend(child);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try { on.resume(child); }
            catch (Throwable t) { failure.set(t); }
        });
        other.start(); other.join();
        check(failure.get() == null, "resume on another carrier");
        on.finish(child, false, false);
        check(on.counts().completed() == 1, "child completes once");
        var childReceipt = on.pollReceipt();
        check(childReceipt != null, "real immutable receipt captured");
        check(childReceipt.context().equals(child.context()), "receipt carries W3C context");
        check(childReceipt.completion().outcome() == ScopedTelemetryProbe.Outcome.OK,
                "receipt has terminal outcome");
        check(on.counts().suspended() == 1, "one suspension, not new span");
        check(on.counts().dropped() == 0, "no unexpected drop");
        on.finish(child, false, false);
        check(on.counts().completed() == 1, "duplicate does not finish twice");
        check(on.counts().dropped() == 1, "duplicate diagnosed");
        on.finish(root, true, false);
        check(on.counts().completed() == 2, "cancelled root finishes once");
        var cancelReceipt = on.pollReceipt();
        check(cancelReceipt != null
                && cancelReceipt.completion().outcome() == ScopedTelemetryProbe.Outcome.CANCELLED,
                "cancel has one immutable terminal receipt");
        check(on.pollReceipt() == null, "duplicate does not enqueue another receipt");

        var full = tracing("--instrument=full", "--ores-trace-calls=true");
        check(full.enabled(), "full build allows calls");
        var errScope = full.begin(3, root.context());
        full.finish(errScope, false, true);
        check(full.counts().completed() == 1, "error completion");
        System.out.println("AUTO_FUNCTION_TRACE_PASS assertions=" + assertions);
    }
}
