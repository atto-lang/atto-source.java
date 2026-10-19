package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.List;

public final class TaskAdmissionProofSmoke {
    private static int checked;

    private TaskAdmissionProofSmoke() { }

    private static void expect(boolean condition, String label) {
        checked++;
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        Ast.Program program = new Ast.Program(List.of());
        Ast.TaskBlockStmt boundary = new Ast.TaskBlockStmt(List.of());

        TaskTransferProvenance ownership =
                new TaskTransferProvenance(program);
        ownership.record(
                boundary,
                List.of(new TaskTransferProvenance.Transfer(
                        "value",
                        Ast.TypeRef.simple("int"),
                        TaskTransferProvenance.TransferKind.COPY)));
        ownership.seal();

        TaskSemanticAdmission semantic =
                new TaskSemanticAdmission(program);
        semantic.record(
                boundary,
                List.of(new TaskSemanticAdmission.Entry(
                        "value", Types.Primitive.INT)));
        semantic.seal();

        TaskAdmissionProof proof =
                TaskAdmissionProof.combine(ownership, semantic);
        expect(proof.boundaryCount() == 1, "one exact boundary");
        expect(proof.transferCount() == 1, "one admitted transfer");
        expect(
                proof.releaseBarrier()
                        == TaskAdmissionProof.ReleaseBarrier
                                .PHYSICAL_TASK_SCOPE_DONE,
                "physical TaskScope done is mandatory");
        expect(proof.transfersFor(boundary).size() == 1,
                "exact identity resolves proof");
        expect(proof.transfersFor(
                new Ast.TaskBlockStmt(List.of())).isEmpty(),
                "lookalike boundary has no proof");

        TaskSemanticAdmission wrong =
                new TaskSemanticAdmission(program);
        wrong.record(
                boundary,
                List.of(new TaskSemanticAdmission.Entry(
                        "other", Types.Primitive.INT)));
        wrong.seal();
        boolean mismatch = false;
        try {
            TaskAdmissionProof.combine(ownership, wrong);
        } catch (IllegalArgumentException expected) {
            mismatch = true;
        }
        expect(mismatch, "semantic/ownership name mismatch rejected");

        TaskSemanticAdmission otherProgram =
                new TaskSemanticAdmission(new Ast.Program(List.of()));
        otherProgram.record(
                boundary,
                List.of(new TaskSemanticAdmission.Entry(
                        "value", Types.Primitive.INT)));
        otherProgram.seal();
        boolean crossProgram = false;
        try {
            TaskAdmissionProof.combine(ownership, otherProgram);
        } catch (IllegalArgumentException expected) {
            crossProgram = true;
        }
        expect(crossProgram, "cross-program proof mixing rejected");

        System.out.println(
                "TaskAdmissionProofSmoke PASS assertions=" + checked);
    }
}
