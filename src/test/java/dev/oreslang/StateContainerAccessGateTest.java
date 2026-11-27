package dev.oreslang;

import dev.oreslang.runtime.StateContainerAccessGate;
import org.junit.jupiter.api.Test;

import static dev.oreslang.runtime.StateContainerAccessGate.Mode.*;
import static org.junit.jupiter.api.Assertions.*;

final class StateContainerAccessGateTest {
    @Test
    void compatibleReadersAndWriterAreExclusiveWithoutParking() {
        StateContainerAccessGate gate = new StateContainerAccessGate(2);
        var r1 = gate.tryRead();
        var r2 = gate.tryRead();
        assertNotNull(r1);
        assertNotNull(r2);
        assertNull(gate.tryRead(), "bounded read admission");
        assertNull(gate.tryWrite(), "writer must not coexist with readers");
        r1.requireActive(READ);
        assertThrows(IllegalStateException.class, () -> r1.requireActive(WRITE));
        r1.close();
        assertEquals(1, gate.readerCount());
        r2.close();
        var writer = gate.tryWrite();
        assertNotNull(writer);
        assertTrue(gate.writerActive());
        assertNull(gate.tryRead());
        assertNull(gate.tryWrite());
        writer.requireActive(WRITE);
        writer.close();
        writer.close(); // double release does not allow a second grant.
        assertFalse(gate.writerActive());
        assertNotNull(gate.tryRead());
    }

    @Test
    void shutdownRevokesExistingLeasesAndCannotResurrectAuthority() {
        StateContainerAccessGate gate = new StateContainerAccessGate(3);
        var read = gate.tryRead();
        assertNotNull(read);
        gate.close();
        assertTrue(gate.isClosed());
        assertFalse(gate.leasesQuiescentAfterClose(), "owner must not free memory with live read lease");
        assertNull(gate.tryRead());
        assertNull(gate.tryWrite());
        assertFalse(read.active());
        assertThrows(IllegalStateException.class, () -> read.requireActive(READ));
        read.close();
        assertEquals(0, gate.readerCount());
        assertTrue(gate.leasesQuiescentAfterClose());
    }

    @Test
    void writeLeaseIsRevokedEvenIfHolderKeepsItAfterClose() {
        StateContainerAccessGate gate = new StateContainerAccessGate(1);
        var old = gate.tryWrite();
        assertNotNull(old);
        long version = old.version();
        old.close();
        var next = gate.tryWrite();
        assertNotNull(next);
        assertTrue(next.version() > version);
        assertThrows(IllegalStateException.class, () -> old.requireActive(WRITE));
        next.close();
    }

    @Test
    void readerReleaseAfterClosingGateDoesNotResurrectWriter() {
        StateContainerAccessGate gate = new StateContainerAccessGate(2);
        var a = gate.tryRead();
        var b = gate.tryRead();
        gate.close();
        a.close();
        b.close();
        assertNull(gate.tryWrite());
        assertEquals(0, gate.readerCount());
    }

    @Test
    void storageRejectsForeignLeasesEvenWithMatchingVersions() {
        var a = new StateContainerAccessGate(2);
        var b = new StateContainerAccessGate(2);
        var ar = a.tryRead();
        var bw = b.tryWrite();
        assertEquals(ar.version(), bw.version());
        a.requireOwnedActive(ar, READ);
        b.requireOwnedActive(bw, READ);
        b.requireOwnedActive(bw, WRITE);
        assertThrows(IllegalStateException.class, () -> a.requireOwnedActive(bw, READ));
        assertThrows(IllegalStateException.class, () -> a.requireOwnedActive(bw, WRITE));
        assertThrows(IllegalStateException.class, () -> b.requireOwnedActive(ar, READ));
        assertThrows(IllegalStateException.class, () -> b.requireOwnedActive(ar, WRITE));
        assertThrows(IllegalStateException.class, () -> a.requireOwnedActive(null, READ));
        assertThrows(IllegalStateException.class, () -> a.requireOwnedActive(ar, WRITE));
        assertEquals(1, a.readerCount());
        assertTrue(b.writerActive());
        a.close();
        assertThrows(IllegalStateException.class, () -> a.requireOwnedActive(ar, READ));
        b.requireOwnedActive(bw, WRITE);
        ar.close();
        assertTrue(a.leasesQuiescentAfterClose());
        bw.close();
        assertThrows(IllegalStateException.class, () -> b.requireOwnedActive(bw, WRITE));
        b.close();
        assertTrue(b.leasesQuiescentAfterClose());
    }
}
