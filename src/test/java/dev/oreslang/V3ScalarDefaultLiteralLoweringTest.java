package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.V3ScalarDefaultLiteralLowering;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact-AST, scalar-only lowering checks. This is not a guest invocation:
 * normal interpreter/type checker still fail closed for v3 declarations.
 */
final class V3ScalarDefaultLiteralLoweringTest {
    private static Ast.FunctionDecl function(String source) {
        return Parser.parseStagedV3(source).modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast).findFirst().orElseThrow();
    }

    private static Ast.CallArgument named(String name, Object scalar) {
        return Ast.CallArgument.named(name, new Ast.LiteralExpr(scalar));
    }

    private static Ast.StagedCallExpr call(Ast.CallArgument... items) {
        return new Ast.StagedCallExpr(
                new Ast.NameExpr("connect"), List.of(), false, List.of(items));
    }

    @Test
    void defaultedInteriorParameterBecomesExactTypedPositionalLiteral() {
        var f = function("""
                @DefaultArgVals(port: 5432)
                pub fnc connect(string host, int port?, bool secure): void { return; }
                """);
        var raw = call(named("secure", true), named("host", "localhost"));
        var result = V3ScalarDefaultLiteralLowering.lower(f, raw);
        assertEquals(3, result.fullArityCall().arguments().size());
        assertEquals(List.of(true, false, true), result.formalUsesDefault().stream()
                .map(isDefault -> !isDefault).toList());
        assertEquals(List.of(0, 1), result.evaluatedSourceCallItemIndices());
        assertEquals("localhost", ((Ast.LiteralExpr) result.fullArityCall().arguments().get(0)).value());
        assertEquals(5432L, ((Ast.LiteralExpr) result.fullArityCall().arguments().get(1)).value());
        assertEquals(true, ((Ast.LiteralExpr) result.fullArityCall().arguments().get(2)).value());
        assertEquals(64, result.selectedSourceContractFingerprint().length());
    }

    @Test
    void perFormalAnnotationIsEquivalentToDeclarationDefault() {
        var fromFormal = function("""
                pub fnc connect(string host, @Default(5432) int port, bool secure): void { return; }
                """);
        var fromDecl = function("""
                @DefaultArgVals(port: 5432)
                pub fnc connect(string host, int port, bool secure): void { return; }
                """);
        var raw = call(named("secure", false), named("host", "local"));
        var a = V3ScalarDefaultLiteralLowering.lower(fromFormal, raw);
        var b = V3ScalarDefaultLiteralLowering.lower(fromDecl, raw);
        assertEquals(a.fullArityCall().arguments(), b.fullArityCall().arguments());
        assertEquals(a.selectedSourceContractFingerprint(), b.selectedSourceContractFingerprint());
    }

    @Test
    void explicitOmissionConsumesCallItemButEvaluatesNothing() {
        var f = function("""
                @DefaultArgVals(port: 80)
                pub fnc connect(string host, int port?, bool secure): void { return; }
                """);
        var raw = call(named("host", "local"),
                Ast.CallArgument.omit("port"), named("secure", true));
        var proof = V3ScalarDefaultLiteralLowering.lower(f, raw);
        assertEquals(List.of(0, 2), proof.evaluatedSourceCallItemIndices());
        assertEquals(List.of(false, true, false), proof.formalUsesDefault());
        assertEquals(80L, ((Ast.LiteralExpr) proof.fullArityCall().arguments().get(1)).value());
    }

    @Test
    void suppliedLiteralOverridesDefaultWithoutAnyCoercion() {
        var f = function("""
                @DefaultArgVals(port: 80)
                pub fnc connect(string host, int port?, bool secure): void { return; }
                """);
        var proof = V3ScalarDefaultLiteralLowering.lower(f,
                call(named("host", "a"), named("port", 123L), named("secure", true)));
        assertEquals(List.of(false, false, false), proof.formalUsesDefault());
        assertEquals(123L, ((Ast.LiteralExpr) proof.fullArityCall().arguments().get(1)).value());
    }

    @Test
    void nonLiteralOrIncorrectTypeIsNeverReorderedIntoRuntimeCall() {
        var f = function("""
                @DefaultArgVals(port: 80)
                pub fnc connect(string host, int port?, bool secure): void { return; }
                """);
        assertThrows(IllegalArgumentException.class, () -> V3ScalarDefaultLiteralLowering.lower(f,
                call(named("host", "a"), named("port", "80"), named("secure", true))));
        assertThrows(IllegalArgumentException.class, () -> V3ScalarDefaultLiteralLowering.lower(f,
                call(Ast.CallArgument.named("host", new Ast.NameExpr("effectful")),
                        named("secure", true))));
    }

    @Test
    void arbitraryOmissionsAndIncorrectSourceLabelShapesFailClosed() {
        var f = function("""
                @DefaultArgVals(port: 80)
                pub fnc connect(string host, int port?, bool secure): void { return; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultLiteralLowering.lower(f, call(named("host", "a"))));
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultLiteralLowering.lower(f,
                        call(named("ghost", "a"), named("secure", true))));
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultLiteralLowering.lower(f,
                        call(named("host", "a"), named("host", "b"), named("secure", true))));
    }

    @Test
    void doesNotConflateOptionDefaultsWithPrimitiveScalarABI() {
        var f = function("""
                @DefaultArgVals(port: 80)
                pub fnc connect(string host, Option<int> port?, bool secure): void { return; }
                """);
        assertThrows(IllegalArgumentException.class,
                () -> V3ScalarDefaultLiteralLowering.lower(f,
                        call(named("host", "a"), named("secure", true))));
    }

    @Test
    void producedFullArityScalarCallPassesActualTypeAndOwnershipCheckersWhenNormalized() {
        var sourceSignature = function("""
                @DefaultArgVals(port: 7)
                pub fnc connect(int host, int port?): int { return host + port; }
                """);
        var candidate = V3ScalarDefaultLiteralLowering.lower(sourceSignature,
                call(named("host", 35L)));
        // This deliberately constructs an *ordinary, fully supplied* guest
        // program. The staging metadata is NOT silently admitted into the
        // runtime. The compiler checks the lowered call using its real passes.
        Ast.Program normal = Parser.parse("""
                pub fnc connect(int host, int port): int { return host + port; }
                pub fnc entry(): int { return connect(0, 0); }
                """);
        Ast.ModuleDecl module = normal.modules().getFirst();
        var changed = new java.util.ArrayList<Ast.Decl>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.name().equals("entry")) {
                changed.add(new Ast.FunctionDecl(
                        fn.name(), fn.kind(), fn.visibility(), fn.async(),
                        fn.suspends(), fn.generator(), fn.structural(),
                        fn.nonLexical(), fn.trapped(), fn.actorKind(),
                        fn.genericParameters(), fn.parameters(), fn.returnType(),
                        fn.annotations(), List.of(new Ast.ReturnStmt(
                                candidate.fullArityCall())), fn.genericSpecs()));
            } else {
                changed.add(decl);
            }
        }
        var rewritten = new Ast.Program(normal.namespace(), normal.imports(), List.of(
                new Ast.ModuleDecl(module.name(), module.annotations(),
                        module.contracts(), changed)));
        assertDoesNotThrow(() -> dev.oreslang.types.TypeChecker.check(rewritten));
        assertDoesNotThrow(() -> dev.oreslang.types.OwnershipChecker.check(rewritten));
    }

    @Test
    void returnedOrdinaryCallIsNotALanguageAdmissionCapability() {
        var f = function("""
                @DefaultArgVals(port: 80)
                pub fnc connect(string host, int port?, bool secure): void { return; }
                """);
        var result = V3ScalarDefaultLiteralLowering.lower(f,
                call(named("secure", true), named("host", "a")));
        assertTrue(result.fullArityCall().callee() instanceof Ast.NameExpr);
        // The original v3 program remains explicitly inadmissible:
        assertThrows(IllegalArgumentException.class, () -> dev.oreslang.types.TypeChecker.check(
                Parser.parseStagedV3("""
                        @DefaultArgVals(port: 80)
                        pub fnc connect(string host, int port?, bool secure): void { return; }
                        """)));
    }
}
