package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the Java source-flow completion lattice directly. A terminal
 * cancellation or runtime quota failure must bypass guest catch, survive
 * lexical cleanup, and never consume an unrelated ARM_RESULT owner.
 */
final class SourceTypedMatchTerminalFlowTest {
    private static final Class<?> EVALUATOR;
    private static final Class<?> FLOW;

    static {
        try {
            EVALUATOR = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Evaluator");
            FLOW = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Evaluator$SourceFlow");
        } catch (ClassNotFoundException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Object call(Class<?> owner, String name,
                               Class<?>[] parameters, Object... args) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (InvocationTargetException ex) {
            throw new AssertionError("source-flow method threw", ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object read(Object target, String method) {
        try {
            Method accessor = FLOW.getDeclaredMethod(method);
            accessor.setAccessible(true);
            return accessor.invoke(target);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static Object throwing(Throwable failure) {
        return call(FLOW, "throwing", new Class<?>[] {Throwable.class}, failure);
    }

    private static Object returning(Object value) {
        return call(FLOW, "returning", new Class<?>[] {Object.class}, value);
    }

    private static Object armResult(Object value, long frameId, Object task) {
        return call(FLOW, "armResult",
                new Class<?>[] {Object.class, long.class, Object.class},
                value, frameId, task);
    }

    private static Object normal() {
        return call(FLOW, "normal", new Class<?>[0]);
    }

    private static Object finallyFlow(Object pending, Object cleanup) {
        return call(EVALUATOR, "sourceFinally", new Class<?>[] {FLOW, FLOW},
                pending, cleanup);
    }

    private static Object deferFailure(Object pending, Throwable failure) {
        return call(EVALUATOR, "sourceCleanup",
                new Class<?>[] {FLOW, Throwable.class}, pending, failure);
    }

    private static String kind(Object flow) {
        return read(flow, "kind").toString();
    }

    @Test
    void cancellationAndFatalErrorsRemainTerminal() {
        CancellationException cancelled = new CancellationException("actor stopped");
        AssertionError fatal = new AssertionError("fatal");
        assertEquals("TERMINAL", kind(throwing(cancelled)));
        assertEquals("TERMINAL", kind(throwing(fatal)));
        assertSame(cancelled, read(throwing(cancelled), "failure"));
        assertSame(fatal, read(throwing(fatal), "failure"));
        assertEquals("THROW", kind(throwing(new IllegalArgumentException("guest"))));
        assertEquals("NORMAL", kind(normal()));
    }

    @Test
    void actorQuotaExhaustionCannotBeDemotedToGuestThrow() throws Exception {
        var ctor = ActorRuntime.UntrustedActorQuotaExceededException.class
                .getDeclaredConstructor(
                        ActorRuntime.UntrustedActorQuotaExceededException.Resource.class,
                        String.class);
        ctor.setAccessible(true);
        for (var resource : ActorRuntime.UntrustedActorQuotaExceededException.Resource.values()) {
            var quota = ctor.newInstance(resource, "quota");
            assertEquals("TERMINAL", kind(throwing(quota)));
            Object flow = finallyFlow(throwing(quota), returning(42));
            assertEquals("TERMINAL", kind(flow));
            assertSame(quota, read(flow, "failure"));
        }
    }

    @Test
    void cancellationCannotBeReplacedByReturnOrOrdinaryFinallyFailure() {
        CancellationException cancellation = new CancellationException("cancel");
        Object flow = throwing(cancellation);
        assertSame(flow, finallyFlow(flow, returning(42)));
        IllegalStateException cleanup = new IllegalStateException("cleanup");
        Object after = finallyFlow(flow, throwing(cleanup));
        assertSame(flow, after);
        assertEquals("TERMINAL", kind(after));
        assertArrayEquals(new Throwable[] {cleanup}, cancellation.getSuppressed());
    }

    @Test
    void hostFatalCleanupOverridesCancellationAndPreservesItsIdentity() {
        CancellationException cancellation = new CancellationException("cancel");
        LinkageError fatal = new LinkageError("fatal cleanup");
        Object after = deferFailure(throwing(cancellation), fatal);
        assertEquals("TERMINAL", kind(after));
        assertSame(fatal, read(after, "failure"));
        assertArrayEquals(new Throwable[] {cancellation}, fatal.getSuppressed());
    }

    @Test
    void typedArmOwnerSurvivesNormalFinallyButNotTerminalCleanup() {
        Object ownerTask = new Object();
        Object arm = armResult(42, 17L, ownerTask);
        assertEquals("ARM_RESULT", kind(arm));
        Object afterNormal = finallyFlow(arm, normal());
        assertSame(arm, afterNormal);
        assertEquals(17L, read(afterNormal, "matchFrameId"));
        assertSame(ownerTask, read(afterNormal, "ownerTask"));
        CancellationException stop = new CancellationException("stop");
        Object afterCancel = deferFailure(arm, stop);
        assertEquals("TERMINAL", kind(afterCancel));
        assertSame(stop, read(afterCancel, "failure"));
        assertEquals(0L, read(afterCancel, "matchFrameId"));
    }

    @Test
    void guestFailureKeepsExistingFirstFailurePrecedence() {
        IllegalStateException original = new IllegalStateException("guest");
        IllegalArgumentException cleanup = new IllegalArgumentException("defer");
        Object after = deferFailure(throwing(original), cleanup);
        assertEquals("THROW", kind(after));
        assertSame(original, read(after, "failure"));
        assertArrayEquals(new Throwable[] {cleanup}, original.getSuppressed());
    }
}
