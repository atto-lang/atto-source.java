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
                failure.getMessage().contains("field/index projection"),
                failure::getMessage);

        IllegalArgumentException wrapped = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse("""
                    pub fnc main(): void {
                        let nested = arr[arr[1, 2], arr[3, 4]];
                        let Task<int> result =
                            (async task |Array<int> value| -> { return 1; })(rt take nested[0]);
                        return;
                    }
                    """)));
        assertTrue(
                wrapped.getMessage().contains("field/index projection"),
                wrapped::getMessage);
    }

    @Test
    void callResultNeedsResolvedReturnOwnershipWitness() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse("""
                    pub fnc main(Option<Array<int>> optional): void {
                        let Task<int> result =
                            (async task |Array<int> value| -> { return 1; })(
                                optional.unwrap());
                        return;
                    }
                    """)));
        assertTrue(
                failure.getMessage().contains("rvalue provenance"),
                failure::getMessage);
    }

    @Test
    void freshLiteralAndExplicitTakeMintOwnedRvalueProofs() {
        Ast.Program literalProgram = Parser.parse("""
            pub fnc main(): void {
                let Task<int> result =
                    (async task |Array<int> value| -> { return 1; })(
                        arr[1, 2, 3]);
                return;
            }
            """);
        Ast.BindingStmt literalBinding =
                (Ast.BindingStmt) main(literalProgram).body().get(0);
        Ast.CallExpr literalBoundary =
                (Ast.CallExpr) literalBinding.initializer();
        OwnershipChecker.CheckResult literalChecked =
                OwnershipChecker.checkWithTaskTransferProvenance(
                        literalProgram);
        assertEquals(
                TaskTransferProvenance.TransferKind.OWNED_RVALUE,
                literalChecked.taskTransfers()
                        .transfersFor(literalBoundary).getFirst().kind());

        Ast.Program takeProgram = Parser.parse("""
            pub fnc main(): void {
                let data = arr[1, 2, 3];
                let Task<int> result =
                    (async task |Array<int> value| -> { return 1; })(
                        rt take data);
                return;
            }
            """);
        Ast.BindingStmt takeBinding =
                (Ast.BindingStmt) main(takeProgram).body().get(1);
        Ast.CallExpr takeBoundary = (Ast.CallExpr) takeBinding.initializer();
        OwnershipChecker.CheckResult takeChecked =
                OwnershipChecker.checkWithTaskTransferProvenance(takeProgram);
        assertEquals(
                TaskTransferProvenance.TransferKind.OWNED_RVALUE,
                takeChecked.taskTransfers()
                        .transfersFor(takeBoundary).getFirst().kind());
    }

    @Test
    void compilerMintedFreshFunctionReturnCanCrossTaskBoundary() {
        Ast.Program program = Parser.parse("""
            pub fnc make(): Array<int> {
                return arr[1, 2, 3];
            }
            pub fnc main(): void {
                let Task<int> result =
                    (async task |Array<int> value| -> { return 1; })(
                        make());
                return;
            }
            """);
        Ast.BindingStmt binding =
                (Ast.BindingStmt) main(program).body().get(0);
        Ast.CallExpr boundary = (Ast.CallExpr) binding.initializer();

        OwnershipChecker.CheckResult checked =
                OwnershipChecker.checkWithTaskTransferProvenance(program);
        assertEquals(
                TaskTransferProvenance.TransferKind.OWNED_RVALUE,
                checked.taskTransfers()
                        .transfersFor(boundary).getFirst().kind());
    }

    @Test
    void aliasingOrNestedCallReturnCannotSelfMintFreshOwnership() {
        IllegalArgumentException alias = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse("""
                    pub fnc alias(Array<int> value): Array<int> {
                        return value;
                    }
                    pub fnc main(): void {
                        let data = arr[1, 2, 3];
                        let Task<int> result =
                            (async task |Array<int> value| -> { return 1; })(
                                alias(data));
                        return;
                    }
                    """)));
        assertTrue(
                alias.getMessage().contains("rvalue provenance"),
                alias::getMessage);

        IllegalArgumentException nestedCall = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse("""
                    pub fnc seed(): Array<int> {
                        return arr[1, 2, 3];
                    }
                    pub fnc make(): Array<Array<int>> {
                        return arr[seed()];
                    }
                    pub fnc main(): void {
                        let Task<int> result =
                            (async task |Array<Array<int>> value| -> {
                                return 1;
                            })(make());
                        return;
                    }
                    """)));
        assertTrue(
                nestedCall.getMessage().contains("rvalue provenance"),
                nestedCall::getMessage);
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
    @Test
    void provenanceLedgerCapacityAndLabelsFailClosedAtomically() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TaskTransferProvenance.Transfer(
                        "x".repeat(257),
                        Ast.TypeRef.simple("int"),
                        TaskTransferProvenance.TransferKind.COPY));

        TaskTransferProvenance boundaryBound =
                new TaskTransferProvenance();
        for (int i = 0; i < 4_096; i++) {
            boundaryBound.record(
                    new Ast.TaskBlockStmt(List.of()), List.of());
        }
        assertEquals(4_096, boundaryBound.boundaryCount());
        assertThrows(
                IllegalStateException.class,
                () -> boundaryBound.record(
                        new Ast.TaskBlockStmt(List.of()), List.of()));
        assertEquals(4_096, boundaryBound.boundaryCount());
        assertEquals(0, boundaryBound.transferCount());

        TaskTransferProvenance.Transfer copy =
                new TaskTransferProvenance.Transfer(
                        "copy",
                        Ast.TypeRef.simple("int"),
                        TaskTransferProvenance.TransferKind.COPY);
        TaskTransferProvenance perBoundary =
                new TaskTransferProvenance();
        assertThrows(
                IllegalStateException.class,
                () -> perBoundary.record(
                        new Ast.TaskBlockStmt(List.of()),
                        java.util.Collections.nCopies(4_097, copy)));
        assertEquals(0, perBoundary.boundaryCount());
        assertEquals(0, perBoundary.transferCount());

        TaskTransferProvenance totalBound =
                new TaskTransferProvenance();
        List<TaskTransferProvenance.Transfer> full =
                java.util.Collections.nCopies(4_096, copy);
        for (int i = 0; i < 16; i++) {
            totalBound.record(
                    new Ast.TaskBlockStmt(List.of()), full);
        }
        assertEquals(65_536, totalBound.transferCount());
        assertThrows(
                IllegalStateException.class,
                () -> totalBound.record(
                        new Ast.TaskBlockStmt(List.of()),
                        List.of(copy)));
        assertEquals(16, totalBound.boundaryCount());
        assertEquals(65_536, totalBound.transferCount());
    }

}
