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
    @Test
    void facadeReadLoanWrapperAllocationFailureReleasesUnderlyingReader() {
        try (var array = InvertedArray.fromClass(particle(), 1)) {
            var row = array.pushValues(11.0, 12.0, 13.0, 14.0, true);
            array.armFieldLoanWrapperFailureForTest();
            assertThrows(OutOfMemoryError.class, () -> array.borrowRead("x"));
            // No unreachable underlying read hold may remain.
            try (var writer = array.borrowWrite("x")) {
                writer.writeAt(0, 15.0);
            }
            assertEquals(15.0, (Double) array.read(row, "x"));
            var second = array.pushValues(21.0, 22.0, 23.0, 24.0, false);
            assertEquals(21.0, (Double) array.read(second, "x"));
        }
    }

    @Test
    void facadeWriteLoanWrapperAllocationFailureReleasesUnderlyingWriter() {
        try (var array = InvertedArray.fromClass(particle(), 1)) {
            var row = array.pushValues(31.0, 32.0, 33.0, 34.0, false);
            try (var otherField = array.borrowRead("y")) {
                array.armFieldLoanWrapperFailureForTest();
                assertThrows(OutOfMemoryError.class, () -> array.borrowWrite("x"));
                // The unrelated field loan remains live, but the failed
                // x writer cannot leak an unreleaseable exclusive borrow.
                try (var reader = array.borrowRead("x")) {
                    assertEquals(31.0, (Double) reader.readAt(0));
                }
                try (var writer = array.borrowWrite("x")) {
                    writer.writeAt(0, 35.0);
                }
                assertEquals(32.0, (Double) otherField.readAt(0));
            }
            assertEquals(35.0, (Double) array.read(row, "x"));
            assertDoesNotThrow(() -> array.pushValues(41.0, 42.0, 43.0, 44.0, true));
        }
    }

final class InvertedArrayTest {
    @Test
    void primitiveF64TransposeUpdatesAreAtomicWithoutGuestCallbacks()
            throws InterruptedException {
        try (var array = InvertedArray.fromClass(particle(), 0)) {
            var first = array.pushValues(0.0, 2.0, 3.0, 4.0, false);
            var second = array.pushValues(7.0, 8.0, 9.0, 10.0, true);
            var x = array.bindField(array.schema().bindField("x"));
            var ready = array.bindField(array.schema().bindField("ready"));

            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Runnable increment = () -> {
                try {
                    for (int i = 0; i < 1000; ++i) x.addF64At(0, 1.0);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            };
            Thread a = new Thread(increment);
            Thread b = new Thread(increment);
            a.start();
            b.start();
            a.join();
            b.join();
            assertNull(failure.get());
            assertEquals(2000.0, (Double) x.getAt(0));
            assertEquals(7.0, (Double) x.getAt(1));

            // Field-sensitive loans: disjoint column access remains valid,
            // while an overlapping loan excludes direct compound mutation.
            try (var writeOther = array.borrowWrite("ready")) {
                x.addF64At(0, 3.0);
                assertThrows(IllegalArgumentException.class,
                        () -> ready.addF64At(0, 1.0));
            }
            try (var readX = array.borrowRead("x")) {
                assertThrows(IllegalStateException.class, () -> x.addF64At(0, 1.0));
                assertThrows(IllegalStateException.class, () -> x.addF64(first, 1.0));
            }
            assertThrows(IllegalArgumentException.class, () -> ready.addF64At(0, 1.0));
            assertThrows(IndexOutOfBoundsException.class, () -> x.addF64At(-1, 1.0));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> x.addF64At(Long.MAX_VALUE, 1.0));

            array.eraseSwap(first);
            x.addF64(second, 5.0); // logical handle remains stable after swap-delete
            assertEquals(12.0, (Double) x.getAt(0));
            assertEquals(12.0, (Double) x.get(second));
            assertThrows(IllegalArgumentException.class, () -> x.addF64(first, 1.0));
            assertThrows(IndexOutOfBoundsException.class, () -> x.addF64At(1, 1.0));
        }
    }

