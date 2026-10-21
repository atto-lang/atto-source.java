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

    @Test
    void sameDomainPointerMovesThroughChannelButSenderCannotReuseIt() {
        String setup = """
                define class Box as
                  pub let int value = 7;
                end
                fnc local(): void {
                  val Channel<Pointer<Box>> values = Channel.new<Pointer<Box>>(1);
                  const p = rt ref new Box();
                  writech values, rt take p;
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(setup + "return; }")));
        rejectActor(setup + "const q = rt take p; return; }", "moved");
        rejectActor(setup.replace("rt take p", "rt copy p") + "return; }", "rt copy");
        rejectActor(setup.replace("rt take p", "rt share p") + "return; }", "rt share");
    }

    @Test
    void wrappersAndActorResultsCannotErasePointerTransferRequirements() {
        for (String wrapper : new String[] {
                "Option<Pointer<Box>>", "Result<int, Pointer<Box>>",
                "{item: Pointer<Box>}", "Array<Pointer<Box>>"}) {
            String box = "define class Box as pub let int value = 7; end ";
            rejectActor(box + "actor fnc bad(" + wrapper + " data): void { return; }", "Sendable");
        }
        rejectActor("""
                define class Box as pub let int value = 7; end
                actor fnc bad(): Pointer<Box> { return rt ref new Box(); }
                """, "Sendable");
    }
}
