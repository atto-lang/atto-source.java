package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresSmartPointersTest {
    @Test
    void boxConsumesExactlyOnce() {
        var box = OresSmartPointers.box("payload");
        assertEquals("payload", box.value());
        assertEquals("payload", box.take());
        assertFalse(box.live());
        assertThrows(IllegalStateException.class, box::value);
        assertThrows(IllegalStateException.class, box::take);
        assertDoesNotThrow(box::close);
    }

    @Test
    void arcStrongWeakLifecycleIsDeterministic() {
        var first = OresSmartPointers.arc("shared");
        var second = first.retain();
        var weak = first.downgrade();

        assertEquals(2, first.strongCount());
        assertEquals(1, first.weakCount());
        assertEquals("shared", weak.upgrade().orElseThrow().value());

        // Drop the temporary upgrade.
        var temporary = weak.upgrade().orElseThrow();
        assertEquals(4, first.strongCount());
        temporary.close();
        assertEquals(3, first.strongCount());

        // One upgrade from the assertion above is still live.
        first.close();
        second.close();
        assertEquals(1, weak.strongCount());
        var last = weak.upgrade().orElseThrow();
        assertEquals("shared", last.value());
        last.close();

        // The assertion-created upgrade must be recovered and dropped before
        // the payload can die, so use a fresh lifecycle below for expiry.
        weak.close();

        var owner = OresSmartPointers.arc(7);
        var expiry = owner.downgrade();
        owner.close();
        assertTrue(expiry.upgrade().isEmpty());
        assertEquals(0, expiry.strongCount());
        expiry.close();
    }

    @Test
    void arcClosedHandlesCannotBeReused() {
        var arc = OresSmartPointers.arc(1);
        arc.close();
        assertTrue(arc.closed());
        assertThrows(IllegalStateException.class, arc::value);
        assertThrows(IllegalStateException.class, arc::retain);
        assertThrows(IllegalStateException.class, arc::downgrade);
        assertDoesNotThrow(arc::close);
    }

    @Test
    void rcIsCarrierLocalAndWeakDoesNotKeepPayloadAlive() throws Exception {
        var rc = OresSmartPointers.rc("local");
        var clone = rc.retain();
        var weak = rc.downgrade();
        assertEquals(2, rc.strongCount());
        assertEquals(1, rc.weakCount());

        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                rc.value();
            } catch (Throwable failure) {
                crossThread.set(failure);
            }
        });
        thread.start();
        thread.join();
        assertInstanceOf(IllegalStateException.class, crossThread.get());

        rc.close();
        assertEquals(1, clone.strongCount());
        clone.close();
        assertTrue(weak.upgrade().isEmpty());
        weak.close();
    }

    @Test
    void refCellEnforcesDynamicBorrowExclusivity() {
        var cell = OresSmartPointers.refCell(1);

        try (var first = cell.borrow();
             var second = cell.borrow()) {
            assertEquals(1, first.value());
            assertEquals(1, second.value());
            assertThrows(IllegalStateException.class, cell::borrowMut);
        }

        try (var mutable = cell.borrowMut()) {
            assertEquals(1, mutable.value());
            mutable.set(2);
            assertEquals(2, mutable.value());
            assertThrows(IllegalStateException.class, cell::borrow);
            assertThrows(IllegalStateException.class, cell::borrowMut);
        }

        try (var read = cell.borrow()) {
            assertEquals(2, read.value());
        }
    }

    @Test
    void refCellGuardCannotCrossCarrierThread() throws Exception {
        var cell = OresSmartPointers.refCell(1);
        var read = cell.borrow();

        AtomicReference<Throwable> crossThread = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                read.value();
            } catch (Throwable failure) {
                crossThread.set(failure);
            }
        });
        thread.start();
        thread.join();

        assertInstanceOf(IllegalStateException.class, crossThread.get());
        read.close();
    }
}
