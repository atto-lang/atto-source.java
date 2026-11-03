package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Channel<T> moves Sendable data, never executable lexical environments. */
final class ChannelCallableTransportTest {
    private static void rejectCallable(String code) {
        var syntax = assertDoesNotThrow(() -> Parser.parse(code),
                "callable fixture must parse before semantic rejection");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(syntax));
        assertTrue(error.getMessage().contains("cannot transport functions"),
                () -> "expected the callable channel diagnostic, got: " + error.getMessage());
    }

    @Test
    void directFunctionAndFunctionAliasPayloadsAreRejected() {
        rejectCallable("""
                fnc bad(Channel<Fnc<int, int>> handlers): void { return; }
                """);
        rejectCallable("""
                fnc bad(Channel<Function<int, int>> handlers): void { return; }
                """);
    }

    @Test
    void functionValuesCannotEnterChannelsAtConstruction() {
        rejectCallable("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |x| -> { return x * 2; };
                  writech handlers, callback;
                  return;
                }
                """);
    }

    @Test
    void optionalAndCollectionCallablePayloadsAreRejectedRecursively() {
        rejectCallable("""
                fnc bad(Channel<Option<Fnc<int, int>>> handlers): void { return; }
                """);
        rejectCallable("""
                fnc bad(Channel<List<Fnc<int, int>>> handlers): void { return; }
                """);
        rejectCallable("""
                fnc bad(Channel<Array<Fnc<int, int>>> handlers): void { return; }
                """);
        rejectCallable("""
                fnc bad(Channel<Result<int, Fnc<int, int>>> handlers): void { return; }
                """);
    }

    @Test
    void nominalClassCannotHideACallableField() {
        rejectCallable("""
                define class HandlerBox as
                  val Fnc<int, int> callback;
                end
                fnc bad(Channel<HandlerBox> handlers): void { return; }
                """);
        rejectCallable("""
                define class HandlerBox as
                  val Option<Fnc<int, int>> callback;
                end
                fnc bad(Channel<HandlerBox> handlers): void { return; }
                """);
    }

    @Test
    void aDeferredSelectCannotRegisterFunctionPayloads() {
        rejectCallable("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |x| -> { return x * 2; };
                  nb select {
                    case writech handlers, callback: { }
                  }
                  return;
                }
                """);
    }

    @Test
    void smartPointerWrappersCannotLaunderCallablePayloads() {
        for (String wrapper : new String[] {
                "Box<Fnc<int, int>>",
                "Arc<Fnc<int, int>>",
                "Option<Box<Fnc<int, int>>>",
                "Result<int, Arc<Fnc<int, int>>>",
                "SharedMutex<Fnc<int, int>>"
        }) {
            rejectCallable("fnc bad(Channel<" + wrapper + "> channel): void { return; }");
        }
    }

    @Test
    void deeplyNestedCollectionCannotLaunderCallablePayloads() {
        rejectCallable("""
                fnc bad(Channel<Option<Result<int, List<Fnc<int, int>>>>> channel): void {
                  return;
                }
                """);
    }

    @Test
    void sendableIsNotEnoughForChannelMessageData() {
        for (String payload : new String[] {
                "Box<int>", "Arc<int>", "Mutex<int>",
                "SharedMutex<int>", "RefCell<int>", "RwLock<int>",
                "Option<Box<int>>", "List<Arc<int>>",
                "Result<int, SharedMutex<int>>"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "fnc bad(Channel<" + payload + "> input): void { return; }")));
            assertTrue(error.getMessage().contains("MessageData"),
                    () -> "expected data-only rejection for " + payload
                            + " but got: " + error.getMessage());
        }
    }

    @Test
    void aNominalFieldCannotLaunderASynchronizedCapability() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class WrappedState as
                  val Mutex<int> locked;
                end
                fnc bad(Channel<WrappedState> input): void { return; }
                """)));
        assertTrue(error.getMessage().contains("MessageData"),
                () -> "expected nested MessageData rejection, got: " + error.getMessage());
    }

    @Test
    void nominalTypesRequireOwnerFreeMessageDataRepresentation() {
        for (String payload : new String[] {
                "Envelope", "Option<Envelope>", "List<Envelope>",
                "Result<int, Envelope>"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                            "define class Envelope as\n"
                                    + "  val int code;\n"
                                    + "end\n"
                                    + "fnc bad(Channel<" + payload
                                    + "> values): void { return; }")));
            assertTrue(error.getMessage().contains("requires MessageData"),
                    () -> "nominal message must reject owner-bearing type "
                            + payload + ": " + error.getMessage());
        }
    }

    @Test
    void nominalGuestFixtureIsRejectedByTheCompiler() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("contracts", "v1", "fixtures",
                        "channel-reject-nominal-payload.ores"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("requires MessageData"),
                error.getMessage());
    }

    @Test
    void actorRefCapabilityExceptionRequiresAConcreteActorTarget() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<ActorRef<int>> input): void { return; }
                """)));
        assertTrue(error.getMessage().contains("ActorRef target"),
                () -> "opaque ActorRef must not become a channel escape hatch: "
                        + error.getMessage());
    }

    @Test
    void borrowedTypesRemainRejectedForChannels() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Option<&int>> values): void { return; }
                """)));
    }

    @Test
    void nonSendableRuntimeCapabilitiesCannotBeChannelPayloads() {
        for (String type : new String[] {"Future<int>", "Rc<int>"}) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "fnc bad(Channel<" + type + "> value): void { return; }")));
            assertTrue(error.getMessage().contains("requires concrete Sendable data"),
                    () -> "expected Sendable diagnostic for " + type + ", got: " + error.getMessage());
        }
    }

    @Test
    void callbacksStayLocalAndChannelCarriesOnlyTheirResults() {
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
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(Channel<Option<List<int>>> values): void { return; }
                """)));
    }

    @Test
    void actorCallableParametersStillRejectFunctionsTransitively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(Fnc<int, int> callback): void { return; }
                """)));
    }

    @Test
    void dataOnlyChannelRoundTripRunsAtRuntime() throws Exception {
        String program = """
                pub routine main(): void {
                  val Channel<int> values = Channel.new<int>(1);
                  writech values, 42;
                  val int received = readch values;
                  stdio.stdout.write(received);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "data-channel.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nestedBorrowedPayloadsRemainRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Array<&int>> payload): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Option<&int>> payload): void { return; }
                """)));
    }

    @Test
    void actorMessageCannotHideCallableInNominalClass() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class HandlerBox as
                  val Fnc<int, int> callback;
                end
                actor fnc bad(HandlerBox box): void { return; }
                """)));
    }
}
