package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AST-only syntax admission; not proof that Scheduler/Region guest APIs execute. */
final class StructuredComputeSyntaxTest {
    private static final String COMPUTE = """
            pub fnc main(): void {
                let scheduler = Scheduler.new(
                    workers: #auto,
                    affinity: #soft,
                    topology: #numa_aware,
                    work_stealing: #hierarchical,
                    task_migration: #safe_points
                );
                let group = ActorGroup.new(
                    scheduler: scheduler,
                    sharding: #cpu_local,
                    batch_dispatch: true
                );
                let motion = Region<Motion>.new(
                    layouts: [#aos, #soa, #aosoa],
                    authority: #soa,
                    tile_size: 32,
                    placement: #numa_local
                );
                await group.compute(
                    region: motion,
                    layout: #aosoa,
                    reads: [Motion.vx, Motion.vy],
                    writes: [Motion.x, Motion.y],
                    kernel: fnc (chunk) {
                        chunk.x += chunk.vx * dt;
                        chunk.y += chunk.vy * dt;
                    }
                );
            }
            """;

    @Test
    void parsesTheExactCanonicalComputeSurfaceWithoutDiscardingMetadata() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse(COMPUTE));
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(4, fn.body().size());
        Ast.CallExpr scheduler = assertInstanceOf(Ast.CallExpr.class,
                ((Ast.BindingStmt) fn.body().get(0)).initializer());
        assertEquals(List.of("workers", "affinity", "topology", "work_stealing", "task_migration"),
                scheduler.arguments().stream()
                        .map(a -> assertInstanceOf(Ast.NamedArgumentExpr.class, a).label()).toList());
        Ast.CallExpr region = assertInstanceOf(Ast.CallExpr.class,
                ((Ast.BindingStmt) fn.body().get(2)).initializer());
        Ast.MemberExpr ctor = assertInstanceOf(Ast.MemberExpr.class, region.callee());
        assertEquals("new", ctor.member());
        Ast.TypeSpecializationExpr generic = assertInstanceOf(
                Ast.TypeSpecializationExpr.class, ctor.receiver());
        assertEquals(new Ast.NameExpr("Region"), generic.typeName());
        assertEquals(1, generic.arguments().size());
        assertEquals("Motion", generic.arguments().getFirst().name());
        Ast.NamedArgumentExpr layouts = assertInstanceOf(Ast.NamedArgumentExpr.class,
                region.arguments().getFirst());
        assertEquals("layouts", layouts.label());
        Ast.ListExpr list = assertInstanceOf(Ast.ListExpr.class, layouts.value());
        assertEquals(3, list.elements().size());
        list.elements().forEach(e -> assertInstanceOf(Ast.LiteralExpr.class, e));
        Ast.AwaitExpr awaited = assertInstanceOf(Ast.AwaitExpr.class,
                ((Ast.ExprStmt) fn.body().get(3)).expression());
        Ast.CallExpr compute = assertInstanceOf(Ast.CallExpr.class, awaited.expression());
        Ast.NamedArgumentExpr kernel = assertInstanceOf(Ast.NamedArgumentExpr.class,
                compute.arguments().get(4));
        assertEquals("kernel", kernel.label());
        Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, kernel.value());
        assertEquals(2, lambda.blockBody().size());
        for (Ast.Stmt stmt : lambda.blockBody()) {
            Ast.CompoundAssignExpr update = assertInstanceOf(Ast.CompoundAssignExpr.class,
                    assertInstanceOf(Ast.ExprStmt.class, stmt).expression());
            assertEquals("+", update.operator());
            assertInstanceOf(Ast.MemberExpr.class, update.target());
        }
    }

    @Test
    void preservesPositionalThenNamedAndSourceOrderedExpressionValues() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    invoke(1, later: 2, kernel: fnc (chunk) { return chunk; });
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().getFirst();
        Ast.CallExpr call = (Ast.CallExpr) ((Ast.ExprStmt) fn.body().getFirst()).expression();
        assertEquals(3, call.arguments().size());
        assertInstanceOf(Ast.LiteralExpr.class, call.arguments().getFirst());
        assertEquals("later", ((Ast.NamedArgumentExpr) call.arguments().get(1)).label());
        assertEquals("kernel", ((Ast.NamedArgumentExpr) call.arguments().get(2)).label());
    }

    @Test
    void rejectsAmbiguousNamedArgumentShapesAtParsing() {
        checkSyntaxRejected("target(a: 1, 2)", "positional arguments cannot follow named");
        checkSyntaxRejected("target(a: 1, a: 2)", "duplicate named call argument");
        checkSyntaxRejected("target(...xs, a: 1)", "cannot mix with argument spreads");
        checkSyntaxRejected("target(a: 1, ...xs)", "positional arguments cannot follow named");
        checkSyntaxRejected("target(...a: 1)", "cannot mix with argument spreads");
    }

    @Test
    void parsesConstructorLabelsButRetainsOldNoSpreadBoundary() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    let candidate = new Candidate(y: 2, x: 1);
                }
                """);
        Ast.FunctionDecl fn = (Ast.FunctionDecl) program.modules().getFirst()
                .declarations().getFirst();
        Ast.NewExpr constructed = assertInstanceOf(Ast.NewExpr.class,
                ((Ast.BindingStmt) fn.body().getFirst()).initializer());
        assertEquals(List.of("y", "x"), constructed.arguments().stream()
                .map(arg -> ((Ast.NamedArgumentExpr) arg).label()).toList());
        checkSyntaxRejected("new Candidate(...items)", "constructor/spawn argument spreads");
        checkSyntaxRejected("spawn Candidate(...items)", "constructor/spawn argument spreads");
        assertTypeDenied("""
                pub fnc main(): void {
                    let candidate = new Candidate(x: 1);
                }
                """, "named value arguments require checked");
    }

    @Test
    void rejectsForgedAstNodesThatBypassSourceParserShapeChecks() {
        Ast.NameExpr fn = new Ast.NameExpr("invoke");
        Ast.LiteralExpr one = new Ast.LiteralExpr(1L);
        assertThrows(NullPointerException.class, () -> new Ast.CallExpr(null, List.of()));
        assertThrows(NullPointerException.class, () ->
                new Ast.CallExpr(null, List.of(Ast.TypeRef.simple("T")), List.of()));
        Ast.NamedArgumentExpr a = new Ast.NamedArgumentExpr("a", one);
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CallExpr(fn, List.of(a, a)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CallExpr(fn, List.of(a, one)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CallExpr(fn, List.of(
                        new Ast.SpreadExpr(new Ast.NameExpr("items")), a)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.NewExpr(Ast.TypeRef.simple("Box"), List.of(
                        new Ast.SpreadExpr(new Ast.NameExpr("items")))));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.NewExpr(Ast.TypeRef.simple("Box"), List.of(a, a)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.NamedArgumentExpr("a",
                        new Ast.SpreadExpr(new Ast.NameExpr("items"))));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CompoundAssignExpr(one, "+", one));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.TypeSpecializationExpr(
                        new Ast.CallExpr(fn, List.of()), List.of(Ast.TypeRef.simple("Motion"))));

        Ast.CallExpr valid = assertDoesNotThrow(() -> new Ast.CallExpr(
                fn, List.of(one, new Ast.NamedArgumentExpr("b", one))));
        assertEquals(2, valid.arguments().size());
    }

    @Test
    void retainsIndexedCompoundWriteAsSingleOpaqueLvalue() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    values[index()] += delta;
                }
                """);
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.CompoundAssignExpr compound = assertInstanceOf(Ast.CompoundAssignExpr.class,
                ((Ast.ExprStmt) fn.body().getFirst()).expression());
        Ast.IndexExpr target = assertInstanceOf(Ast.IndexExpr.class, compound.target());
        assertEquals(new Ast.NameExpr("values"), target.receiver());
        Ast.CallExpr index = assertInstanceOf(Ast.CallExpr.class, target.index());
        assertEquals(new Ast.NameExpr("index"), index.callee());
        assertEquals(new Ast.NameExpr("delta"), compound.value());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
        assertThrows(RuntimeException.class, () -> OwnershipChecker.check(program));
    }

    @Test
    void namedConstructorMustFailBeforeUnknownClassOrFieldInference() {
        // Regression for the existing early type and ownership admission.
        // Neither an unknown type nor a known constructor may bypass the
        // checked named-argument lowering gate.
        assertTypeDenied("""
                pub fnc main(): void {
                    let candidate = new MissingClass(value: 1);
                }
                """, "named value arguments require checked");
        assertTypeDenied("""
                define class KnownClass as
                    pub let int value = 0;
                end
                pub fnc main(): void {
                    let candidate = new KnownClass(value: 1);
                }
                """, "named value arguments require checked");
    }

    @Test
    void rejectsUnsupportedGuestLoweringBeforeNativeOrInterpreterCanRun() {
        assertTypeDenied("""
                pub fnc target(int x): int { return x; }
                pub fnc main(): int { return target(x: 3); }
                """, "named value arguments require checked");
        assertTypeDenied("""
                pub fnc main(): void { Region<Motion>.new(); return; }
                """, "generic type-member syntax requires checked");
        assertTypeDenied("""
                pub fnc main(): int { let x = 1; x += 2; return x; }
                """, "compound assignment requires single-evaluation");
    }

    @Test
    void allTrustedCompilerAdmissionEntrypointsRejectStagedStructuredCalls() {
        String unsupported = """
                pub fnc id(int x): int { return x; }
                pub fnc main(): int { return id(x: 41); }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(Parser.parse(unsupported)));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(unsupported));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileForBuild(unsupported, BuildOptions.executable()));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.validateForIsolate(unsupported, IsolatePolicy.developer()));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(COMPUTE));
    }

    private static void checkSyntaxRejected(String call, String reason) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("pub fnc main(): void { " + call + "; }"));
        assertTrue(error.getMessage().contains(reason), error::getMessage);
    }

    private static void assertTypeDenied(String source, String reason) {
        Ast.Program program = Parser.parse(source);
        IllegalArgumentException typeError = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(typeError.getMessage().contains(reason), typeError::getMessage);
        RuntimeException ownerError = assertThrows(RuntimeException.class,
                () -> OwnershipChecker.check(program));
        assertTrue(ownerError.getMessage().contains(reason), ownerError::getMessage);
    }
}
