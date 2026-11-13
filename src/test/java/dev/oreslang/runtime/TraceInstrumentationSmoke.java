package dev.oreslang.runtime;

/** Runs without JUnit for exact-source javac/JDK validation. */
public final class TraceInstrumentationSmoke {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void rejects(Class<? extends Throwable> expected, Runnable action) {
        assertions++;
        try { action.run(); }
        catch (Throwable t) {
            if (expected.isInstance(t)) return;
            throw new AssertionError("wrong error", t);
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static AdaptiveTraceCodeV1.Identity identity(String tenant) {
        return new AdaptiveTraceCodeV1.Identity(tenant, "checkout", HASH, HASH, HASH, HASH);
    }
    public static void main(String[] args) {
        var none = new TraceInstrumentationControlV1(TraceInstrumentationControlV1.BuildMode.NONE);
        check(!none.enabled(TraceInstrumentationControlV1.BOUNDARY), "default disabled");
        rejects(IllegalArgumentException.class, () -> none.configure(TraceInstrumentationControlV1.BOUNDARY));
        check(!none.hasStaticSite(TraceInstrumentationControlV1.BOUNDARY), "stripped AOT site");
        var boundary = new TraceInstrumentationControlV1(TraceInstrumentationControlV1.BuildMode.BOUNDARIES);
        check(boundary.hasStaticSite(TraceInstrumentationControlV1.BOUNDARY), "boundary compiled");
        check(!boundary.hasStaticSite(TraceInstrumentationControlV1.CALL), "call not compiled");
        rejects(IllegalArgumentException.class, () -> boundary.configure(TraceInstrumentationControlV1.CALL));
        check(boundary.configure(TraceInstrumentationControlV1.BOUNDARY) == 1, "policy version");
        check(boundary.enabled(TraceInstrumentationControlV1.BOUNDARY), "policy activated");
        check(!boundary.enabled(TraceInstrumentationControlV1.CALL), "no call activation");
        boundary.configure(0);
        check(!boundary.enabled(TraceInstrumentationControlV1.BOUNDARY), "runtime disabled");
        var full = new TraceInstrumentationControlV1(TraceInstrumentationControlV1.BuildMode.FULL);
        full.configure(TraceInstrumentationControlV1.ALL);
        check(full.enabled(TraceInstrumentationControlV1.DIAGNOSTIC), "full diagnostic");
        rejects(IllegalArgumentException.class, () -> full.configure(8));
        rejects(IllegalArgumentException.class, () -> full.configure(-1));
        rejects(IllegalArgumentException.class, () -> new AdaptiveTraceCodeV1<>(
                full, identity("t1"), 3, "plain", 2, 2));
        rejects(IllegalArgumentException.class, () -> {
            new AdaptiveTraceCodeV1.Identity("", "x", HASH, HASH, HASH, HASH);
        });

        var adaptive = new TraceInstrumentationControlV1(TraceInstrumentationControlV1.BuildMode.ADAPTIVE);
        check(!adaptive.hasStaticSite(TraceInstrumentationControlV1.CALL), "adaptive needs codegen");
        adaptive.configure(TraceInstrumentationControlV1.CALL);
        var router = new AdaptiveTraceCodeV1<>(adaptive, identity("tenant-a"), 5, "v0", 3, 2);
        var old = router.pin();
        check(old.generation() == 0 && old.executable().equals("v0"), "old call pinned");
        rejects(IllegalStateException.class, () -> router.prepare(TraceInstrumentationControlV1.CALL, 4));
        rejects(IllegalArgumentException.class, () -> router.prepare(TraceInstrumentationControlV1.DIAGNOSTIC, 5));
        var t1 = router.prepare(TraceInstrumentationControlV1.CALL, 5);
        rejects(IllegalStateException.class, () -> router.prepare(TraceInstrumentationControlV1.CALL, 5));
        rejects(SecurityException.class, () -> router.publish(t1,
                new AdaptiveTraceCodeV1.Candidate<>(identity("tenant-b"),
                        TraceInstrumentationControlV1.CALL, "evil")));
        check(router.publish(t1, new AdaptiveTraceCodeV1.Candidate<>(
                identity("tenant-a"), TraceInstrumentationControlV1.CALL, "v1")) == 1, "new version");
        rejects(SecurityException.class, () -> router.publish(t1, new AdaptiveTraceCodeV1.Candidate<>(
                identity("tenant-a"), TraceInstrumentationControlV1.CALL, "replay")));
        var next = router.pin();
        check(next.executable().equals("v1") && next.generation() == 1, "new calls use new code");
        check(old.executable().equals("v0"), "suspended old call retains old code");
        check(router.retainedGenerations() == 2, "old pinned version retained");
        rejects(IllegalStateException.class, () -> router.prepare(TraceInstrumentationControlV1.CALL, 5));
        old.close(); old.close();
        check(router.retainedGenerations() == 1, "old version reclaimed after pin release");
        var t2 = router.prepare(TraceInstrumentationControlV1.CALL, 5);
        adaptive.configure(0);
        rejects(SecurityException.class, () -> router.publish(t2, new AdaptiveTraceCodeV1.Candidate<>(
                identity("tenant-a"), TraceInstrumentationControlV1.CALL, "v2")));
        router.abort(t2);
        adaptive.configure(TraceInstrumentationControlV1.CALL);
        var t3 = router.prepare(TraceInstrumentationControlV1.CALL, 5);
        router.retire();
        rejects(SecurityException.class, () -> router.publish(t3, new AdaptiveTraceCodeV1.Candidate<>(
                identity("tenant-a"), TraceInstrumentationControlV1.CALL, "late")));
        rejects(IllegalStateException.class, router::pin);
        check(next.executable().equals("v1"), "retirement preserves already-running code");
        next.close();
        check(router.isRetired(), "retired after stop");
        check(router.compilationAttempts() == 3, "bounded attempts include aborted");
        System.out.println("TRACE_INSTRUMENTATION_PASS assertions=" + assertions);
    }
}
