package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DurableActivityWorkflowDeclarationTest {
    private static Ast.Decl first(Ast.Program program) {
        return program.modules().stream().flatMap(module -> module.declarations().stream()).findFirst().orElseThrow();
    }

    @Test void parsesClassShapedActivityWithItsOwnAstIdentity() {
        Ast.Program program = Parser.parse("""
                define activity EmailDelivery as
                    send(int customer_id) -> int { return customer_id; }
                end
                """);
        Ast.ActivityDecl activity = assertInstanceOf(Ast.ActivityDecl.class, first(program));
        assertEquals("EmailDelivery", activity.name());
        assertEquals(1, activity.body().methods().size());
        assertEquals("send", activity.body().methods().getFirst().name());
        assertTrue(activity.annotations().isEmpty());
    }

    @Test void parsesClassShapedWorkflowWithItsOwnAstIdentity() {
        Ast.Program program = Parser.parse("""
                define workflow OrderFulfillment as
                    run(int order_id) -> int { return order_id; }
                end
                """);
        Ast.WorkflowDecl workflow = assertInstanceOf(Ast.WorkflowDecl.class, first(program));
        assertEquals("OrderFulfillment", workflow.name());
        assertEquals("run", workflow.body().methods().getFirst().name());
        assertTrue(workflow.annotations().isEmpty());
    }

    @Test void declarationsAreSupportedInsideModules() {
        Ast.Program program = Parser.parse("""
                define module Commerce as
                    define activity PaymentProcessing as
                        charge(int cents) -> int { return cents; }
                    end
                    define workflow Checkout as
                        run(int order_id) -> int { return order_id; }
                    end
                end
                """);
        assertInstanceOf(Ast.ActivityDecl.class, program.modules().getFirst().declarations().get(0));
        assertInstanceOf(Ast.WorkflowDecl.class, program.modules().getFirst().declarations().get(1));
    }

    @Test void activityAndWorkflowMembersUseTheOrdinaryCheckedMemberTypeRules() {
        Ast.Program program = Parser.parse("""
                define activity PaymentProvider as
                    charge(int cents) -> int { return cents; }
                    refund(int cents) -> bool { return cents > 0; }
                end
                define workflow Checkout as
                    run(int order_id) -> int { return order_id; }
                end
                """);
        Ast.Program checked = assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
        assertInstanceOf(Ast.ActivityDecl.class, checked.modules().getFirst().declarations().get(0));
        assertInstanceOf(Ast.WorkflowDecl.class, checked.modules().getFirst().declarations().get(1));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "define activity Broken as charge(int cents) -> int { return \"wrong\"; } end")));
    }

    @Test void ordinaryClassDeclarationRemainsDistinct() {
        Ast.Program program = Parser.parse("define class Email as send(int id) -> int { return id; } end");
        assertInstanceOf(Ast.ClassDecl.class, first(program));
    }
    @Test void declarationAnnotationsArePreservedInsteadOfDiscarded() {
        Ast.Program program = Parser.parse("""
                @DurablePolicy
                define workflow Checkout as
                    run(int id) -> int { return id; }
                end
                """);
        Ast.WorkflowDecl workflow = assertInstanceOf(Ast.WorkflowDecl.class, first(program));
        assertEquals(1, workflow.annotations().size());
        assertEquals("DurablePolicy", workflow.annotations().getFirst().name());
    }

    @Test void unsupportedActorAndCallableModifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define shared activity Email as send() -> void { return; } end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define suspends workflow Checkout as run() -> void { return; } end"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define workflow fnc Checkout() -> void { return; }"));
    }
    @Test void directCompilerPassesRejectDurableDeclarationsUntilTheirContractsExist() {
        String source = "define workflow Checkout as run(int id) -> int { return id; } end";
        Ast.Program program = Parser.parse(source);
        Ast.Program expanded = assertDoesNotThrow(() -> AnnotationExpander.expand(program));
        assertInstanceOf(Ast.WorkflowDecl.class, expanded.modules().getFirst().declarations().getFirst());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TreeShaker.shake(program, BuildOptions.library(java.util.Map.of()))).getMessage()
                .contains("WORKFLOW_TREE_SHAKING_UNSUPPORTED"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas())).getMessage()
                .contains("WORKFLOW_CAPABILITY_CHECK_UNSUPPORTED"));
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(java.util.Map.of("workflow.atto", source)));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source)).getMessage()
                .contains("WORKFLOW_EXECUTION_UNSUPPORTED"));

        Ast.Program duplicateType = Parser.parse("""
                define class Checkout as end
                define workflow Checkout as run(int id) -> int { return id; } end
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(duplicateType)).getMessage()
                .contains("DURABLE_TYPE_NAMESPACE_DUPLICATE"));
    }

    @Test void declarationAnnotationListsAreImmutable() {
        Ast.Program program = Parser.parse("@DurablePolicy define activity Email as send() -> void { return; } end");
        Ast.ActivityDecl activity = assertInstanceOf(Ast.ActivityDecl.class, first(program));
        assertThrows(UnsupportedOperationException.class, () -> activity.annotations().clear());
    }
}
