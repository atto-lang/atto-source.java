package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Oreslang source declarations feed the host lowering target.
 * Source execution remains fail-closed until Java/LLVM guest IR is wired.
 */
final class InvertedArrayTest {
    private static Ast.ClassDecl declaration(String source) {
        Ast.Program program = Parser.parse(source);
        return (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
    }

    private static Ast.ClassDecl particle() {
        return declaration("""
                define inverted class Particle as
                    x: f64;
                    y: f64;
                    vx: f64;
                    vy: f64;
                    ready: bool;
                end
                """);
    }

    @Test
    void derivesNominalColumnSchemaFromRealOreslangParser() {
        var schema = InvertedArray.Schema.fromClass(particle());
        assertEquals("Particle", schema.className());
        assertEquals(5, schema.fieldCount());
        assertEquals(0, schema.index("x"));
        assertEquals(3, schema.index("vy"));
        assertEquals(InvertedColumnStore.Kind.F64, schema.fields().get(0).kind());
        assertEquals(InvertedColumnStore.Kind.BOOL, schema.fields().get(4).kind());
        assertThrows(IllegalArgumentException.class, () -> schema.index("nonexistent"));

        try (var array = new InvertedArray(schema, 2)) {
            assertEquals(0, array.size());
            assertEquals(2, array.capacity());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("x", 1.0);
            row.put("y", 2.0);
            row.put("vx", 3.0);
            row.put("vy", 4.0);
            row.put("ready", true);
            var handle = array.push(row);
            assertEquals(1, array.size());
            assertEquals(1.0, array.read(handle, "x"));
            assertEquals(2.0, array.read(handle, "y"));

            // Method-body lowering can bind an AST field once to the
            // physical column and then execute checked scalar accesses.
            var x = array.bindField("x");
            var vx = array.bindField("vx");
            assertEquals("x", x.name());
            assertEquals(InvertedColumnStore.Kind.F64, x.kind());
            x.set(handle, (double) x.get(handle) + (double) vx.get(handle) * 0.1);
            assertEquals(1.3, (double) x.get(handle), 1e-12);

            var projected = array.row(handle);
            projected.set("y", 7.0);
            assertEquals(7.0, projected.get("y"));
            var snapshot = projected.snapshot();
            assertEquals(5, snapshot.size());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", 2.0));
            assertEquals(1.3, (double) snapshot.get("x"), 1e-12);
            assertThrows(IllegalArgumentException.class, () -> projected.set("vx", 2));
            assertEquals(3.0, projected.get("vx"));
            assertThrows(IllegalArgumentException.class, () -> projected.get("other"));

            array.eraseSwap(handle);
            assertThrows(IllegalArgumentException.class, () -> projected.get("x"));
            assertThrows(IllegalArgumentException.class, () -> x.get(handle));
        }
    }

    @Test
    void rejectsExtraMissingWrongTypedAndNonAtomicRowUpdates() {
        try (var array = InvertedArray.fromClass(particle(), 0)) {
            var valid = Map.of("x", 1.0, "y", 2.0, "vx", 3.0, "vy", 4.0, "ready", true);
            var handle = array.push(valid);
            assertThrows(IllegalArgumentException.class, () -> array.push(Map.of("x", 1.0)));
            assertThrows(IllegalArgumentException.class, () -> array.push(Map.of(
                    "x", 1.0, "y", 2.0, "vx", 3.0, "vy", 4.0, "ready", true, "unexpected", 5)));
            assertThrows(IllegalArgumentException.class, () -> array.push(Map.of(
                    "x", 1, "y", 2.0, "vx", 3.0, "vy", 4.0, "ready", true)));
            assertThrows(IllegalArgumentException.class, () -> array.updateRow(handle,
                    Map.of("x", 100.0, "y", 200.0, "vx", 300.0, "vy", 400.0, "ready", "bad")));
            assertEquals(valid, array.snapshot(handle));
            assertEquals(1, array.size());
            assertThrows(IllegalArgumentException.class, () -> array.pushValues(1.0, 2.0));
            assertEquals(1, array.size());
        }
    }

    @Test
    void fieldLoansAreDisjointAndViewsAreRevokedAfterClose() {
        var array = InvertedArray.fromClass(particle(), 1);
        var row = array.pushValues(1.0, 2.0, 3.0, 4.0, false);
        var x = array.bindField("x");
        var vx = array.bindField("vx");
        var read = array.borrowRead("x");
        var write = array.borrowWrite("vx");
        try {
            assertEquals("x", read.fieldName());
            assertEquals(1.0, read.readAt(0));
            write.writeAt(0, 11.0);
            assertThrows(IllegalStateException.class, () -> x.set(row, 8.0));
            assertThrows(IllegalStateException.class, () -> array.pushValues(9.0, 9.0, 9.0, 9.0, true));
            assertThrows(IllegalStateException.class, () -> array.eraseSwap(row));
            assertThrows(IllegalStateException.class, () -> array.borrowWrite("x"));
            assertThrows(IllegalStateException.class, () -> read.writeAt(0, 9.0));
        } finally {
            write.close();
            read.close();
        }
        assertEquals(11.0, vx.get(row));
        var view = array.row(row);
        var loan = array.borrowRead("y");
        array.close();
        assertThrows(IllegalStateException.class, () -> view.get("x"));
        assertThrows(IllegalStateException.class, () -> loan.readAt(0));
        assertThrows(IllegalStateException.class, () -> x.get(row));
        loan.close();
    }

    @Test
    void handlesCannotCrossNominalOwnersEvenForIdenticalPhysicalSchemas() {
        try (var a = InvertedArray.fromClass(particle(), 1);
             var b = InvertedArray.fromClass(particle(), 1)) {
            var row = a.pushValues(0.0, 0.0, 0.0, 0.0, false);
            assertThrows(IllegalArgumentException.class, () -> b.read(row, "x"));
            assertThrows(IllegalArgumentException.class, () -> b.row(row));
            assertThrows(IllegalArgumentException.class, () -> b.updateRow(row,
                    Map.of("x", 3.0, "y", 2.0, "vx", 1.0, "vy", 4.0, "ready", true)));
            assertThrows(IllegalArgumentException.class, () -> b.bindField("x").get(row));
        }
    }

    @Test
    void rejectsUnprovedLayoutsAndGuestExecutionRemainsClosed() {
        String[] invalid = {
                "define class NotInverted as x: i32; end",
                "define inverted class Generic<T> as x: i32; end",
                "define inverted class Empty as end",
                "define inverted class Bad as x: string; end",
                "define inverted class Bad as x: Array<i32>; end",
                "define inverted class Bad as x: bigint; end",
                "define inverted class Bad as x: int; end",
                "define inverted class Bad as x: f64 = 9.0; end",
                "define inverted class Bad as x: i32; x: i32; end",
                "define inverted class WithMethods as x: f32; move(f32 dt): void as self.x = 2; end end"
        };
        for (String src : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> InvertedArray.Schema.fromClass(declaration(src)), src);
        }

        Ast.Program guest = Parser.parse("""
                define inverted class Position as
                    x: f64;
                end
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(guest),
                "no direct guest AoS lowering may occur before true compiler integration");
    }
}
