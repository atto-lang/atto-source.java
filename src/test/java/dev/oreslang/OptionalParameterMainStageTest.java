package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.UnloweredParameterPresenceGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These tests prove parsed shape and fail-closed admission, not execution of
 * omitted defaults or named-value calls. Full-arity lowering is a later gate.
 */
final class OptionalParameterMainStageTest {
    private static Ast.Program parse(String parameters) {
        return Parser.parse("pub fnc staged(" + parameters
                + "): int { return 42; }");
    }

    private static List<Ast.Param> formals(Ast.Program program) {
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        return fn.parameters();
    }

    @Test
    void typeQuestionIsRequiredAndNameQuestionIsIndependent() {
        Ast.Program program = parse("int? required, int count? = 2, int? later?");
        List<Ast.Param> params = formals(program);
        assertEquals(3, params.size());

        Ast.Param required = params.get(0);
        assertEquals("Option", required.type().name());
        assertEquals("int", required.type().arguments().getFirst().name());
        assertTrue(required.optionTypeSugar());
        assertFalse(required.omittable());
        assertNull(required.defaultValue());

        Ast.Param count = params.get(1);
        assertEquals("int", count.type().name());
        assertFalse(count.optionTypeSugar());
        assertTrue(count.omittable());
        assertNotNull(count.defaultValue());

        Ast.Param later = params.get(2);
        assertEquals("Option", later.type().name());
        assertTrue(later.optionTypeSugar());
        assertTrue(later.omittable());
        assertNull(later.defaultValue());
    }

    @Test
    void nestedOptionSugarPreservesTheInnerType() {
        Ast.Param value = formals(parse("Option<int>? value")).getFirst();
        assertEquals("Option", value.type().name());
        assertEquals("Option", value.type().arguments().getFirst().name());
        assertEquals("int", value.type().arguments().getFirst()
                .arguments().getFirst().name());
        assertFalse(value.omittable());
    }

    @Test
    void compatibilityNamedParameterPreservesIndependentFlags() {
        Ast.Param value = formals(parse("value: int?")).getFirst();
        assertEquals("Option", value.type().name());
        assertTrue(value.optionTypeSugar());
        assertFalse(value.omittable());
    }

    @Test
    void missingScalarDefaultAndNonOmittableDefaultAreParserErrors() {
        assertThrows(IllegalArgumentException.class, () -> parse("int count?"));
        assertThrows(IllegalArgumentException.class, () -> parse("int count = 2"));
        assertThrows(IllegalArgumentException.class, () -> parse("void? arg"));
        assertThrows(IllegalArgumentException.class, () -> parse("int?? arg"));
    }

