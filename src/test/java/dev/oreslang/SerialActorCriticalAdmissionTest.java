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

            pub increment(): int with state.read, state.write {
                return self.handle_state(critical write |s| -> {
                    s.count = s.count + 1;
                    return s.count;
                });
            }

            pub get_count(): int with state.read {
                return self.handle_state(critical read |s| -> s.count);
            }

            receive(ActorMail<String> mail): void { return; }
        end
        """;

    @Test void serialActorRequiresExplicitModesAndRetainsEffectsAndReturnTypes() {
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

    @Test void readMayOmitCriticalButWritesRemainCriticalEvenInSerialActor() {
        // A read is shared/readonly unless critical requests exclusive write.
        String unmarkedRead = SERIAL.replace("critical read |s|", "|s|");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(unmarkedRead)));
        String plainCriticalWrite = SERIAL.replace("critical write |s|", "critical |s|");
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(plainCriticalWrite)));
    }

    @Test void nonSerialActorUsesSameExplicitStateModeContract() {
        String ordinary = SERIAL.replace("define serial actor", "define actor");
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse(ordinary)));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(ordinary)));
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
                let int count = 0;
                receive(ActorMail<String> mail): void { return; }
            end
            """);
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(legacy));
        // Breaking pre-1.0 rule: every actor must use the sealed state aggregate.
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(Parser.parse("""
            define actor Legacy as
                let int count = 0;
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

    @Test void staticMethodCannotAcquireInstanceStateCapability() {
        String staticallyBound = """
            define serial actor Counter as
                state { count: int = 0; }
                static fnc bad(): int with state.read {
                    return self.handle_state(critical read |s| -> s.count);
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
        String underdeclared = SERIAL.replace("with state.read, state.write", "with state.write");
        assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(Parser.parse(underdeclared)));
        String wrongType = SERIAL.replace(
                "return self.handle_state(critical read |s| -> s.count);",
                "return self.handle_state(critical read |s| -> true);");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(wrongType)));
    }
}
