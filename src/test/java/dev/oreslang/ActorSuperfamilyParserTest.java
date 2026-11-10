package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser/AST scope only. No actor superclass linking, runtime state schema,
 * generic method override, purity, inheritance lowering or guest execution.
 */
final class ActorSuperfamilyParserTest {

    private Ast.ClassDecl parseOne(String source) {
        var parsed = Parser.parse(source);
        assertEquals(1, parsed.modules().size());
        var declarations = parsed.modules().get(0).declarations();
        assertEquals(1, declarations.size());
        return (Ast.ClassDecl) declarations.get(0);
    }

    @Test
    void abstractParentHasActorKindAndSignatureOnlyMethods() {
        var node = parseOne("""
                define pub abstract actor EventSourcedActor<State, Command, Event> as
                    decide(State before, Command command): Array<Event>;
                    apply_event(State before, Event event): State;
                end
                """);
        assertTrue(node.isAbstract());
        assertNotEquals(Ast.ActorKind.NONE, node.actorKind());
        assertEquals(3, node.genericParameters().size());
        assertTrue(node.fields().isEmpty());
        assertTrue(node.methods().stream().allMatch(Ast.MethodDecl::isAbstract));
    }

    @Test
    void concreteActorCanReferenceOneGenericParentWithoutBeingPromotedYet() {
        var node = parseOne("""
                define actor Account from EventSourcedActor<int, int, int> as
                    current(): int { return 42; }
                end
                """);
        assertFalse(node.isAbstract());
        assertEquals(1, node.parents().size());
        assertEquals("EventSourcedActor", node.parents().get(0).name());
        assertNotEquals(Ast.ActorKind.NONE, node.actorKind());
    }

    @Test
    void abstractParentCannotHideMutableState() {
        var error = assertThrows(RuntimeException.class, () -> parseOne("""
                define abstract actor Bad as
                    let int balance = 10;
                end
                """));
        assertTrue(error.getMessage().contains("stateless"), error.getMessage());
    }

    @Test
    void abstractParentCannotSupplyStateBlockBeforeSealedLoweringExists() {
        var error = assertThrows(RuntimeException.class, () -> parseOne("""
                define abstract actor Bad as
                    state { balance: int = 0 }
                end
                """));
        assertTrue(error.getMessage().contains("state schema"), error.getMessage());
    }

    @Test
    void actorCannotInheritMultipleNominalParents() {
        var error = assertThrows(RuntimeException.class, () -> parseOne("""
                define actor Bad from A, B as
                end
                """));
        assertTrue(error.getMessage().contains("exactly one nominal parent"), error.getMessage());
    }

    @Test
    void concreteActorCannotDeclareAnUnimplementedAbstractMethod() {
        var error = assertThrows(RuntimeException.class, () -> parseOne("""
                define actor Bad as
                    apply_event(int value): int;
                end
                """));
        assertTrue(error.getMessage().contains("abstract class")
                || error.getMessage().contains("signature-only"), error.getMessage());
    }

    @Test
    void noTransportPolicyCanAppearOnAbstractActorFamily() {
        var error = assertThrows(RuntimeException.class, () -> parseOne("""
                define abstract http actor Bad as
                end
                """));
        assertTrue(error.getMessage().contains("abstract actor"), error.getMessage());
    }

    @Test
    void ordinaryActorDeclarationStillParses() {
        var node = parseOne("define actor Healthy as end");
        assertFalse(node.isAbstract());
        assertTrue(node.parents().isEmpty());
    }
}
