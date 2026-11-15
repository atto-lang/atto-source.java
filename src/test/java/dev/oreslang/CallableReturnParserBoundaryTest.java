package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Syntax-only tests intentionally isolate callable return admission from
 * type checking, so parser accept/reject behavior stays explicit across backends.
 */
final class CallableReturnParserBoundaryTest {
    private static Ast.FunctionDecl firstFunction(Ast.Program program) {
        return assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    private static Ast.BindingStmt firstBinding(String body) {
        Ast.Program program = Parser.parse("fnc main(): void { " + body + " return; }");
        return assertInstanceOf(Ast.BindingStmt.class, firstFunction(program).body().getFirst());
    }

    @Test
    void namedCallableSignatureUsesFunctionTypeNotGenericBinding() {
        Ast.BindingStmt binding = firstBinding("""
                const add2<(int, int) => int> = fnc(a: int, b: int) -> int {
                    return a + b;
                };
                """);
        assertEquals("add2", binding.name());
        assertEquals("Fnc", binding.declaredType().name());
        assertEquals(List.of("int", "int", "int"),
                binding.declaredType().arguments().stream().map(Ast.TypeRef::name).toList());
        Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, binding.initializer());
        assertEquals("int", lambda.declaredReturnType().name());
    }

    @Test
    void nestedCallableArgumentKeepsInnerAndOuterSignaturesDistinct() {
        Ast.BindingStmt binding = firstBinding("""
                const nested_callback<((int) => int) => int> =
                    fnc(callback: (int) => int) -> int {
                        return callback(42);
                    };
                """);
        Ast.TypeRef outer = binding.declaredType();
        assertEquals("Fnc", outer.name());
        assertEquals(2, outer.arguments().size());
        Ast.TypeRef nested = outer.arguments().getFirst();
        assertEquals("Fnc", nested.name());
        assertEquals(List.of("int", "int"),
                nested.arguments().stream().map(Ast.TypeRef::name).toList());
        assertEquals("int", outer.arguments().getLast().name());
    }

    @Test
    void genericTypeFirstBindingRetainsItsOriginalGrammar() {
        Ast.BindingStmt binding = firstBinding("const Option<int> value = Some(42);");
        assertEquals("value", binding.name());
        assertEquals("Option", binding.declaredType().name());
        assertEquals("int", binding.declaredType().arguments().getFirst().name());
    }

    @Test
    void moduleLevelTypedCallableRetainsSignature() {
        Ast.Program program = Parser.parse("""
                const add2<(int, int) => int> =
                    fnc(a: int, b: int) -> int { return a + b; };
                pub fnc main(): void { return; }
                """);
        Ast.FieldDecl field = assertInstanceOf(Ast.FieldDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals("Fnc", field.type().name());
        assertEquals("int", field.type().arguments().getLast().name());
    }

    @Test
    void explicitAndInferredVoidDeferHaveDifferentAstAnnotations() {
        Ast.Program parsed = Parser.parse("""
                fnc main(): void {
                    defer || -> void { stdio.println("done"); };
                    defer || -> { stdio.println("inferred"); };
                    return;
                }
                """);
        Ast.FunctionDecl main = firstFunction(parsed);
        Ast.LambdaExpr explicit = assertInstanceOf(Ast.LambdaExpr.class,
                ((Ast.DeferStmt) main.body().get(0)).expression());
        Ast.LambdaExpr inferred = assertInstanceOf(Ast.LambdaExpr.class,
                ((Ast.DeferStmt) main.body().get(1)).expression());
        assertEquals("void", explicit.declaredReturnType().name());
        assertNull(inferred.declaredReturnType());
    }

    @Test
    void scalarTypeWithoutBlockFailsSyntaxAdmission() {
        for (String scalar : List.of("int", "i32", "bool", "string", "f64", "decimal")) {
            String source = "fnc main(): void { const bad = || -> "
                    + scalar + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), scalar);
        }
    }

    @Test
    void ordinaryExpressionBodyIsNotConsumedByTypeProbe() {
        Ast.BindingStmt binding = firstBinding("""
                const callback = |value: int| -> value + 1;
                """);
        Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, binding.initializer());
        assertNull(lambda.declaredReturnType());
        assertNotNull(lambda.expressionBody());
    }

    @Test
    void typedCallableSyntaxCannotUseExecutableSlimArrowInTypePosition() {
        for (String bad : List.of(
                "const bad<(int, int) -> int> = fnc(a: int, b: int) -> int { return a+b; };",
                "const bad<int> = fnc(a: int) -> int { return a; };",
                "const bad<(int, int) =>> = fnc(a: int,b: int) -> int { return a+b; };")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc main(): void { " + bad + " return; }"), bad);
        }
    }
}
