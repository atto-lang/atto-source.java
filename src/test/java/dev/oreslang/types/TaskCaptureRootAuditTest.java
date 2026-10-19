package dev.oreslang.types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Compiler capture-root preflight, not an executable Task runtime test. */
final class TaskCaptureRootAuditTest {
    private static TaskCaptureRootAudit.Capture c(
            Object root, String name, boolean affine, boolean write) {
        return new TaskCaptureRootAudit.Capture(root, name, affine, write);
    }

    @Test void rejectsTwoNamesForOneAffineAllocation() {
        Object canonical = new Object();
        var error = assertThrows(IllegalArgumentException.class, () ->
                TaskCaptureRootAudit.verify(List.of(
                        c(canonical, "data", true, false),
                        c(canonical, "alias", true, false))));
        assertTrue(error.getMessage().contains("same affine or writable allocation root"));
    }

    @Test void rejectsReadAndWriteOfOneRootRegardlessOfOrder() {
        Object root = new Object();
        assertThrows(IllegalArgumentException.class, () ->
                TaskCaptureRootAudit.verify(List.of(
                        c(root, "reader", false, false),
                        c(root, "writer", false, true))));
        assertThrows(IllegalArgumentException.class, () ->
                TaskCaptureRootAudit.verify(List.of(
                        c(root, "writer", false, true),
                        c(root, "reader", false, false))));
    }

    @Test void permitsMultipleImmutableCopyViewsOfSameRoot() {
        Object root = new Object();
        assertDoesNotThrow(() -> TaskCaptureRootAudit.verify(List.of(
                c(root, "a", false, false),
                c(root, "b", false, false),
                c(root, "c", false, false))));
    }

    @Test void permitsDifferentOwnedRootsEvenIfBothWritable() {
        assertDoesNotThrow(() -> TaskCaptureRootAudit.verify(List.of(
                c(new Object(), "first", true, true),
                c(new Object(), "second", true, true))));
    }

    @Test void valueEqualityCannotSpoofCanonicalOwnershipIdentity() {
        Object first = new String("same-contents");
        Object second = new String("same-contents");
        assertNotSame(first, second);
        assertEquals(first, second);
        assertDoesNotThrow(() -> TaskCaptureRootAudit.verify(List.of(
                c(first, "first", true, false),
                c(second, "second", true, false))));
    }

    @Test void namesCannotConferDisjointnessToSameRoot() {
        Object root = new Object();
        assertThrows(IllegalArgumentException.class, () ->
                TaskCaptureRootAudit.verify(List.of(
                        c(root, "first", true, false),
                        c(root, "distinctName", false, false))));
    }

    @Test void noInheritedClaimStateCanBeMutatedThroughAudit() {
        Object root = new Object();
        var claims = new ArrayList<>(List.of(c(root, "a", true, false)));
        TaskCaptureRootAudit.verify(claims);
        assertEquals(1, claims.size());
        claims.add(c(root, "b", true, false));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCaptureRootAudit.verify(claims));
        claims.remove(1);
        assertDoesNotThrow(() -> TaskCaptureRootAudit.verify(claims));
    }

    @Test void rejectsNullRootsAndBlankNames() {
        assertThrows(NullPointerException.class, () -> c(null, "bad", false, false));
        assertThrows(IllegalArgumentException.class, () -> c(new Object(), "", false, false));
        assertThrows(NullPointerException.class, () ->
                TaskCaptureRootAudit.verify(java.util.Arrays.asList(
                        (TaskCaptureRootAudit.Capture) null)));
    }

    @Test void captureResourceBudgetFailsClosed() {
        var claims = new ArrayList<TaskCaptureRootAudit.Capture>();
        for (int i = 0; i <= 4096; i++) {
            claims.add(c(new Object(), "capture" + i, false, false));
        }
        var error = assertThrows(IllegalArgumentException.class,
                () -> TaskCaptureRootAudit.verify(claims));
        assertTrue(error.getMessage().contains("budget"));
    }
}
