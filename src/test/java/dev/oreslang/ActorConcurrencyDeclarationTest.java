package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorConcurrencyDeclarationTest {
    private static Ast.ClassDecl actor(String source) {
        return Parser.parse(source).modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void serialKeywordAndExplicitOneOneAreExactlyEquivalent() {
        Ast.ClassDecl shortForm = actor("""
                define serial http actor CriticalSectionActor as
                end
                """);
        Ast.ClassDecl longForm = actor("""
                define http actor CriticalSectionActor as
                    int requestConcurrency = 1;
                    int messageConcurrency = 1;
                end
                """);
        assertEquals(shortForm, longForm);
        assertEquals("http", shortForm.concurrencySpec().protocol());
        assertTrue(shortForm.concurrencySpec().serial());
        assertTrue(shortForm.fields().isEmpty());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define serial http actor CriticalSectionActor as
                end
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define http actor CriticalSectionActor as
                    int requestConcurrency = 1;
                    int messageConcurrency = 1;
                end
                """)));
    }

    @Test
    void parsesHigherLogicalConcurrencyButFailsClosedUntilSchedulerSupportsIt() {
        Ast.ClassDecl actor = actor("""
                define http actor StreamServer as
                    int requestConcurrency = 3;
                    int messageConcurrency = 4;
                end
                """);
        assertEquals(3, actor.concurrencySpec().requestConcurrency());
        assertEquals(4, actor.concurrencySpec().messageConcurrency());
        assertEquals(0, actor.fields().size(),
                "admission limits are declarations, never guest mutable fields");
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define http actor StreamServer as
                    int requestConcurrency = 3;
                    int messageConcurrency = 4;
                end
                """)));
        assertTrue(error.getMessage().contains("multi-continuation mailbox admission"),
                error::getMessage);
    }

    @Test
    void allowsSerialActorAndProtocolVariantsAsContextualKeywords() {
        for (String prefix : new String[] {
                "serial", "serial http", "serial tcp", "serial websocket",
                "http", "tcp", "websocket"
        }) {
            Ast.ClassDecl klass = actor("define " + prefix + " actor Worker as\nend");
            assertNotNull(klass.concurrencySpec(), prefix);
            assertTrue(klass.concurrencySpec().serial(), prefix);
            assertDoesNotThrow(
                    () -> TypeChecker.check(Parser.parse(
                            "define " + prefix + " actor Worker as\nend")), prefix);
        }
    }

    @Test
    void rejectsDuplicateOutOfRangeAndConflictingSerialDeclarations() {
        String head = "define http actor Worker as\n";
        String tail = "end";
        for (String bad : new String[] {
                "int requestConcurrency = 0;",
                "int requestConcurrency = 1025;",
                "int messageConcurrency = 0;",
                "int requestConcurrency = -1;",
                "int requestConcurrency = 1;\nint requestConcurrency = 1;",
                "int messageConcurrency = 2;\nint messageConcurrency = 2;"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(head + bad + "\n" + tail), bad);
        }
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define serial http actor Worker as
                            int requestConcurrency = 2;
                        end
                        """));
    }

    @Test
    void admissionSettingsAreNotAssignableActorState() {
        Ast.ClassDecl actor = actor("""
                define http actor Worker as
                    int requestConcurrency = 1;
                    int messageConcurrency = 1;
                    const int counter = 0;
                end
                """);
        assertEquals(1, actor.fields().size());
        assertEquals("counter", actor.fields().getFirst().name());
    }

    @Test
    void ordinaryActorsExposeOnlyMessageConcurrency() {
        Ast.ClassDecl actor = actor("""
                define actor Worker as
                    int messageConcurrency = 1;
                end
                """);
        assertNotNull(actor.concurrencySpec());
        assertEquals("actor", actor.concurrencySpec().protocol());
        assertTrue(actor.concurrencySpec().serial());
        assertTrue(actor.fields().isEmpty());
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                    int messageConcurrency = 1;
                end
                """)));
        for (String illegal : new String[] {
                "int requestConcurrency = 1;",
                "int requestConcurrency = 3;"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse("define actor Worker as\n" + illegal + "\nend"));
            assertTrue(error.getMessage().contains("ordinary actors expose only messageConcurrency"),
                    error::getMessage);
        }
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                    int messageConcurrency = 4;
                end
                """)));
        assertTrue(error.getMessage().contains("multi-continuation mailbox admission"),
                error::getMessage);
    }

    @Test
    void typedStateBindingsCannotImpersonateActorAdmissionConfiguration() {
        for (String declaration : new String[] {
                "val int requestConcurrency = 3;",
                "let int messageConcurrency = 4;",
                "const int requestConcurrency = 1;"
        }) {
            String source = "define actor Worker as\n"
                    + declaration + "\nend";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), declaration);
            assertTrue(error.getMessage().contains("cannot store"),
                    error::getMessage);
        }
    }

    @Test
    void supportsNetworkActorDeclarationsInsideModules() {
        Ast.Program program = Parser.parse("""
                define module network as
                    define serial websocket actor Connection as
                    end
                end
                """);
        Ast.ClassDecl actor = assertInstanceOf(
                Ast.ClassDecl.class, program.modules().getFirst().declarations().getFirst());
        assertEquals("websocket", actor.concurrencySpec().protocol());
        assertTrue(actor.concurrencySpec().serial());
    }

    @Test
    void protocolActorSpawnNeverPretendsToBeAnHttpListener() {
        Ast.Program program = Parser.parse("""
                define serial http actor HttpEndpoint as
                end
                pub routine main(): void {
                    const endpoint = spawn HttpEndpoint();
                    return;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertTrue(failure.getMessage().contains("request/session adapter"),
                failure::getMessage);
    }

    @Test
    void allNetworkActorKindsMustHaveAdaptersBeforeSpawnOrPipe() {
        for (String protocol : new String[] {"http", "tcp", "websocket"}) {
            String declaration = "define " + protocol + " actor Endpoint as\n"
                    + "  int requestConcurrency = 1;\n"
                    + "  int messageConcurrency = 1;\nend\n";
            for (String spelling : new String[]{
                    "spawn Endpoint()", "spawn new Endpoint()",
                    "spawn (new Endpoint())"
            }) {
                String source = declaration + "pub routine main(): void {\n"
                        + "  val running = " + spelling + ";\n  return;\n}";
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(source)),
                        protocol + " " + spelling);
                assertTrue(failure.getMessage().contains("request/session adapter"),
                        failure::getMessage);
            }
            String pipe = declaration + """
                    define actor Sink as
                      receive(ActorMail<int> mail): void { return; }
                    end
                    pub routine main(): void {
                      val graph = spawn Endpoint() pipe spawn Sink();
                      return;
                    }
                    """;
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(pipe)), protocol);
            assertTrue(failure.getMessage().contains("request/session adapter"),
                    failure::getMessage);
        }
    }

    @Test
    void actorAdmissionSettingNamesCannotBeInstanceOrStaticMethods() {
        for (String actorHeader : new String[] {
                "define actor Worker as",
                "define serial actor Worker as",
                "define http actor Worker as"
        }) {
            for (String setting : new String[] {
                    "requestConcurrency", "messageConcurrency"
            }) {
                for (String method : new String[] {
                        setting + "(): int { return 1; }",
                        "static fnc " + setting + "(): int { return 1; }"
                }) {
                    String source = actorHeader + "\n" + method + "\nend";
                    IllegalArgumentException error = assertThrows(
                            IllegalArgumentException.class,
                            () -> TypeChecker.check(Parser.parse(source)), source);
                    assertTrue(error.getMessage().contains("cannot declare admission setting"),
                            error::getMessage);
                }
            }
        }
    }

    @Test
    void admissionSettingMethodNamesRemainLegalOutsideActors() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Metrics as
                    pub requestConcurrency(): int { return 1; }
                    pub messageConcurrency(): int { return 1; }
                end
                pub routine main(): void { return; }
                """)));
    }

    @Test
    void nonProtocolIdentifiersRemainValid() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc calculate(int serial, int http, int tcp, int websocket): int {
                    return serial + http + tcp + websocket;
                }
                pub routine main(): void { return; }
                """)));
    }
}
