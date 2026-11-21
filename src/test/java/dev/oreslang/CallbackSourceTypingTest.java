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
    void futureAttachCallbackTypeChecksSynchronousRegistrar() {
        String source = """
                async fnc chained_callback() => int {
                  val source = Future.from_callback<int>(|completion| -> {
                    completion.resolve(40);
                    return;
                  });
                  val chained = source.attach_callback<int>(|value, completion| -> {
                    completion.resolve(value + 2);
                    return;
                  });
                  return await chained;
                }
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void futureAttachCallbackRequiresInlineRegistrarCapabilityScope() {
        String source = """
                fnc detached_registrar(int value, Callback<int> completion) -> void {
                  completion.resolve(value + 2);
                  return;
                }

                async fnc bad_chain() => int {
                  val source = Future.from_callback<int>(|completion| -> {
                    completion.resolve(40);
                    return;
                  });
                  val chained = source.attach_callback<int>(detached_registrar);
                  return await chained;
                }
                """;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("inline callback registrar"),
                failure::getMessage);
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
