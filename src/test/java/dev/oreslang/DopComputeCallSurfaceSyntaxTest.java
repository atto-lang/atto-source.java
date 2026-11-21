package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.ReturnCleanupChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-only milestone for the complete proposed Scheduler/ActorGroup/Region
 * compute surface. Semantic/runtime support remains intentionally fail-closed.
 */
final class DopComputeCallSurfaceSyntaxTest {
    private static final String SAMPLE = """
            pub fnc main(): void {
                let dt = 0.5;

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
                    fnc (chunk) {
                        chunk.x += chunk.vx * dt;
                        chunk.y += chunk.vy * dt;
                    }
                );
                return;
            }
            """;

    @Test
    void completeProposedSurfaceParsesIntoExplicitStagedNodes() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse(SAMPLE));
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());

        Ast.BindingStmt scheduler =
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(1));
        Ast.StagedCallExpr schedulerCall =
                assertInstanceOf(Ast.StagedCallExpr.class, scheduler.initializer());
        assertEquals(5, schedulerCall.arguments().size());

        Ast.BindingStmt group =
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(2));
        assertInstanceOf(Ast.StagedCallExpr.class, group.initializer());

        Ast.BindingStmt motion =
                assertInstanceOf(Ast.BindingStmt.class, main.body().get(3));
        Ast.StagedCallExpr regionFactory =
                assertInstanceOf(Ast.StagedCallExpr.class, motion.initializer());
        Ast.MemberExpr factoryMember =
                assertInstanceOf(Ast.MemberExpr.class, regionFactory.callee());
        assertEquals("new", factoryMember.member());
        Ast.TypeExpr regionType =
                assertInstanceOf(Ast.TypeExpr.class, factoryMember.receiver());
        assertEquals("Region", regionType.type().name());
        assertEquals(1, regionType.type().arguments().size());
        assertEquals("Motion", regionType.type().arguments().getFirst().name());

        Ast.ExprStmt computeStmt =
                assertInstanceOf(Ast.ExprStmt.class, main.body().get(4));
        Ast.AwaitExpr awaited =
                assertInstanceOf(Ast.AwaitExpr.class, computeStmt.expression());
        Ast.StagedCallExpr compute =
                assertInstanceOf(Ast.StagedCallExpr.class, awaited.expression());
        Ast.CallArgument callback = compute.arguments().getLast();
        assertTrue(callback.trailingClosure());
        Ast.LambdaExpr lambda = assertInstanceOf(Ast.LambdaExpr.class, callback.value());
        assertEquals(2, lambda.blockBody().size());

        Ast.ExprStmt firstUpdate =
                assertInstanceOf(Ast.ExprStmt.class, lambda.blockBody().getFirst());
        Ast.CompoundAssignExpr xUpdate =
                assertInstanceOf(Ast.CompoundAssignExpr.class, firstUpdate.expression());
        assertEquals("+", xUpdate.operator());
        assertInstanceOf(Ast.MemberExpr.class, xUpdate.target());

        Ast.ExprStmt secondUpdate =
                assertInstanceOf(Ast.ExprStmt.class, lambda.blockBody().getLast());
        Ast.CompoundAssignExpr yUpdate =
                assertInstanceOf(Ast.CompoundAssignExpr.class, secondUpdate.expression());
        assertEquals("+", yUpdate.operator());
    }

    @Test
    void semanticAdmissionStillFailsClosed() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(SAMPLE)));
        assertTrue(failure.getMessage().contains("named/trailing-closure")
                        || failure.getMessage().contains("type-qualified")
                        || failure.getMessage().contains("compound-place"),
                failure::toString);
    }

    @Test
    void cleanupAnalysisRejectsGenericTypeQualifierEvenWithoutNamedArguments() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    Region<Motion>.new();
                    return;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(program));
        assertTrue(failure.getMessage().contains("type-qualified"), failure::toString);
    }

    @Test
    void cleanupAnalysisRejectsCompoundPlaceBeforeSkippingItsEffects() {
        Ast.Program program = Parser.parse("""
                pub fnc main(): void {
                    let x = 1;
                    x += side_effect();
                    return;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ReturnCleanupChecker.check(program));
        assertTrue(failure.getMessage().contains("compound-place"), failure::toString);
    }

    @Test
    void capabilityCheckerRejectsStagedTypeAndCompoundNodesDirectly() {
        Ast.Program typeProgram = Parser.parse("""
                pub fnc main(): void {
                    Region<Motion>.new();
                    return;
                }
                """);
        SecurityException typeFailure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(typeProgram, IsolatePolicy.developer()));
        assertTrue(typeFailure.getMessage().contains("type-qualified"), typeFailure::toString);

        Ast.Program compoundProgram = Parser.parse("""
                pub fnc main(): void {
                    let x = 1;
                    x += side_effect();
                    return;
                }
                """);
        SecurityException compoundFailure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(compoundProgram, IsolatePolicy.developer()));
        assertTrue(compoundFailure.getMessage().contains("compound-place"),
                compoundFailure::toString);
    }

    @Test
    void allCompoundOperatorsUseAPlacePreservingAst() {
        String source = """
                pub fnc main(): void {
                    let x = 10;
                    x += 1;
                    x -= 2;
                    x *= 3;
                    x /= 4;
                    x %= 5;
                    return;
                }
                """;
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parse(source).modules().getFirst().declarations().getFirst());
        String[] ops = {"+", "-", "*", "/", "%"};
        for (int i = 0; i < ops.length; i++) {
            Ast.ExprStmt stmt =
                    assertInstanceOf(Ast.ExprStmt.class, main.body().get(i + 1));
            Ast.CompoundAssignExpr update =
                    assertInstanceOf(Ast.CompoundAssignExpr.class, stmt.expression());
            assertEquals(ops[i], update.operator());
            assertInstanceOf(Ast.NameExpr.class, update.target());
        }
    }

    @Test
    void compoundAssignmentRejectsNonPlaceTargetsAtParseTime() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub fnc main(): void {
                            (1 + 2) += 3;
                            return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("target"), failure::toString);
    }

    @Test
    void ordinaryComparisonsDoNotBecomeGenericTypeQualifiers() {
        Ast.Program program = assertDoesNotThrow(() -> Parser.parse("""
                pub fnc main(): bool {
                    let a = 1;
                    let b = 2;
                    let c = 3;
                    return a < b && b > c;
                }
                """));
        Ast.FunctionDecl main = assertInstanceOf(Ast.FunctionDecl.class,
                program.modules().getFirst().declarations().getFirst());
        Ast.ReturnStmt result =
                assertInstanceOf(Ast.ReturnStmt.class, main.body().getLast());
        assertFalse(result.value() instanceof Ast.TypeExpr);
    }
}
