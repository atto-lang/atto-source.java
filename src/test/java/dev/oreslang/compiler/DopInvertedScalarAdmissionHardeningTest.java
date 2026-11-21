package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Public AST verifier boundary and exact signed i64 values; not a Truffle
 * guest, Java AOT or native LLVM execution test.
 */
final class DopInvertedScalarAdmissionHardeningTest {
    private static String source(String ops) {
        return "define inverted struct Row as let value: int; token: int; end\n"
                + "fnc main(): int { let rows = InvertedArray<Row>.with_capacity(3);\n"
                + ops + "\n}\n";
    }

    private static void rejected(String source) {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileDopInvertedScalarI64(source));
        assertTrue(ex.getMessage().contains("DOP_INVERTED_V1_REJECTED"));
    }

    private static Ast.Program forged(Ast.Program original,
                                      Ast.BindingStmt first, Ast.TypeRef resultType) {
        var module = original.modules().getFirst();
        var function = (Ast.FunctionDecl) module.declarations().get(1);
        var stmts = new ArrayList<>(function.body());
        stmts.set(0, first);
        var rebuilt = new Ast.FunctionDecl(
                function.name(), function.kind(), function.visibility(), function.async(),
                function.suspends(), function.generator(), function.structural(),
                function.nonLexical(), function.trapped(), function.actorKind(),
                function.genericParameters(), function.parameters(), resultType,
                function.annotations(), stmts, function.genericSpecs(), function.effects());
        var rewritten = new Ast.ModuleDecl(module.name(), module.annotations(),
                module.contracts(), List.of(module.declarations().getFirst(), rebuilt));
        return new Ast.Program(original.namespace(), original.imports(), List.of(rewritten));
    }

    @Test
    void exactSignedI64LiteralsCanReachTheHostSoaWithoutArithmeticWrapping() {
        assertEquals(-7L, OresCompiler.compileDopInvertedScalarI64(source(
                "rows.push(-7, +70); return rows.get(0, value);")).executeHostReference());
        assertEquals(Long.MIN_VALUE, OresCompiler.compileDopInvertedScalarI64(source(
                "rows.push(-9223372036854775808, 0); return rows.get(0, value);"
        )).executeHostReference());
        assertEquals(-42L, OresCompiler.compileDopInvertedScalarI64(source(
                "rows.push(11, -42); return rows.get(0, token);")).executeHostReference());
    }

    @Test
    void negationOverflowAndCompoundOrDynamicExpressionsFailClosed() {
        rejected(source("rows.push(-(-9223372036854775808),0); "
                + "return rows.get(0,value);"));
        rejected(source("rows.push(-(-7),0); return rows.get(0,value);"));
        rejected(source("rows.push(1 + 2,0); return rows.get(0,value);"));
        rejected(source("rows.push(1,0); return rows.get(-1,value);"));
    }

    @Test
    void forgedInferredArrayQualifierCannotBypassThePublicAstLowerer() {
        Ast.Program p = Parser.parseDopInvertedScalarV1(source(
                "rows.push(1,2); return rows.get(0,value);"));
        var fn = (Ast.FunctionDecl) p.modules().getFirst().declarations().get(1);
        var binding = (Ast.BindingStmt) fn.body().getFirst();
        var factory = (Ast.NewExpr) binding.initializer();
        var forgedType = new Ast.TypeRef("InvertedArray", factory.type().arguments(), true);
        var forgedBinding = new Ast.BindingStmt(
                binding.kind(), binding.declaredType(), binding.name(),
                new Ast.NewExpr(forgedType, factory.arguments()),
                binding.mutableReferent(), binding.inferredTypeName());
        assertThrows(IllegalArgumentException.class,
                () -> DopInvertedScalarCompilerV1.lower(forged(p,forgedBinding,fn.returnType())));

        var inferredRow = new Ast.TypeRef("Row", List.of(), true);
        var nestedFactory = new Ast.NewExpr(
                new Ast.TypeRef("InvertedArray",List.of(inferredRow),false),
                factory.arguments());
        var nestedBinding = new Ast.BindingStmt(
                binding.kind(), binding.declaredType(), binding.name(),
                nestedFactory, binding.mutableReferent(), binding.inferredTypeName());
        assertThrows(IllegalArgumentException.class,
                () -> DopInvertedScalarCompilerV1.lower(forged(p,nestedBinding,fn.returnType())));
    }

    @Test
    void forgedResultAndMutableReferentMetadataAreNotTrusted() {
        Ast.Program p = Parser.parseDopInvertedScalarV1(source(
                "rows.push(1,2); return rows.get(0,value);"));
        var fn = (Ast.FunctionDecl) p.modules().getFirst().declarations().get(1);
        var binding = (Ast.BindingStmt) fn.body().getFirst();
        var forgedResult = new Ast.TypeRef("int",List.of(),true);
        assertThrows(IllegalArgumentException.class,
                () -> DopInvertedScalarCompilerV1.lower(forged(p,binding,forgedResult)));
        var forgedBinding = new Ast.BindingStmt(
                binding.kind(), binding.declaredType(), binding.name(),
                binding.initializer(), true, null);
        assertThrows(IllegalArgumentException.class,
                () -> DopInvertedScalarCompilerV1.lower(forged(p,forgedBinding,fn.returnType())));
        var discardedOwner = new Ast.BindingStmt(
                binding.kind(), binding.declaredType(), "_",
                binding.initializer(), binding.mutableReferent(), null);
        assertThrows(IllegalArgumentException.class,
                () -> DopInvertedScalarCompilerV1.lower(forged(p,discardedOwner,fn.returnType())));
    }

    @Test
    void ordinaryLanguageEntryPointStillHasNoInvertedGuestAuthority() {
        String candidate = source("rows.push(-7,0); return rows.get(0,value);");
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(candidate));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(candidate));
    }
}
