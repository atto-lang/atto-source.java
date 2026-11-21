package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.InvertedColumnStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Host bridge conformance only. It does not execute guest .atto code. */
final class InvertedSchemaStorageBridgeTest {
    private static Ast.InvertedStructDecl schema(String source) {
        Ast.Program program = Parser.parse(source);
        assertEquals(1, program.modules().size());
        return assertInstanceOf(Ast.InvertedStructDecl.class,
                program.modules().getFirst().declarations().getFirst());
    }

    @Test
    void parsedColumnMutabilityControlsPhysicalWritesAndLoans() {
        var source = "define inverted struct Row as let value: int; token: int; end";
        try (var bridge = new InvertedSchemaStorageBridge(schema(source))) {
            assertEquals("Row", bridge.schemaName());
            assertEquals(List.of("value", "token"), bridge.fields());
            assertThrows(UnsupportedOperationException.class,
                    () -> bridge.fields().set(0, "renamed"));
            assertTrue(bridge.isMutable("value"));
            assertFalse(bridge.isMutable("token"));

            var row = bridge.push(11, 100);
            assertEquals(11L, bridge.get(row, "value"));
            assertEquals(100L, bridge.get(row, "token"));
            bridge.write(row, "value", 42L);
            assertEquals(42L, bridge.get(row, "value"));

            assertThrows(IllegalStateException.class,
                    () -> bridge.write(row, "token", 99L));
            assertThrows(IllegalStateException.class, () -> bridge.borrowWrite("token"));
            assertEquals(100L, bridge.get(row, "token"));
            assertThrows(IllegalArgumentException.class, () -> bridge.get(row, "not-a-field"));

            try (InvertedColumnStore.ColumnLoan write = bridge.borrowWrite("value");
                 InvertedColumnStore.ColumnLoan read = bridge.borrowRead("token")) {
                assertThrows(IllegalStateException.class,
                        () -> bridge.get(row, "value"));
                assertEquals(100L, read.get(0));
                write.set(0, 77L);
                assertEquals(77L, write.get(0));
                assertThrows(IllegalStateException.class,
                        () -> bridge.eraseSwap(row));
            }
            assertEquals(77L, bridge.get(row, "value"));
            assertEquals(100L, bridge.get(row, "token"));
        }
    }

    @Test
    void swapCompactionPreservesSurvivorHandleAndRevokesDeletedRow() {
        var source = "define inverted struct Row as let value: int; token: int; end";
        try (var bridge = new InvertedSchemaStorageBridge(schema(source))) {
            var first = bridge.push(1, 10);
            var last = bridge.push(2, 20);
            bridge.eraseSwap(first);
            assertEquals(1, bridge.size());
            assertEquals(2L, bridge.get(last, "value"));
            assertEquals(20L, bridge.get(last, "token"));
            assertThrows(IllegalArgumentException.class, () -> bridge.get(first, "value"));
            var recycled = bridge.push(3, 30);
            assertThrows(IllegalArgumentException.class, () -> bridge.write(first, "value", 99));
            assertEquals(3L, bridge.get(recycled, "value"));
            assertEquals(2L, bridge.get(last, "value"));
        }
    }

    @Test
    void writabilityIsNominalAndNotInferredFromPhysicalColumnType() {
        var source = "define inverted struct Row as value: int; let token: int; end";
        try (var bridge = new InvertedSchemaStorageBridge(schema(source))) {
            assertFalse(bridge.isMutable("value"));
            assertTrue(bridge.isMutable("token"));
            var row = bridge.push(5, 6);
            assertThrows(IllegalStateException.class, () -> bridge.write(row, "value", 7));
            bridge.write(row, "token", 8);
            assertEquals(5L, bridge.get(row, "value"));
            assertEquals(8L, bridge.get(row, "token"));
        }
    }

    @Test
    void closedBridgeRejectsEveryOutstandingHandleAndLoan() {
        var bridge = new InvertedSchemaStorageBridge(schema(
                "define inverted struct Row as let value: int; token: int; end"));
        var row = bridge.push(1, 2);
        var loan = bridge.borrowRead("token");
        bridge.close();
        assertThrows(IllegalStateException.class, () -> bridge.get(row, "value"));
        assertThrows(IllegalStateException.class, () -> bridge.push(3, 4));
        assertThrows(IllegalStateException.class, () -> loan.get(0));
        loan.close();
        bridge.close();
    }
}
