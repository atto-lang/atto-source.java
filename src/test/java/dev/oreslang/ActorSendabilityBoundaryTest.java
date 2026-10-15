package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor-transfer safety is a separate compiler contract from network
 * serialization and from execution-domain-local Channel<T> ownership.
 */
final class ActorSendabilityBoundaryTest {
    private void rejectActor(String source, String expected) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    @Test
    void actorParametersCannotLaunderUniquePointerOwnership() {
        rejectActor("""
                define class Box as
                  pub let int value = 7;
                end
                actor fnc bad(Pointer<Box> p): void { return; }
                """, "Sendable");
    }

    @Test
    void nestedActorPointerParametersCannotLaunderUniqueOwnership() {
        rejectActor("""
                define class Box as
                  pub let int value = 7;
                end
                actor fnc bad(Array<Pointer<Box>> values): void { return; }
                """, "Sendable");
    }

    @Test
    void classWithOnlyDataFieldsIsNotAutomaticallyActorSendable() {
        rejectActor("""
                define class WireOnly as
                  pub let String label = "ok";
                end
                actor fnc bad(WireOnly data): void { return; }
                """, "Sendable");
    }

    @Test
    void localDataCanStillBePassedAcrossOrdinaryChannel() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): void {
                  val Channel<int> values = Channel.new<int>(1);
                  writech values, 7;
                  return;
                }
                """)));
    }

    @Test
    void borrowedPayloadsStillCannotEnterChannelStorage() {
        rejectActor("""
                fnc bad(Channel<borrow String> values): void { return; }
                """, "borrow");
    }
}
