package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.types.TaskAdmissionProof;
import dev.oreslang.types.TaskTransferProvenance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TaskAdmissionRuntimeBridgeTest {

    @Test
    void executionAnalysisBindsExactProgramAndTaskBoundary() {
        OresCompiler.ExecutionAnalysis analysis =
                OresCompiler.parseAndProveForExecution("""
                        pub routine main(): void {
                            let int value = 41;
                            let worker = nb {
                                return value + 1;
                            };
                            return;
                        }
                        """);
        Ast.TaskExpr task = onlyTask(analysis.program());

        List<TaskAdmissionProof.AdmittedTransfer> admitted =
                analysis.taskAdmissions()
                        .requireFor(analysis.program(), task);

        assertEquals(1, admitted.size());
        assertEquals("value", admitted.getFirst().name());
        assertEquals(
                TaskTransferProvenance.TransferKind.COPY,
                admitted.getFirst().ownership());
        assertEquals(
                TaskAdmissionProof.ReleaseBarrier.PHYSICAL_TASK_SCOPE_DONE,
                analysis.taskAdmissions().releaseBarrier());
    }

    @Test
    void provenEmptyTaskIsNotConfusedWithMissingBoundary() {
        OresCompiler.ExecutionAnalysis first =
                OresCompiler.parseAndProveForExecution("""
                        pub routine main(): void {
                            let worker = nb {
                                return 1;
                            };
                            return;
                        }
                        """);
        Ast.TaskExpr firstTask = onlyTask(first.program());
        assertTrue(first.taskAdmissions()
                .requireFor(first.program(), firstTask).isEmpty());

        OresCompiler.ExecutionAnalysis second =
                OresCompiler.parseAndProveForExecution("""
                        pub routine main(): void {
                            let worker = nb {
                                return 1;
                            };
                            return;
                        }
                        """);
        Ast.TaskExpr lookalike = onlyTask(second.program());

        IllegalStateException boundaryFailure = assertThrows(
                IllegalStateException.class,
                () -> first.taskAdmissions()
                        .requireFor(first.program(), lookalike));
        assertTrue(boundaryFailure.getMessage().contains("exact joined"));

        IllegalStateException programFailure = assertThrows(
                IllegalStateException.class,
                () -> first.taskAdmissions()
                        .requireFor(second.program(), firstTask));
        assertTrue(programFailure.getMessage().contains(
                "different program instance"));
    }

    @Test
    void moveOnlyTaskCaptureCarriesUniqueMoveProof() {
        OresCompiler.ExecutionAnalysis analysis =
                OresCompiler.parseAndProveForExecution("""
                        pub routine main(): void {
                            let values = arr[41, 42];
                            let worker = nb {
                                return values[0];
                            };
                            return;
                        }
                        """);
        Ast.TaskExpr task = onlyTask(analysis.program());
        List<TaskAdmissionProof.AdmittedTransfer> admitted =
                analysis.taskAdmissions()
                        .requireFor(analysis.program(), task);

        assertEquals(1, admitted.size());
        assertEquals("values", admitted.getFirst().name());
        assertEquals(
                TaskTransferProvenance.TransferKind.UNIQUE_MOVE,
                admitted.getFirst().ownership());
    }

    private static Ast.TaskExpr onlyTask(Ast.Program program) {
        Ast.FunctionDecl main = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst()
                .orElseThrow();
        return main.body().stream()
                .filter(Ast.BindingStmt.class::isInstance)
                .map(Ast.BindingStmt.class::cast)
                .map(Ast.BindingStmt::initializer)
                .filter(Ast.TaskExpr.class::isInstance)
                .map(Ast.TaskExpr.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
