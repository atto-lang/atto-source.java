package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fault injection into the same retirement sequence as OresContext.close().
 * This proves independent stage attempts but not native worker exit.
 */
final class OresContextRetirementStagesTest {
    @Test
    void fatalActorSealAndNativeJoinErrorsStillAttemptAllIndependentStages() {
        List<String> events = new ArrayList<>();
        AssertionError seal = new AssertionError("actor-seal-fatal");
        AssertionError io = new AssertionError("native-io-join-fatal");
        IllegalStateException failed = assertThrows(IllegalStateException.class,
                () -> OresContext.joinRetirementStages(
                        () -> { events.add("actor-seal"); throw seal; },
                        () -> events.add("native-revoke"),
                        () -> events.add("actor-join"),
                        () -> { events.add("native-join"); throw io; }));
        assertEquals(List.of("actor-seal", "native-revoke", "actor-join", "native-join"),
                events, "an Error cannot suppress independent native cancellation");
        assertTrue(failed.getMessage().contains("retirement incomplete"));
        assertSame(seal, failed.getCause());
        assertEquals(1, failed.getCause().getSuppressed().length);
        assertSame(io, failed.getCause().getSuppressed()[0]);
    }

    @Test
    void nativeRevocationErrorCannotSuppressActorAndNativePhysicalJoins() {
        List<String> events = new ArrayList<>();
        var revoke = new LinkageError("native-revocation");
        var joinActor = new IllegalStateException("actor-physical-join");
        var failed = assertThrows(IllegalStateException.class,
                () -> OresContext.joinRetirementStages(
                        () -> events.add("actor-seal"),
                        () -> { events.add("native-revoke"); throw revoke; },
                        () -> { events.add("actor-join"); throw joinActor; },
                        () -> events.add("native-join")));
        assertEquals(List.of("actor-seal", "native-revoke", "actor-join", "native-join"), events);
        assertSame(revoke, failed.getCause());
        assertEquals(1, revoke.getSuppressed().length);
        assertSame(joinActor, revoke.getSuppressed()[0]);
    }

    @Test
    void sharedFatalErrorObjectCannotBeAddedAsItsOwnSuppression() {
        var same = new AssertionError("common-failure");
        List<String> events = new ArrayList<>();
        var failed = assertThrows(IllegalStateException.class,
                () -> OresContext.joinRetirementStages(
                        () -> { events.add("seal"); throw same; },
                        () -> { events.add("revoke"); throw same; },
                        () -> { events.add("actor"); throw same; },
                        () -> { events.add("native"); throw same; }));
        assertEquals(List.of("seal", "revoke", "actor", "native"), events);
        assertSame(same, failed.getCause());
        assertEquals(0, same.getSuppressed().length);
    }

    @Test
    void allSuccessfulStagesRetireInRevokeBeforeJoinOrder() {
        List<String> events = new ArrayList<>();
        assertDoesNotThrow(() -> OresContext.joinRetirementStages(
                () -> events.add("actor-seal"),
                () -> events.add("native-revoke"),
                () -> events.add("actor-join"),
                () -> events.add("native-join")));
        assertEquals(List.of("actor-seal", "native-revoke", "actor-join", "native-join"), events);
    }
}
