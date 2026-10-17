package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

/** Maven JUnit wrapper for the independently executable pure-Java routing proof. */
final class ActorPipelineRoutePlanTest {
    @Test
    void nominalDAGFanoutRoundRobinKeyAffinityAndConcurrentSelection() throws Exception {
        ActorPipelineRoutePlanStandaloneSmoke.main(new String[0]);
    }
}
