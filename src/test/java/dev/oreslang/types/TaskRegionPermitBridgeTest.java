package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler admission / trusted physical-exit callback contract only.
 * Full Oreslang nb source lowering is deliberately disabled.
 */
final class TaskRegionPermitBridgeTest {
    private static final SendSyncTraits SOURCE = new SendSyncTraits(
            new Ast.Program(List.of()), Set.of("Array"));
    private static final ThreadSafetyTraits.NominalProof RESOLVED =
            (type, trait) -> false;
    private static final TaskAccessVerifier.Place POSITIONS =
            TaskAccessVerifier.Place.whole(
                    new TaskAccessVerifier.Root("PROCESS", 911, 1, true))
                    .field("position", true);

    private static TaskAccessVerifier.CoherentTypedClaim write(long from, long to) {
        return new TaskAccessVerifier.CoherentTypedClaim(
                new TaskAccessVerifier.Claim(
                        POSITIONS.range(from, to, true), TaskAccessVerifier.Mode.WRITE),
                new Ast.TypeRef("Array",
                        List.of(new Ast.TypeRef("int", List.of(), false)), false),
                new Types.ListType(Types.Primitive.INT));
    }

    private static TaskAccessVerifier.Claim wholeWrite() {
        return new TaskAccessVerifier.Claim(
                POSITIONS, TaskAccessVerifier.Mode.WRITE);
    }

    @Test
    void disjointWritersHoldMemoryThroughCancellationUntilPhysicalExit() {
        var bridge = new TaskRegionPermitBridge();
        var left = bridge.admit(List.of(write(0, 128)), SOURCE, RESOLVED);
        var right = bridge.admit(List.of(write(128, 256)), SOURCE, RESOLVED);
        Runnable leftPhysicalExit = left.bindPhysicalExit();
        Runnable rightPhysicalExit = right.bindPhysicalExit();
        assertEquals(2, bridge.activeChildren());
        assertFalse(bridge.parentMayAccess(wholeWrite()));
        assertThrows(IllegalArgumentException.class,
                () -> bridge.admit(List.of(write(127, 129)), SOURCE, RESOLVED));
        assertEquals(2, bridge.activeChildren());
        left.requestCancel();
        assertTrue(left.cancellationRequested());
        assertFalse(left.retired());
        assertFalse(bridge.parentMayAccess(wholeWrite()));
        assertThrows(IllegalStateException.class, bridge::finishScope);
        leftPhysicalExit.run(); // only trusted host invokes after actual physical exit
        assertTrue(left.retired());
        assertFalse(bridge.parentMayAccess(wholeWrite()));
        assertDoesNotThrow(leftPhysicalExit::run); // completed exit is idempotent
        assertThrows(IllegalStateException.class, left::requestCancel);
        rightPhysicalExit.run();
        assertEquals(0, bridge.activeChildren());
        assertTrue(bridge.parentMayAccess(wholeWrite()));
        bridge.finishScope();
        assertFalse(bridge.parentMayAccess(wholeWrite()));
        assertThrows(IllegalStateException.class,
                () -> bridge.admit(List.of(write(256, 384)), SOURCE, RESOLVED));
    }

    @Test
    void rejectedSourceProofDoesNotAllocateAnyTicket() {
        var bridge = new TaskRegionPermitBridge();
        var claim = write(0, 128);
        var wrong = new TaskAccessVerifier.CoherentTypedClaim(
                claim.claim(), claim.sourceType(), Types.Primitive.INT);
        assertThrows(IllegalArgumentException.class,
                () -> bridge.admit(List.of(wrong), SOURCE, RESOLVED));
        assertThrows(IllegalArgumentException.class,
                () -> bridge.admit(List.of(), SOURCE, RESOLVED));
        assertEquals(0, bridge.activeChildren());
        assertNotNull(bridge.admit(List.of(claim), SOURCE, RESOLVED));
    }

