package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BuiltinActorInheritanceTest {
    private static Ast.ClassDecl firstActor(String source) {
        return Parser.parse(source).modules().getFirst().declarations().stream()
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void parsesBuiltinSerialActorBaseWithBothBodyDelimiters() {
        Ast.ClassDecl braced = firstActor("""
                define actor Worker from serial actor as {
                    on_start(): void { return; }
                }
                """);
        Ast.ClassDecl ended = firstActor("""
                define actor Worker from serial actor as
                    on_start(): void { return; }
                end
                """);
        assertEquals(braced, ended);
        assertEquals(Ast.BuiltinActorBase.SERIAL, braced.builtinActorBase());
        assertEquals(Ast.ActorKind.SHARED, braced.actorKind());
        assertEquals(new Ast.ActorConcurrencySpec("actor", 1, 1), braced.concurrencySpec());
        assertTrue(braced.parents().isEmpty(),
                "built-in serial actor must not be resolved as an ordinary class parent");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker from serial actor as {
                    on_start(): void { return; }
                }
                """)));
    }

    @Test
    void allowsOverridableReceiveAndOnStartHooks() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker from serial actor as
                    let int initialized;

                    on_start(): void {
                        self.initialized = 1;
                        return;
                    }

                    receive(ActorMail<String> mail): void {
                        if self.initialized == 1; do
                            self.send(mail.value);
                        fi
                        self.end();
                        return;
                    }
                end
                """)));
    }

    @Test
    void finalBuiltinLifecycleAndTransportSlotsCannotBeOverridden() {
        for (String reserved : new String[] {
                "send", "stop", "cancel", "end", "endWithCleanup",
                "ready", "done", "outputs", "id",
                "get_ready_signal", "get_done_signal"
        }) {
            String source = "define actor Worker from serial actor as\n"
                    + reserved + "(): void { return; }\nend";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), reserved);
            // Several builtin control names (e.g. end/done) are reserved
            // tokens and must be rejected by the parser before override
            // analysis. Ordinary identifiers must reach the final-slot
            // type-check guard.
            assertTrue(error.getMessage().contains("final builtin actor method")
                            || error.getMessage().contains("Oreslang parse error"),
                    error::getMessage);
        }
    }

    @Test
    void serialParentCannotBeOverriddenViaOrdinaryActorInheritance() {
        Ast.Program valid = Parser.parse("""
                define actor Parent from serial actor as
                    pub label(): String { return "parent"; }
                end
                define actor Child extends Parent as
                    pub label(): String { return "child"; }
                end
                """);
        assertDoesNotThrow(() -> TypeChecker.check(valid));

        Ast.Program forbidden = Parser.parse("""
                define actor Parent from serial actor as
                    receive(ActorMail<String> mail): void { self.end(); return; }
                end
                define actor Child extends Parent as
                    send(): void { return; }
                end
                """);
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(forbidden));
        assertTrue(error.getMessage().contains("final builtin actor method"),
                error::getMessage);
    }

    @Test
    void explicitNonSerialPolicyCannotWeakenBuiltinSerialSuperclass() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker from serial actor as
                    int requestConcurrency = 2;
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker from serial actor as
                    int messageConcurrency = 3;
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define serial actor Worker from serial actor as
                end
                """));
    }

    @Test
    void rejectsUnsupportedSuperclassAndActorIsolationChanges() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker from untrusted actor as
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define isoactor Worker from serial actor as
                end
                """));
    }

    @Test
    void nativeRuntimeOperationsAreFinalForAllActorKinds() {
        for (String header : new String[] {
                "define actor Worker as",
                "define serial actor Worker as",
                "define isoactor Worker as",
                "define untrusted actor Worker as"
        }) {
            String source = header + "\n"
                    + "send(): void { return; }\nend";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), header);
            assertTrue(failure.getMessage().contains("final builtin actor method"),
                    failure::getMessage);
        }
    }

    @Test
    void nativeRuntimeSlotsCannotBeOrdinaryActorFields() {
        for (String name : new String[] {
                "send", "outputs", "get_done_signal"
        }) {
            String source = "define actor Worker as\n"
                    + "let int " + name + ";\nend";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), name);
            assertTrue(failure.getMessage().contains("final builtin actor member"),
                    failure::getMessage);
        }
    }

    @Test
    void ordinaryActorParentCannotSmuggleNativeSlotsIntoSerialChild() {
        String source = """
                define actor Parent as
                    pub get_done_signal(): void { return; }
                end
                define actor Child from serial actor extends Parent as
                end
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("final builtin actor method"),
                failure::getMessage);
    }

    @Test
    void fromWordKeepsItsExistingImportAndExpressionMeanings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc serial(int value): int { return value; }
                pub routine main(): void { return; }
                """)));
    }
}
