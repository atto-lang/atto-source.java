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
    @Test
    void actorStopBetweenCreationCreditAndAttachQuarantinesNativeHandle()
            throws Exception {
        var ceiling = RuntimePermissions.denyAll();
        var policy = IsolatePolicy.developer();
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var actualHandle = new Tracked();
        var completed = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var actorRef = new java.util.concurrent.atomic.AtomicReference<
                ActorRuntime.ActorRef<String>>();
        var runtime = new ActorRuntime(policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var actor = runtime.<String>spawnSharedTrusted(policy,
                    ignored -> (message, turn) -> {
                        try (var permit = io.reserveResourceCreation(7)) {
                            turn.runtime().stop(actorRef.get());
                            assertThrows(SecurityException.class, () ->
                                    io.registerCreated(actualHandle, permit));
                            assertTrue(permit.quarantined());
                            assertEquals(1, io.registeredResourceCount());
                            assertEquals(7, io.registeredAccountedBytes());
                        } catch (Throwable unexpected) {
                            failure.compareAndSet(null, unexpected);
                        } finally {
                            completed.countDown();
                        }
                    });
            actorRef.set(actor);
            actor.send("stop after precreation");
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertFalse(actor.awaitTermination(50,
                    java.util.concurrent.TimeUnit.MILLISECONDS),
                    "STOP cannot free the arena while quarantined handle lives");
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertTrue(actor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, actualHandle.closes.get());
            assertEquals(0, io.registeredResourceCount());
        } finally {
            try { OresContext.joinActorsAndNativeIo(runtime, io); }
            catch (IllegalStateException ignored) { }
        }
    }

    @Test
    void siblingCannotAttachBorrowOrTransferAnotherActorsNativeResource()
            throws Exception {
        var policy = IsolatePolicy.developer();
        var ceiling = RuntimePermissions.denyAll();
        var io = new AsyncNativeIo(2, 1, 2, 2);
        var tracked = new Tracked();
        var ticket = new java.util.concurrent.atomic.AtomicReference<
                AsyncNativeIo.ResourceCreationPermit>();
        var firstReady = new java.util.concurrent.CountDownLatch(1);
        var checked = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var wrongAbort = new java.util.concurrent.atomic.AtomicBoolean();
        var wrongAttach = new java.util.concurrent.atomic.AtomicBoolean();
        var wrongBorrow = new java.util.concurrent.atomic.AtomicBoolean();
        var wrongUnregister = new java.util.concurrent.atomic.AtomicBoolean();

        var runtime = new ActorRuntime(policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var first = runtime.<String>spawnSharedTrusted(policy,
                    ignored -> (message, context) -> {
                        try {
                            ticket.set(io.reserveResourceCreation(0));
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        } finally {
                            firstReady.countDown();
                        }
                    });
            var second = runtime.<String>spawnSharedTrusted(policy,
                    ignored -> (message, context) -> {
                        try {
                            try {
                                ticket.get().close();
                            } catch (SecurityException foreign) {
                                wrongAbort.set(true);
                            }
                            try {
                                io.registerCreated(tracked, ticket.get());
                            } catch (SecurityException foreign) {
                                wrongAttach.set(true);
                            }
                            try {
                                io.borrow(tracked).close();
                            } catch (SecurityException foreign) {
                                wrongBorrow.set(true);
                            }
                            try {
                                io.unregister(tracked);
                            } catch (SecurityException foreign) {
                                wrongUnregister.set(true);
                            }
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        } finally {
                            checked.countDown();
                        }
                    });

            first.send("reserve");
            assertTrue(firstReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertNotNull(ticket.get());
            second.send("attempt foreign transfer");
            assertTrue(checked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(wrongAbort.get(), "foreign actor must not refund ticket");
            assertTrue(wrongAttach.get(), "foreign actor must not attach ticket");
            assertTrue(wrongBorrow.get(), "foreign actor must not borrow owner handle");
            assertTrue(wrongUnregister.get(), "foreign actor must not steal ownership");
            assertTrue(ticket.get().quarantined());
            assertEquals(1, io.registeredResourceCount());
            assertEquals(0, tracked.closes.get());
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertTrue(first.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, tracked.closes.get());
            assertEquals(0, io.registeredResourceCount());
        } finally {
            if (ticket.get() != null) ticket.get().close();
            try { OresContext.joinActorsAndNativeIo(runtime, io); }
            catch (IllegalStateException ignored) { }
        }
    }

    @Test
    void actorOwnedSocketCannotRefundRegistrationBeforePhysicalClose()
            throws Exception {
        var policy = IsolatePolicy.developer();
        var ceiling = RuntimePermissions.denyAll();
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var checked = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var refusedPrematureRefund = new java.util.concurrent.atomic.AtomicBoolean();
        var retainedHandleCount = new AtomicInteger(-1);
        var afterCloseHandleCount = new AtomicInteger(-1);
        var runtime = new ActorRuntime(policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var actor = runtime.<String>spawnSharedTrusted(policy,
                    ignored -> (message, turn) -> {
                        try {
                            var socket = new java.net.Socket();
                            io.register(socket);
                            try {
                                io.unregister(socket);
                            } catch (SecurityException rejected) {
                                refusedPrematureRefund.set(true);
                            }
                            retainedHandleCount.set(io.registeredResourceCount());
                            socket.close();
                            io.unregister(socket);
                            afterCloseHandleCount.set(io.registeredResourceCount());
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        } finally {
                            checked.countDown();
                        }
                    });
            actor.send("probe");
            assertTrue(checked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(refusedPrematureRefund.get());
            assertEquals(1, retainedHandleCount.get(),
                    "unclosed actor socket must retain its physical resource credit");
            assertEquals(0, afterCloseHandleCount.get(),
                    "successful socket.close permits exact-once credit retirement");
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertTrue(actor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            try { OresContext.joinActorsAndNativeIo(runtime, io); }
            catch (IllegalStateException ignored) { }
        }
    }

    @Test
    void overridingSocketIsClosedCannotForgeActorRetirement()
            throws Exception {
        var policy = IsolatePolicy.developer();
        var ceiling = RuntimePermissions.denyAll();
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var checked = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var rejected = new java.util.concurrent.atomic.AtomicBoolean();
        var beforeHostClose = new AtomicInteger(-1);
        class DishonestSocket extends java.net.Socket {
            final AtomicInteger physicalCloseCalls = new AtomicInteger();
            boolean jdkClosedFlag() { return super.isClosed(); }
            @Override public boolean isClosed() {
                // Deliberate lie: the base Socket can still be open.
                return true;
            }
            @Override public void close() throws java.io.IOException {
                super.close();
                physicalCloseCalls.incrementAndGet();
            }
        }
        var socket = new DishonestSocket();
        assertFalse(socket.jdkClosedFlag(), "the real JDK socket begins open");
        var runtime = new ActorRuntime(policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var actor = runtime.<String>spawnSharedTrusted(policy,
                    ignored -> (message, turn) -> {
                        try {
                            io.register(socket);
                            try {
                                io.unregister(socket);
                            } catch (SecurityException validDenial) {
                                rejected.set(true);
                            }
                            beforeHostClose.set(io.registeredResourceCount());
                        } catch (Throwable error) {
                            failure.compareAndSet(null, error);
                        } finally {
                            checked.countDown();
                        }
                    });
            actor.send("forged close");
            assertTrue(checked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(rejected.get(), "overridden close flag cannot refund native quota");
            assertEquals(1, beforeHostClose.get());
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertTrue(actor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, io.registeredResourceCount());
            assertEquals(1, socket.physicalCloseCalls.get());
            assertTrue(socket.jdkClosedFlag(), "real Socket.close must run before quota refund");
        } finally {
            try { OresContext.joinActorsAndNativeIo(runtime, io); }
            catch (IllegalStateException ignored) { }
        }
    }

}
