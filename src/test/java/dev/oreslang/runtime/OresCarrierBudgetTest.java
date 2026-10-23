package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class OresCarrierBudgetTest {

    private static OresCarrierBudget parse(String configured) {
        String previous = System.getProperty(OresCarrierBudget.PROPERTY);
        try {
            if (configured == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, configured);
            return OresCarrierBudget.configured();
        } finally {
            if (previous == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, previous);
        }
    }

    @Test void absentPropertyDoesNotChangeLegacyTopology() {
        assertNull(parse(null));
    }

    @Test void controlActorsAndLifecycleObserversFitEverySupportedBudget() {
        for (int cap : new int[] {5, 6, 7, 8, 10, 16, 64, 255, 256}) {
            OresCarrierBudget budget = parse(Integer.toString(cap));
            assertEquals(cap, budget.maximum());
            assertEquals(1, budget.controlReserve());
            assertTrue(budget.privateMaximum() >= 1);
            assertEquals(budget.privateMaximum(), budget.sharedMaximum());
            assertEquals(budget.privateMaximum(), budget.untrustedMaximum());
            assertTrue(budget.lifecycleObserverMaximum() >= 1);
            assertTrue(budget.lifecycleObserverMaximum() <= 4);
            assertEquals(cap, budget.allocatedCeiling(),
                    "configured VM ceiling must account for every runtime execution carrier");
        }
    }

    @Test void invalidBudgetFailsClosedRatherThanDisablingTheGuard() {
        for (String bad : new String[] {"", " ", "0", "-1", "3", "4", "257",
                "99999999999999999", "1.5", "8 ", "wat", "+5", "0x10", "5\n"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(bad), bad);
        }
    }

    @Test void boundedVmKeepsControlAndLifecycleCapacityColdUntilDemand() throws Exception {
        String previous = System.getProperty(OresCarrierBudget.PROPERTY);
        String previousBackend = System.getProperty("ores.runtime.carriers");
        String previousAffinity = System.getProperty("ores.actor.affinity");
        try {
            System.setProperty(OresCarrierBudget.PROPERTY, "5");
            System.setProperty("ores.runtime.carriers", "java");
            System.setProperty("ores.actor.affinity", "none");
            OresVM vm = OresVM.create(Runnable::run);
            try {
                assertEquals(0, vm.controlCarrierCount());
                assertEquals(0, vm.nioReactorCount());
                assertEquals(5, vm.carrierBudget().allocatedCeiling());
                assertEquals(1, vm.carrierBudget().lifecycleObserverMaximum());
                vm.executeControl(() -> { }).get(3, TimeUnit.SECONDS);
                assertEquals(1, vm.controlCarrierCount(),
                        "CONTROL always retains an admissible carrier in the bounded VM");

                var actorConfig = new ActorRuntime.DispatcherConfig(1, 1, 16, 32);
                var actors = new ActorRuntime(IsolatePolicy.developer(), actorConfig,
                        ActorRuntime.TurnExecutor.direct(),
                        ActorRuntime.ControlDispatcher.unavailable(),
                        vm.carrierBudget(),
                        vm.carrierPermitPool());
                CountDownLatch observerEntered = new CountDownLatch(1);
                CountDownLatch releaseObserver = new CountDownLatch(1);
                try {
                    assertEquals(0, actors.startedActorCarrierCount(),
                            "three actor domains start cold even with global budget");
                    assertEquals(0, actors.lifecycleObserverCarrierCount(),
                            "lifecycle observer domain must also start cold");

                    var actor = actors.<String>spawnShared(
                            () -> (message, context) -> context.self().stop());
                    actor.done().whenCompleteRuntime((ignored, failure) -> {
                        observerEntered.countDown();
                        try {
                            releaseObserver.await(3, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    actor.send("stop");
                    assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
                    assertTrue(observerEntered.await(3, TimeUnit.SECONDS));
                    assertEquals(1, actors.lifecycleObserverCarrierCount());
                    assertTrue(actors.lifecycleObserverCarrierCount()
                                    <= vm.carrierBudget().lifecycleObserverMaximum());
                    assertTrue(vm.controlCarrierCount()
                                    + actors.startedActorCarrierCount()
                                    + actors.lifecycleObserverCarrierCount()
                                    <= vm.carrierBudget().maximum(),
                            "runtime-owned execution carriers must stay within configured ceiling");
                    assertEquals(
                            vm.controlCarrierCount()
                                    + actors.startedActorCarrierCount()
                                    + actors.lifecycleObserverCarrierCount(),
                            vm.carrierPermitPool().totalHeld(),
                            "shared coordinator must equal all live JVM execution carriers");
                } finally {
                    releaseObserver.countDown();
                    actors.close();
                }
            } finally {
                vm.close();
            }
        } finally {
            if (previous == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, previous);
            if (previousBackend == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previousBackend);
            if (previousAffinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousAffinity);
        }
    }

    @Test void budgetedControlDomainCanBorrowIdleGlobalPermits() throws Exception {
        String previous = System.getProperty(OresCarrierBudget.PROPERTY);
        String previousBackend = System.getProperty("ores.runtime.carriers");
        String previousAffinity = System.getProperty("ores.actor.affinity");
        try {
            System.setProperty(OresCarrierBudget.PROPERTY, "8");
            System.setProperty("ores.runtime.carriers", "java");
            System.setProperty("ores.actor.affinity", "none");
            OresVM vm = OresVM.create(Runnable::run);
            CountDownLatch entered = new CountDownLatch(4);
            CountDownLatch release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 4; i++) {
                    vm.dispatchHostIoMapping(() -> {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                assertTrue(entered.await(3, TimeUnit.SECONDS),
                        "CONTROL should borrow all three spare permits while preserving four reserves");
                assertEquals(4, vm.controlCarrierCount());
                var pressure = vm.carrierPermitSnapshot();
                assertNotNull(pressure);
                assertEquals(4, pressure.controlHeld());
                assertEquals(4, pressure.totalHeld());
                assertEquals(4, pressure.free());
                assertEquals(0L, pressure.deniedCapacity());
                assertEquals(0L, pressure.deniedReserveProtection());
            } finally {
                release.countDown();
                vm.close();
            }
        } finally {
            if (previous == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, previous);
            if (previousBackend == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previousBackend);
            if (previousAffinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousAffinity);
        }
    }

    @Test void defaultTopologyDoesNotBorrowControlAgainstUntrackedActorCapacity()
            throws Exception {
        String previous = System.getProperty(OresCarrierBudget.PROPERTY);
        String previousBackend = System.getProperty("ores.runtime.carriers");
        String previousAffinity = System.getProperty("ores.actor.affinity");
        try {
            System.setProperty(OresCarrierBudget.PROPERTY, "8");
            System.clearProperty("ores.runtime.carriers");
            System.clearProperty("ores.actor.affinity");
            OresVM vm = OresVM.create(Runnable::run);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch secondEntered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            try {
                vm.dispatchHostIoMapping(() -> {
                    firstEntered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                vm.dispatchHostIoMapping(secondEntered::countDown);

                assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
                assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS),
                        "default/native-affine topology must not borrow CONTROL capacity");
                assertEquals(1, vm.controlCarrierCount());
                assertEquals(1, vm.carrierPermitSnapshot().controlHeld());

                release.countDown();
                assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
            } finally {
                release.countDown();
                vm.close();
            }
        } finally {
            if (previous == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, previous);
            if (previousBackend == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previousBackend);
            if (previousAffinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousAffinity);
        }
    }

    @Test void affinityJvmTopologyKeepsLifecycleObserversOnStaticReservation()
            throws Exception {
        String previous = System.getProperty(OresCarrierBudget.PROPERTY);
        String previousBackend = System.getProperty("ores.runtime.carriers");
        String previousAffinity = System.getProperty("ores.actor.affinity");
        try {
            System.setProperty(OresCarrierBudget.PROPERTY, "8");
            System.setProperty("ores.runtime.carriers", "java");
            System.setProperty("ores.actor.affinity", "lane");
            OresVM vm = OresVM.create(Runnable::run);
            ActorRuntime actors = null;
            try {
                OresCarrierBudget budget = vm.carrierBudget();
                assertNotNull(budget);
                var config = new ActorRuntime.DispatcherConfig(
                        budget.privateMaximum(),
                        budget.sharedMaximum(),
                        16,
                        32);
                actors = new ActorRuntime(
                        IsolatePolicy.developer(),
                        config,
                        ActorRuntime.TurnExecutor.direct(),
                        ActorRuntime.ControlDispatcher.unavailable(),
                        budget,
                        vm.carrierPermitPool());

                var field = ActorRuntime.class.getDeclaredField(
                        "lifecycleObserverDispatcher");
                field.setAccessible(true);
                var observerPool = (java.util.concurrent.ThreadPoolExecutor)
                        field.get(actors);
                assertEquals(
                        budget.lifecycleObserverMaximum(),
                        observerPool.getMaximumPoolSize(),
                        "affinity lanes are not individually borrow-safe; lifecycle "
                                + "workers must stay on the static reservation");
                assertEquals(1, vm.carrierPermitSnapshot().maximum()
                        - (budget.privateMaximum() * 3)
                        - budget.lifecycleObserverMaximum(),
                        "fixture must retain exactly the CONTROL static reserve");
            } finally {
                if (actors != null) actors.close();
                vm.close();
            }
        } finally {
            if (previous == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, previous);
            if (previousBackend == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previousBackend);
            if (previousAffinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousAffinity);
        }
    }

    @Test void budgetedJvmActorDomainCanBorrowIdleGlobalPermits() throws Exception {
        String previous = System.getProperty(OresCarrierBudget.PROPERTY);
        String previousBackend = System.getProperty("ores.runtime.carriers");
        String previousAffinity = System.getProperty("ores.actor.affinity");
        try {
            System.setProperty(OresCarrierBudget.PROPERTY, "8");
            System.setProperty("ores.runtime.carriers", "java");
            System.setProperty("ores.actor.affinity", "none");
            OresVM vm = OresVM.create(Runnable::run);
            CountDownLatch entered = new CountDownLatch(4);
            CountDownLatch release = new CountDownLatch(1);
            ActorRuntime actors = null;
            try {
                OresCarrierBudget budget = vm.carrierBudget();
                assertNotNull(budget);
                assertEquals(4, budget.borrowingMaximumPerDomain());

                var config = new ActorRuntime.DispatcherConfig(
                        budget.privateMaximum(),
                        budget.sharedMaximum(),
                        16,
                        32);
                actors = new ActorRuntime(
                        IsolatePolicy.developer(),
                        config,
                        ActorRuntime.TurnExecutor.direct(),
                        ActorRuntime.ControlDispatcher.unavailable(),
                        budget,
                        vm.carrierPermitPool());

                var refs = new java.util.ArrayList<ActorRuntime.ActorRef<String>>();
                for (int i = 0; i < 4; i++) {
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
                        "shared actor domain should borrow two idle permits beyond its preferred slice");
                var scheduler = actors.schedulerSnapshot();
                assertEquals(4, scheduler.sharedDomain().maximumCarriers());
                assertEquals(4, scheduler.sharedDomain().activeCarriers());

                var global = actors.globalCarrierPressure().orElseThrow();
                assertEquals(8, global.maximum());
                assertEquals(4, global.held());
                assertEquals(4, global.free());
                assertEquals(4, global.sharedHeld());
                assertEquals(0, global.privateHeld());
                assertEquals(0, global.untrustedHeld());
                assertEquals(0, global.lifecycleHeld());
                assertEquals(0, global.controlHeld());
                assertEquals(0L, global.deniedCapacity());
                assertEquals(0L, global.deniedReserveProtection());

                release.countDown();
                for (var ref : refs) {
                    ref.drain().get(5, TimeUnit.SECONDS);
                    ref.stop();
                    assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
                }
            } finally {
                release.countDown();
                if (actors != null) actors.close();
                vm.close();
            }
        } finally {
            if (previous == null) System.clearProperty(OresCarrierBudget.PROPERTY);
            else System.setProperty(OresCarrierBudget.PROPERTY, previous);
            if (previousBackend == null) System.clearProperty("ores.runtime.carriers");
            else System.setProperty("ores.runtime.carriers", previousBackend);
            if (previousAffinity == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousAffinity);
        }
    }

    @Test void forgedBudgetCannotOvercommitOrEraseRequiredReserves() {
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
