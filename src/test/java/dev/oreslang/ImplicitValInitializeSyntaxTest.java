package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ImplicitValInitializeSyntaxTest {
    private static Ast.ClassDecl firstClass(String source) {
        return assertInstanceOf(Ast.ClassDecl.class,
                Parser.parse(source).modules().getFirst().declarations().getFirst());
    }

    @Test
    void bareTypeFirstFieldsAreEquivalentToExplicitVal() {
        Ast.ClassDecl implicit = firstClass("""
                pub define class Target as
                  pub String method;
                  pub String authority;
                  pub String path;
                  pub bool tunnel;
                end
                """);
        Ast.ClassDecl explicit = firstClass("""
                pub define class Target as
                  pub val String method;
                  pub val String authority;
                  pub val String path;
                  pub val bool tunnel;
                end
                """);
        assertEquals(4, implicit.fields().size());
        assertEquals(explicit.fields(), implicit.fields(),
                "implicit val must preserve binding and referent mutability semantics");
        for (Ast.FieldDecl field : implicit.fields()) {
            assertEquals(Ast.BindingKind.VAL, field.bindingKind());
            assertTrue(field.mutableReferent(), "implicit val is equivalent to explicit val");
            assertNull(field.initializer());
        }
    }

    @Test
    void typeFirstInitializersAreValUnlessLetIsExplicit() {
        Ast.ClassDecl klass = firstClass("""
                define class Settings as
                  int fixed: 5;
                  let int mutable: 6;
                  final int read_only: 7;
                  const int constant: 8;
                end
                """);
        assertEquals(Ast.BindingKind.VAL, klass.fields().get(0).bindingKind());
        assertEquals(Ast.BindingKind.LET, klass.fields().get(1).bindingKind());
        assertEquals(Ast.BindingKind.VAL, klass.fields().get(2).bindingKind());
        assertFalse(klass.fields().get(2).mutableReferent());
        assertEquals(Ast.BindingKind.CONST, klass.fields().get(3).bindingKind());
    }

    @Test
    void allFourInitializeFormsLowerToTheConstructorNode() {
        String[] bodies = {
                "initialize(int id) -> void ::\n self.connection_id = id;\n end",
                "initialize(int id) -> void\n self.connection_id = id;\n end",
                "initialize(int id): void\n self.connection_id = id;\n end",
                "initialize(int id): void { self.connection_id = id; }"
        };
        for (String initializer : bodies) {
            String source = "define class Connection as\n"
                    + " final int connection_id;\n" + initializer + "\nend\n";
            Ast.ClassDecl klass = firstClass(source);
            assertNotNull(klass.constructor(), initializer);
            assertEquals(1, klass.constructor().parameters().size(), initializer);
            assertEquals(1, klass.constructor().body().size(), initializer);
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)), initializer);
        }
    }

    @Test
    void rejectsAsMissingReturnTypeAndNonVoidInitialize() {
        String[] bad = {
                "initialize(int id) as self.connection_id = id; end",
                "initialize(int id) { self.connection_id = id; }",
                "initialize(int id) -> int { self.connection_id = id; }",
                "initialize(int id) => void { self.connection_id = id; }"
        };
        for (String initialize : bad) {
            String source = "define class Connection as\n"
                    + " final int connection_id;\n" + initialize + "\nend\n";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), initialize);
        }
    }

    @Test
    void rejectsDuplicateConstructorAndInitialize() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class C as
                  constructor() {}
                  initialize() -> void {}
                end
                """));
    }

    @Test
    void implicitBodyRequiresPhysicalNewlineAndExplicitDelimiterAdmitsSameLine() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class C as
                  ping() -> void return; end
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class C as
                  initialize() -> void return; end
                end
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                define class C as
                  ping() -> void :: return; end
                end
                """));
    }

    @Test
    void typedMethodBodiesSupportEndAndRejectAs() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class C as
                  ping(): void ::
                    return;
                  end
                  pong() -> void
                    return;
                  end
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class C as
                  ping() -> void as
                    return;
                  end
                end
                """));
    }
}