    @Test
    void unboundPermitMayAbortOnlyBeforeScheduling() {
        var bridge = new TaskRegionPermitBridge();
        var own = bridge.admit(List.of(write(0, 128)), SOURCE, RESOLVED);
        assertEquals(1, bridge.activeChildren());
        assertFalse(bridge.parentMayAccess(wholeWrite()));
        own.requestCancel(); // cancellation never discharges a reservation
        assertEquals(1, bridge.activeChildren());
        own.abortBeforeScheduling(); // no scheduler frame was bound
        assertTrue(own.retired());
        assertEquals(0, bridge.activeChildren());
        assertTrue(bridge.parentMayAccess(wholeWrite()));
        assertDoesNotThrow(own::abortBeforeScheduling);
        assertThrows(IllegalStateException.class, own::bindPhysicalExit);
        bridge.finishScope();
    }

    @Test
    void boundPermitCannotBeAbandonedOrBoundTwice() {
        var bridge = new TaskRegionPermitBridge();
        var own = bridge.admit(List.of(write(0, 128)), SOURCE, RESOLVED);
        Runnable exit = own.bindPhysicalExit();
        assertThrows(IllegalStateException.class, own::bindPhysicalExit);
        assertThrows(IllegalStateException.class, own::abortBeforeScheduling);
        assertEquals(1, bridge.activeChildren());
        assertFalse(bridge.parentMayAccess(wholeWrite()));
        own.requestCancel();
        assertEquals(1, bridge.activeChildren());
        exit.run(); // scheduler's physical-exit callback in production
        assertTrue(own.retired());
        assertDoesNotThrow(exit::run);
        assertThrows(IllegalStateException.class, own::requestCancel);
        bridge.finishScope();
    }

    @Test
    void concurrentBindingAndAbandonmentHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 128; round++) {
            var bridge = new TaskRegionPermitBridge();
            var own = bridge.admit(List.of(write(0, 128)), SOURCE, RESOLVED);
            var start = new java.util.concurrent.CountDownLatch(1);
            var done = new java.util.concurrent.CountDownLatch(2);
            var success = new java.util.concurrent.atomic.AtomicInteger();
            var physicalExit = new java.util.concurrent.atomic.AtomicReference<Runnable>();
            var failures = new java.util.concurrent.atomic.AtomicInteger();
            Thread binder = new Thread(() -> {
                try {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("binder timed out");
                    physicalExit.set(own.bindPhysicalExit());
                    success.incrementAndGet();
                } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            Thread abandoner = new Thread(() -> {
                try {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("abandoner timed out");
                    own.abortBeforeScheduling();
                    success.incrementAndGet();
                } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            binder.start();
            abandoner.start();
            start.countDown();
            assertTrue(done.await(8, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, success.get());
            assertEquals(1, failures.get());
            // Once bound, only its trusted exit callback can retire.
            if (physicalExit.get() != null) physicalExit.get().run();
            assertEquals(0, bridge.activeChildren());
            bridge.finishScope();
            binder.join(5000);
            abandoner.join(5000);
            assertFalse(binder.isAlive());
            assertFalse(abandoner.isAlive());
        }
    }

    @Test
    void staleGenerationAndDuplicatedBorrowedMemoryDoNotAcquireAuthority() {
        var bridge = new TaskRegionPermitBridge();
        var own = bridge.admit(List.of(write(0, 64)), SOURCE, RESOLVED);
        var forbidden = new TaskAccessVerifier.CoherentTypedClaim(
                new TaskAccessVerifier.Claim(
                        POSITIONS.range(64, 128, true), TaskAccessVerifier.Mode.WRITE),
                Ast.TypeRef.borrowed(new Ast.TypeRef("int", List.of(), false), true),
                new Types.Borrow(Types.Primitive.INT, true));
        assertThrows(IllegalArgumentException.class,
                () -> bridge.admit(List.of(forbidden), SOURCE, RESOLVED));
        var stale = new TaskAccessVerifier.Place(
                new TaskAccessVerifier.Root("PROCESS", 911, 2, true),
                POSITIONS.projections());
        var wrongGeneration = new TaskAccessVerifier.CoherentTypedClaim(
                new TaskAccessVerifier.Claim(
                        stale.range(128, 192, true), TaskAccessVerifier.Mode.WRITE),
                new Ast.TypeRef("int", List.of(), false),
                Types.Primitive.INT);
        assertThrows(IllegalStateException.class,
                () -> bridge.admit(List.of(wrongGeneration), SOURCE, RESOLVED));
        assertEquals(1, bridge.activeChildren());
        own.bindPhysicalExit().run();
        bridge.finishScope();
        assertThrows(IllegalStateException.class, own::requestCancel);
    }
}
