package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static dev.oreslang.runtime.ActorUpgradeEpochGate.ActorKind.*;
import static dev.oreslang.runtime.ActorUpgradeEpochGate.ProtectionDomain.*;
import static dev.oreslang.runtime.ActorUpgradeEpochGate.CodeMode.*;
import static dev.oreslang.runtime.ActorUpgradeEpochGate.LeaseKind.*;

/**
 * Host-gate proofs only: these labels do not instantiate protected address
 * spaces, compile guest code or prove source actor hot reload.
 */
final class ActorHotReloadPlacementAndTaskPinTest {
    private static String digest(char digit) {
        return String.valueOf(digit).repeat(64);
    }

    private static ActorUpgradeEpochGate.Image image(
            char code, ActorUpgradeEpochGate.CodeMode mode) {
        return new ActorUpgradeEpochGate.Image(
                digest(code), digest('1'), digest('2'), digest('3'), mode);
    }

    private static ActorUpgradeEpochGate.Placement placement(
            ActorUpgradeEpochGate.ActorKind kind,
            ActorUpgradeEpochGate.ProtectionDomain isolation) {
        return new ActorUpgradeEpochGate.Placement(kind, isolation, digest('4'));
    }

    @Test
    void untrustedNeverAdmittedIntoTrustedSharedProcess() {
        for (var domain : new ActorUpgradeEpochGate.ProtectionDomain[]{
                TRUSTED_HOST, TRUSTED_PROCESS}) {
            assertThrows(SecurityException.class,
                    () -> placement(UNTRUSTED, domain));
        }
        for (var sandbox : new ActorUpgradeEpochGate.ProtectionDomain[]{
                SANDBOX_PROCESS, SANDBOX_ISOLATE}) {
            assertDoesNotThrow(() -> placement(UNTRUSTED, sandbox));
            assertThrows(SecurityException.class, () -> placement(PRIVATE, sandbox));
            assertThrows(SecurityException.class, () -> placement(SHARED, sandbox));
        }
    }

    @Test
    void trustedPrivateAddressSpaceIsExplicitNotImpliedByActorHeap() {
        var sameProcess = placement(PRIVATE, TRUSTED_HOST);
        var protectedProcess = placement(PRIVATE, TRUSTED_PROCESS);
        assertNotEquals(sameProcess, protectedProcess);
        try (var gate = new ActorUpgradeEpochGate(
                101, protectedProcess, image('a', LLVM_PIC_AOT))) {
            assertEquals(PRIVATE, gate.placement().actorKind());
            assertEquals(TRUSTED_PROCESS, gate.placement().protection());
            assertThrows(SecurityException.class,
                    () -> gate.prepare(sameProcess, image('b', LLVM_ORC_JIT)));
            assertFalse(gate.frozen());
            assertEquals(1, gate.epoch());
        }
    }

    @Test
    void sharedCellAbiAndUntrustedProtectionAreInvariantAcrossUpgrades() {
        var sandbox = placement(UNTRUSTED, SANDBOX_PROCESS);
        try (var gate = new ActorUpgradeEpochGate(
                77, sandbox, image('a', LLVM_PIC_AOT))) {
            var changedSharedSchema = new ActorUpgradeEpochGate.Placement(
                    UNTRUSTED, SANDBOX_PROCESS, digest('5'));
            assertThrows(SecurityException.class,
                    () -> gate.prepare(changedSharedSchema, image('b', LLVM_ORC_JIT)));
            assertThrows(SecurityException.class,
                    () -> gate.prepare(placement(UNTRUSTED, SANDBOX_ISOLATE),
                            image('b', LLVM_ORC_JIT)));
            assertEquals(0, gate.livePins());
            assertFalse(gate.frozen());
            assertEquals(image('a', LLVM_PIC_AOT), gate.image());
        }
    }