    @Test
    void omittedParameterCannotReachTypeOrOwnershipCheckers() {
        Ast.Program program = parse("int? optional? = side_effect()");
        IllegalArgumentException typeFailure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.checkTypes(program));
        assertTrue(typeFailure.getMessage().contains("OPTIONAL_FORMAL_ABI_UNSUPPORTED"),
                typeFailure::toString);
        IllegalArgumentException ownerFailure = assertThrows(
                IllegalArgumentException.class, () -> OwnershipChecker.check(program));
        assertTrue(ownerFailure.getMessage().contains("OPTIONAL_FORMAL_ABI_UNSUPPORTED"),
                ownerFailure::toString);
    }

    @Test
    void forgedParameterShapesFailBeforeATypeOrRuntimePlan() {
        Ast.TypeRef intType = Ast.TypeRef.simple("int");
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.Param(intType, "x", false, false, true, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.Param(intType, "x", false, false, false, true, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.Param(intType, "x", false, false, false, false,
                        new Ast.LiteralExpr(5L)));
    }

    @Test
    void nameFirstAndTypeFirstHaveExactlyEqualSemanticParamRecords() {
        List<Ast.Param> canonical = formals(parse(
                "int x, int y? = 10, int? z?"));
        List<Ast.Param> opposite = formals(parse(
                "x: int, y?: int = 10, z?: int?"));
        assertEquals(canonical, opposite,
                "the two source spellings must normalize to one parameter ABI");
        assertEquals(List.of("x", "y", "z"),
                opposite.stream().map(Ast.Param::name).toList());
        assertEquals(List.of(false, true, true),
                opposite.stream().map(Ast.Param::omittable).toList());
        assertEquals(List.of(false, false, true),
                opposite.stream().map(Ast.Param::optionTypeSugar).toList());
    }

    @Test
    void nameFirstRequiredOptionRemainsRequiredAndNestedOptionKeepsItsDepth() {
        Ast.Param required = formals(parse("value: int?")).getFirst();
        assertTrue(required.optionTypeSugar());
        assertFalse(required.omittable());
        assertNull(required.defaultValue());

        Ast.Param omitted = formals(parse("value?: int?")).getFirst();
        assertTrue(omitted.optionTypeSugar());
        assertTrue(omitted.omittable());
        assertNull(omitted.defaultValue());

        Ast.Param nested = formals(parse("value?: Option<int>?")).getFirst();
        assertEquals("Option", nested.type().name());
        assertEquals("Option", nested.type().arguments().getFirst().name());
        assertEquals("int", nested.type().arguments().getFirst()
                .arguments().getFirst().name());
        assertTrue(nested.omittable());
        assertTrue(nested.optionTypeSugar());
    }

    @Test
    void nameFirstDefaultsAndWhitespaceNormalizeToTypeFirst() {
        assertEquals(formals(parse("int y? = 10")),
                formals(parse("y ? : int = 10")));
        assertEquals(formals(parse("int? z? = Some(5)")),
                formals(parse("z?: int? = Some(5)")));
        assertEquals(formals(parse("int? z")),
                formals(parse("z: int?")));
        assertEquals(formals(parse("Option<int>? z")),
                formals(parse("z: Option<int>?")));
    }

    @Test
    void nameFirstErrorsDoNotShiftQuestionMarksBetweenNameAndType() {
        for (String bad : List.of(
                "x?: int",
                "x: int = 10",
                "x: int??",
                "x?: int??",
                "x??: int",
                "x?: bool",
                "x?: void",
                "x?: int = side_effect() ? 1 : 2, x: int")) {
            assertThrows(IllegalArgumentException.class,
                    () -> parse(bad), "expected syntax rejection for " + bad);
        }
    }

    @Test
    void mixedDeclarationStylesCannotDuplicateFormalLabels() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("int x, x: int"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("x?: int = 10, int x"));
    }

    @Test
    void nameFirstOmissionIsRejectedByTypeAndOwnershipUntilAbiIsLowered() {
        Ast.Program program = parse("x: int, y?: int = 10, z?: int?");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(program));
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(program));
    }

    @Test
    void nameFirstFormalWorksInNormalAndStrictSourceAdmission() {
        String code = """
                pub fnc calculate(x: int, y?: int = 10, z?: int?): int {
                  return x + y;
                }
                """;
        assertEquals(formals(Parser.parse(code)),
                formals(Parser.parseStrictAtto(code)));
    }

    @Test
    void nameFirstMetadataFeedsTheSameCheckedShapeBinder() {
        List<Ast.Param> formals = formals(parse(
                "x: int, y?: int = 10, z?: int?"));
        var plan = dev.oreslang.types.CheckedCallPlan.shapeForDeclaredParameters(
                formals,
                List.of(dev.oreslang.types.CheckedCallPlan.InputSlot.named("x", 0),
                        dev.oreslang.types.CheckedCallPlan.InputSlot.named("y", 1)));
        assertEquals(List.of(0, 1, -1), plan.abiToSource());
        assertEquals(1, plan.omittedCount());
        assertTrue(plan.isOmitted(2));
    }

    @Test
    void nameFirstInBracedMethodAndInterfaceDeclarationsSharesParamShape() {
        Ast.Program program = Parser.parse("""
                define class Calculator as
                  pub calculate(x: int, y?: int = 10, z?: int?): int {
                    return x + y;
                  }
                end
                define interface Calculates {
                  fnc calculate(x: int, y?: int = 10, z?: int?) -> int;
                }
                """);
        Ast.ClassDecl klass = (Ast.ClassDecl)
                program.modules().getFirst().declarations().get(0);
        Ast.InterfaceDecl iface = (Ast.InterfaceDecl)
                program.modules().getFirst().declarations().get(1);
        assertEquals(formals(parse("int x, int y? = 10, int? z?")),
                klass.methods().getFirst().parameters());
        Ast.InterfaceFunctionDecl member = (Ast.InterfaceFunctionDecl)
                iface.members().getFirst();
        assertEquals(klass.methods().getFirst().parameters(), member.parameters());
    }

    @Test
    void omissionInNameFirstLambdaStillFailsBeforeClosureAbiLowering() {
        for (String expression : List.of(
                "(x?: int) -> x",
                "|x?: int| -> x")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(
                            "pub fnc main(): int { val f = " + expression
                                    + "; return 42; }"),
                    "lambda should fail closed: " + expression);
        }
    }

    @Test
    void linkedClassAndInterfaceSignaturesCannotSmuggleOmission() {
        Ast.Param opt = formals(parse("int? x?")).getFirst();
        Ast.ConstructorDecl ctor = new Ast.ConstructorDecl(
                Ast.Visibility.PRIVATE, List.of(opt), List.of(), List.of());
        Ast.ClassDecl klass = new Ast.ClassDecl(
                "Sample", Ast.Visibility.PRIVATE, false, Ast.ActorKind.NONE,
                List.of(), List.of(), List.of(), List.of(), ctor, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> UnloweredParameterPresenceGuard.checkClass(klass));

        Ast.InterfaceFunctionDecl member = new Ast.InterfaceFunctionDecl(
                "do_work", List.of(), List.of(opt), Ast.TypeRef.simple("void"));
        Ast.InterfaceDecl iface = new Ast.InterfaceDecl(
                "Api", List.of(), List.of(member));
        assertThrows(IllegalArgumentException.class,
                () -> UnloweredParameterPresenceGuard.checkInterface(iface));
    }
}
