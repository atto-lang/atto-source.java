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
