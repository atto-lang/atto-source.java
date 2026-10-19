package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.ThreadSafetyTraits;
import dev.oreslang.types.ThreadSafetyTraits.Trait;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ThreadSafetyTraitsTest {
    private static final ThreadSafetyTraits.NominalProof NO_NOMINALS = (type, trait) -> false;

    private static boolean has(Type type, Trait trait) {
        return ThreadSafetyTraits.has(type, trait, NO_NOMINALS);
    }

    @Test
    void scalarsAndImmutableValuesDeriveBothTraitsTransitively() {
        Type frozen = new Types.Record(Map.of(
                "pos", new Types.Tuple(List.of(Types.Primitive.FLOAT, Types.Primitive.FLOAT)),
                "hp", Types.Primitive.INT), true);
        for (Trait trait : Trait.values()) {
            assertTrue(has(Types.Primitive.INT, trait));
            assertTrue(has(Types.Primitive.STRING, trait));
            assertTrue(has(frozen, trait));
            assertTrue(has(new Types.Named("Option", List.of(frozen)), trait));
            assertTrue(has(new Types.Named("Result", List.of(frozen, Types.Primitive.STRING)), trait));
            assertTrue(has(new Types.Union(List.of(Types.Primitive.INT, Types.Primitive.STRING)), trait));
            assertFalse(has(Types.Primitive.VOID, trait));
        }
    }

    @Test
    void writableContainersAreSendButNotSyncEvenForSyncElements() {
        Type list = new Types.ListType(Types.Primitive.INT);
        Type record = new Types.Record(Map.of("hp", Types.Primitive.INT));
        Type fixedArray = new Types.Tuple(
                List.of(Types.Primitive.INT, Types.Primitive.INT),
                Types.TupleKind.FIXED_ARRAY);
        Type fixedList = new Types.Tuple(
                List.of(Types.Primitive.INT), Types.TupleKind.FIXED_LIST);
        for (Type mutable : List.of(list, record, fixedArray, fixedList)) {
            assertTrue(has(mutable, Trait.SEND), "owned aggregate can move: " + mutable);
            assertFalse(has(mutable, Trait.SYNC), "mutable aggregate cannot be shared: " + mutable);
            assertFalse(has(new Types.Borrow(mutable, false), Trait.SEND),
                    "non-Sync borrowed aggregate cannot cross threads: " + mutable);
        }
        Type readonlyWithMutableNestedField = new Types.Record(Map.of("values", list), true);
        assertTrue(has(readonlyWithMutableNestedField, Trait.SEND));
        assertFalse(has(readonlyWithMutableNestedField, Trait.SYNC));
        assertFalse(has(new Types.Named("Option", List.of(list)), Trait.SYNC));
        assertFalse(has(new Types.Named("Result", List.of(record, Types.Primitive.INT)), Trait.SYNC));
        assertFalse(has(new Types.Union(List.of(list, Types.Primitive.INT)), Trait.SYNC));

        // Even an authorized nominal Sync proof for an element cannot turn
        // the enclosing mutable collection into Sync.
        Type syncOnly = new Types.Named("SyncOnly", List.of());
        ThreadSafetyTraits.NominalProof proof =
                (type, trait) -> type.name().equals("SyncOnly") && trait == Trait.SYNC;
        assertFalse(ThreadSafetyTraits.has(new Types.ListType(syncOnly), Trait.SYNC, proof));
        assertFalse(ThreadSafetyTraits.has(new Types.Record(Map.of("x", syncOnly)), Trait.SYNC, proof));
        assertTrue(ThreadSafetyTraits.has(
                new Types.Record(Map.of("x", syncOnly), true), Trait.SYNC, proof));
    }

    @Test
    void unknownGenericCallableAndCapabilityTypesFailClosed() {
        for (Trait trait : Trait.values()) {
            assertFalse(has(Types.Unknown.INSTANCE, trait));
            assertFalse(has(new Types.Generic("T"), trait));
            assertFalse(has(new Types.Named("UnknownFromHost", List.of()), trait));
            assertFalse(has(new Types.Named("MutexGuard", List.of(Types.Primitive.INT)), trait));
            assertFalse(has(new Types.Named("ActorRef", List.of(Types.Primitive.INT)), trait));
            assertFalse(has(new Types.Named("Future", List.of(Types.Primitive.INT)), trait));
            assertFalse(has(new Types.Function(List.of(Types.Primitive.INT), Types.Primitive.INT), trait));
            assertFalse(has(new Types.ListType(Types.Unknown.INSTANCE), trait));
        }
    }

    @Test
    void mutableAndImmutableBorrowHaveDistinctSendRequirements() {
        Type sendOnly = new Types.Named("SendOnly", List.of());
        ThreadSafetyTraits.NominalProof proof =
                (type, trait) -> type.name().equals("SendOnly") && trait == Trait.SEND;
        assertTrue(ThreadSafetyTraits.has(sendOnly, Trait.SEND, proof));
        assertFalse(ThreadSafetyTraits.has(sendOnly, Trait.SYNC, proof));

        // A shared reference can cross threads only if the referent is Sync.
        assertFalse(ThreadSafetyTraits.has(new Types.Borrow(sendOnly, false), Trait.SEND, proof));
        // An exclusive mutable reference can move when the referent is Send.
        assertTrue(ThreadSafetyTraits.has(new Types.Borrow(sendOnly, true), Trait.SEND, proof));
        // Neither reference provides shared thread access to a non-Sync referent.
        assertFalse(ThreadSafetyTraits.has(new Types.Borrow(sendOnly, true), Trait.SYNC, proof));
        assertTrue(ThreadSafetyTraits.has(new Types.Borrow(Types.Primitive.INT, false), Trait.SEND, proof));
        assertTrue(ThreadSafetyTraits.has(new Types.Borrow(Types.Primitive.INT, true), Trait.SYNC, proof));
    }

    @Test
    void channelIsSendForSendPayloadButNotYetProvenSync() {
        Type channel = new Types.Named("Channel", List.of(Types.Primitive.INT));
        assertTrue(has(channel, Trait.SEND));
        assertFalse(has(channel, Trait.SYNC));
        assertFalse(has(new Types.Named("Channel", List.of(Types.Unknown.INSTANCE)), Trait.SEND));
        assertFalse(has(new Types.Named("Channel", List.of(new Types.Named("MutexGuard", List.of(Types.Primitive.INT)))), Trait.SEND));
        assertFalse(has(new Types.Named("Channel", List.of()), Trait.SEND));
        assertFalse(has(new Types.Named("Option", List.of(channel)), Trait.SYNC));
    }

    @Test
    void asyncBoundaryStillAcceptsOwnedSendChannels() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc poll(Channel<Option<List<int>>> inbox): int {
                  val Option<List<int>> x = await (nb readch inbox);
                  if x.is_none() then
                    return 0;
                  fi
                  return x.unwrap().length;
                }
                """)));
    }

    @Test
    void threadTraitNeverOverridesExistingTaskOrActorTransportRules() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(Channel<Future<int>> inbox): int {
                  return 1;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(Mutex<int> lock): int {
                  return 1;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Receiver as
                  receive(ActorMail<Channel<int>> mail): void {
                    return;
                  }
                end
                """)));
    }
}
