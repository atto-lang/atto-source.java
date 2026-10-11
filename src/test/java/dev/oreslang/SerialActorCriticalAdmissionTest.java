package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SerialActorCriticalAdmissionTest {
    private static final String SERIAL = """
        define serial actor Counter as
            state { count: int = 0; }

            pub increment(): int using state.read, state.write {
                return self.handle_state(|s| -> {
                    s.count = s.count + 1;
                    return s.count;
                });
            }

            pub get_count(): int using state.read {
                return self.handle_state(|s| -> s.count);
            }

            receive(ActorMail<String> mail): void { return; }
        end
        """;

    @Test void serialActorCanOmitCriticalWithoutLosingEffectsOrReturnTypes() {
        Ast.Program program = Parser.parse(SERIAL);
        Ast.ClassDecl counter = (Ast.ClassDecl) program.modules().getFirst()
                .declarations().getFirst();
        assertTrue(counter.serial());
        assertEquals(Ast.ActorKind.SHARED, counter.actorKind());
        assertDoesNotThrow(() -> ActorStateContract.verify(program));
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(program));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
        assertTrue(((Ast.ClassDecl) TypeChecker.checkTypes(program)
                .modules().getFirst().declarations().getFirst()).serial(),
                "annotation expansion must preserve serial actor metadata");
    }

    @Test void criticalMayRemainExplicitOnSerialActor() {
        String qualified = SERIAL.replace(
                "self.handle_state(|s|", "self.handle_state(critical |s|");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(qualified)));
    }

    @Test void nonSerialActorStillRequiresCriticalCallback() {
        String ordinary = SERIAL.replace("define serial actor", "define actor");
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(ordinary)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(ordinary)));
    }

    @Test void serialSchedulingRemainsOrthogonalToIsolation() {
        String isolated = SERIAL.replace("define serial actor", "define serial isoactor");
        Ast.Program program = Parser.parse(isolated);
        Ast.ClassDecl worker = (Ast.ClassDecl) program.modules()
                .getFirst().declarations().getFirst();
        assertTrue(worker.serial());
        assertEquals(Ast.ActorKind.PRIVATE, worker.actorKind());
        assertDoesNotThrow(() -> ActorStateContract.verify(program));
    }

    @Test void serialActorAlsoAcceptsAsBracedDeclaration() {
        Ast.Program parsed = Parser.parse("""
            define serial actor Empty as {
                state { count: int = 0; }
            }
            """);
        Ast.ClassDecl actor = (Ast.ClassDecl) parsed.modules().getFirst()
                .declarations().getFirst();
        assertTrue(actor.serial());
        assertEquals(1, actor.fields().size());
    }

    @Test void serialActorsWithLegacyFieldsMustMigrateToSingleStateAggregate() {
        var legacy = Parser.parse("""
            define serial actor Legacy as
                let count: int = 0;
                receive(ActorMail<String> mail): void { return; }
            end
            """);
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(legacy));
        // Legacy scalar fields remain supported for non-serial migration.
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse("""
            define actor Legacy as
                let count: int = 0;
                receive(ActorMail<String> mail): void { return; }
            end
            """)));
    }

    @Test void inheritedMutableActorStateRequiresExplicitLaneComposition() {
        String derived = """
            define actor Base as
                state { base: int = 0; }
            end
            define serial actor Derived extends Base as
                state { count: int = 0; }
            end
            """;
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(Parser.parse(derived)));
    }

    @Test void nonSerialInstanceAndStaticMethodCannotAcquireImplicitCritical() {
        String staticallyBound = """
            define serial actor Counter as
                state { count: int = 0; }
                static fnc bad(): int using state.read {
                    return self.handle_state(|s| -> s.count);
                }
            end
            """;
        var program = Parser.parse(staticallyBound);
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(program));
        assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(program));
    }

    @Test void invalidSerialDeclarationsAndUsingBoundsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define serial class Counter as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define serial serial actor Counter as end"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define actor Counter as serial pub get(): int { return 1; } end"));
        String underdeclared = SERIAL.replace("using state.read, state.write", "using state.write");
        assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(Parser.parse(underdeclared)));
        String wrongType = SERIAL.replace(
                "return self.handle_state(|s| -> s.count);",
                "return self.handle_state(|s| -> true);");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(wrongType)));
    }
}
