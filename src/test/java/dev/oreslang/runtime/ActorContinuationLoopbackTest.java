package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Registers the standalone host check in the ordinary Java test suite. */
final class ActorContinuationLoopbackTest {
    @Test
    @Timeout(45)
    void loopbackCompletionCannotReviveSealedContinuation() throws Exception {
        ActorContinuationLoopbackCheck.main(new String[0]);
    }
}
