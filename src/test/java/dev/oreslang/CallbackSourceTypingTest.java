package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CallbackSourceTypingTest {

    @Test
    void futureFromCallbackAndCallbackMembersTypeCheck() {
        String source = """
                async fnc immediate_callback() => int {
                  val ready = Future.from_callback<int>(|completion| -> {
                    completion.resolve(41);
                    return;
                  });
                  return await ready;
                }

                pub async routine main() => void {
                  val value = await immediate_callback();
                  stdio.println(value);
                  return;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void callbackValueSupportsDirectOneShotInvocation() {
        String source = """
                async fnc direct_callback() => int {
                  val ready = Future.from_callback<int>(|completion| -> {
                    completion(41);
                    return;
                  });
                  return await ready;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void callbackResolveRejectsWrongPayloadType() {
        String source = """
                async fnc bad_callback() => int {
                  val ready = Future.from_callback<int>(|completion| -> {
                    completion.resolve("wrong");
                    return;
                  });
                  return await ready;
                }
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("argument")
                        || failure.getMessage().contains("assign")
                        || failure.getMessage().contains("int"),
                failure::getMessage);
    }

    @Test
    void callbackCapabilityCannotBecomeSharedMutexState() {
        String source = """
                async fnc bad_share() => int {
                  val ready = Future.from_callback<int>(|completion| -> {
                    val leaked = SharedMutex.new(completion);
                    completion.resolve(41);
                    return;
                  });
                  return await ready;
                }
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("shared-safe")
                        || failure.getMessage().contains("shareable"),
                failure::getMessage);
    }

    @Test
    void unknownCallbackMemberFailsClosed() {
        String source = """
                async fnc bad_member() => int {
                  val ready = Future.from_callback<int>(|completion| -> {
                    completion.enter_guest();
                    completion.resolve(41);
                    return;
                  });
                  return await ready;
                }
                """;

        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }
}
