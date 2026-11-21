package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ActorRuntimeRetirementOrderingTest {

    @Test
    void compilerRetirementPrecedesQuotaAndQuiescencePublication() {
        List<String> order = new ArrayList<>();

        ActorRuntime.retireUntrustedCompilerTransfer(
                () -> order.add("retire"),
                () -> order.add("quota"),
                () -> order.add("quiescent"));

        assertEquals(List.of("retire", "quota", "quiescent"), order);
    }

    @Test
    void retirementFailurePinsQuotaAndSuppressesQuiescenceWitness() {
        List<String> order = new ArrayList<>();
        RuntimeException failure = new RuntimeException("retirement failed");

        RuntimeException observed = assertThrows(
                RuntimeException.class,
                () -> ActorRuntime.retireUntrustedCompilerTransfer(
                        () -> {
                            order.add("retire");
                            throw failure;
                        },
                        () -> order.add("quota"),
                        () -> order.add("quiescent")));

        assertSame(failure, observed);
        assertEquals(List.of("retire"), order);
    }
}
