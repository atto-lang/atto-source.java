package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ConstructorReceiverSyntaxHardeningTest {
    @Test
    void leadingSelfDoesNotCountTowardArity() {
        Ast.ClassDecl klass = (Ast.ClassDecl) Parser.parse("""
                define class Sample as
                  constructor(self, int value) { return; }
                  constructor(int value, string label) { return; }
                end
                """).modules().getFirst().declarations().getFirst();
        assertEquals(List.of(1, 2),
                klass.constructors().stream().map(Ast.ConstructorDecl::arity).toList());
    }

    @Test
    void sameCallerArityCollidesAcrossReceiverSpellings() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Sample as
                          constructor(int value) { return; }
                          constructor(self, string value) { return; }
                        end
                        """));
        assertTrue(failure.getMessage().contains("duplicate constructor arity 1"));
    }

    @Test
    void malformedSelfReceiverSpellingsReject() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Sample as
                  constructor(self int value) { return; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Sample as
                  constructor(int value, self) { return; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Sample as
                  constructor(mut self, int value) { return; }
                end
                """));
    }

    @Test
    void actorConstructorWithExplicitSelfStillRejects() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        actor Worker {
                          constructor(self, int value) { return; }
                        }
                        """));
        assertTrue(failure.getMessage().contains("actors do not declare constructors"));
    }
    @Test
    void directAstCannotForgeDuplicateOrReservedConstructorBindings() {
        var intType = Ast.TypeRef.simple("int");
        var p = new Ast.Param(intType, "value");
        assertThrows(IllegalArgumentException.class, () ->
                new Ast.ConstructorDecl(Ast.Visibility.PRIVATE,
                        List.of(p, p), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new Ast.ConstructorDecl(Ast.Visibility.PRIVATE,
                        List.of(new Ast.Param(intType, "self")), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new Ast.ConstructorDecl(Ast.Visibility.PRIVATE,
                        List.of(new Ast.Param(intType, "arguments")), List.of(), List.of()));
        assertEquals(2, new Ast.ConstructorDecl(Ast.Visibility.PRIVATE,
                List.of(p, new Ast.Param(intType, "other")),
                List.of(), List.of()).arity());
    }

    @Test
    void parsedConstructorCannotDeclareDuplicateParameterName() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Sample as
                  constructor(int id, string id) { return; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Sample as
                  constructor(int arguments) { return; }
                end
                """));
    }

}
