package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.BoundInvocation;
import dev.oreslang.types.OptionalCallScalarAbiFrame;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Typed scalar Option ABI reference values, NOT runtime guest call execution.
 * A passing test here cannot authorize TypeChecker/OresEvalRootNode admission.
 */
final class OptionalCallScalarAbiFrameTest {
    private static Ast.Program parse(String contents) {
        return Parser.parse(contents);
    }

    private static OptionalCallScalarAbiFrame.Frame frame(Ast.Program program) {
        Ast.FunctionDecl caller =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        var staged = (Ast.StagedCallExpr)
                ((Ast.BindingStmt) caller.body().getFirst()).initializer();
        return OptionalCallScalarAbiFrame.from(
                TypeChecker.inspectStagedScalarInvocation(program, caller, staged));
    }

    @Test
    void namedReorderingUsesDeclarationAbiButRetainsLexicalSourceIndexes() {
        var result = frame(parse("""
                define module app as
                    pub fnc target(int a, bool? enabled, string? label, bool required): int {
                        return a;
                    }
                    pub fnc caller(): void {
                        const result = target(required: false, label: _, enabled: true, a: 0);
                        return;
                    }
                end
                """));
        assertEquals(List.of(3, 2, 1, 0), result.slots().stream()
                .map(OptionalCallScalarAbiFrame.Slot::originalCallItemIndex).toList());
        assertEquals(List.of(2, 1, -1, 0), result.slots().stream()
                .map(OptionalCallScalarAbiFrame.Slot::evaluatedSourceIndex).toList());
        assertEquals(new OptionalCallScalarAbiFrame.Required(
                Ast.TypeRef.simple("int"), 0L), result.slots().get(0).value());
        assertEquals(new OptionalCallScalarAbiFrame.Some(
                new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("bool")), false), true),
                result.slots().get(1).value());
        assertEquals(new OptionalCallScalarAbiFrame.None(
                new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("string")), false)),
                result.slots().get(2).value());
        assertEquals(new OptionalCallScalarAbiFrame.Required(
                Ast.TypeRef.simple("bool"), false), result.slots().get(3).value());
        assertSame(result.selected(), result.checkedSource().selectedDeclaration());
        assertSame(result.checkedSource().lexical().source(),
                result.checkedSource().lexical().tempRecipe().bound().source());
        assertEquals(BoundInvocation.Readiness.SHAPE_ONLY, result.readiness());
        assertThrows(IllegalStateException.class, result::requireExecutable);
    }

    @Test
    void implicitOmissionHasNoRawCallItemYetKeepsTypedNoneSlot() {
        var result = frame(parse("""
                define module app as
                    pub fnc target(int a, string? label): int { return a; }
                    pub fnc caller(): void {
                        const result = target(a: 4);
                        return;
                    }
                end
                """));
        assertEquals(2, result.slots().size());
        var omitted = result.slots().get(1);
        assertEquals(-1, omitted.originalCallItemIndex());
        assertEquals(-1, omitted.evaluatedSourceIndex());
        assertInstanceOf(OptionalCallScalarAbiFrame.None.class, omitted.value());
        assertEquals(Ast.TypeRef.simple("string"),
                ((OptionalCallScalarAbiFrame.None) omitted.value())
                        .abiType().arguments().getFirst());
    }

    @Test
    void suppliedZeroAndFalseAreSomeNotOmission() {
        var result = frame(parse("""
                define module app as
                    pub fnc target(int? count, bool? enabled, int required): int {
                        return required;
                    }
                    pub fnc caller(): void {
                        const result = target(enabled: false, count: 0, required: 1);
                        return;
                    }
                end
                """));
        assertEquals(new OptionalCallScalarAbiFrame.Some(
                new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("int")), false), 0L),
                result.slots().get(0).value());
        assertEquals(new OptionalCallScalarAbiFrame.Some(
                new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("bool")), false), false),
                result.slots().get(1).value());
        assertTrue(result.slots().stream().noneMatch(s ->
                s.value() instanceof OptionalCallScalarAbiFrame.None));
    }

    @Test
    void standaloneTypedOptionValuesNeverAcceptMalformedScalarOrNone() {
        var opt = new Ast.TypeRef("Option", List.of(Ast.TypeRef.simple("int")), false);
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallScalarAbiFrame.Some(opt, null));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallScalarAbiFrame.Some(opt, false));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallScalarAbiFrame.None(Ast.TypeRef.simple("int")));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallScalarAbiFrame.Required(Ast.TypeRef.simple("int"), false));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallScalarAbiFrame.Slot(0, 0, -1,
                        new OptionalCallScalarAbiFrame.Required(Ast.TypeRef.simple("int"), 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> new OptionalCallScalarAbiFrame.Slot(0, 0, 0,
                        new OptionalCallScalarAbiFrame.None(opt)));
    }

    @Test
    void checkedFrameIsImmutableAndCannotAuthorizeCompilerExecution() {
        Ast.Program source = parse("""
                define module app as
                    pub fnc target(int a, string? label): int { return a; }
                    pub fnc caller(): void {
                        const result = target(a: 4, label: omit);
                        return;
                    }
                end
                """);
        var result = frame(source);
        assertThrows(UnsupportedOperationException.class, () -> result.slots().clear());
        assertThrows(IllegalStateException.class, result::requireExecutable);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(source));
    }

    @Test
    void referenceFrameCannotBeConstructedOutsideTheCheckedIssuer() {
        assertEquals(1, OptionalCallScalarAbiFrame.Frame.class.getDeclaredConstructors().length);
        assertTrue(java.lang.reflect.Modifier.isPrivate(
                OptionalCallScalarAbiFrame.Frame.class.getDeclaredConstructors()[0].getModifiers()));
    }

    @Test
    void nonliteralSideEffectsNeverReceiveScalarFrameAuthority() {
        Ast.Program source = parse("""
                define module app as
                    pub fnc target(int a): int { return a; }
                    pub fnc caller(): void {
                        const result = target(a: sideEffect());
                        return;
                    }
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> frame(source));
    }
}
