package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-only arbitration tests. Real AttoLang parallel guest dispatch is a
 * separate compiler/scheduler proof, not inferred from these tests.
 */
final class ActorReadWriteLoansTest {
    @Test
    void multipleReadersCanCoexistAndWritersAreExcluded() {
        try (ActorReadWriteLoans gate = new ActorReadWriteLoans()) {
            Object r1 = new Object(), r2 = new Object(), writerId = new Object();
            var a = gate.tryRead(r1);
            var b = gate.tryRead(r2);
            assertNotNull(a);
            assertNotNull(b);
            assertEquals(2, gate.readerCount());
            assertEquals(2, gate.activeCount());
            assertNull(gate.tryWrite(writerId));
            assertThrows(IllegalStateException.class, () -> gate.tryRead(r1),
                    "the same logical handler cannot reenter an active loan");
            assertTrue(a.heldBy(r1));
            assertFalse(a.heldBy(r2));
            a.close();
            assertNull(gate.tryWrite(writerId), "one remaining reader excludes a writer");
            b.close();
            var write = gate.tryWrite(writerId);
            assertNotNull(write);
            assertTrue(write.writable());
            assertTrue(gate.hasWriter());
            assertNull(gate.tryRead(new Object()));
            assertNull(gate.tryWrite(new Object()));
            write.close();
            assertFalse(gate.hasWriter());
            var after = gate.tryRead(new Object());
            assertNotNull(after);
            after.close();
            assertEquals(0, gate.activeCount());
        }
    }

    @Test
    void closingSealsViewsButKeepsOutstandingPhysicalTickets() {
        ActorReadWriteLoans gate = new ActorReadWriteLoans();
        Object handler = new Object();
        var reader = gate.tryRead(handler);
        assertNotNull(reader);
        gate.close();
        assertFalse(reader.heldBy(handler));
        assertEquals(1, gate.activeCount(),
                "STOP cannot manufacture physical cleanup while a lexical read is live");
        assertThrows(IllegalStateException.class, () -> gate.tryRead(new Object()));
        assertThrows(IllegalStateException.class, () -> gate.tryWrite(new Object()));
        reader.close();
        reader.close();
        assertEquals(0, gate.activeCount());
        gate.close();
    }

    @Test
    void actualConcurrentHostReadersOverlapAndDoNotAdmitWriter() throws Exception {
        try (ActorReadWriteLoans gate = new ActorReadWriteLoans();
             var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch bothStarted = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            var a = pool.submit(() -> {
                var ticket = gate.tryRead(new Object());
                assertNotNull(ticket);
                bothStarted.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } finally {
                    ticket.close();
                }
            });
            var b = pool.submit(() -> {
                var ticket = gate.tryRead(new Object());
                assertNotNull(ticket);
                bothStarted.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } finally {
                    ticket.close();
                }
            });
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
            assertEquals(2, gate.readerCount());
            assertNull(gate.tryWrite(new Object()));
            release.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            assertEquals(0, gate.activeCount());
            assertNotNull(gate.tryWrite(new Object()));
        }
    }
}
