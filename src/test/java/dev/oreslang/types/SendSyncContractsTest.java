package dev.oreslang.types;

import static dev.oreslang.types.Types.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.parser.Parser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The type checker must fail closed; marker evidence is never user-forgeable. */
final class SendSyncContractsTest {
    private static final SendSyncContracts.NominalProof NONE = (type, marker) -> false;

    @Test void primitiveAndStructuralValuesAreSendAndSync() {
        var particle = new Types.Record(Map.of(
                "x", Primitive.FLOAT, "velocity", new ListType(Primitive.FLOAT)));
        assertTrue(SendSyncContracts.isSend(particle, NONE));
        assertTrue(SendSyncContracts.isSync(particle, NONE));
        var rows = new Tuple(List.of(particle, new Named("Option", List.of(Primitive.INT))));
        assertTrue(SendSyncContracts.isSend(rows, NONE));
        assertTrue(SendSyncContracts.isSync(rows, NONE));
    }

    @Test void negativeUnsafeAndGenericTypesHaveNoMarkerEvidence() {
        for (Type type : List.of(Unknown.INSTANCE, new Generic("T"),
                new Not(Primitive.INT), new Named("Shared", List.of(Primitive.INT)),
                new Named("ActorRef", List.of(Primitive.INT)),
                new Named("MutexGuard", List.of(Primitive.INT)),
                new Named("GpuContext", List.of()), new Function(List.of(), Primitive.INT))) {
            assertFalse(SendSyncContracts.isSend(type, NONE), "Send " + type);
            assertFalse(SendSyncContracts.isSync(type, NONE), "Sync " + type);
        }
        assertThrows(IllegalArgumentException.class,
                () -> SendSyncContracts.requireSend(Unknown.INSTANCE, NONE, "spawn"));
    }

    @Test void sharedReferenceSendNeedsSyncNotMerelySend() {
        var movedOnly = new Named("ThreadBoundOwned", List.of());
        SendSyncContracts.NominalProof resolver = (type, marker) ->
                type.name().equals("ThreadBoundOwned") && marker == SendSyncContracts.Marker.SEND;
        assertTrue(SendSyncContracts.isSend(movedOnly, resolver));
        assertFalse(SendSyncContracts.isSync(movedOnly, resolver));
        assertFalse(SendSyncContracts.isSend(new Borrow(movedOnly, false), resolver));
        assertTrue(SendSyncContracts.isSend(new Borrow(movedOnly, true), resolver));
        assertFalse(SendSyncContracts.isSync(new Borrow(movedOnly, true), resolver));
        assertThrows(IllegalArgumentException.class,
                () -> SendSyncContracts.requireSync(movedOnly, resolver, "read-only query"));
    }

    @Test void channelsRequireSendPayloadButNotSyncPayload() {
        var ownedOnly = new Named("Movable", List.of());
        SendSyncContracts.NominalProof resolver = (type, marker) ->
                type.name().equals("Movable") && marker == SendSyncContracts.Marker.SEND;
        assertTrue(SendSyncContracts.isSend(new Named("Channel", List.of(ownedOnly)), resolver));
        assertTrue(SendSyncContracts.isSync(new Named("Channel", List.of(ownedOnly)), resolver));
        assertFalse(SendSyncContracts.isSend(new Named("Channel", List.of(Unknown.INSTANCE)), resolver));
        assertFalse(SendSyncContracts.isSend(new Named("Channel", List.of()), resolver));
    }

    @Test void genericAndForeignNominalsCannotForgeEvidence() {
        assertFalse(SendSyncContracts.isSend(new Named("UnverifiedHandle", List.of()), NONE));
        assertFalse(SendSyncContracts.isSend(new Named("Option", List.of(new Generic("T"))), NONE));
        assertFalse(SendSyncContracts.isSync(
                new Record(Map.of("untrusted", new Named("Future", List.of(Primitive.INT)))), NONE));
        assertFalse(SendSyncContracts.isSend(new Named("Result", List.of(Primitive.INT)), NONE));
    }

    @Test void realTypeCheckerAcceptsOwnedAsyncAndRejectsGuardCapture() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc compute(int x): int {
                    return x + 1;
                }
                """)));
        var invalid = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                async fnc compute(MutexGuard<int> guard): int {
                    return 1;
                }
                """)));
        assertTrue(invalid.getMessage().contains("Send")
                || invalid.getMessage().contains("task-safe"));
    }
}
