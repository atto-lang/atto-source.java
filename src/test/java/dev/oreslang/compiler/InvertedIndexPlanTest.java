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
    void stagedIndexPlanRejectsNonIntegralLiteralsAndNonArithmeticOperators() {
        Ast.ClassDecl particle = particle();
        Ast.NameExpr array = new Ast.NameExpr("particles");
        for (Ast.Expr invalid : List.<Ast.Expr>of(
                new Ast.LiteralExpr(true),
                new Ast.LiteralExpr("0"),
                new Ast.LiteralExpr(0.0),
                new Ast.LiteralExpr(0.0f),
                new Ast.UnaryExpr("not", new Ast.NameExpr("i")),
                new Ast.UnaryExpr("!", new Ast.NameExpr("i")),
                new Ast.BinaryExpr("eq", new Ast.NameExpr("i"),
                        new Ast.LiteralExpr(0)),
                new Ast.BinaryExpr("and", new Ast.NameExpr("i"),
                        new Ast.LiteralExpr(1)),
                new Ast.BinaryExpr("||", new Ast.NameExpr("i"),
                        new Ast.LiteralExpr(1)))) {
            Ast.IndexExpr site = new Ast.IndexExpr(array, invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> InvertedIndexPlan.compile(
                            particle, site, "x", InvertedIndexPlan.Access.READ),
                    () -> "unverified noninteger site must fail closed: " + invalid);
        }

        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.planInvertedIndex(
                        particle,
                        new Ast.IndexExpr(array, new Ast.LiteralExpr(true)),
                        "x", InvertedIndexPlan.Access.READ),
                "the public staging facade must not bypass index shape admission");

        for (Ast.Expr validCandidate : List.<Ast.Expr>of(
                new Ast.LiteralExpr(0),
                new Ast.LiteralExpr(1L),
                new Ast.UnaryExpr("-", new Ast.NameExpr("i")),
                new Ast.BinaryExpr("+", new Ast.NameExpr("i"),
                        new Ast.LiteralExpr(1)),
                new Ast.BinaryExpr("*", new Ast.NameExpr("i"),
                        new Ast.LiteralExpr(2L)))) {
            Ast.IndexExpr site = new Ast.IndexExpr(array, validCandidate);
            InvertedIndexPlan candidate = InvertedIndexPlan.compile(
                    particle, site, "x", InvertedIndexPlan.Access.READ);
            candidate.requireExactSite(site);
        }
    }

    @Test
    void syntheticDeepOrOverBudgetIndexAstFailsWithDiagnosticNotStackOverflow() {
        Ast.ClassDecl particle = particle();
        Ast.Expr nested = new Ast.NameExpr("i");
        for (int i = 0; i < 1025; i++) {
            nested = new Ast.UnaryExpr("-", nested);
        }
        Ast.IndexExpr deep = new Ast.IndexExpr(
                new Ast.NameExpr("particles"), nested);
        IllegalArgumentException depth = assertThrows(
                IllegalArgumentException.class,
                () -> InvertedIndexPlan.compile(
                        particle, deep, "x", InvertedIndexPlan.Access.READ));
        assertTrue(depth.getMessage().contains("complexity budget"),
                depth::getMessage);

        // A shared binary AST subtree can be expanded in a short source
        // description but still require exponential staging work if visited
        // recursively. The cap must count logical visits, not unique nodes.
        Ast.Expr wide = new Ast.NameExpr("i");
        for (int i = 0; i < 13; i++) {
            wide = new Ast.BinaryExpr("+", wide, wide);
        }
        Ast.IndexExpr overBudget = new Ast.IndexExpr(
                new Ast.NameExpr("particles"), wide);
        IllegalArgumentException nodes = assertThrows(
                IllegalArgumentException.class,
                () -> InvertedIndexPlan.compile(
                        particle, overBudget, "x", InvertedIndexPlan.Access.READ));
        assertTrue(nodes.getMessage().contains("complexity budget"),
                nodes::getMessage);

        Ast.Expr withinBudget = new Ast.NameExpr("i");
        for (int i = 0; i < 100; i++) {
            withinBudget = new Ast.UnaryExpr("-", withinBudget);
        }
        Ast.IndexExpr acceptedCandidate = new Ast.IndexExpr(
                new Ast.NameExpr("particles"), withinBudget);
        assertDoesNotThrow(() -> InvertedIndexPlan.compile(
                particle, acceptedCandidate, "x", InvertedIndexPlan.Access.READ));
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
    void differentDeclarationsWithSameNameAndLayoutCannotBorrowIndexPlanAuthority() {
        Ast.ClassDecl admitted = particle();
        Ast.ClassDecl lookalike = particle();
        assertNotSame(admitted, lookalike,
                "a name and layout do not establish nominal declaration identity");
        InvertedIndexPlan read = InvertedIndexPlan.compile(
                admitted, site(), "x", InvertedIndexPlan.Access.READ);
        InvertedIndexPlan write = InvertedIndexPlan.compile(
                admitted, site(), "x", InvertedIndexPlan.Access.WRITE);

        try (var original = InvertedArray.fromClass(admitted, 1);
             var impostor = InvertedArray.fromClass(lookalike, 1)) {
            original.pushValues(1.0, 2.0, false);
            impostor.pushValues(3.0, 4.0, true);
            assertTrue(original.schema().representsDeclaration(admitted));
            assertFalse(impostor.schema().representsDeclaration(admitted));
            assertEquals(1.0, read.bind(original).read(0));
            assertThrows(IllegalArgumentException.class, () -> read.bind(impostor));
            assertThrows(IllegalArgumentException.class, () -> write.bind(impostor));
            assertEquals(3.0, impostor.bindField("x").getAt(0));
        }

        Ast.ClassDecl immutableLookalike = declaration("""
                define inverted class Particle as
                    val f64 x;
                    val f64 y;
                    val bool ready;
                end
                """);
        try (var immutable = InvertedArray.fromClass(immutableLookalike, 1)) {
            immutable.pushValues(7.0, 8.0, false);
            assertThrows(IllegalArgumentException.class, () -> write.bind(immutable),
                    "a same-name physical layout must not launder immutable fields");
            assertEquals(7.0, immutable.bindField("x").getAt(0));
        }
    }

    @Test
    void fusedIndexedAccessTracksCurrentDenseRowAfterSwapDelete() {
        Ast.ClassDecl particle = particle();
        var read = InvertedIndexPlan.compile(
                particle, site(), "x", InvertedIndexPlan.Access.READ);
        var write = InvertedIndexPlan.compile(
                particle, site(), "x", InvertedIndexPlan.Access.WRITE);
        try (var array = InvertedArray.fromClass(particle, 2)) {
            var removed = array.pushValues(11.0, 1.0, false);
            var survivor = array.pushValues(22.0, 2.0, true);
            assertEquals(11.0, read.bind(array).read(0));
            assertEquals(22.0, read.bind(array).read(1));

            array.eraseSwap(removed);
            assertEquals(22.0, read.bind(array).read(0),
                    "dense index must select the survivor after swap-delete");
            assertThrows(IllegalArgumentException.class,
                    () -> read.bind(array).read(1));
            write.bind(array).write(0, 33.0);
            assertEquals(33.0, array.read(survivor, "x"),
                    "positional write must update the surviving logical row");
            assertThrows(IllegalArgumentException.class,
                    () -> array.read(removed, "x"));
            assertThrows(IllegalArgumentException.class,
                    () -> write.bind(array).write(Long.MAX_VALUE, 44.0));
            assertEquals(33.0, read.bind(array).read(0));
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
