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
    void nonProtocolIdentifiersRemainValid() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc calculate(int serial, int http, int tcp, int websocket): int {
                    return serial + http + tcp + websocket;
                }
                pub routine main(): void { return; }
                """)));
    }
}
