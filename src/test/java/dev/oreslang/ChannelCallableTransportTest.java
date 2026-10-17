package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

/** Channels carry recursively data-only values; callbacks remain local. */
final class ChannelCallableTransportTest {
    @Test
    void channelPayloadsRejectFirstClassFunctionsTransitively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Fnc<int, int>> handlers): void {
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Option<Fnc<int, int>>> handlers): void {
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class HandlerBox as
                  val Fnc<int, int> callback;
                end
                fnc bad(Channel<HandlerBox> handlers): void {
                  return;
                }
                """)));
    }

    @Test
    void channelNewRejectsCallablePayloadsBeforeAnyWriteOccurs() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> handlers =
                      Channel.new<Fnc<int, int>>(1);
                  return;
                }
                """)));
    }

    @Test
    void borrowedPayloadTypesStillCannotOutliveChannelStorage() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<&int> borrowed): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Option<&int>> payload): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Array<&int>> payload): void { return; }
                """)));
    }

    @Test
    void channelPayloadsRejectRuntimeCapabilitiesTransitively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Future<int>> pending): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Channel<int>> nested): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Option<Future<int>>> nested): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class CapabilityBox as
                  val Channel<int> input;
                end
                fnc bad(Channel<CapabilityBox> nested): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Mutex<int>> locked): void { return; }
                """)));
    }

    @Test
    void localCallbacksAndOrdinaryDataChannelsRemainLegal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc apply(Fnc<int, int> callback, int value): int {
                  return callback(value);
                }

                fnc ok(): void {
                  val Fnc<int, int> twice = |value| -> { return value * 2; };
                  val int result = apply(twice, 21);
                  val Channel<int> values = Channel.new<int>(1);
                  writech values, result;
                  return;
                }
                """)));
    }
}
