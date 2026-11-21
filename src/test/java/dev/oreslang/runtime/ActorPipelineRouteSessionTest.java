package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

/** JUnit entrypoint for the exact standalone reply-correlation regression. */
final class ActorPipelineRouteSessionTest {
    @Test
    void fanoutFaninReplyGenerationAndConcurrentRoundRobin() throws Exception {
        ActorPipelineRouteSessionSmoke.main(new String[0]);
    }
}
