package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCpuAffinityConfigTest {
    private static final String MODE = "ores.actor.affinity";
    private static final String CPUS = "ores.actor.cpus";

    @Test void hardAffinityListCapsCpuBudgetAndPreservesOrder() {
        withProperties("cpu", "7, 3,11", () -> {
            assertEquals("cpu", ActorCpuAffinityConfig.mode());
            int[] cpus = ActorCpuAffinityConfig.configuredCpus();
            assertEquals(3, cpus.length);
            assertEquals(7, cpus[0]);
            assertEquals(3, cpus[1]);
            assertEquals(11, cpus[2]);
            assertEquals(3, ActorCpuAffinityConfig.budgetCpuCount(64));
            assertEquals(2, ActorCpuAffinityConfig.budgetCpuCount(2));
        });
    }

    @Test void nonHardModesUseAvailableProcessorBudget() {
        withProperties("lane", null, () -> {
            assertEquals(12, ActorCpuAffinityConfig.budgetCpuCount(12));
            assertEquals(0, ActorCpuAffinityConfig.configuredCpus().length);
        });
        withProperties("none", null, () -> assertEquals(6, ActorCpuAffinityConfig.budgetCpuCount(6)));
    }

    @Test void malformedAndDuplicateCpuListsFailClosed() {
        withProperties("cpu", "1,1", () ->
                assertThrows(IllegalArgumentException.class, ActorCpuAffinityConfig::configuredCpus));
        withProperties("cpu", "1,,2", () ->
                assertThrows(IllegalArgumentException.class, ActorCpuAffinityConfig::configuredCpus));
        withProperties("cpu", "-1", () ->
                assertThrows(IllegalArgumentException.class, ActorCpuAffinityConfig::configuredCpus));
        withProperties("cpu", "abc", () ->
                assertThrows(IllegalArgumentException.class, ActorCpuAffinityConfig::configuredCpus));
        withProperties("cpu", " ", () ->
                assertThrows(IllegalArgumentException.class, ActorCpuAffinityConfig::configuredCpus));
    }

    @Test void invalidModeAndCpuCountFailClosed() {
        withProperties("bogus", null, () ->
                assertThrows(IllegalArgumentException.class, ActorCpuAffinityConfig::mode));
        withProperties("lane", null, () ->
                assertThrows(IllegalArgumentException.class,
                        () -> ActorCpuAffinityConfig.budgetCpuCount(0)));
    }

    private static void withProperties(String mode, String cpus, Runnable body) {
        String oldMode = System.getProperty(MODE);
        String oldCpus = System.getProperty(CPUS);
        try {
            if (mode == null) System.clearProperty(MODE); else System.setProperty(MODE, mode);
            if (cpus == null) System.clearProperty(CPUS); else System.setProperty(CPUS, cpus);
            body.run();
        } finally {
            if (oldMode == null) System.clearProperty(MODE); else System.setProperty(MODE, oldMode);
            if (oldCpus == null) System.clearProperty(CPUS); else System.setProperty(CPUS, oldCpus);
        }
    }
}
