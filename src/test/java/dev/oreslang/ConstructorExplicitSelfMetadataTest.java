package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ConstructorSemanticIr;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parser-to-semantic-IR migration contract; does not claim receiver isolation yet. */
final class ConstructorExplicitSelfMetadataTest {
    private static Ast.ClassDecl clazz(String source) {
        return (Ast.ClassDecl) Parser.parse(source)
                .modules().getFirst().declarations().getFirst();
    }

    @Test
    void parserRecordsExplicitSelfWithoutChangingCallerVisibleArity() {
        Ast.ClassDecl klass = clazz("""
                define class Sample as
                  constructor(self, int value) { return; }
                  constructor(int x, int y) { return; }
                end
                """);
        assertTrue(klass.constructors().get(0).bindsSelf());
        assertFalse(klass.constructors().get(1).bindsSelf());
        assertEquals(1, klass.constructors().get(0).arity());
        assertEquals(2, klass.constructors().get(1).arity());
    }

    @Test
    void emptyAndSelfOnlyConstructorsHaveEqualEffectiveArity() {
        Ast.ClassDecl withSelf = clazz("""
                define class Explicit as
                  constructor(self) { return; }
                end
                """);
        Ast.ClassDecl withoutSelf = clazz("""
                define class Implicit as
                  constructor() { return; }
                end
                """);
        assertTrue(withSelf.constructor().bindsSelf());
        assertFalse(withoutSelf.constructor().bindsSelf());
        assertEquals(0, withSelf.constructor().arity());
        assertEquals(0, withoutSelf.constructor().arity());
    }

    @Test
    void semanticIrDerivesReceiverBindingFromSource() {
        Ast.ClassDecl klass = clazz("""
                define class Sample as
                  constructor(self) { return; }
                  constructor(int v) { return; }
                end
                """);
        assertEquals(
                ConstructorSemanticIr.ReceiverBinding.EXPLICIT_SELF,
                ConstructorSemanticIr.ConstructorDecl.of("Sample", klass.constructor())
                        .receiverBinding());
        assertEquals(
                ConstructorSemanticIr.ReceiverBinding.IMPLICIT,
                ConstructorSemanticIr.ConstructorDecl.of("Sample", klass.constructorOverloads().getFirst())
                        .receiverBinding());
    }

    @Test
    void overloadCollisionDoesNotChangeWithReceiverMetadata() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> clazz("""
                        define class Sample as
                          constructor(self, int v) { return; }
                          constructor(int w) { return; }
                        end
                        """));
        assertTrue(error.getMessage().contains("duplicate constructor arity 1"));
    }
}
