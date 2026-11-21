package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TaskAdmissionProof;
import dev.oreslang.types.TaskTransferProvenance;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class TaskAdmissionProofTest {
    private static Ast.FunctionDecl main(Ast.Program program) {
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("main"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void compilerJoinsExactOwnershipAndResolvedSendProofs() {
        OresCompiler.TaskTransferAnalysis checked =
                OresCompiler.proveTaskTransfers(Parser.parse("""
                    pub fnc main(): void {
                        let int number = 11;
                        let data = arr[1, 2, 3];
                        do nb {
                            stdio.stdout.log(number);
                            stdio.stdout.log(data);
                        };
                        return;
                    }
                    """));

        Ast.TaskBlockStmt boundary =
                (Ast.TaskBlockStmt) main(checked.program()).body().get(2);
        TaskAdmissionProof proof = checked.admissions();

        assertEquals(
                TaskAdmissionProof.ReleaseBarrier.PHYSICAL_TASK_SCOPE_DONE,
                proof.releaseBarrier());
        assertEquals(1, proof.boundaryCount());
        assertEquals(2, proof.transferCount());

        Map<String, TaskAdmissionProof.AdmittedTransfer> byName =
                proof.transfersFor(boundary).stream().collect(
                        Collectors.toMap(
                                TaskAdmissionProof.AdmittedTransfer::name,
                                Function.identity()));
        assertEquals(
                TaskTransferProvenance.TransferKind.COPY,
                byName.get("number").ownership());
        assertEquals(Types.Primitive.INT, byName.get("number").resolvedType());
        assertEquals(
                TaskTransferProvenance.TransferKind.UNIQUE_MOVE,
                byName.get("data").ownership());
        assertInstanceOf(Types.ListType.class,
                byName.get("data").resolvedType());

        Ast.TaskBlockStmt lookalike =
                new Ast.TaskBlockStmt(boundary.body());
        assertThrows(IllegalArgumentException.class,
                () -> proof.transfersFor(lookalike),
                "missing exact-boundary proof must fail closed");
    }

    @Test
    void freshFunctionReturnFlowsThroughCombinedAdmissionProof() {
        OresCompiler.TaskTransferAnalysis checked =
                OresCompiler.proveTaskTransfers(Parser.parse("""
                    pub fnc make(): Array<int> {
                        return arr[1, 2, 3];
                    }
                    pub fnc main(): void {
                        let Task<int> result =
                            (async task |Array<int> value| -> { return 1; })(
                                make());
                        return;
                    }
                    """));

        Ast.BindingStmt binding =
                (Ast.BindingStmt) main(checked.program()).body().get(0);
        Ast.CallExpr boundary = (Ast.CallExpr) binding.initializer();
        var transfer = checked.admissions()
                .transfersFor(boundary).getFirst();

        assertEquals(
                TaskTransferProvenance.TransferKind.OWNED_RVALUE,
                transfer.ownership());
        assertInstanceOf(Types.ListType.class, transfer.resolvedType());
    }

    @Test
    void movedParameterReturnFlowsThroughCombinedAdmissionProof() {
        OresCompiler.TaskTransferAnalysis checked =
                OresCompiler.proveTaskTransfers(Parser.parse("""
                    pub fnc moveThrough(
                            Array<int> value): Array<int> {
                        return value;
                    }
                    pub fnc main(): void {
                        let data = arr[1, 2, 3];
                        let Task<int> result =
                            (async task |Array<int> value| -> {
                                return 1;
                            })(moveThrough(data));
                        return;
                    }
                    """));

        Ast.BindingStmt binding =
                (Ast.BindingStmt) main(checked.program())
                        .body().get(1);
        Ast.CallExpr boundary =
                (Ast.CallExpr) binding.initializer();
        TaskAdmissionProof.AdmittedTransfer transfer =
                checked.admissions()
                        .transfersFor(boundary).getFirst();

        assertEquals(
                TaskTransferProvenance.TransferKind.OWNED_RVALUE,
                transfer.ownership());
        assertInstanceOf(
                Types.ListType.class, transfer.resolvedType());
    }

    @Test
    void readonlyBackingFailsAtResolvedSemanticHalf() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.proveTaskTransfers(Parser.parse("""
                    pub fnc main(): void {
                        const value = infer struct{foo: "bar"};
                        do nb {
                            stdio.stdout.log(value);
                        };
                        return;
                    }
                    """)));
        assertTrue(
                failure.getMessage().contains("concrete owned Send data"),
                failure::getMessage);
    }

    @Test
    void ordinaryCompilerAnalyzeStillRejectsTaskExecution() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.analyze(Parser.parse("""
                    pub fnc main(): void {
                        let data = arr[1, 2, 3];
                        do nb { stdio.stdout.log(data); };
                        return;
                    }
                    """)));
        assertTrue(
                failure.getMessage().contains("source execution is not enabled"),
                failure::getMessage);
    }
}
