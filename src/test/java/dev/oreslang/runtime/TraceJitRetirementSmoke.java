package dev.oreslang.runtime;

import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exact-source, standalone JDK21 smoke for adaptive JIT host control only.
 * This does NOT compile a guest, perform dynamic machine-code generation, or
 * certify zero GC. Use with the two real production control-plane classes.
 */
public final class TraceJitRetirementSmoke {
    private static final String HASH = "a".repeat(64);
    private static int assertions;

    private TraceJitRetirementSmoke() { }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> expected, Runnable run) {
        assertions++;
        try {
            run.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) return;
            throw new AssertionError("unexpected exception", failure);
        }
        throw new AssertionError("expected " + expected.getSimpleName());
    }

    private static AdaptiveTraceCodeV1.Identity identity() {
        return new AdaptiveTraceCodeV1.Identity(
                "tenant", "function", HASH, HASH, HASH, HASH);
    }

    private static TraceInstrumentationControlV1 policy() {
        var policy = new TraceInstrumentationControlV1(
                TraceInstrumentationControlV1.BuildMode.ADAPTIVE);
        policy.configure(TraceInstrumentationControlV1.CALL);
        return policy;
    }

    private static void retireAllGenerationsOnlyAfterFinalPin() {
        var trace = policy();
        var router = new AdaptiveTraceCodeV1<>(trace, identity(), 11, "original", 4, 3);
        var old = router.pin();
        var ticket = router.prepare(TraceInstrumentationControlV1.CALL, 11);
        router.publish(ticket, new AdaptiveTraceCodeV1.Candidate<>(
                identity(), TraceInstrumentationControlV1.CALL, "instrumented"));
        var newer = router.pin();
        check(router.retainedGenerations() == 2, "both pinned generations exist");
        router.retire();
        router.retire();
        check(router.retainedGenerations() == 2, "retirement preserves pinned code");
        check("original".equals(old.executable()), "old pin still sees old code");
        check("instrumented".equals(newer.executable()), "new pin still sees new code");
        rejects(IllegalStateException.class, router::pin);
        rejects(IllegalStateException.class,
                () -> router.prepare(TraceInstrumentationControlV1.CALL, 11));
        newer.close();
        newer.close();
        check(router.retainedGenerations() == 1, "last active image detached on final close");
        old.close();
        old.close();
        check(router.retainedGenerations() == 0, "retired dispatcher owns no executable");
    }

    private static void policyRevocationCannotBeReplayed() {
        var trace = policy();
        var router = new AdaptiveTraceCodeV1<>(trace, identity(), 12, "plain", 5, 2);
        var ticket = router.prepare(TraceInstrumentationControlV1.CALL, 12);
        trace.configure(0);
        trace.configure(TraceInstrumentationControlV1.CALL);
        rejects(SecurityException.class, () -> router.publish(ticket,
                new AdaptiveTraceCodeV1.Candidate<>(
                        identity(), TraceInstrumentationControlV1.CALL, "stale")));
        router.abort(ticket);
        check("plain".equals(router.pin().executable()), "stale candidate not published");
        // The fresh policy epoch permits a separately authorized compilation.
        var fresh = router.prepare(TraceInstrumentationControlV1.CALL, 12);
        router.publish(fresh, new AdaptiveTraceCodeV1.Candidate<>(
                identity(), TraceInstrumentationControlV1.CALL, "fresh"));
        var invocation = router.pin();
        check("fresh".equals(invocation.executable()), "fresh epoch published");
        invocation.close();
        router.retire();
        check(router.retainedGenerations() == 1,
                "previously unclosed plain-code pin must continue charging");
        // This scenario intentionally created an outstanding plain pin above.
        // The caller must close that pin; do not conflate shutdown with cleanup.
    }

    private static void policyAndRetireRace() throws InterruptedException {
        for (int i = 0; i < 64; i++) {
            var trace = policy();
            var router = new AdaptiveTraceCodeV1<>(trace, identity(), 13, "base", 4, 2);
            var pin = router.pin();
            var ticket = router.prepare(TraceInstrumentationControlV1.CALL, 13);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var exceptions = new AtomicInteger();
            var publishes = new AtomicInteger();
            Thread publication = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("publish barrier expired");
                    router.publish(ticket, new AdaptiveTraceCodeV1.Candidate<>(
                            identity(), TraceInstrumentationControlV1.CALL, "new"));
                    publishes.incrementAndGet();
                } catch (SecurityException expectedPolicyRevocation) {
                    // This is the expected path if policy configuration wins first.
                } catch (Throwable unexpected) {
                    exceptions.incrementAndGet();
                }
            });
            Thread revocation = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("policy barrier expired");
                    trace.configure(0);
                    trace.configure(TraceInstrumentationControlV1.CALL);
                } catch (Throwable unexpected) {
                    exceptions.incrementAndGet();
                }
            });
            publication.start();
            revocation.start();
            check(ready.await(5, TimeUnit.SECONDS), "workers ready");
            start.countDown();
            publication.join(5000);
            revocation.join(5000);
            check(!publication.isAlive() && !revocation.isAlive(), "workers terminated");
            check(exceptions.get() == 0, "only authorized publish or policy denial");
            check(publishes.get() >= 0 && publishes.get() <= 1, "single publication");
            router.retire();
            pin.close();
            check(router.retainedGenerations() == 0, "race leaves no code image charged");
        }
    }

    private static void allocationObservationWithoutForcingGc() {
        var trace = policy();
        var router = new AdaptiveTraceCodeV1<>(trace, identity(), 14, "baseline", 3, 2);
        for (int i = 0; i < 10_000; i++) router.pin().close();
        Long before = currentThreadBytes();
        final int iterations = 20_000;
        for (int i = 0; i < iterations; i++) router.pin().close();
        Long after = currentThreadBytes();
        long delta = (before != null && after != null && after >= before)
                ? after - before : -1;
        System.out.println("TRACE_JIT_HOST_PIN_ALLOCATION"
                + " iterations=" + iterations
                + " allocated_bytes=" + (delta >= 0 ? delta : "UNAVAILABLE")
                + " bytes_per_pin=" + (delta >= 0 ? delta / (double) iterations : "UNAVAILABLE")
                + " guest_execution=false native_image=false gc_claim=false");
        router.retire();
        check(router.retainedGenerations() == 0, "churn leaves no retained generation");
    }

    private static Long currentThreadBytes() {
        try {
            var bean = ManagementFactory.getThreadMXBean();
            if (bean instanceof com.sun.management.ThreadMXBean counter
                    && counter.isThreadAllocatedMemorySupported()
                    && counter.isThreadAllocatedMemoryEnabled()) {
                long bytes = counter.getThreadAllocatedBytes(Thread.currentThread().threadId());
                if (bytes >= 0) return bytes;
            }
        } catch (UnsupportedOperationException | SecurityException unavailable) {
            // UNAVAILABLE must not be reported as zero.
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        retireAllGenerationsOnlyAfterFinalPin();
        policyRevocationCannotBeReplayed();
        policyAndRetireRace();
        allocationObservationWithoutForcingGc();
        System.out.println("TRACE_JIT_RETIRE_PASS assertions=" + assertions);
    }
}
