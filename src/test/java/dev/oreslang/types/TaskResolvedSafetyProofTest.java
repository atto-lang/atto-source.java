package dev.oreslang.types;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Resolved proof, not guest identity or a runtime shared-storage permit. */
final class TaskResolvedSafetyProofTest {
    private static final ThreadSafetyTraits.NominalProof DENY =
            (named, marker) -> false;

    @Test
    void absenceAndVoidAreNeverOwnedWorkerTransferValues() {
        for (var absent : List.of(Types.Primitive.VOID, Types.Primitive.NULL)) {
            for (var marker : ThreadSafetyTraits.Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(absent, marker, DENY),
                        absent + " must not obtain " + marker);
                assertFalse(ThreadSafetyTraits.has(
                        new Types.Named("Option", List.of(absent)), marker, DENY),
                        "wrapping an invalid value must not launder " + marker);
            }
        }
        for (var scalar : List.of(Types.Primitive.INT, Types.Primitive.BOOL,
                                Types.Primitive.FLOAT, Types.Primitive.STRING)) {
            assertTrue(ThreadSafetyTraits.has(scalar,
                    ThreadSafetyTraits.Trait.SEND, DENY));
        }
    }

    @Test
    void readonlyRecordsDoNotBecomeSyncThroughWritableAliases() {
        var writable = new Types.Record(Map.of("x", Types.Primitive.INT), false);
        var projectedReadonly = new Types.Record(Map.of("x", Types.Primitive.INT), true);
        for (var record : List.of(writable, projectedReadonly)) {
            assertTrue(ThreadSafetyTraits.has(record,
                    ThreadSafetyTraits.Trait.SEND, DENY));
            assertFalse(ThreadSafetyTraits.has(record,
                    ThreadSafetyTraits.Trait.SYNC, DENY));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Borrow(record, false),
                    ThreadSafetyTraits.Trait.SEND, DENY));
            assertFalse(ThreadSafetyTraits.has(
                    new Types.Named("Option", List.of(record)),
                    ThreadSafetyTraits.Trait.SYNC, DENY));
        }
    }

    @Test
    void actorAndRuntimeCapabilitiesCannotClaimWorkerRights() {
        for (var handle : List.of("Arc", "Weak", "Channel", "ActorRef",
                                  "Task", "NativeHandle", "FfiHandle")) {
            var candidate = new Types.Named(handle, List.of(Types.Primitive.INT));
            for (var marker : ThreadSafetyTraits.Trait.values()) {
                assertFalse(ThreadSafetyTraits.has(candidate, marker,
                        (named, requested) -> true));
            }
        }
    }
}