    @Test
    void sourceParsedBracketWriteAndReadBindWithoutMacroExpansion() {
        Ast.Program source = Parser.parse("""
                define inverted class Particle as
                    x: f64;
                end

                fnc probe(InvertedArray<Particle> rows, int i): f64 {
                    rows[i].x = 7.0;
                    return rows[i].x;
                }
                """);
        var declarations = source.modules().getFirst().declarations();
        var cls = (Ast.ClassDecl) declarations.get(0);
        var fn = (Ast.FunctionDecl) declarations.get(1);
        var schema = InvertedArray.Schema.fromClass(cls);
        var statement = (Ast.ExprStmt) fn.body().getFirst();
        var returned = (Ast.ReturnStmt) fn.body().get(1);

        var write = InvertedIndexedFieldPlan.bindWrite(statement.expression(), schema);
        var read = InvertedIndexedFieldPlan.bindRead(returned.value(), schema);
        assertEquals("x", write.field().name());
        assertSame(write.field(), read.field());
        assertTrue(write.receiver() instanceof Ast.NameExpr);
        assertTrue(write.index() instanceof Ast.NameExpr);
        assertSame(((Ast.AssignExpr) statement.expression()).value(), write.value());
        assertEquals(InvertedIndexedFieldPlan.Operation.WRITE, write.operation());
        assertEquals(InvertedIndexedFieldPlan.Operation.READ, read.operation());

        // Recognizing AST shapes does not satisfy typed ownership lowering.
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(source));
    }

    @Test
    void indexedFieldAstPlanRetainsExactExpressionsAndNominalBindings() {
        var schema = InvertedArray.Schema.fromClass(particle());
        var differentSchema = InvertedArray.Schema.fromClass(particle());
        Ast.Expr receiver = new Ast.NameExpr("rows");
        Ast.Expr index = new Ast.CallExpr(new Ast.NameExpr("nextIndex"),
                java.util.List.of());
        Ast.Expr rhs = new Ast.CallExpr(new Ast.NameExpr("nextValue"),
                java.util.List.of());
        var selected = new Ast.IndexExpr(receiver, index);
        var field = new Ast.MemberExpr(selected, "x");
        var write = new Ast.AssignExpr(field, rhs);

        var readPlan = InvertedIndexedFieldPlan.bindRead(field, schema);
        assertEquals(InvertedIndexedFieldPlan.Operation.READ, readPlan.operation());
        assertSame(receiver, readPlan.receiver());
        assertSame(index, readPlan.index());
        assertNull(readPlan.value());
        assertSame(schema.bindField("x"), readPlan.field());

        var writePlan = InvertedIndexedFieldPlan.bindWrite(write, schema);
        readPlan.requireExactSource(field, schema);
        writePlan.requireExactSource(write, schema);
        assertThrows(IllegalStateException.class,
                () -> readPlan.requireExactSource(
                        new Ast.MemberExpr(new Ast.IndexExpr(receiver, index), "x"), schema),
                "equal-by-value new AST must not inherit an old field binding");
        assertThrows(IllegalStateException.class,
                () -> writePlan.requireExactSource(
                        new Ast.AssignExpr(field, rhs), schema),
                "reconstructed assignment must revalidate proof");
        assertThrows(IllegalStateException.class,
                () -> writePlan.requireExactSource(write, InvertedArray.Schema.fromClass(particle())),
                "nominally distinct declaration identity cannot reuse proof");
        assertThrows(IllegalStateException.class,
                () -> writePlan.requireExactSource(
                        new Ast.AssignExpr(new Ast.MemberExpr(selected, "y"), rhs), schema));
        assertThrows(IllegalStateException.class,
                () -> writePlan.requireExactSource(
                        new Ast.AssignExpr(field, new Ast.LiteralExpr(3.0)), schema));
        assertEquals(InvertedIndexedFieldPlan.Operation.WRITE, writePlan.operation());
        assertSame(receiver, writePlan.receiver());
        assertSame(index, writePlan.index());
        assertSame(rhs, writePlan.value());
        assertSame(schema.bindField("x"), writePlan.field());
        assertNotSame(differentSchema.bindField("x"), writePlan.field());
        assertThrows(IllegalArgumentException.class,
                () -> InvertedIndexedFieldPlan.bindRead(
                        new Ast.MemberExpr(selected, "no_such_field"), schema));
        assertThrows(IllegalArgumentException.class,
                () -> InvertedIndexedFieldPlan.bindWrite(field, schema));
        assertThrows(IllegalArgumentException.class,
                () -> InvertedIndexedFieldPlan.bindRead(selected, schema));
        assertThrows(IllegalArgumentException.class,
                () -> InvertedIndexedFieldPlan.bindWrite(
                        new Ast.AssignExpr(new Ast.NameExpr("x"), rhs), schema));
        assertThrows(IllegalArgumentException.class,
                () -> InvertedIndexedFieldPlan.bindRead(
                        new Ast.MemberExpr(
                                new Ast.IndexExpr(receiver, null), "x"), schema));
    }

    @Test
    void boundColumnCompoundUpdateIsAtomicAndDoesNotRebindByGuestName()
            throws InterruptedException {
        var schema = InvertedArray.Schema.fromClass(declaration("""
                define inverted class OddName as
                    __ores_internal_row: i32;
                end
                """));
        try (var array = new InvertedArray(schema, 0)) {
            var row = array.pushValues(0);
            var binding = schema.bindField("__ores_internal_row");
            var accessor = array.bindField(binding);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Runnable increment = () -> {
                try {
                    for (int i = 0; i < 1000; i++) {
                        accessor.transformAt(0, old -> (Integer) old + 1);
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            };
            Thread first = new Thread(increment);
            Thread second = new Thread(increment);
            first.start();
            second.start();
            first.join();
            second.join();
            assertNull(failure.get());
            assertEquals(2000, accessor.getAt(0));
            assertEquals(2000, accessor.get(row));

            assertThrows(IllegalStateException.class,
                    () -> accessor.transformAt(0, old -> {
                        try { accessor.getAt(0); } catch (IllegalStateException ignored) { }
                        return 9999; // swallowed nested error must taint transaction
                    }));
            assertEquals(2000, accessor.getAt(0));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> accessor.transformAt(Long.MAX_VALUE, old -> 0));
            assertEquals(2000, accessor.getAt(0));

            try (var read = array.borrowRead("__ores_internal_row")) {
                assertThrows(IllegalStateException.class,
                        () -> accessor.transformAt(0, old -> 1));
            }
            accessor.transformAt(0, old -> (Integer) old + 1);
            assertEquals(2001, accessor.get(row));
        }
    }

    @Test
    void generatedTransposeNamespaceDoesNotCollideWithGuestMemberNames() {
        var decl = declaration("""
                define inverted class UnusualNames as
                    row: i32;
                    columns: i32;
                    at: i32;
                    bindField: i32;
                    __ores_internal_row: i32;
                end
                """);
        var schema = InvertedArray.Schema.fromClass(decl);
        var unrelated = InvertedArray.Schema.fromClass(decl);
        var field = schema.bindField("__ores_internal_row");
        assertEquals("__ores_internal_row", field.name());
        assertEquals(InvertedColumnStore.Kind.I32, field.kind());

        try (var first = new InvertedArray(schema, 0);
             var second = new InvertedArray(schema, 0);
             var unrelatedStore = new InvertedArray(unrelated, 0)) {
            var a = first.pushValues(1, 2, 3, 4, 5);
            var b = second.pushValues(11, 12, 13, 14, 15);
            unrelatedStore.pushValues(21, 22, 23, 24, 25);

            // The same nominal schema namespace can bind different owners.
            assertEquals(5, first.bindField(field).get(a));
            assertEquals(15, second.bindField(field).get(b));
            assertEquals(5, first.bindField(field).getAt(0));
            assertEquals(15, second.bindField(field).getAt(0));

            first.bindField(schema.bindField("row")).setAt(0, 100);
            first.bindField(schema.bindField("columns")).setAt(0, 200);
            first.bindField(schema.bindField("at")).setAt(0, 300);
            first.bindField(schema.bindField("bindField")).setAt(0, 400);
            first.bindField(field).setAt(0, 500);
            assertArrayEquals(new Object[]{100, 200, 300, 400, 500},
                    first.snapshot(a).values().toArray());

            // Class names and field spellings alone cannot authorize reuse
            // of a generated binding from an unrelated compiler namespace.
            assertThrows(IllegalArgumentException.class,
                    () -> unrelatedStore.bindField(field));
            assertThrows(IllegalArgumentException.class,
                    () -> first.bindField(unrelated.bindField("row")));
            assertThrows(IllegalArgumentException.class,
                    () -> first.bindField((InvertedArray.Schema.FieldBinding) null));
            assertThrows(IllegalArgumentException.class,
                    () -> schema.bindField("doesNotExist"));
            assertEquals(21, unrelatedStore.bindField("row").getAt(0));
        }
    }

    @Test
    void fusedTransposeAccessTracksLiveDensePositionsAndLoanConflicts() {
        try (var array = InvertedArray.fromClass(particle(), 0)) {
            var x = array.bindField(array.schema().bindField("x"));
            var ready = array.bindField(array.schema().bindField("ready"));
            assertThrows(IndexOutOfBoundsException.class, () -> x.getAt(0));
            assertThrows(IndexOutOfBoundsException.class, () -> x.setAt(0, 1.0));
            var first = array.pushValues(1.0, 2.0, 3.0, 4.0, false);
            var last = array.pushValues(9.0, 8.0, 7.0, 6.0, true);
            assertEquals(1.0, x.getAt(0));
            assertEquals(9.0, x.getAt(1));
            assertEquals(true, ready.getAt(1));
            assertThrows(IndexOutOfBoundsException.class, () -> x.getAt(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> x.getAt(Long.MAX_VALUE));
            assertThrows(IndexOutOfBoundsException.class, () -> x.setAt(Long.MIN_VALUE, 8.0));
            assertThrows(IllegalArgumentException.class, () -> x.setAt(0, 123));

            // Field-disjoint loans permit other columns but block conflicting
            // direct accesses. The fused target must not bypass this check.
            try (var read = array.borrowRead("x");
                 var write = array.borrowWrite("ready")) {
                assertEquals(1.0, x.getAt(0));
                assertThrows(IllegalStateException.class, () -> x.setAt(0, 7.0));
                assertThrows(IllegalStateException.class, () -> ready.getAt(0));
                assertThrows(IllegalStateException.class, () -> ready.setAt(0, true));
            }
            x.setAt(0, 5.0);
            array.eraseSwap(first);
            assertEquals(9.0, x.getAt(0));
            assertEquals(9.0, x.get(last));
            assertThrows(IndexOutOfBoundsException.class, () -> x.getAt(1));
            assertThrows(IllegalArgumentException.class, () -> x.get(first));
        }
    }

    @Test
    void proposedBracketTargetsResolvePositionsAndOpaqueHandlesWithoutAliasing() {
        try (var array = InvertedArray.fromClass(particle(), 0)) {
            assertThrows(IndexOutOfBoundsException.class, () -> array.at(0));
            var a = array.pushValues(1.0, 2.0, 3.0, 4.0, false);
            var b = array.pushValues(5.0, 6.0, 7.0, 8.0, true);
            var c = array.pushValues(9.0, 10.0, 11.0, 12.0, false);
            var selected = array.at(2L);
            assertEquals(9.0, selected.get("x"));
            assertEquals(9.0, array.at(c).get("x"));
            array.at(0).set("x", 13.0);
            assertEquals(13.0, array.at(a).get("x"));
            assertThrows(IndexOutOfBoundsException.class, () -> array.at(-1L));
            assertThrows(IndexOutOfBoundsException.class, () -> array.at(3L));
            assertThrows(IndexOutOfBoundsException.class, () -> array.at(Long.MAX_VALUE));
            assertThrows(IndexOutOfBoundsException.class, () -> array.at(Long.MIN_VALUE));

            array.eraseSwap(b);
            assertEquals(9.0, array.at(1).get("x"));
            assertEquals(9.0, selected.get("x"));
            assertThrows(IllegalArgumentException.class, () -> array.at(b));
            assertThrows(IndexOutOfBoundsException.class, () -> array.at(2));
            array.pushValues(17.0, 18.0, 19.0, 20.0, true);
            assertEquals(17.0, array.at(2).get("x"));
            assertEquals(9.0, selected.get("x"));
        }
    }

    @Test
    void proposedBracketTargetsRespectColumnLoansAndStoreRevocation() {
        var array = InvertedArray.fromClass(particle(), 0);
        var h = array.pushValues(1.0, 2.0, 3.0, 4.0, false);
        var projection = array.at(0);
        try (var write = array.borrowWrite("x")) {
            assertThrows(IllegalStateException.class, () -> array.at(0));
            assertThrows(IllegalStateException.class, () -> array.at(h));
            assertThrows(IllegalStateException.class, () -> projection.get("x"));
            assertThrows(IllegalStateException.class, () -> projection.set("x", 7.0));
        }
        assertEquals(1.0, array.at(h).get("x"));
        array.close();
        assertThrows(IllegalStateException.class, () -> array.at(0));
        assertThrows(IllegalStateException.class, () -> array.at(h));
        assertThrows(IllegalStateException.class, () -> projection.get("x"));
    }

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
            assertThrows(IllegalArgumentException.class, () -> b.at(row));
            assertThrows(IllegalArgumentException.class, () ->
                    a.at((InvertedColumnStore.Handle) null));
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
}
