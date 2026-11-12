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
}
