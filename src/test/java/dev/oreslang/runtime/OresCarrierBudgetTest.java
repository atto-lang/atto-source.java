package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class OresCarrierBudgetTest {
    private record Props(String budget, String backend, String affinity, String control) {
        static Props capture() {
            return new Props(
                    System.getProperty(OresCarrierBudget.PROPERTY),
                    System.getProperty("ores.runtime.carriers"),
                    System.getProperty("ores.actor.affinity"),
                    System.getProperty("ores.runtime.control.parallelism"));
        }
        void restore() {
            restoreProperty(OresCarrierBudget.PROPERTY, budget);
            restoreProperty("ores.runtime.carriers", backend);
            restoreProperty("ores.actor.affinity", affinity);
            restoreProperty("ores.runtime.control.parallelism", control);
        }
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static OresCarrierBudget parse(String configured) {
        String previous = System.getProperty(OresCarrierBudget.PROPERTY);
        try {
            if (configured == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, configured);
            return OresCarrierBudget.configured();
        } finally {
            restoreProperty(OresCarrierBudget.PROPERTY, previous);
        }
    }

    private static void enableBudgetedJvm(String maximum) {
        System.setProperty(OresCarrierBudget.PROPERTY, maximum);
        System.setProperty("ores.runtime.carriers", "java");
        System.setProperty("ores.actor.affinity", "none");
        System.clearProperty("ores.runtime.control.parallelism");
    }

    @Test void absentPropertyDoesNotChangeLegacyTopology() {
        assertNull(parse(null));
    }

    @Test void everySupportedBudgetAccountsForAllReservedDomains() {
        for (int cap : new int[] {5, 6, 7, 8, 10, 16, 64, 255, 256}) {
            OresCarrierBudget budget = parse(Integer.toString(cap));
            assertEquals(cap, budget.maximum());
            assertEquals(1, budget.controlReserve());
            assertTrue(budget.privateMaximum() >= 1);
            assertEquals(budget.privateMaximum(), budget.sharedMaximum());
            assertEquals(budget.privateMaximum(), budget.untrustedMaximum());
            assertTrue(budget.lifecycleMaximum() >= 1);
            assertTrue(budget.lifecycleMaximum() <= 4);
            assertEquals(cap, budget.allocatedCeiling());
        }
    }

    @Test void malformedBudgetFailsClosed() {
        for (String bad : new String[] {
                "", " ", "0", "-1", "3", "4", "257", "99999999999999999",
                "1.5", "8 ", "wat", "+5", "0x10", "5\n"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(bad), bad);
        }
    }

    @Test void globalBudgetRequiresTrackedJvmNonAffineTopology() {
        Props prior = Props.capture();
        try {
            System.setProperty(OresCarrierBudget.PROPERTY, "8");
            System.setProperty("ores.runtime.carriers", "native");
            System.setProperty("ores.actor.affinity", "none");
            assertThrows(IllegalStateException.class, () -> OresVM.create(Runnable::run));

            System.setProperty("ores.runtime.carriers", "java");
            System.setProperty("ores.actor.affinity", "lane");
            assertThrows(IllegalStateException.class, () -> OresVM.create(Runnable::run));
        } finally {
            prior.restore();
        }
    }

    @Test void explicitControlParallelismCannotEraseProtectedReserve() {
        Props prior = Props.capture();
        try {
            enableBudgetedJvm("8");
            System.setProperty("ores.runtime.control.parallelism", "2");
            assertThrows(IllegalArgumentException.class, () -> OresVM.create(Runnable::run));
        } finally {
            prior.restore();
        }
    }

    @Test void budgetedVmPrestartsExactlyOneTrackedControlCarrier() {
        Props prior = Props.capture();
        try {
            enableBudgetedJvm("8");
            OresVM vm = OresVM.create(Runnable::run);
            try {
                assertEquals(1, vm.controlCarrierCount());
                var snapshot = vm.carrierPermitSnapshot();
                assertNotNull(snapshot);
                assertEquals(8, snapshot.maximum());
                assertEquals(1, snapshot.totalHeld());
                assertEquals(1, snapshot.controlHeld());
                assertEquals(7, snapshot.free());
            } finally {
                vm.close();
            }
            assertEquals(0, vm.carrierPermitSnapshot().totalHeld(),
                    "CONTROL permit must retire only after its physical worker exits");
        } finally {
            prior.restore();
        }
    }

    @Test void physicalStopVirtualTasksDoNotConsumePlatformCarrierPermits() throws Exception {
        Props prior = Props.capture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        try {
            enableBudgetedJvm("5");
            OresVM vm = OresVM.create(Runnable::run);
            try {
                vm.executePhysicalStop(() -> {
                    virtual.set(Thread.currentThread().isVirtual());
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                assertTrue(virtual.get(), "physical STOP revoker must remain virtual-thread lifecycle work");

                var snapshot = vm.carrierPermitSnapshot();
                assertEquals(1, snapshot.totalHeld(),
                        "virtual physical STOP work must not consume a dedicated platform carrier permit");
                assertEquals(1, snapshot.controlHeld());
                assertEquals(0, snapshot.lifecycleHeld());
                assertEquals(4, snapshot.free());
            } finally {
                release.countDown();
                vm.close();
            }
            assertEquals(0, vm.carrierPermitSnapshot().totalHeld());
        } finally {
            release.countDown();
            prior.restore();
        }
    }

    @Test void nioBootstrapCompletionWorkerUsesLifecyclePermit() throws Exception {
        Props prior = Props.capture();
        CountDownLatch release = new CountDownLatch(1);
        try {
            enableBudgetedJvm("5");
            OresVM vm = OresVM.create(Runnable::run);
            var pipe = java.nio.channels.Pipe.open();
            try {
                pipe.source().configureBlocking(false);
                CountDownLatch callbackEntered = new CountDownLatch(1);
                var ready = vm.nioReadiness().watch(
                        pipe.source(), java.nio.channels.SelectionKey.OP_READ);
                ready.whenCompleteRuntime((events, failure) -> {
                    callbackEntered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[] {1}));
                assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));

                var snapshot = vm.carrierPermitSnapshot();
                assertEquals(2, snapshot.totalHeld(),
                        "prestarted CONTROL plus one NIO completion worker are tracked");
                assertEquals(1, snapshot.controlHeld());
                assertEquals(1, snapshot.lifecycleHeld(),
                        "NIO bootstrap/completion helper belongs to LIFECYCLE reserve");
                assertEquals(3, snapshot.free());
            } finally {
                release.countDown();
                pipe.source().close();
                pipe.sink().close();
                vm.close();
            }
            assertEquals(0, vm.carrierPermitSnapshot().totalHeld());
        } finally {
            release.countDown();
            prior.restore();
        }
    }

    @Test void sharedActorDomainBorrowsIdlePermitsWithoutConsumingOtherReserves()
            throws Exception {
        Props prior = Props.capture();
        CountDownLatch release = new CountDownLatch(1);
        try {
            enableBudgetedJvm("8");
            OresVM vm = OresVM.create(Runnable::run);
            ActorRuntime actors = null;
            try {
                OresCarrierBudget budget = vm.carrierBudget();
                var config = new ActorRuntime.DispatcherConfig(
                        budget.privateMaximum(),
                        budget.sharedMaximum(),
                        16,
                        64);
                actors = new ActorRuntime(
                        IsolatePolicy.developer(),
                        config,
                        ActorRuntime.TurnExecutor.direct(),
                        ActorRuntime.ControlDispatcher.unavailable(),
                        budget,
                        vm.carrierPermitPool());

                CountDownLatch entered = new CountDownLatch(4);
                var refs = new ArrayList<ActorRuntime.ActorRef<String>>();
                for (int i = 0; i < 4; ++i) {
                    var ref = actors.<String>spawnShared(() -> (message, context) -> {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    refs.add(ref);
                    ref.send("block");
                }

                assertTrue(entered.await(3, TimeUnit.SECONDS),
                        "shared domain should borrow two idle permits beyond its preferred slice");
                var pressure = vm.carrierPermitSnapshot();
                assertEquals(5, pressure.totalHeld(),
                        "one CONTROL + four SHARED workers should be physically present");
                assertEquals(1, pressure.controlHeld());
                assertEquals(4, pressure.sharedHeld());
                assertEquals(3, pressure.free(),
                        "PRIVATE, UNTRUSTED and LIFECYCLE reserves must remain available");
                assertEquals(0, pressure.privateHeld());
                assertEquals(0, pressure.untrustedHeld());
                assertEquals(0, pressure.lifecycleHeld());
                assertTrue(actors.startedActorCarrierCount() >= 4);
            } finally {
                release.countDown();
                if (actors != null) actors.close();
                vm.close();
            }
            assertEquals(0, vm.carrierPermitSnapshot().totalHeld(),
                    "all actor and CONTROL permits must be returned after physical shutdown");
        } finally {
            release.countDown();
            prior.restore();
        }
    }

    @Test void forgedPartitionCannotOvercommitOrEraseRequiredReserves() {
        assertThrows(IllegalArgumentException.class,
                () -> new OresCarrierBudget(5, 0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OresCarrierBudget(5, 1, 2, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new OresCarrierBudget(5, 1, 1, 1, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new OresCarrierBudget(5, 1, 1, 1, 1, 5));
    }
}
