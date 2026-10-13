package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Channels are data-only transports, never executable callable envelopes. */
final class ChannelCallableTransportTest {
    @Test
    void localChannelsRejectFunctionsAndClassesEvenWhenOwned() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class HandlerBox as
                  val Fnc<int, int> callback;
                end
                fnc bad(Channel<HandlerBox> handlers): void { return; }
                """)));

        // A class without a method or a callable field still has nominal
        // identity and is not a statically closed data-only struct/record.
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class PlainBox as
                  val int value;
                end
                fnc bad(Channel<PlainBox> handlers): void { return; }
                """)));
    }

    @Test
    void nestedCallablesNeverBecomeChannelDataThroughAnAggregate() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<{callback: Fnc<int, int>}> c): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Array<Option<Fnc<int, int>>>> c): void { return; }
                """)));
    }

    @Test
    void actorCallableParametersRejectFunctionsTransitively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(Fnc<int, int> callback): void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class HandlerBox as
                  val Fnc<int, int> callback;
                end
                actor fnc bad(HandlerBox box): void { return; }
                """)));
    }

    @Test
    void borrowedPayloadTypesCannotOutliveChannelStorage() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<&int> borrowed): void { return; }
                """)));
    }

    @Test
    void nestedBorrowedPayloadsAreRejectedBeforeChannelCreation() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Option<&int>> payload): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Array<&int>> payload): void { return; }
                """)));
    }

    @Test
    void nbSelectRegistrationOwnsEveryMoveOnlyWriteCandidate() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |x| -> { return x * 2; };
                  nb select {
                    case writech handlers, callback: { }
                  }
                  callback(2);
                  return;
                }
                """)));
    }

    @Test
    void nbSelectCannotRegisterSameMoveOnlyPayloadTwice() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> a = Channel.new<Fnc<int, int>>(1);
                  val Channel<Fnc<int, int>> b = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |x| -> { return x * 2; };
                  nb select {
                    case writech a, callback: { }
                    case writech b, callback: { }
                  }
                  return;
                }
                """)));
    }

    @Test
    void ordinaryDataChannelsRemainLegal() {
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

    @Test
    void callableChannelIsRejectedBeforeRuntimeCreation() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  return;
                }
                """)));
    }

}
