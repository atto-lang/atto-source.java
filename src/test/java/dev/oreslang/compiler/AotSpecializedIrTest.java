package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class AotSpecializedIrTest {

    @Test
    void nestedGenericCallsLowerToConcreteStaticHirWithoutRuntimeSpecialization() {
        AotSpecializedIr.Module ir = OresCompiler.lowerFiniteAotHir("""
                fnc identity<T>(T value): T {
                    return value;
                }
                fnc wrapper<T>(T value): T {
                    return identity<T>(value);
                }
                fnc main(): int {
                    return wrapper<int>(42);
                }
                """, BuildOptions.executable());

        assertEquals(3, ir.functions().size());
        assertEquals(MacroSourceUnitIdentity.MEMORY_UNIT, ir.sourceUnitId());

        AotSpecializedIr.Function main = ir.functions().stream()
                .filter(f -> f.instance().name().equals("main")).findFirst().orElseThrow();
        AotSpecializedIr.StaticCall call = assertInstanceOf(AotSpecializedIr.StaticCall.class,
                assertInstanceOf(AotSpecializedIr.Return.class, main.body().getFirst()).value());
        assertEquals("wrapper", call.target().name());
        assertEquals(List.of(Ast.TypeRef.simple("int")), call.target().typeArguments());
        assertEquals("int", call.type().name());

        AotSpecializedIr.Function wrapper = ir.functions().stream()
                .filter(f -> f.instance().name().equals("wrapper")).findFirst().orElseThrow();
        assertEquals(Ast.TypeRef.simple("int"), wrapper.parameters().getFirst().type());
        assertEquals(Ast.TypeRef.simple("int"), wrapper.returnType());

        AotSpecializedIr.StaticCall nested = assertInstanceOf(AotSpecializedIr.StaticCall.class,
                assertInstanceOf(AotSpecializedIr.Return.class,
                        wrapper.body().getFirst()).value());
        assertEquals("identity", nested.target().name());
        assertEquals(List.of(Ast.TypeRef.simple("int")), nested.target().typeArguments());
        assertInstanceOf(AotSpecializedIr.LocalRef.class, nested.arguments().getFirst());
    }

    @Test
    void repeatedSpecializationEmitsOneConcreteFunctionBody() {
        AotSpecializedIr.Module ir = OresCompiler.lowerFiniteAotHir("""
                fnc identity<T>(T value): T {
                    return value;
                }
                fnc main(): int {
                    return identity<int>(1) + identity<int>(2);
                }
                """, BuildOptions.executable());
        assertEquals(2, ir.functions().size());
        AotSpecializedIr.Function main = ir.functions().stream()
                .filter(f -> f.instance().name().equals("main")).findFirst().orElseThrow();
        AotSpecializedIr.Binary sum = assertInstanceOf(AotSpecializedIr.Binary.class,
                assertInstanceOf(AotSpecializedIr.Return.class, main.body().getFirst()).value());
        assertEquals("+", sum.operator());
        assertInstanceOf(AotSpecializedIr.StaticCall.class, sum.left());
        assertInstanceOf(AotSpecializedIr.StaticCall.class, sum.right());
    }

    @Test
    void genericLocalBindingAndReturnUseConcreteTypeAndBody() {
        AotSpecializedIr.Module ir = OresCompiler.lowerFiniteAotHir("""
                fnc identity<T>(T value): T {
                    const T copy = value;
                    return copy;
                }
                fnc main(): int { return identity<int>(42); }
                """, BuildOptions.executable());
        AotSpecializedIr.Function identity = ir.functions().stream()
                .filter(fn -> fn.instance().name().equals("identity"))
                .findFirst().orElseThrow();
        AotSpecializedIr.Local local = assertInstanceOf(AotSpecializedIr.Local.class,
                identity.body().getFirst());
        assertEquals(Ast.TypeRef.simple("int"), local.type());
        assertEquals(Ast.TypeRef.simple("int"),
                assertInstanceOf(AotSpecializedIr.LocalRef.class, local.initializer()).type());
        assertEquals("copy", assertInstanceOf(AotSpecializedIr.LocalRef.class,
                assertInstanceOf(AotSpecializedIr.Return.class,
                        identity.body().get(1)).value()).name());
    }

    @Test
    void genericSourceIsFullyCheckedBeforeTheEmitterReceivesIt() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.lowerFiniteAotHir("""
                        fnc identity<T>(T value): T { return value; }
                        fnc main(): int { return identity<int>(true); }
                        """, BuildOptions.executable()),
                "checked signature must reject an invalid argument before HIR emission");
    }

    @Test
    void inferredGenericCallsStillFailClosed() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.lowerFiniteAotHir("""
                        fnc identity<T>(T value): T { return value; }
                        fnc main(): int { return identity(42); }
                        """, BuildOptions.executable()));
        assertTrue(err.getMessage().contains("inferred generic"), err::toString);
    }

    @Test
    void arithmeticWithoutProvenBackendSemanticsCannotLower() {
        Ast.FunctionDecl main = new Ast.FunctionDecl(
                "main", Ast.Visibility.PUBLIC, false, List.of(), List.of(),
                Ast.TypeRef.simple("int"), List.of(),
                List.of(new Ast.ReturnStmt(new Ast.BinaryExpr("/",
                        new Ast.LiteralExpr(7L), new Ast.LiteralExpr(2L)))));
        Ast.Program synthetic = new Ast.Program(List.of(
                new Ast.ModuleDecl(dev.oreslang.parser.Parser.ROOT_MODULE, List.of(main))));
        AotFiniteGenericClosure.Plan plan =
                AotFiniteGenericClosure.plan(synthetic, BuildOptions.executable());
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> AotSpecializedIr.lower(synthetic, plan));
        assertTrue(err.getMessage().contains("operator lacks proven scalar lowering semantics"),
                err::toString);
    }

    @Test
    void manifestMayNotOmitAStaticCallDependency() {
        Ast.Program source = OresCompiler.parseAndTypeCheck("""
                fnc helper(): int { return 42; }
                fnc main(): int { return helper(); }
                """);
        AotFiniteGenericClosure.Plan incomplete = new AotFiniteGenericClosure.Plan(
                MacroSourceUnitIdentity.MEMORY_UNIT,
                List.of(new AotFiniteGenericClosure.Instance(
                        dev.oreslang.parser.Parser.ROOT_MODULE, "main", 0, List.of())));
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> AotSpecializedIr.lower(source, incomplete));
        assertTrue(err.getMessage().contains("not present in closed reachability graph"),
                err::toString);
    }

    @Test
    void syntheticManifestCannotSmuggleTypeMismatchedStaticCallIntoHir() {
        Ast.FunctionDecl identity = new Ast.FunctionDecl(
                "identity", Ast.Visibility.PUBLIC, false, List.of(),
                List.of(new Ast.Param(Ast.TypeRef.simple("int"), "value")),
                Ast.TypeRef.simple("int"), List.of(),
                List.of(new Ast.ReturnStmt(new Ast.NameExpr("value"))));
        Ast.FunctionDecl main = new Ast.FunctionDecl(
                "main", Ast.Visibility.PUBLIC, false, List.of(), List.of(),
                Ast.TypeRef.simple("int"), List.of(),
                List.of(new Ast.ReturnStmt(new Ast.CallExpr(
                        new Ast.NameExpr("identity"),
                        List.of(new Ast.LiteralExpr(true))))));
        Ast.Program raw = new Ast.Program(List.of(new Ast.ModuleDecl(
                dev.oreslang.parser.Parser.ROOT_MODULE, List.of(identity, main))));
        AotFiniteGenericClosure.Plan plan =
                AotFiniteGenericClosure.plan(raw, BuildOptions.executable());
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> AotSpecializedIr.lower(raw, plan));
        assertTrue(err.getMessage().contains("specialized call argument type mismatch"),
                err::toString);
    }

    @Test
    void malformedBinaryOperandFailsClosedWithoutNullDereference() {
        Ast.FunctionDecl main = new Ast.FunctionDecl(
                "main", Ast.Visibility.PUBLIC, false, List.of(), List.of(),
                Ast.TypeRef.simple("int"), List.of(),
                List.of(new Ast.ReturnStmt(new Ast.BinaryExpr("+", null,
                        new Ast.LiteralExpr(7L)))));
        Ast.Program raw = new Ast.Program(List.of(new Ast.ModuleDecl(
                dev.oreslang.parser.Parser.ROOT_MODULE, List.of(main))));
        AotFiniteGenericClosure.Plan plan =
                AotFiniteGenericClosure.plan(raw, BuildOptions.executable());
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> AotSpecializedIr.lower(raw, plan));
        assertTrue(err.getMessage().contains("operator lacks proven scalar lowering semantics"),
                err::toString);
    }

    @Test
    void copiedManifestFromAnotherLogicalSourceUnitFails() {
        Ast.Program source = OresCompiler.parseAndTypeCheck(
                "fnc main(): int { return 42; }");
        AotFiniteGenericClosure.Plan other = new AotFiniteGenericClosure.Plan(
                "other/unit.ores", List.of(new AotFiniteGenericClosure.Instance(
                        dev.oreslang.parser.Parser.ROOT_MODULE, "main", 0, List.of())));
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> AotSpecializedIr.lower(source, other));
        assertTrue(err.getMessage().contains("source unit/manifest mismatch"), err::toString);
    }

    @Test
    void unsupportedOpenGenericValueLayoutDoesNotBecomeDynamicCodegen() {
        Ast.TypeRef list = new Ast.TypeRef(
                "List", List.of(Ast.TypeRef.simple("int")), false);
        Ast.FunctionDecl main = new Ast.FunctionDecl(
                "main", Ast.Visibility.PUBLIC, false, List.of(), List.of(),
                list, List.of(), List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(7L))));
        Ast.Program synthetic = new Ast.Program(List.of(
                new Ast.ModuleDecl(dev.oreslang.parser.Parser.ROOT_MODULE, List.of(main))));
        AotFiniteGenericClosure.Plan plan =
                AotFiniteGenericClosure.plan(synthetic, BuildOptions.executable());
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> AotSpecializedIr.lower(synthetic, plan));
        assertTrue(err.getMessage().contains("fixed scalar layout proof"), err::toString);
    }
}
