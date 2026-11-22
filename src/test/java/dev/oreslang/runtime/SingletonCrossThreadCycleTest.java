package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

final class SingletonCrossThreadCycleTest {
    @Test
    void crossThreadInitializerCycleAndAcyclicRegression() throws Exception {
        SingletonCrossThreadCycleSmoke.main(new String[0]);
    }
}
