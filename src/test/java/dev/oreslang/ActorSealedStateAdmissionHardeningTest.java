package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Defense-in-depth: these gates must hold even if annotation expansion or
 * another compiler pass constructs AST nodes not representable in source.
 * This is source admission only, not a runtime isolation proof.
 */
final class ActorSealedStateAdmissionHardeningTest {
    private static Ast.Program source(String defaultValue) {
        return Parser.parse("define actor Counter as\n"
                + "state { value: int = " + defaultValue + "; }\n"
                + "end\n");
    }

    private static Ast.Program replaceActor(Ast.Program source, Ast.ClassDecl replacement) {
        Ast.ModuleDecl original = source.modules().getFirst();
        return new Ast.Program(source.namespace(), source.imports(),
                List.of(new Ast.ModuleDecl(original.name(), original.annotations(),
                        original.contracts(), List.of(replacement))));
    }

    @Test void literalInitializationAllowedButAmbientReadsAndCallsAreDenied() {
        assertDoesNotThrow(() -> ActorStateContract.verify(source("0")));
        assertDoesNotThrow(() -> ActorStateContract.verify(source("-1")));
        for (String expression : List.of("helper()", "other.value", "self.state.value")) {
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(source(expression)), expression);
            assertTrue(denied.getMessage().contains("owned constant expression"),
                    () -> expression + ": " + denied.getMessage());
        }
    }

    @Test void purelyLiteralFreshCollectionsAreAllowedAtAdmission() {
        Ast.Program program = Parser.parse("define actor Counter as\n"
                + "state { values: List[int] = [1, 2, 3]; }\n"
                + "end\n");
        assertDoesNotThrow(() -> ActorStateContract.verify(program));
    }

    @Test void syntheticActorConstructorCannotAcquireUncontrolledInitialization() {
        Ast.Program parsed = source("0");
        Ast.ClassDecl actor = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.ConstructorDecl forged = new Ast.ConstructorDecl(
                Ast.Visibility.PRIVATE, List.of(), List.of(), List.of());
        Ast.ClassDecl rewritten = new Ast.ClassDecl(
                actor.name(), actor.visibility(), actor.isAbstract(), actor.actorKind(),
                actor.genericParameters(), actor.parents(), actor.interfaces(), actor.fields(),
                forged, actor.methods(), actor.serial());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(replaceActor(parsed, rewritten)));
        assertTrue(denied.getMessage().contains("constructors cannot bypass"));
    }

    @Test void transformedActorCannotExposeHiddenStorageOrForgeMarker() {
        Ast.Program parsed = source("0");
        Ast.ClassDecl actor = (Ast.ClassDecl) parsed.modules().getFirst().declarations().getFirst();
        Ast.FieldDecl field = actor.fields().getFirst();
        Ast.FieldDecl exposed = new Ast.FieldDecl(field.name(), Ast.Visibility.PUBLIC,
                field.bindingKind(), field.type(), field.annotations(),
                field.initializer(), field.mutableReferent());
        Ast.ClassDecl rewritten = new Ast.ClassDecl(
                actor.name(), actor.visibility(), actor.isAbstract(), actor.actorKind(),
                actor.genericParameters(), actor.parents(), actor.interfaces(), List.of(exposed),
                actor.constructor(), actor.methods(), actor.serial());
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(replaceActor(parsed, rewritten)));
        assertTrue(denied.getMessage().contains("canonical sealed compiler-owned layout"));
    }
}
