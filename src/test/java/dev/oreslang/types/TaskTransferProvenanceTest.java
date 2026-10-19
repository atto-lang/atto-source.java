package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class TaskTransferProvenanceTest {
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
    void taskBlockMintsCopyAndUniqueMoveProofsOnlyAfterOwnershipCheck() {
        Ast.Program program = Parser.parse("""
            pub fnc main(): void {
                let int number = 11;
                let data = arr[1, 2, 3];
                do nb {
                    stdio.stdout.log(number);
                    stdio.stdout.log(data);
                };
                return;
            }
            """);
        Ast.TaskBlockStmt boundary = (Ast.TaskBlockStmt) main(program).body().get(2);

        OwnershipChecker.CheckResult checked =
                OwnershipChecker.checkWithTaskTransferProvenance(program);
        TaskTransferProvenance provenance = checked.taskTransfers();

        assertTrue(provenance.sealed());
        assertEquals(1, provenance.boundaryCount());
        assertEquals(2, provenance.transferCount());

        Map<String, TaskTransferProvenance.Transfer> byName =
                provenance.transfersFor(boundary).stream().collect(
                        Collectors.toMap(
                                TaskTransferProvenance.Transfer::name,
                                Function.identity()));
        assertEquals(
                TaskTransferProvenance.TransferKind.COPY,
                byName.get("number").kind());
        assertEquals(
                TaskTransferProvenance.TransferKind.UNIQUE_MOVE,
                byName.get("data").kind());

        // Proofs are tied to the exact checked AST node, never merely to an
        // equal-looking boundary manufactured by a later/untrusted phase.
        Ast.TaskBlockStmt lookalike =
                new Ast.TaskBlockStmt(boundary.body());
        assertTrue(provenance.transfersFor(lookalike).isEmpty());
    }

    @Test
    void immediateScheduledArgumentMintsUniqueMoveProof() {
        Ast.Program program = Parser.parse("""
            pub fnc main(): void {
                let data = arr[1, 2, 3];
                let Task<int> result =
                    (async task |Array<int> value| -> { return 1; })(data);
                return;
            }
            """);
        Ast.BindingStmt binding = (Ast.BindingStmt) main(program).body().get(1);
        Ast.CallExpr boundary = (Ast.CallExpr) binding.initializer();

        OwnershipChecker.CheckResult checked =
                OwnershipChecker.checkWithTaskTransferProvenance(program);
        List<TaskTransferProvenance.Transfer> transfers =
                checked.taskTransfers().transfersFor(boundary);

        assertEquals(1, transfers.size());
        assertEquals("$arg0", transfers.getFirst().name());
        assertEquals(
                TaskTransferProvenance.TransferKind.UNIQUE_MOVE,
                transfers.getFirst().kind());
    }

    @Test
    void scheduledMoveOnlyIndexProjectionRequiresNamedOwner() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse("""
                    pub fnc main(): void {
                        let nested = arr[arr[1, 2], arr[3, 4]];
                        let Task<int> result =
                            (async task |Array<int> value| -> { return 1; })(nested[0]);
                        return;
                    }
                    """)));
        assertTrue(
                failure.getMessage().contains("move-only projection"),
                failure::getMessage);
    }

    @Test
    void rejectedBorrowNeverProducesTransferResult() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.checkWithTaskTransferProvenance(
                        Parser.parse("""
                            pub fnc main(): void {
                                let data = arr[1, 2, 3];
                                const borrowed = rt borrow data;
                                do nb { stdio.stdout.log(borrowed); };
                                return;
                            }
                            """)));
        assertTrue(
                failure.getMessage().contains("borrow"),
                failure::getMessage);
    }
}
