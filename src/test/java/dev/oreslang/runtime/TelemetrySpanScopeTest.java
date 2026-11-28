package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only scope checks, complementary to compiled guest async fixtures. */
@Timeout(15)
final class TelemetrySpanScopeTest {
    private static final TraceContext PARENT = TraceContext.root(
            "11111111111111111111111111111111", "2222222222222222", true);

    @Test
    void disabledByDefaultAndRuntimeDisableRetainsIncomingContext() {
        TelemetrySpanScope.disableExistingSites();
        TraceContext previous = TraceContext.install(PARENT);
        try {
            long before = TelemetrySpanScope.startedCount();
            try (TelemetrySpanScope span = TelemetrySpanScope.open("checkout")) {
                assertFalse(span.recording());
                assertSame(PARENT, TraceContext.current());
                assertEquals(PARENT.traceId(), span.traceId());
                span.attribute("operation", "disabled");
            }
            assertSame(PARENT, TraceContext.current());
            assertEquals(before, TelemetrySpanScope.startedCount());
        } finally {
            TraceContext.install(previous);
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void nestedScopesRestoreParentAcrossAwaitStyleHandoffsAndCloseOnce() {
        TelemetrySpanScope.enableExistingSites(100, 10_000, "service.");
        TraceContext previous = TraceContext.install(PARENT);
        try {
            long starts = TelemetrySpanScope.startedCount();
            long ends = TelemetrySpanScope.endedCount();
            TelemetrySpanScope outer = TelemetrySpanScope.open("service.checkout");
            assertTrue(outer.recording());
            TraceContext outerContext = TraceContext.current();
            assertEquals(PARENT.traceId(), outerContext.traceId());
            assertNotEquals(PARENT.spanId(), outerContext.spanId());
            outer.attribute("operation.kind", "purchase");
            // Simulate the scheduler saving its heap-owned trace identity on
            // await and clearing this physical carrier while another task runs.
            TraceContext.install(previous);
            assertSame(previous, TraceContext.current());
            TraceContext.install(outerContext); // resumed task, possibly new carrier
            try (TelemetrySpanScope inner = TelemetrySpanScope.open("service.payment")) {
                TraceContext innerContext = TraceContext.current();
                assertEquals(outerContext.traceId(), innerContext.traceId());
                assertNotEquals(outerContext.spanId(), innerContext.spanId());
                inner.attribute("attempt", 1);
                assertEquals("1", inner.attributesSnapshot().get("attempt"));
            }
            assertSame(outerContext, TraceContext.current());
            outer.close();
            outer.close();
            assertSame(PARENT, TraceContext.current());
            assertEquals(starts + 2, TelemetrySpanScope.startedCount());
            assertEquals(ends + 2, TelemetrySpanScope.endedCount());
        } finally {
            TraceContext.install(previous);
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void disablingRecordingLeavesAnOpenSpanValidAndStopsNewRecordings() {
        TelemetrySpanScope.disableExistingSites();
        TraceContext previous = TraceContext.install(PARENT);
        long starts = TelemetrySpanScope.startedCount();
        long ends = TelemetrySpanScope.endedCount();
        try {
            TelemetrySpanScope.enableExistingSites(100, 10_000, "switch.");
            try (TelemetrySpanScope active = TelemetrySpanScope.open("switch.ongoing")) {
                TraceContext within = TraceContext.current();
                assertTrue(active.recording(), "in-flight scope records at admission");
                TelemetrySpanScope.disableExistingSites();
                assertSame(within, TraceContext.current(), "operator OFF does not interrupt active code");
                try (TelemetrySpanScope later = TelemetrySpanScope.open("switch.later")) {
                    assertFalse(later.recording(), "OFF gates subsequent scope creation");
                    assertSame(within, TraceContext.current(), "disabled scope preserves parent context");
                }
                active.attribute("step", 1);
                assertEquals("1", active.attributesSnapshot().get("step"));
            }
            assertSame(PARENT, TraceContext.current(), "open span still closes exactly once");
            assertEquals(starts + 1, TelemetrySpanScope.startedCount());
            assertEquals(ends + 1, TelemetrySpanScope.endedCount());
        } finally {
            TraceContext.install(previous);
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void rejectsWrongTaskEndAndSensitiveAttributes() throws Exception {
        TelemetrySpanScope.enableExistingSites(100, 10_000, "");
        TraceContext previous = TraceContext.install(null);
        try {
            TelemetrySpanScope scope = TelemetrySpanScope.open("checkout");
            try {
                assertThrows(SecurityException.class,
                        () -> scope.attribute("http.authorization", "secret"));
                assertThrows(IllegalArgumentException.class,
                        () -> scope.attribute("not valid key", "value"));
                CountDownLatch checked = new CountDownLatch(1);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread foreign = Thread.ofPlatform().start(() -> {
                    try {
                        assertThrows(IllegalStateException.class, scope::close);
                        assertThrows(IllegalStateException.class,
                                () -> scope.attribute("request.id", "foreign"));
                    } catch (Throwable t) {
                        failure.set(t);
                    } finally {
                        checked.countDown();
                    }
                });
                assertTrue(checked.await(5, TimeUnit.SECONDS));
                foreign.join(5_000);
                assertNull(failure.get());
                assertFalse(scope.ended());
            } finally {
                scope.close();
            }
        } finally {
            TraceContext.install(previous);
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void rejectsHostileNumbersAndNonfiniteMetricsWithoutInvokingToString() {
        TelemetrySpanScope.enableExistingSites(100, 10_000, "harden.");
        TraceContext previous = TraceContext.install(null);
        try {
            java.util.concurrent.atomic.AtomicInteger maliciousToString =
                    new java.util.concurrent.atomic.AtomicInteger();
            Number hostile = new Number() {
                @Override public int intValue() { return 7; }
                @Override public long longValue() { return 7L; }
                @Override public float floatValue() { return 7.0f; }
                @Override public double doubleValue() { return 7.0; }
                @Override public String toString() {
                    maliciousToString.incrementAndGet();
                    throw new AssertionError("untrusted toString executed on actor carrier");
                }
            };
            try (TelemetrySpanScope scope = TelemetrySpanScope.open("harden.checkout")) {
                assertThrows(IllegalArgumentException.class,
                        () -> scope.attribute("value", hostile));
                assertEquals(0, maliciousToString.get());
                assertThrows(IllegalArgumentException.class,
                        () -> scope.attribute("value", Double.NaN));
                assertThrows(IllegalArgumentException.class,
                        () -> scope.attribute("value", Float.POSITIVE_INFINITY));
                assertThrows(SecurityException.class,
                        () -> scope.attribute("request.api_key", "sensitive"));
                assertThrows(SecurityException.class,
                        () -> scope.attribute("http.headers", "sensitive"));
                assertThrows(SecurityException.class,
                        () -> scope.attribute("file.path", "/private/keys"));
                assertThrows(SecurityException.class,
                        () -> scope.attribute("url.query", "password=secret"));
                assertThrows(SecurityException.class,
                        () -> scope.attribute("db.query.parameters", "sensitive"));
                assertThrows(SecurityException.class,
                        () -> scope.attribute("trace.baggage", "secret"));
                scope.attribute("attempt", 1L);
                assertEquals("1", scope.attributesSnapshot().get("attempt"));
            }
        } finally {
            TraceContext.install(previous);
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void diagnosticsAreOwnedAndClosedDurationsStopIncreasing() throws Exception {
        TelemetrySpanScope.enableExistingSites(100, 10_000, "harden.");
        TraceContext previous = TraceContext.install(null);
        try {
            TelemetrySpanScope scope = TelemetrySpanScope.open("harden.duration");
            try {
                scope.attribute("attempt", 1);
                java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                Thread foreign = Thread.ofPlatform().start(() -> {
                    try {
                        assertThrows(IllegalStateException.class, scope::attributesSnapshot);
                        assertThrows(IllegalStateException.class, scope::elapsedNanos);
                    } catch (Throwable t) {
                        failure.set(t);
                    }
                });
                foreign.join(5_000);
                assertFalse(foreign.isAlive(), "foreign inspection thread must terminate");
                assertNull(failure.get(), "foreign task must not inspect a live scope");
            } finally {
                scope.close();
            }
            long frozen = scope.elapsedNanos();
            Thread.sleep(5);
            assertEquals(frozen, scope.elapsedNanos(),
                    "duration must freeze at lexical span close");
            assertEquals("1", scope.attributesSnapshot().get("attempt"));
        } finally {
            TraceContext.install(previous);
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void rejectsUnsafeSpanNamesEvenWhenDisabled() {
        TelemetrySpanScope.disableExistingSites();
        assertThrows(IllegalArgumentException.class,
                () -> TelemetrySpanScope.open("checkout" + (char) 10 + "forged"));
        assertThrows(IllegalArgumentException.class,
                () -> TelemetrySpanScope.open("checkout" + (char) 0x202e + "forged"));
        assertThrows(IllegalArgumentException.class,
                () -> TelemetrySpanScope.open("checkout" + (char) 0xd800));
        assertThrows(IllegalArgumentException.class,
                () -> TelemetrySpanScope.enableExistingSites(100, 1000, "unsafe" + (char) 10));
        try (TelemetrySpanScope scope = TelemetrySpanScope.open("safe.checkout")) {
            assertFalse(scope.recording());
        } finally {
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void inheritedContextNeverGrantsChildTaskParentSpanAuthority() throws Exception {
        TelemetrySpanScope.enableExistingSites(100, 10_000, "test.");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            java.util.concurrent.atomic.AtomicInteger pc = new java.util.concurrent.atomic.AtomicInteger();
            AtomicReference<TelemetrySpanScope> handle = new AtomicReference<>();
            AtomicReference<TraceContext> inherited = new AtomicReference<>();
            OresFuture<Boolean> parent = scheduler.start(resume -> {
                if (pc.getAndIncrement() == 0) {
                    TelemetrySpanScope scope = TelemetrySpanScope.open("test.parent");
                    handle.set(scope);
                    inherited.set(TraceContext.current());
                    OresFuture<Boolean> child = scheduler.start(childResume -> {
                        assertSame(inherited.get(), TraceContext.current(),
                                "immutable trace identity must be inherited");
                        assertThrows(IllegalStateException.class,
                                () -> scope.attribute("child.attempt", 1));
                        assertThrows(IllegalStateException.class, scope::close);
                        return OresScheduler.done(true);
                    });
                    return OresScheduler.await(child);
                }
                assertEquals(Boolean.TRUE, resume.value());
                assertSame(inherited.get(), TraceContext.current());
                handle.get().close();
                return OresScheduler.done(true);
            });
            assertTrue(parent.get(5, TimeUnit.SECONDS));
            assertTrue(handle.get().ended());
        } finally {
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void guestSchedulerTurnsCannotChangeInstrumentationPolicy() throws Exception {
        TelemetrySpanScope.disableExistingSites();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Boolean> guest = scheduler.start(resume -> {
                assertThrows(SecurityException.class,
                        () -> TelemetrySpanScope.enableExistingSites(100, 1000, ""));
                assertThrows(SecurityException.class,
                        TelemetrySpanScope::disableExistingSites);
                return OresScheduler.done(true);
            });
            assertTrue(guest.get(5, TimeUnit.SECONDS));
        } finally {
            TelemetrySpanScope.disableExistingSites();
        }
    }

    @Test
    void invalidOperatorRequestsDoNotEnableTracing() {
        TelemetrySpanScope.disableExistingSites();
        assertThrows(IllegalArgumentException.class,
                () -> TelemetrySpanScope.enableExistingSites(101, 1000, ""));
        assertThrows(IllegalArgumentException.class,
                () -> TelemetrySpanScope.enableExistingSites(20, 0, ""));
        assertThrows(IllegalArgumentException.class,
                () -> TelemetrySpanScope.enableExistingSites(20, 1000, "x".repeat(129)));
        assertThrows(NullPointerException.class,
                () -> TelemetrySpanScope.enableExistingSites(20, 1000, null));
        try (var scope = TelemetrySpanScope.open("no-probes")) {
            assertFalse(scope.recording());
        } finally {
            TelemetrySpanScope.disableExistingSites();
        }
    }
    @Test
    void otelStyleEventsAreBoundedOwnerCheckedAndImmutableAfterClose() {
        TelemetrySpanScope.enableExistingSites(100, 10_000, "otel.");
        TraceContext previous = TraceContext.install(PARENT);
        try (TelemetrySpanScope scope = TelemetrySpanScope.open("otel.checkout")) {
            scope.setAttribute("cart.items", 3);
            assertEquals("3", scope.attributesSnapshot().get("cart.items"));
            scope.addEvent("payment_started");
            assertThrows(IllegalArgumentException.class,
                    () -> TelemetrySpanScope.open("bad" + (char) 10 + "name"));
            for (int i = 0; i < 32; i++) {
                scope.addEvent("step." + i);
            }
            assertEquals(16, scope.eventsSnapshot().size());
            assertEquals(17, scope.droppedEvents(), "overflow must be accounted for");
            assertEquals("payment_started", scope.eventsSnapshot().getFirst());
            assertThrows(SecurityException.class,
                    () -> scope.addEvent("secret_token"));
            assertThrows(IllegalArgumentException.class,
                    () -> scope.addEvent("newline" + (char) 10 + "forged"));
            assertThrows(IllegalArgumentException.class,
                    () -> scope.addEvent("oversized_" + "x".repeat(81)));
            scope.close();
            assertThrows(IllegalStateException.class, () -> scope.addEvent("late"));
            assertEquals(16, scope.eventsSnapshot().size(),
                    "the host diagnostic view is immutable after lexical close");
            assertThrows(UnsupportedOperationException.class,
                    () -> scope.eventsSnapshot().add("forged"));
        } finally {
            TraceContext.install(previous);
            TelemetrySpanScope.disableExistingSites();
        }
        try (TelemetrySpanScope disabled = TelemetrySpanScope.open("otel.disabled")) {
            disabled.addEvent("valid_event");
            disabled.setAttribute("safe.key", "value");
            assertTrue(disabled.eventsSnapshot().isEmpty());
            assertEquals(0, disabled.droppedEvents());
            assertTrue(disabled.attributesSnapshot().isEmpty());
        }
    }

}
