package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TaskBoundaryAdmissionsTest {
    private static Ast.FunctionDecl main(Ast.Program program) {
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst()
                .orElseThrow();
    }

    private static Ast.TaskExpr firstTask(Ast.Program program) {
        for (Ast.Stmt statement : main(program).body()) {
            if (statement instanceof Ast.BindingStmt binding
                    && binding.initializer() instanceof Ast.AwaitExpr awaited
                    && awaited.expression() instanceof Ast.TaskExpr task) {
                return task;
            }
        }
        throw new AssertionError("no awaited TaskExpr");
    }

    @Test
    void compilerAdmissionRequiresExactProgramAndTaskIdentity() {
        String source = """
                pub fnc main(): void {
                    let int value = await task { return 42; };
                    return;
                }
                """;
        OresCompiler.TaskBoundaryAnalysis checked =
                OresCompiler.parseAndAnalyzeTaskBoundaries(source);
        Ast.TaskExpr boundary = firstTask(checked.program());

        TaskBoundaryAdmissions.ScalarAdmission admitted =
                checked.taskAdmissions().requireScalar(
                        checked.program(), boundary);
        assertSame(ScalarTaskPlan.returnedScalar(boundary),
                admitted.returnedExpression());
        assertEquals(Types.Primitive.INT, admitted.resolvedType());
        assertEquals(1, checked.taskAdmissions().boundaryCount());

        Ast.TaskExpr lookalike = new Ast.TaskExpr(boundary.body());
        assertThrows(IllegalArgumentException.class,
                () -> checked.taskAdmissions().requireScalar(
                        checked.program(), lookalike));

        OresCompiler.TaskBoundaryAnalysis other =
                OresCompiler.parseAndAnalyzeTaskBoundaries(source);
        assertThrows(IllegalArgumentException.class,
                () -> checked.taskAdmissions().requireScalar(
                        other.program(), boundary));
        assertThrows(IllegalArgumentException.class,
                () -> checked.taskAdmissions().requireScalar(
                        checked.program(), firstTask(other.program())));
    }

    @Test
    void literalZeroCaptureStillRequiresConcreteAdmission() {
        OresCompiler.TaskBoundaryAnalysis checked =
                OresCompiler.parseAndAnalyzeTaskBoundaries("""
                    pub fnc main(): void {
                        let bool value = await task { return true; };
                        return;
                    }
                    """);
        Ast.TaskExpr boundary = firstTask(checked.program());
        assertNotNull(checked.taskAdmissions().requireScalar(
                checked.program(), boundary));

        TaskBoundaryAdmissions empty =
                TaskBoundaryAdmissions.rejectAll(checked.program());
        assertThrows(IllegalArgumentException.class,
                () -> empty.requireScalar(checked.program(), boundary),
                "missing proof must not be confused with proved zero capture");
    }

    @Test
    void scopedLoanMintUsesClaimsStoredInExactBoundaryProof() {
        Ast.TaskExpr first = new Ast.TaskExpr(List.of(
                new Ast.ReturnStmt(new Ast.LiteralExpr(1L))));
        Ast.TaskExpr second = new Ast.TaskExpr(List.of(
                new Ast.ReturnStmt(new Ast.LiteralExpr(2L))));
        Ast.Program program = new Ast.Program(List.of());
        SendSyncTraits sourceProof =
                new SendSyncTraits(program, Set.of());
        ThreadSafetyTraits.NominalProof resolved = (type, trait) -> false;

        TaskAccessVerifier.Root generationOne =
                new TaskAccessVerifier.Root(
                        "PROCESS", "PROCESS", 77, 1, true);
        TaskAccessVerifier.Root generationTwo =
                new TaskAccessVerifier.Root(
                        "PROCESS", "PROCESS", 77, 2, true);

        TaskBoundaryAdmissions admissions =
                new TaskBoundaryAdmissions(program);
        admissions.recordScopedLoan(
                first,
                List.of(new TaskAccessVerifier.CoherentTypedClaim(
                        new TaskAccessVerifier.Claim(
                                TaskAccessVerifier.Place.whole(generationOne),
                                TaskAccessVerifier.Mode.READ),
                        Ast.TypeRef.simple("int"),
                        Types.Primitive.INT)),
                sourceProof,
                resolved);
        admissions.recordScopedLoan(
                second,
                List.of(new TaskAccessVerifier.CoherentTypedClaim(
                        new TaskAccessVerifier.Claim(
                                TaskAccessVerifier.Place.whole(generationTwo),
                                TaskAccessVerifier.Mode.READ),
                        Ast.TypeRef.simple("int"),
                        Types.Primitive.INT)),
                sourceProof,
                resolved);
        admissions.seal();

        TaskAccessVerifier verifier = new TaskAccessVerifier();
        assertNotNull(admissions.mintScopedLoan(program, first, verifier));
        assertEquals(1, verifier.activeChildren());

        assertThrows(IllegalStateException.class,
                () -> admissions.mintScopedLoan(program, second, verifier),
                "stale allocation generation must fail before runtime capability mint");

        assertThrows(IllegalArgumentException.class,
                () -> admissions.mintScopedLoan(
                        new Ast.Program(List.of()), first, verifier));
        assertThrows(IllegalArgumentException.class,
                () -> admissions.mintScopedLoan(
                        program, new Ast.TaskExpr(first.body()), verifier));
        assertEquals(1, verifier.activeChildren(),
                "failed hostile lookups must not mutate the loan ledger");
    }

    @Test
    void scalarBoundaryCannotBeReinterpretedAsScopedLoan() {
        OresCompiler.TaskBoundaryAnalysis checked =
                OresCompiler.parseAndAnalyzeTaskBoundaries("""
                    pub fnc main(): void {
                        let int value = await task { return 7; };
                        return;
                    }
                    """);
        TaskAccessVerifier verifier = new TaskAccessVerifier();
        assertThrows(IllegalArgumentException.class,
                () -> checked.taskAdmissions().mintScopedLoan(
                        checked.program(), firstTask(checked.program()), verifier));
        assertEquals(0, verifier.activeChildren());
    }
}
