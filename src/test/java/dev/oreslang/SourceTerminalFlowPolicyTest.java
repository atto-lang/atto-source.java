package dev.oreslang;

import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Terminal source-flow policy must survive heap-owned continuations without
 * becoming an ordinary catchable THROW or being replaced by cleanup failures.
 * Reflection intentionally checks the interpreter's sealed internal flow.
 */
final class SourceTerminalFlowPolicyTest {
    private static final Class<?> FLOW;

    static {
        try {
            FLOW = Class.forName(
                    "dev.oreslang.nodes.OresEvalRootNode$Evaluator$SourceFlow");
        } catch (ClassNotFoundException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Object call(String name, Class<?>[] parameters, Object... arguments)
            throws Exception {
        Method method = FLOW.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static Object throwing(Throwable failure) throws Exception {
        return call("throwing", new Class<?>[] {Throwable.class}, failure);
    }

    private static Object normal() throws Exception {
        return call("normal", new Class<?>[0]);
    }

    private static Object returning(Object value) throws Exception {
        return call("returning", new Class<?>[] {Object.class}, value);
    }

    private static Object afterCleanup(Object pending, Object cleanup) throws Exception {
        return call("afterCleanup", new Class<?>[] {FLOW, FLOW}, pending, cleanup);
    }

    private static String kind(Object flow) throws Exception {
        Method method = FLOW.getDeclaredMethod("kind");
        method.setAccessible(true);
        return method.invoke(flow).toString();
    }

    private static Throwable failure(Object flow) throws Exception {
        Method method = FLOW.getDeclaredMethod("failure");
        method.setAccessible(true);
        return (Throwable) method.invoke(flow);
    }

    @Test
    void cancellationAndControlErrorsRemainTerminalNotGuestThrow() throws Exception {
        var cancelled = new CancellationException("stop");
        var fatal = new AssertionError("terminal");
        var guest = new IllegalStateException("ordinary");

        assertEquals("TERMINAL", kind(throwing(cancelled)));
        assertEquals("TERMINAL", kind(throwing(fatal)));
        assertEquals("THROW", kind(throwing(guest)));
        assertSame(cancelled, failure(throwing(cancelled)));
        assertSame(fatal, failure(throwing(fatal)));
    }

    @Test
    void cleanupFailureCannotReplaceTerminalCancellation() throws Exception {
        var cancelled = new CancellationException("stop");
        var cleanupError = new IllegalStateException("defer failed");
        Object pending = throwing(cancelled);
        Object completed = afterCleanup(pending, throwing(cleanupError));

        assertSame(pending, completed);
        assertEquals("TERMINAL", kind(completed));
        assertSame(cancelled, failure(completed));
        assertArrayEquals(new Throwable[] {cleanupError}, cancelled.getSuppressed());
        assertSame(pending, afterCleanup(pending, returning(123)));
        assertSame(pending, afterCleanup(pending, normal()));
    }

    @Test
    void terminalCleanupOverridesOrdinaryGuestFailure() throws Exception {
        var ordinary = new IllegalArgumentException("guest failure");
        var terminal = new AssertionError("fatal cleanup");
        Object completed = afterCleanup(throwing(ordinary), throwing(terminal));

        assertEquals("TERMINAL", kind(completed));
        assertSame(terminal, failure(completed));
        assertArrayEquals(new Throwable[] {ordinary}, terminal.getSuppressed());
    }

    @Test
    void ordinaryCleanupFailureOverridesOrdinaryGuestFailure() throws Exception {
        var ordinary = new IllegalArgumentException("guest failure");
        var cleanupFailure = new IllegalStateException("finally failed");
        Object completed = afterCleanup(throwing(ordinary), throwing(cleanupFailure));

        assertEquals("THROW", kind(completed));
        assertSame(cleanupFailure, failure(completed));
        assertArrayEquals(new Throwable[] {ordinary}, cleanupFailure.getSuppressed());
    }
}
