package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
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

    @Test void declarationsDoNotFallBackToOrdinaryClassTypeChecking() {
        Ast.Program activity = Parser.parse("define activity Email as send(int id) -> int { return id; } end");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(activity));
        assertTrue(failure.getMessage().contains("ACTIVITY_EXECUTION_UNSUPPORTED"));
        Ast.Program workflow = Parser.parse("define workflow Checkout as run(int id) -> int { return id; } end");
        IllegalArgumentException workflowFailure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(workflow));
        assertTrue(workflowFailure.getMessage().contains("WORKFLOW_EXECUTION_UNSUPPORTED"));
    }

    @Test void ordinaryClassDeclarationRemainsDistinct() {
        Ast.Program program = Parser.parse("define class Email as send(int id) -> int { return id; } end");
        assertInstanceOf(Ast.ClassDecl.class, first(program));
    }
}
