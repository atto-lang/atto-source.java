package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

/**
 * Run the same standalone JDK receipt vectors in source Maven CI.
 * Host-only evidence; not Graal guest ActorRef or authenticated remote IPC.
 */
class IpcReceiptParityTest {
    @Test
    void boundedCorrelationsAndConcurrentAck() throws Exception {
        IpcReceiptCorrelationSelfTest.main(new String[0]);
    }

    @Test
    void terminalFutureAndTransportLossRaces() throws Exception {
        IpcReceiptCompletionBridgeSelfTest.main(new String[0]);
    }

    @Test
    void oneShotPhysicalBridgeBindingRejectsReconnectAba() {
        IpcReceiptPhysicalBindingSelfTest.main(new String[0]);
    }

    @Test
    void closeAndAdmissionLinearizeWithoutAmbiguousNeverSentWindow() throws Exception {
        IpcSessionAdmissionCloseRaceSelfTest.main(new String[0]);
    }

    @Test
    void trustedPhysicalSessionWriteAndCloseDrain() throws Exception {
        IpcTransportSessionLeaseSelfTest.main(new String[0]);
    }

    @Test
    void authenticatedLoopbackSocketFramesAndReceipts() throws Exception {
        IpcAuthenticatedSocketTransportSelfTest.main(new String[0]);
    }

    @Test
    void authenticatedSocketFramesCrossForkedJvmBoundary() throws Exception {
        IpcAuthenticatedSocketTwoProcessSelfTest.main(new String[0]);
    }

    @Test
    void actorCarriersCannotEnterBlockingIpcHostOperations() throws Exception {
        IpcBlockingGuardSelfTest.main(new String[0]);
    }
}
