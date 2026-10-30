package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pre-allocation host admission tests. Source checked in: executable outcomes
 * remain unverified until an authorized JDK25 runner executes this class.
 */
@Timeout(30)
final class NativeResourceCreationPermitTest {
    private static final class Tracked implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();
        @Override public void close() {
            if (closes.incrementAndGet() != 1)
                throw new IllegalStateException("double native handle close");
        }
    }

    @Test
    void reservationRejectsSecondCreationBeforeAnyHandleExists() {
        try (var io = new AsyncNativeIo(2, 1, 2, 1)) {
            try (var first = io.reserveResourceCreation(0)) {
                assertEquals(0, io.registeredResourceCount());
                assertThrows(RejectedExecutionException.class, () ->
                        io.reserveResourceCreation(0));
                var second = new Tracked();
                assertThrows(RejectedExecutionException.class, () ->
                        io.register(second, 0));
                second.close();
            }
            assertEquals(0, io.registeredResourceCount());
            try (var next = io.reserveResourceCreation(0)) {
                assertNotNull(next);
            }
        }
    }

    @Test
    void trustedBytesAreChargedBeforeCreatingNativeBufferOrSocket() {
        try (var io = new AsyncNativeIo(2, 1, 2, 2)) {
            try (var full = io.reserveResourceCreation(64L * 1024 * 1024)) {
                assertThrows(RejectedExecutionException.class, () ->
                        io.reserveResourceCreation(1));
                assertThrows(RejectedExecutionException.class, () ->
                        io.register(new Tracked(), 1));
            }
            try (var free = io.reserveResourceCreation(1)) {
                assertNotNull(free);
            }
        }
    }

    @Test
    void successfulTransferNeverRefundsCreationCreditEarly() throws Exception {
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var resource = new Tracked();
        try {
            try (var permit = io.reserveResourceCreation(18)) {
                io.registerCreated(resource, permit);
                assertEquals(1, io.registeredResourceCount());
                assertEquals(18, io.registeredAccountedBytes());
                assertThrows(RejectedExecutionException.class, () ->
                        io.reserveResourceCreation(0));
                assertThrows(IllegalStateException.class, () ->
                        io.registerCreated(new Tracked(), permit));
            }
            // Closing the transferred permit cannot retire the real handle.
            assertEquals(1, io.registeredResourceCount());
            io.unregister(resource);
            resource.close();
            assertEquals(0, io.registeredResourceCount());
            assertEquals(0L, io.registeredAccountedBytes());
            try (var renewed = io.reserveResourceCreation(0)) {
                assertNotNull(renewed);
            }
        } finally {
            io.close();
        }
        assertEquals(1, resource.closes.get());
    }

    @Test
    void abortedConstructorReturnsCreditWithoutPublishingHandle() {
        try (var io = new AsyncNativeIo(2, 1, 2, 1)) {
            var permit = io.reserveResourceCreation(4);
            permit.close();
            permit.close(); // try-with-resources-style idempotent cleanup
            assertEquals(0, io.registeredResourceCount());
            assertEquals(0, io.registeredAccountedBytes());
            try (var reused = io.reserveResourceCreation(4)) {
                assertNotNull(reused);
            }
        }
    }

    @Test
    void forgedCrossContextPermitCannotPublishResource() throws Exception {
        try (var first = new AsyncNativeIo(2, 1, 2, 1);
                var other = new AsyncNativeIo(2, 1, 2, 1)) {
            var raw = new Tracked();
            try (var permit = first.reserveResourceCreation(0)) {
                assertThrows(SecurityException.class, () ->
                        other.registerCreated(raw, permit));
                assertEquals(0, other.registeredResourceCount());
                assertEquals(0, first.registeredResourceCount());
            }
            raw.close();
            assertEquals(1, raw.closes.get());
        }
    }

    @Test
    void shutdownCannotPretendUnusedCreationPermitWasPhysicallyRetired() {
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var outstanding = io.reserveResourceCreation(0);
        try {
            io.requestShutdown();
            assertThrows(IllegalStateException.class, io::close,
                    "pending native construction must hold quarantine");
            outstanding.close();
            io.close();
            assertEquals(0, io.registeredResourceCount());
        } finally {
            outstanding.close();
            io.close();
        }
    }

    @Test
    void lateCreationIsRejectedButRetainedForPhysicalClose() throws Exception {
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var resource = new Tracked();
        var permit = io.reserveResourceCreation(12);
        try {
            io.requestShutdown();
            assertThrows(RejectedExecutionException.class, () ->
                    io.registerCreated(resource, permit));
            assertTrue(permit.quarantined());
            assertEquals(1, io.registeredResourceCount());
            assertEquals(12, io.registeredAccountedBytes());
            permit.close(); // attached credit must not free the native handle
            assertEquals(1, io.registeredResourceCount());
            io.close();
            assertEquals(1, resource.closes.get());
            assertEquals(0, io.registeredResourceCount());
        } finally {
            permit.close();
            io.close();
        }
    }
}
