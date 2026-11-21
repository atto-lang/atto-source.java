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
    void parsesAndExecutesRestrictedSourceMethodsOnPhysicalColumns() {
        Ast.ClassDecl declaration = declaration("""
                define inverted class Particle as
                    x: f64;
                    y: f64;
                    vx: f64;
                    vy: f64;

                    move(f64 dt): void {
                        self.x = self.x + self.vx * dt;
                        self.y = self.y + self.vy * dt;
                    }
                end
                """);
        assertEquals(1, declaration.methods().size());
        try (var array = InvertedArray.fromClass(declaration, 2)) {
            var h = array.pushValues(1.0, 2.0, 3.0, 4.0);
            var row = array.row(h);
            row.invoke("move", 0.1);
            assertEquals(1.3, (double) row.get("x"), 1e-12);
            assertEquals(2.4, (double) row.get("y"), 1e-12);
            row.invoke("move", 0.1);
            assertEquals(1.6, (double) row.get("x"), 1e-12);
            assertEquals(2.8, (double) row.get("y"), 1e-12);
            assertThrows(IllegalArgumentException.class, () -> row.invoke("move", 1));
            assertThrows(IllegalArgumentException.class, () -> row.invoke("missing", 1.0));
            array.eraseSwap(h);
            assertThrows(IllegalArgumentException.class, () -> row.invoke("move", 1.0));
        }
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                Parser.parse("""
                    define inverted class Particle as
                        x: f64;
                        move(f64 dt): void { self.x = self.x + dt; }
                    end
                    """)),
                "The guest interpreter must remain disabled until compiler ownership admission");
    }

    @Test
    void pureRowMethodsCommitAtomicallyWithoutLostConcurrentUpdates() throws InterruptedException {
        var declaration = declaration("""
                define inverted class Counter as
                    value: f64;
                    inc(f64 d): void { self.value = self.value + d; }
                end
                """);
        try (var array = InvertedArray.fromClass(declaration, 1)) {
            var handle = array.pushValues(0.0);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Runnable increment = () -> {
                try {
                    for (int i = 0; i < 1000; ++i) array.invoke(handle, "inc", 1.0);
                } catch (Throwable t) { failure.compareAndSet(null, t); }
            };
            Thread first = new Thread(increment);
            Thread second = new Thread(increment);
            first.start();
            second.start();
            first.join();
            second.join();
            assertNull(failure.get());
            assertEquals(2000.0, array.read(handle, "value"));
        }
    }

    @Test
    void cannotSilentlyDropDeclaredMethodsThroughSchemaOnlyConstructor() {
        var withMethod = declaration("""
                define inverted class Counter as
                    value: f64;
                    inc(f64 d): void { self.value = self.value + d; }
                end
                """);
        var schema = InvertedArray.Schema.fromClass(withMethod);
        assertThrows(IllegalArgumentException.class, () -> new InvertedArray(schema, 1));
        try (var array = InvertedArray.fromClass(withMethod, 1)) {
            var row = array.pushValues(1.0);
            array.invoke(row, "inc", 2.0);
            assertEquals(3.0, array.read(row, "value"));
        }
    }

    @Test
    void methodPlannerRejectsExcessiveParameterAndExpressionBudgets() {
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            if (i != 0) params.append(", ");
            params.append("f64 p").append(i);
        }
        String tooManyParams = "define inverted class Counter as value: f64; "
                + "inc(" + params + "): void { self.value = self.value + p0; } end";
        assertThrows(IllegalArgumentException.class,
                () -> InvertedArray.fromClass(declaration(tooManyParams), 1));

        // 60 bounded-depth expressions, with a cumulative AST node budget
        // exceeding the stage-one compiler's 4,096 expression-node ceiling.
        String expression = "self.value" + " + 1.0".repeat(45);
        String body = ("self.value = " + expression + "; ").repeat(60);
        String tooManyNodes = "define inverted class Counter as value: f64; "
                + "inc(f64 delta): void { " + body + " } end";
        var ex = assertThrows(IllegalArgumentException.class,
                () -> InvertedArray.fromClass(declaration(tooManyNodes), 1));
        assertTrue(ex.getMessage().contains("budget"), ex.getMessage());
    }

    @Test
    void rejectsUnsupportedAndEffectfulMethodBodiesBeforeAllocatingOwner() {
        String[] methods = {
                "sleep(f64 x): void { await x; }",
                "bad(f64 x): void { self.value = f(x); }",
                "bad(f64 x): void { self.value = self.value / x; }",
                "bad(f64 x): void { self.value = x; return; }",
                "bad(f64 x): void { self.value = 1; }",
                "bad(i32 x): void { self.value = self.value + x; }"
        };
        for (String method : methods) {
            String source = "define inverted class Counter as value: f64; "
                    + method + " end";
            assertThrows(IllegalArgumentException.class,
                    () -> InvertedArray.fromClass(declaration(source), 0), source);
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
                "define inverted class Bad as x: i32; x: i32; end"
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

    @Test
    void completeNominalSchemaCheckRejectsSameNamePhysicalSubstitution() {
        Ast.ClassDecl expected = declaration("""
                define inverted class Particle as
                    x: f64;
                    y: f64;
                end
                """);
        Ast.ClassDecl wrongKind = declaration("""
                define inverted class Particle as
                    x: i64;
                    y: f64;
                end
                """);
        Ast.ClassDecl wrongOrder = declaration("""
                define inverted class Particle as
                    y: f64;
                    x: f64;
                end
                """);
        Ast.ClassDecl wrongRecord = declaration("""
                define inverted class Particle as
                    sample: record { ok: bool, x: f64 };
                end
                """);
        Ast.ClassDecl otherRecord = declaration("""
                define inverted class Particle as
                    sample: record { ok: bool, x: i64 };
                end
                """);

        try (var array = InvertedArray.fromClass(expected, 0);
                var kindMismatch = InvertedArray.fromClass(wrongKind, 0);
                var orderMismatch = InvertedArray.fromClass(wrongOrder, 0)) {
            assertDoesNotThrow(() -> array.schema().requireMatches(expected));
            assertEquals(
                    array.schema().physicalFingerprint(),
                    InvertedArray.Schema.fromClass(expected).physicalFingerprint());
            assertNotEquals(
                    array.schema().physicalFingerprint(),
                    kindMismatch.schema().physicalFingerprint());
            assertNotEquals(
                    array.schema().physicalFingerprint(),
                    orderMismatch.schema().physicalFingerprint());
            assertThrows(IllegalStateException.class,
                    () -> array.schema().requireMatches(wrongKind));
            assertThrows(IllegalStateException.class,
                    () -> array.schema().requireMatches(wrongOrder));
        }

        try (var array = InvertedArray.fromClass(wrongRecord, 0);
                var other = InvertedArray.fromClass(otherRecord, 0)) {
            assertNotEquals(
                    array.schema().physicalFingerprint(),
                    other.schema().physicalFingerprint());
            assertThrows(IllegalStateException.class,
                    () -> array.schema().requireMatches(otherRecord));
        }
    }

}
