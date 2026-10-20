package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.List;

public final class TaskTransferProvenanceSmoke {
    private static int checked;
    private TaskTransferProvenanceSmoke() { }

    private static void expect(boolean condition, String label) {
        checked++;
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        TaskTransferProvenance provenance = new TaskTransferProvenance();
        Ast.TaskBlockStmt boundary = new Ast.TaskBlockStmt(List.of());
        provenance.record(
                boundary,
                List.of(
                        new TaskTransferProvenance.Transfer(
                                "copy",
                                Ast.TypeRef.simple("int"),
                                TaskTransferProvenance.TransferKind.COPY),
                        new TaskTransferProvenance.Transfer(
                                "owned",
                                new Ast.TypeRef(
                                        "Array",
                                        List.of(Ast.TypeRef.simple("int")),
                                        false),
                                TaskTransferProvenance.TransferKind.UNIQUE_MOVE)));
        provenance.seal();

        expect(provenance.sealed(), "ledger sealed");
        expect(provenance.boundaryCount() == 1, "one boundary");
        expect(provenance.transferCount() == 2, "two transfers");
        expect(provenance.transfersFor(boundary).size() == 2,
                "exact identity resolves proof");
        expect(provenance.transfersFor(
                new Ast.TaskBlockStmt(List.of())).isEmpty(),
                "lookalike identity cannot claim proof");
        expect(
                provenance.lexicalContextForIdentity(boundary)
                        .selfMode()
                        == TaskTransferProvenance.SelfMode.NONE,
                "default lexical context is explicit none");
        expect(
                provenance.lexicalContextForIdentity(
                        new Ast.TaskBlockStmt(List.of())) == null,
                "lookalike identity cannot claim lexical context");

        boolean rejected = false;
        try {
            provenance.record(boundary, List.of());
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        expect(rejected, "sealed ledger rejects mutation");

        System.out.println(
                "TaskTransferProvenanceSmoke PASS assertions=" + checked);
    }
}
