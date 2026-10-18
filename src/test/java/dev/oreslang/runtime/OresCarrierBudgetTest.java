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
