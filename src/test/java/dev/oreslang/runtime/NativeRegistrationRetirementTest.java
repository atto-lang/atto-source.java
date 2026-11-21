package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves registration itself is a physical actor-owned resource, independently
 * of current read/write loans. A bare unregister is not a close witness.
 */
@Timeout(30)
final class NativeRegistrationRetirementTest {
    @Test
    void actorOwnedSocketWithoutActiveLoanBlocksDoneUntilPhysicallyClosed() throws Exception {
        try (var io = new AsyncNativeIo(2);
             var runtime = new ActorRuntime();
             var socket = new Socket()) {
            runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
            io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
            CountDownLatch registered = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                try {
                    io.register(socket);
                } catch (Throwable caught) {
                    failure.set(caught);
                } finally {
                    registered.countDown();
                }
            });
            actor.send("register");
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, io.outstandingActorOperations(actor.id()),
                    "registration must be accounted even without active IoLoan");
            actor.cancel();
            assertFalse(actor.done().isDone(),
                    "stopped actor may not free memory while its socket remains registered");
            assertThrows(SecurityException.class, () -> io.unregister(socket),
                    "detaching a live socket must not forge a retirement receipt");
            assertEquals(1, io.outstandingActorOperations(actor.id()));
            assertFalse(actor.done().isDone());
            socket.close();
            io.unregister(socket);
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "physical socket close and unregister must unblock actor finalization");
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        }
    }

    @Test
    void deferredUnregisterWaitsForTheFinalPhysicalLoan() throws Exception {
        try (var io = new AsyncNativeIo(2);
             var runtime = new ActorRuntime();
             var socket = new Socket()) {
            runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
            io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
            AtomicReference<AsyncNativeIo.IoLoan> borrowed = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch admitted = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                try {
                    io.register(socket);
                    borrowed.set(io.borrow(socket));
                } catch (Throwable caught) {
                    failure.set(caught);
                } finally {
                    admitted.countDown();
                }
            });
            actor.send("open");
            assertTrue(admitted.await(3, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(2, io.outstandingActorOperations(actor.id()),
                    "registered handle and active physical loan have independent receipts");
            actor.cancel();
            assertFalse(actor.done().isDone());
            socket.close();
            io.unregister(socket);
            assertEquals(2, io.outstandingActorOperations(actor.id()),
                    "deferred unregister may not retire either loan early");
            assertFalse(actor.done().isDone());
            borrowed.get().close();
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        }
    }

    @Test
    void separateRegistrationOwnerAndBorrowingActorRetireIndependently() throws Exception {
        try (var io = new AsyncNativeIo(2);
             var runtime = new ActorRuntime();
             var socket = new Socket()) {
            runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
            io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch borrowed = new CountDownLatch(1);
            var physicalBorrow = new AtomicReference<AsyncNativeIo.IoLoan>();
            var failure = new AtomicReference<Throwable>();
            var owner = runtime.<String>spawnShared(() -> (message, ctx) -> {
                try {
                    io.register(socket);
                } catch (Throwable caught) {
                    failure.compareAndSet(null, caught);
                } finally {
                    registered.countDown();
                }
            });
            owner.send("register");
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            var borrower = runtime.<String>spawnShared(() -> (message, ctx) -> {
                try {
                    physicalBorrow.set(io.borrow(socket));
                } catch (Throwable caught) {
                    failure.compareAndSet(null, caught);
                } finally {
                    borrowed.countDown();
                }
            });
            borrower.send("borrow");
            assertTrue(borrowed.await(3, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertNotEquals(owner.id(), borrower.id());
            assertEquals(1, io.outstandingActorOperations(owner.id()));
            assertEquals(1, io.outstandingActorOperations(borrower.id()));
            owner.cancel();
            borrower.cancel();
            assertFalse(owner.done().isDone());
            assertFalse(borrower.done().isDone());
            socket.close();
            io.unregister(socket);
            assertEquals(1, io.outstandingActorOperations(owner.id()),
                    "registered owner cannot be released by another actor's outstanding borrow");
            assertEquals(1, io.outstandingActorOperations(borrower.id()));
            physicalBorrow.get().close();
            assertTrue(owner.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(borrower.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, io.outstandingActorOperations(owner.id()));
            assertEquals(0, io.outstandingActorOperations(borrower.id()));
        }
    }

    @Test
    void failedConnectCleanupClosesSocketBeforeRegistrationRetirement() throws Exception {
        try (var io = new AsyncNativeIo(1);
             var runtime = new ActorRuntime()) {
            runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
            io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
            var ownerId = new AtomicReference<ActorRuntime.ActorId>();
            var countAtPhysicalClose = new AtomicInteger(-1);
            var socket = new Socket() {
                @Override public synchronized void close() throws java.io.IOException {
                    countAtPhysicalClose.set(io.outstandingActorOperations(ownerId.get()));
                    super.close();
                }
            };
            CountDownLatch registered = new CountDownLatch(1);
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                try {
                    io.register(socket);
                } catch (Exception failure) {
                    throw new AssertionError("actor socket registration failed", failure);
                } finally {
                    registered.countDown();
                }
            });
            ownerId.set(actor.id());
            actor.send("register");
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertEquals(1, io.outstandingActorOperations(actor.id()));
            actor.cancel();
            assertFalse(actor.done().isDone());
            var connectFailure = new java.io.IOException("injected failed connect");
            NativeIo.retireFailedConnect(io, socket, connectFailure);
            assertEquals(1, countAtPhysicalClose.get(),
                    "actor must still own its registration when physical close begins");
            assertTrue(socket.isClosed());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, io.outstandingActorOperations(actor.id()));
            assertEquals(0, connectFailure.getSuppressed().length);
        }
    }

    @Test
    void failedPhysicalSocketCloseNeverForgesActorDone() throws Exception {
        var io = new AsyncNativeIo(1);
        var runtime = new ActorRuntime();
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        var failedOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        var socket = new Socket() {
            @Override public synchronized void close() throws java.io.IOException {
                if (failedOnce.getAndSet(false)) {
                    throw new java.io.IOException("injected physical close failure");
                }
                super.close();
            }
        };
        CountDownLatch registered = new CountDownLatch(1);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                try {
                    io.register(socket);
                } catch (Exception failure) {
                    throw new AssertionError("actor registration failed", failure);
                } finally {
                    registered.countDown();
                }
            });
            actor.send("register");
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            actor.cancel();
            assertFalse(actor.done().isDone());
            var connectFailure = new java.io.IOException("connect rejected");
            NativeIo.retireFailedConnect(io, socket, connectFailure);
            assertFalse(socket.isClosed());
            assertEquals(1, connectFailure.getSuppressed().length);
            assertEquals(1, io.outstandingActorOperations(actor.id()));
            assertFalse(actor.done().isDone(),
                    "failed close must not retire the physical registration");
            // Context-owned shutdown retries the physical close and only then
            // supplies the actor registration retirement notification.
            io.close();
            assertTrue(socket.isClosed());
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        } finally {
            io.close();
            runtime.close();
        }
    }

    @Test
    void contextShutdownJoinsRegisteredHandleBeforeRetiringItsActor() throws Exception {
        var io = new AsyncNativeIo(1);
        var runtime = new ActorRuntime();
        var socket = new Socket();
        var entered = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                try {
                    io.register(socket);
                } catch (Throwable caught) {
                    failure.set(caught);
                } finally {
                    entered.countDown();
                }
            });
            actor.send("register");
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, io.outstandingActorOperations(actor.id()));
            // The first actor close may be incomplete due to the registered
            // handle; context join must close I/O, then retry actor finalization.
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertTrue(socket.isClosed());
            assertEquals(0, io.outstandingActorOperations(actor.id()));
            assertTrue(actor.done().isDone(),
                    "joined context cannot publish success before actor DONE");
        } finally {
            try { io.close(); } finally { runtime.close(); }
        }
    }

    @Test
    void actorOwnedGenericCloseableCannotTransferWithoutPhysicalWitness() throws Exception {
        var io = new AsyncNativeIo(1);
        var runtime = new ActorRuntime();
        AtomicInteger closed = new AtomicInteger();
        AutoCloseable generic = closed::incrementAndGet;
        CountDownLatch registered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                try {
                    io.register(generic);
                } catch (Throwable caught) {
                    failure.set(caught);
                } finally {
                    registered.countDown();
                }
            });
            actor.send("register");
            assertTrue(registered.await(3, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            actor.cancel();
            assertFalse(actor.done().isDone());
            assertThrows(SecurityException.class, () -> io.unregister(generic),
                    "unverifiable generic close must stay under context ownership");
            io.close();
            assertEquals(1, closed.get(), "native context must close actor-owned resource once");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                    "verified context close unblocks physical registration gate");
            assertEquals(0, io.outstandingActorOperations(actor.id()));
        } finally {
            io.close();
            runtime.close();
        }
    }
}
