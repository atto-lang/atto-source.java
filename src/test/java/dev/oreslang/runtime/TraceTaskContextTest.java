package dev.oreslang.runtime;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Logical continuation context substrate, NOT an executed HTTP/actor exchange.
 * SourceTask already snapshots TaskContext on await/resume; this test proves
 * the new immutable W3C binding survives detached installation and cleanup.
 */
final class TraceTaskContextTest {
    @Test
    void captureInstallAndRestoreAcrossCarrierWithoutThreadLocalAuthority() throws Exception {
        TaskContext original = TaskContext.install(null);
        try {
            TraceParent parent = TraceParent.newRoot(true);
            TaskContext previous = TaskContext.pushTraceParent(parent);
            assertNull(previous);
            TaskContext.enter("request_key", "x");
            TaskContext capturedContinuation = TaskContext.current();
            assertEquals(parent, TaskContext.traceParent());
            assertEquals("x", TaskContext.lookup("request_key"));

            TaskContext.install(null);
            assertNull(TaskContext.traceParent());
            assertNull(TaskContext.lookup("request_key"));

            var pool = Executors.newSingleThreadExecutor();
            try {
                var returned = pool.submit(() -> {
                    TaskContext threadPrevious = TaskContext.install(capturedContinuation);
                    try {
                        assertEquals(parent, TaskContext.traceParent());
                        assertEquals("x", TaskContext.lookup("request_key"));
                        TaskContext.exit();
                        assertEquals(parent, TaskContext.traceParent());
                        TaskContext.pushTraceParent(parent.newChild());
                        assertNotEquals(parent.spanId(), TaskContext.traceParent().spanId());
                        return TaskContext.traceParent();
                    } finally {
                        TaskContext.install(threadPrevious);
                        assertNull(TaskContext.traceParent());
                    }
                }).get(10, TimeUnit.SECONDS);
                assertEquals(parent.traceId(), returned.traceId());
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
            // Caller has no implicit access to a migrated continuation.
            assertNull(TaskContext.traceParent());
            assertNull(TaskContext.lookup("request_key"));
        } finally {
            TaskContext.install(original);
        }
    }

    @Test
    void nestedTraceParentsAreScopedAndImmutableAndDoNotBecomeStringBindings() {
        TaskContext previous = TaskContext.install(null);
        try {
            TraceParent root = TraceParent.newRoot(false);
            TaskContext beforeRoot = TaskContext.pushTraceParent(root);
            assertFalse(TaskContext.traceParent().sampled());
            assertNull(TaskContext.lookup("traceparent"));
            assertThrows(IllegalStateException.class, TaskContext::exit,
                    "string task_context exit must not accidentally pop a tracing scope");
            TraceParent child = root.newChild();
            TaskContext beforeChild = TaskContext.pushTraceParent(child);
            assertEquals(child, TaskContext.traceParent());
            TaskContext.install(beforeChild);
            assertEquals(root, TaskContext.traceParent());
            TaskContext.install(beforeRoot);
            assertNull(TaskContext.traceParent());
        } finally {
            TaskContext.install(previous);
        }
    }

    @Test
    void malformedContextCannotBePushedOrEscaped() {
        TaskContext previous = TaskContext.install(null);
        try {
            assertThrows(NullPointerException.class,
                    () -> TaskContext.pushTraceParent(null));
            assertNull(TaskContext.traceParent());
        } finally {
            TaskContext.install(previous);
        }
    }
}
