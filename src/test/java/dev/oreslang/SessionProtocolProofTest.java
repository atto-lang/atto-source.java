package dev.oreslang;

import dev.oreslang.types.SessionProtocolProof;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static dev.oreslang.types.SessionProtocolProof.Action.*;
import static dev.oreslang.types.SessionProtocolProof.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

final class SessionProtocolProofTest {
    private static SessionProtocolProof.Block b(List<SessionProtocolProof.Action> ops,
                                                Integer... next) {
        return new SessionProtocolProof.Block(ops, List.of(next));
    }
    private static SessionProtocolProof.Graph graph(SessionProtocolProof.Block... blocks) {
        return new SessionProtocolProof.Graph(42, 7, List.of(blocks));
    }

    @Test void acceptsAuthCaptureAndCloseOverOneAffineSession() {
        var proof = SessionProtocolProof.verify(graph(b(List.of(
                MINT, ADMIT_AUTH, AUTH_TIMEOUT, SETTLE_AUTH,
                ADMIT_CAPTURE, SETTLE_CAPTURE, CLOSE))));
        assertEquals(Set.of(CLOSED), proof.outgoing().get(0));
    }

    @Test void rejectsPrematureCaptureAndReusedConsumedSession() {
        assertThrows(IllegalArgumentException.class, () ->
                SessionProtocolProof.verify(graph(b(List.of(MINT, ADMIT_CAPTURE)))));
        assertThrows(IllegalArgumentException.class, () ->
                SessionProtocolProof.verify(graph(b(List.of(MINT, ADMIT_AUTH, AUTH_TIMEOUT, REUSE_CREATED)))));
        assertThrows(IllegalArgumentException.class, () ->
                SessionProtocolProof.verify(graph(b(List.of(MINT, ADMIT_AUTH, SETTLE_AUTH, ADMIT_CAPTURE,
                        REUSE_AUTHORIZED)))));
        assertThrows(IllegalArgumentException.class, () ->
                SessionProtocolProof.verify(graph(b(List.of(MINT, ADMIT_AUTH, AUTH_TIMEOUT, CLOSE)))));
    }

    @Test void rejectsForkedCopyAndGenerationRebinding() {
        assertThrows(IllegalArgumentException.class, () ->
                SessionProtocolProof.verify(graph(b(List.of(MINT, COPY_HANDLE)))));
        assertThrows(IllegalArgumentException.class, () ->
                SessionProtocolProof.verify(graph(b(List.of(MINT, CROSS_GENERATION)))));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionProtocolProof.Graph(0, 7, List.of(b(List.of(MINT)))));
    }

    @Test void joinsAllPossibleIncomingStatesAndRejectsWrongPhaseOnOnePath() {
        var validBranch = graph(
                b(List.of(MINT), 1, 2),
                b(List.of(ADMIT_AUTH, SETTLE_AUTH), 3),
                b(List.of(REUSE_CREATED), 3),
                b(List.of()));
        var proof = SessionProtocolProof.verify(validBranch);
        assertEquals(Set.of(CREATED, AUTHORIZED), proof.incoming().get(3));
        assertThrows(IllegalArgumentException.class, () ->
                SessionProtocolProof.verify(graph(
                        b(List.of(MINT), 1, 2),
                        b(List.of(ADMIT_AUTH, SETTLE_AUTH), 3),
                        b(List.of(REUSE_CREATED), 3),
                        b(List.of(ADMIT_CAPTURE)))));
    }

    @Test void rejectsProtocolLoopsThatCanReenterConsumedPhase() {
        assertThrows(IllegalArgumentException.class, () ->
                SessionProtocolProof.verify(graph(
                        b(List.of(MINT), 1),
                        b(List.of(ADMIT_AUTH), 1))));
    }

    @Test void refusesInvalidCFGEdgesAndMissingBlocks() {
        assertThrows(IllegalArgumentException.class, () ->
                graph(b(List.of(MINT), 7)));
        assertThrows(IllegalArgumentException.class, () ->
                new SessionProtocolProof.Graph(1, 1, List.of()));
    }
}
