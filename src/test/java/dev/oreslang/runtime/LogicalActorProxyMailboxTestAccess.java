package dev.oreslang.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Test-source-only access to the mailbox ledger state machine.
 *
 * Production code has no sequence-only logical completion capability: real
 * credit refund must flow through completeAfterPhysicalReceipt(). Unit models
 * that exercise sparse watermark mechanics invoke the private ledger primitive
 * reflectively from the test classpath instead.
 */
final class LogicalActorProxyMailboxTestAccess {
    private static final Method COMPLETE_LOCKED;

    static {
        try {
            COMPLETE_LOCKED = LogicalActorProxyMailboxV1.class
                    .getDeclaredMethod("completeLocked", long.class);
            COMPLETE_LOCKED.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private LogicalActorProxyMailboxTestAccess() { }

    static void complete(
            LogicalActorProxyMailboxV1 mailbox, long messageSequence) {
        try {
            COMPLETE_LOCKED.invoke(mailbox, messageSequence);
        } catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(
                    "unexpected checked failure from mailbox test ledger", cause);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "cannot invoke mailbox test ledger primitive", failure);
        }
    }
}