    @Test
    void aotToJitToAotIsLegalWithoutChangingActorShapeOrRequiringJit() {
        var trusted = placement(PRIVATE, TRUSTED_PROCESS);
        try (var gate = new ActorUpgradeEpochGate(
                4, trusted, image('a', LLVM_PIC_AOT))) {
            var jit = gate.prepare(trusted, image('b', LLVM_ORC_JIT));
            assertTrue(gate.publish(jit));
            assertEquals(LLVM_ORC_JIT, gate.image().codeMode());
            var aot = gate.prepare(trusted, image('c', LLVM_PIC_AOT));
            assertTrue(gate.publish(aot));
            assertEquals(LLVM_PIC_AOT, gate.image().codeMode());
            var aotAgain = gate.prepare(trusted, image('d', LLVM_PIC_AOT));
            assertTrue(gate.publish(aotAgain));
            assertEquals(4, gate.epoch());
            assertEquals(4, gate.actorIdentity());
            assertEquals(trusted, gate.placement());
        }
    }

    @Test
    void suspendedTaskAndExternalLeasesPreventCodeReclamation() {
        try (var gate = new ActorUpgradeEpochGate(
                8, placement(SHARED, TRUSTED_HOST),
                image('a', GRAAL_NATIVE_AOT))) {
            var pendingTask = gate.pin(SUSPENDED_TASK);
            var ffi = gate.pin(FFI_CALLBACK);
            var shared = gate.pin(SHARED_REGION);
            var output = gate.pin(OUTPUT_READER);
            var candidate = gate.prepare(image('b', GRAAL_JIT));
            assertTrue(gate.permits(pendingTask),
                    "frozen admission must let old pinned cleanup drain");
            assertThrows(IllegalStateException.class, () -> gate.pin(ACTOR_TURN));
            for (var held : new ActorUpgradeEpochGate.Lease[]{
                    pendingTask, ffi, shared, output}) {
                assertFalse(gate.publish(candidate));
                assertEquals(image('a', GRAAL_NATIVE_AOT), gate.image());
                held.close();
                assertFalse(gate.permits(held));
                assertEquals(0, gate.livePins(held.kind()));
            }
            assertEquals(0, gate.livePins());
            assertTrue(gate.publish(candidate));
            assertThrows(IllegalStateException.class, () -> gate.publish(candidate));
        }
    }

    @Test
    void frozenUpgradeWaitsForSuspendedTaskPhysicalExitAcrossThreads()
            throws Exception {
        try (var gate = new ActorUpgradeEpochGate(
                99, placement(SHARED, TRUSTED_HOST), image('a', GRAAL_JIT))) {
            var task = gate.pin(SUSPENDED_TASK);
            var prepared = gate.prepare(image('b', GRAAL_NATIVE_AOT));
            CountDownLatch finishing = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    finishing.countDown();
                    exit.await();
                    // This is the physical guest-continuation cleanup barrier,
                    // NOT cancellation of its result Future.
                    task.close();
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            try {
                assertTrue(finishing.await(2, TimeUnit.SECONDS));
                assertEquals(1, gate.livePins(SUSPENDED_TASK));
                assertFalse(gate.publish(prepared));
                assertEquals(1, gate.epoch());
            } finally {
                exit.countDown();
                worker.join();
            }
            assertNull(failure.get());
            assertEquals(0, gate.livePins());
            assertTrue(gate.publish(prepared));
            assertEquals(2, gate.epoch());
        }
    }

    @Test
    void abortOrCloseNeverChangesCommittedCodeAndNeverDropsPhysicalPins() {
        var gate = new ActorUpgradeEpochGate(
                11, placement(UNTRUSTED, SANDBOX_PROCESS),
                image('a', LLVM_PIC_AOT));
        var activeFfi = gate.pin(FFI_CALLBACK);
        var candidate = gate.prepare(image('b', LLVM_ORC_JIT));
        gate.abort(candidate);
        assertEquals(image('a', LLVM_PIC_AOT), gate.image());
        assertTrue(gate.permits(activeFfi));
        assertFalse(gate.frozen());
        gate.close();
        assertFalse(gate.retired());
        assertFalse(gate.permits(activeFfi));
        assertThrows(IllegalStateException.class,
                () -> gate.prepare(image('c', LLVM_PIC_AOT)));
        activeFfi.close();
        assertTrue(gate.retired());
    }
}
