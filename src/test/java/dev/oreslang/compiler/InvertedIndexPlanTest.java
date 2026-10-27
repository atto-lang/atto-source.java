package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.InvertedArray;
import dev.oreslang.runtime.InvertedColumnStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class InvertedIndexPlanTest {
    private static Ast.ClassDecl declaration(String source) {
        Ast.Program program = Parser.parse(source);
        return (Ast.ClassDecl) program.modules().getFirst()
                .declarations().getFirst();
    }

    private static Ast.ClassDecl particle() {
        return declaration("""
                define inverted class Particle as
                    x: f64;
                    y: f64;
                    ready: bool;
                end
                """);
    }

    private static Ast.IndexExpr site() {
        return new Ast.IndexExpr(
                new Ast.NameExpr("particles"),
                new Ast.NameExpr("i"));
    }

    @Test
    void loweredReadAndWriteEvaluateOperandsExactlyOnceInLanguageOrder() {
        Ast.ClassDecl particle = particle();
        Ast.IndexExpr readSite = site();
        Ast.IndexExpr writeSite = site();
        var read = OresCompiler.planInvertedIndex(
                particle, readSite, "x", InvertedIndexPlan.Access.READ);
        var write = OresCompiler.planInvertedIndex(
                particle, writeSite, "x", InvertedIndexPlan.Access.WRITE);

        try (var array = InvertedArray.fromClass(particle, 2)) {
            array.pushValues(1.0, 2.0, false);
            array.pushValues(3.0, 4.0, true);

            List<String> order = new ArrayList<>();
            AtomicInteger receivers = new AtomicInteger();
            AtomicInteger indexes = new AtomicInteger();
            Object value = read.read(
                    () -> {
                        receivers.incrementAndGet();
                        order.add("receiver");
                        return array;
                    },
                    () -> {
                        indexes.incrementAndGet();
                        order.add("index");
                        return 1L;
                    });
            assertEquals(3.0, value);
            assertEquals(1, receivers.get());
            assertEquals(1, indexes.get());
            assertEquals(List.of("receiver", "index"), order);

            order.clear();
            AtomicInteger values = new AtomicInteger();
            Object assigned = write.write(
                    () -> {
                        values.incrementAndGet();
                        order.add("value");
                        return 9.0;
                    },
                    () -> {
                        receivers.incrementAndGet();
                        order.add("receiver");
                        return array;
                    },
                    () -> {
                        indexes.incrementAndGet();
                        order.add("index");
                        return 0L;
                    });
            assertEquals(9.0, assigned);
            assertEquals(List.of("value", "receiver", "index"), order);
            assertEquals(1, values.get());
            assertEquals(2, receivers.get());
            assertEquals(2, indexes.get());
            assertEquals(9.0, read.bind(array).read(0));
        }
    }

    @Test
    void exactAstIdentityIsAuthorityAndRewritesMustBeReplanned() {
        Ast.IndexExpr original = site();
        var plan = InvertedIndexPlan.compile(
                particle(), original, "x", InvertedIndexPlan.Access.READ);
        plan.requireExactSite(original);

        Ast.IndexExpr reconstructed = site();
        assertEquals(original, reconstructed,
                "records are structurally equal for this fixture");
        assertNotSame(original, reconstructed);
        assertThrows(IllegalStateException.class,
                () -> plan.requireExactSite(reconstructed));
    }

    @Test
    void v1FailsClosedOnSuspendingDynamicOrMemberDerivedBracketOperands() {
        Ast.ClassDecl particle = particle();

        var awaited = new Ast.IndexExpr(
                new Ast.NameExpr("particles"),
                new Ast.AwaitExpr(new Ast.NameExpr("futureIndex")));
        assertThrows(IllegalArgumentException.class, () ->
                InvertedIndexPlan.compile(
                        particle, awaited, "x", InvertedIndexPlan.Access.READ));

        var called = new Ast.IndexExpr(
                new Ast.NameExpr("particles"),
                new Ast.CallExpr(
                        new Ast.NameExpr("nextIndex"),
                        List.of(), false, List.of(), Ast.CallOrigin.DIRECT));
        assertThrows(IllegalArgumentException.class, () ->
                InvertedIndexPlan.compile(
                        particle, called, "x", InvertedIndexPlan.Access.READ));

        var memberReceiver = new Ast.IndexExpr(
                new Ast.MemberExpr(new Ast.NameExpr("owner"), "particles"),
                new Ast.LiteralExpr(0L));
        assertThrows(IllegalArgumentException.class, () ->
                InvertedIndexPlan.compile(
                        particle, memberReceiver, "x", InvertedIndexPlan.Access.READ));
    }

    @Test
    void runtimeSchemaAndColumnLoanAuthorityRemainMandatory() {
        Ast.ClassDecl particle = particle();
        var read = InvertedIndexPlan.compile(
                particle, site(), "x", InvertedIndexPlan.Access.READ);
        var write = InvertedIndexPlan.compile(
                particle, site(), "x", InvertedIndexPlan.Access.WRITE);

        try (var array = InvertedArray.fromClass(particle, 1)) {
            array.pushValues(1.0, 2.0, false);

            try (var mutableLoan = array.borrowWrite("x")) {
                assertThrows(IllegalStateException.class,
                        () -> read.bind(array).read(0));
            }
            try (var readLoan = array.borrowRead("x")) {
                assertThrows(IllegalStateException.class,
                        () -> write.bind(array).write(0, 7.0));
            }

            assertThrows(IllegalArgumentException.class,
                    () -> read.bind(array).read(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> read.bind(array).read(Long.MAX_VALUE));
        }

        Ast.ClassDecl other = declaration("""
                define inverted class Other as
                    x: f64;
                    y: f64;
                    ready: bool;
                end
                """);
        try (var wrong = InvertedArray.fromClass(other, 1)) {
            wrong.pushValues(1.0, 2.0, false);
            assertThrows(IllegalArgumentException.class, () -> read.bind(wrong));
        }
    }

    @Test
    void compilerPlanApiDoesNotExposeRowViewsOrHandles() {
        for (Method method : InvertedIndexPlan.class.getMethods()) {
            assertNotEquals(
                    InvertedArray.RowView.class, method.getReturnType(),
                    () -> "compiler plan leaks a RowView through " + method);
            assertNotEquals(
                    InvertedColumnStore.Handle.class, method.getReturnType(),
                    () -> "compiler plan leaks a row Handle through " + method);
        }
        for (Method method : InvertedIndexPlan.Bound.class.getMethods()) {
            assertNotEquals(
                    InvertedArray.RowView.class, method.getReturnType(),
                    () -> "bound plan leaks a RowView through " + method);
            assertNotEquals(
                    InvertedColumnStore.Handle.class, method.getReturnType(),
                    () -> "bound plan leaks a row Handle through " + method);
        }
    }
}
