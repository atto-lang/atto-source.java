package dev.oreslang.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Map;

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
    private static final Field LOCK;
    private static final Field OUTSTANDING;

    static {
        try {
            COMPLETE_LOCKED = LogicalActorProxyMailboxV1.class
                    .getDeclaredMethod("completeLocked", long.class);
            COMPLETE_LOCKED.setAccessible(true);
            LOCK = LogicalActorProxyMailboxV1.class
                    .getDeclaredField("lock");
            LOCK.setAccessible(true);
            OUTSTANDING = LogicalActorProxyMailboxV1.class
                    .getDeclaredField("outstanding");
            OUTSTANDING.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private LogicalActorProxyMailboxTestAccess() { }

    @SuppressWarnings("unchecked")
    static void requeueBySequence(
            LogicalActorProxyMailboxV1 mailbox, long messageSequence) {
        try {
            Object lock = LOCK.get(mailbox);
            synchronized (lock) {
                Map<Long, LogicalActorProxyMailboxV1.Envelope> outstanding =
                        (Map<Long, LogicalActorProxyMailboxV1.Envelope>)
                                OUTSTANDING.get(mailbox);
                var envelope = outstanding.get(Long.valueOf(messageSequence));
                if (envelope == null) {
                    throw new IllegalStateException(
                            "test sequence is not currently outstanding");
                }
                mailbox.requeueProvenUnsent(
                        envelope, mailbox.snapshot().routeEpoch());
            }
        } catch (IllegalAccessException failure) {
            throw new AssertionError(
                    "cannot inspect mailbox test outstanding ledger", failure);
        }
    }

    static void complete(
            LogicalActorProxyMailboxV1 mailbox, long messageSequence) {
        try {
            Object lock = LOCK.get(mailbox);
            synchronized (lock) {
                COMPLETE_LOCKED.invoke(mailbox, messageSequence);
            }
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
