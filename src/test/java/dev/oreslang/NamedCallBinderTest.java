package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.NamedCallBinder;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NamedCallBinderTest {
    private static final String HEADER = """
            define module sample as
              fnc foo(int bar, bool a): int {
                return a ? bar : 0;
              }
            """;
    private static final String TAIL = "end\n";

    private static Ast.CallExpr parsedCall(String arguments) {
        Ast.Program program = Parser.parse(HEADER + """
                  fnc test(): int {
                    return foo(%s);
                  }
                """.formatted(arguments) + TAIL);
        Ast.FunctionDecl test = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().get(1);
        return (Ast.CallExpr) ((Ast.ReturnStmt) test.body().getFirst()).value();
    }

    private static List<Ast.Param> formals() {
        return List.of(
                new Ast.Param(Ast.TypeRef.simple("int"), "bar"),
                new Ast.Param(Ast.TypeRef.simple("bool"), "a"));
    }

    @Test
    void positionalAndNamedCallsBindToOneAritySlot() {
        NamedCallBinder.Plan positional =
                NamedCallBinder.bind(parsedCall("5, true").arguments(), formals(), "foo");
        NamedCallBinder.Plan named =
                NamedCallBinder.bind(parsedCall("bar: 5, a: true").arguments(), formals(), "foo");
        NamedCallBinder.Plan reordered =
                NamedCallBinder.bind(parsedCall("a: true, bar: 6").arguments(), formals(), "foo");
        NamedCallBinder.Plan mixed =
                NamedCallBinder.bind(parsedCall("7, a: true").arguments(), formals(), "foo");
        assertEquals(List.of(0, 1), positional.sourceToFormal());
        assertEquals(List.of(0, 1), named.sourceToFormal());
        assertEquals(List.of(1, 0), reordered.sourceToFormal());
        assertEquals(List.of(0, 1), mixed.sourceToFormal());
        assertEquals(List.of(6, true), reordered.toFormalOrder(List.of(true, 6)));
        assertEquals(List.of("a", "bar"), reordered.parametersInSourceOrder(formals())
                .stream().map(Ast.Param::name).toList());
    }

    @Test
    void sourceOrderMustSurviveStaticSlotPermutation() {
        Ast.CallExpr call = parsedCall("a: first(), bar: second()");
        NamedCallBinder.Plan plan = NamedCallBinder.bind(call.arguments(), formals(), "foo");
        assertEquals(List.of(1, 0), plan.sourceToFormal());
        assertEquals("a", ((Ast.NamedArgumentExpr) call.arguments().getFirst()).name());
        assertEquals("bar", ((Ast.NamedArgumentExpr) call.arguments().get(1)).name());
    }

    @Test
    void parserRejectsDuplicateLabelsAndUnsafeOrdering() {
        assertThrows(IllegalArgumentException.class, () -> parsedCall("bar: 5, bar: 6"));
        assertThrows(IllegalArgumentException.class, () -> parsedCall("a: true, 5"));
        assertThrows(IllegalArgumentException.class, () -> parsedCall("...values, a: true"));
        assertThrows(IllegalArgumentException.class, () -> parsedCall("a: true, ...values"));
    }

    @Test
    void binderRejectsUnknownAndConflictingSlots() {
        assertThrows(IllegalArgumentException.class, () ->
                NamedCallBinder.bind(parsedCall("x: 5, a: true").arguments(), formals(), "foo"));
        assertThrows(IllegalArgumentException.class, () ->
                NamedCallBinder.bind(parsedCall("5, bar: 6").arguments(), formals(), "foo"));
        assertThrows(IllegalArgumentException.class, () ->
                NamedCallBinder.bind(parsedCall("a: true").arguments(), formals(), "foo"));
    }

    @Test
    void namedValueDelimiterIsNotTypeMetadataDelimiter() {
        // Assignment is not a named call argument.
        Ast.CallExpr call = parsedCall("bar = 5, true");
        assertFalse(NamedCallBinder.hasNamed(call.arguments()));
    }
}
