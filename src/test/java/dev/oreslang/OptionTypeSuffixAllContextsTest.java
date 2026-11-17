package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Postfix type '?' is Option<T> in every type-reference context. */
final class OptionTypeSuffixAllContextsTest {
    @Test
    void suffixWrapsAliasesFieldsFormalsReturnsLocalsAndNestedGenericArguments() {
        Ast.Program program = Parser.parse("""
                type MaybeInt = int?;

                define class Box as
                    final string? label: None;
                end

                fnc identity(Option<int>? nested): Option<int>? {
                    return nested;
                }

                fnc maybe(): int? {
                    return None;
                }

                fnc list_value(List<int?> values): List<Option<int>> {
                    return values;
                }

                fnc union_left(): int? | bool {
                    return None;
                }

                fnc union_right(): int | bool? {
                    return 1;
                }

                fnc grouped_union(): (int | bool)? {
                    return None;
                }

                pub routine main(): void {
                    const int? local = maybe();
                    return;
                }
                """);

        var declarations = program.modules().getFirst().declarations();
        Ast.TypeAliasDecl alias = (Ast.TypeAliasDecl) declarations.get(0);
        assertOptionOf(alias.target(), "int");

        Ast.ClassDecl box = (Ast.ClassDecl) declarations.get(1);
        assertOptionOf(box.fields().getFirst().type(), "string");

        Ast.FunctionDecl identity = (Ast.FunctionDecl) declarations.get(2);
        assertOptionOf(identity.parameters().getFirst().type().arguments().getFirst(), "int");
        assertOptionOf(identity.returnType(), "Option");
        assertOptionOf(identity.returnType().arguments().getFirst(), "int");

        Ast.FunctionDecl maybe = (Ast.FunctionDecl) declarations.get(3);
        assertOptionOf(maybe.returnType(), "int");

        Ast.FunctionDecl listValue = (Ast.FunctionDecl) declarations.get(4);
        assertEquals("List", listValue.parameters().getFirst().type().name());
        assertOptionOf(listValue.parameters().getFirst().type().arguments().getFirst(), "int");
        assertOptionOf(listValue.returnType().arguments().getFirst(), "int");

        Ast.FunctionDecl unionLeft = (Ast.FunctionDecl) declarations.get(5);
        assertTrue(unionLeft.returnType().isUnion());
        assertTrue(unionLeft.returnType().arguments().stream().anyMatch(t ->
                t.name().equals("Option") && t.arguments().getFirst().name().equals("int")));
        assertTrue(unionLeft.returnType().arguments().stream().anyMatch(t ->
                t.name().equals("bool")));

        Ast.FunctionDecl unionRight = (Ast.FunctionDecl) declarations.get(6);
        assertTrue(unionRight.returnType().isUnion());
        assertTrue(unionRight.returnType().arguments().stream().anyMatch(t ->
                t.name().equals("Option") && t.arguments().getFirst().name().equals("bool")));

        Ast.FunctionDecl groupedUnion = (Ast.FunctionDecl) declarations.get(7);
        assertEquals("Option", groupedUnion.returnType().name());
        Ast.TypeRef groupedInner = groupedUnion.returnType().arguments().getFirst();
        assertTrue(groupedInner.isUnion());
        assertEquals(2, groupedInner.arguments().size());

        Ast.FunctionDecl main = (Ast.FunctionDecl) declarations.get(8);
        Ast.BindingStmt local = (Ast.BindingStmt) main.body().getFirst();
        assertOptionOf(local.declaredType(), "int");
    }

    @Test
    void nestedOptionCanBeWrittenExplicitlyWithoutAmbiguousDoubleSuffix() {
        Ast.Program program = Parser.parse("""
                fnc nested(): Option<Option<int>> {
                    return None;
                }
                """);
        Ast.FunctionDecl function = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        assertOptionOf(function.returnType(), "Option");
        assertOptionOf(function.returnType().arguments().getFirst(), "int");
    }

    @Test
    void expressionPostfixPropagationAndTernaryRemainExpressionSyntax() {
        Ast.Program program = Parser.parse("""
                fnc propagate(int? value): int? {
                    return value?;
                }

                fnc choose(bool yes, int value): int {
                    return yes ? value : 0;
                }
                """);
        assertEquals(2, program.modules().getFirst().declarations().size());
    }

    @Test
    void voidNullInferAndRepeatedTypeSuffixesReject() {
        for (String type : java.util.List.of("void?", "null?", "int??", "Option<>?")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse("fnc invalid(): " + type + " { return 1; }"),
                    type);
            assertTrue(failure.getMessage().contains("?")
                    || failure.getMessage().contains("type"), failure.getMessage());
        }
    }

    private static void assertOptionOf(Ast.TypeRef actual, String innerName) {
        assertEquals("Option", actual.name());
        assertEquals(1, actual.arguments().size());
        assertEquals(innerName, actual.arguments().getFirst().name());
    }
}
