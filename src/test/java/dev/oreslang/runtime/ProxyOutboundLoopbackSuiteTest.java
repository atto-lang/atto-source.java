package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Maven/JDK25 entry point for the standalone real-socket proxy regressions.
 *
 * The standalone classes remain directly runnable by the exact-source shell
 * harness; this suite lets reviewed private-CI plans execute the same behavior
 * without authorizing a shell interpreter.
 */
@Timeout(90)
final class ProxyOutboundLoopbackSuiteTest {
    @Test void loopbackStatusHeadersAdmissionAndBudget() throws Exception {
        ProxyOutboundLoopbackCheck.main(new String[0]);
    }

    @Test void forwardingWirePreservesOriginSemantics() throws Exception {
        ProxyLoopbackForwardingWireCheck.main(new String[0]);
    }

    @Test void ownerShutdownCancellationAndPhysicalRetirement() throws Exception {
        ProxyOutboundLifecycleCheck.main(new String[0]);
    }

    @Test void responseHandoffRaceIsLinearized() throws Exception {
        ProxyOutboundHandoffRaceCheck.main(new String[0]);
    }

    @Test void streamStartCloseRaceIsLinearized() throws Exception {
        ProxyOutboundStartCloseRaceCheck.main(new String[0]);
    }

    @Test void rawAndConnectionHeaderBudgetsFailClosed() throws Exception {
        ProxyOutboundHeaderBudgetCheck.main(new String[0]);
    }
}
