package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.DurableDeclarationAnalyzer;
import dev.oreslang.compiler.DurableExecutionManifest;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class DurableWorkflowSemanticAnalysisTest {
    private static Ast.Program parseValidPair() {
        return Parser.parse("""
                define activity PaymentProvider as
                    charge(int cents) -> int { return cents; }
                    refund(int cents) -> bool { return cents > 0; }
                end
                define workflow Checkout as
                    normalize(int order_id) -> int { return order_id; }
                    status(int order_id) -> int { return order_id; }
                    run(int order_id) -> int { return normalize(order_id); }
                end
                """);
    }

    private static Ast.WorkflowDecl workflow(Ast.Program program) {
        return (Ast.WorkflowDecl) program.modules().getFirst().declarations().stream()
                .filter(Ast.WorkflowDecl.class::isInstance).findFirst().orElseThrow();
    }

    private static Ast.ActivityDecl activity(Ast.Program program) {
        return (Ast.ActivityDecl) program.modules().getFirst().declarations().stream()
                .filter(Ast.ActivityDecl.class::isInstance).findFirst().orElseThrow();
    }

    @Test
    void activityWithMultipleOperationsAndWorkflowRunAreSemanticallyAnalyzed() {
        Ast.Program program = parseValidPair();
        Ast.Program checked = assertDoesNotThrow(() -> TypeChecker.checkTypes(program));
        assertEquals(2, activity(checked).body().methods().size());
        assertTrue(workflow(checked).body().methods().stream().anyMatch(m -> m.name().equals("run")));
    }

    @Test
    void activityMemberSignatureAndBodyErrorsAreNotHiddenByDurableWrapper() {
        Ast.Program badReturn = Parser.parse(
                "define activity Broken as charge(int cents) -> int { return \"wrong\"; } end");
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(badReturn));

        Ast.Program badArgument = Parser.parse("""
                define activity Broken as charge(int cents) -> int { return cents; } end
                define workflow Checkout as run() -> int { return charge("wrong"); } end
                """);
        assertThrows(IllegalArgumentException.class, () -> DurableDeclarationAnalyzer.analyze(badArgument));
    }

    @Test
    void workflowRequiresExactlyOneRunEntryPoint() {
        Ast.Program noRun = Parser.parse(
                "define workflow Checkout as normalize(int id) -> int { return id; } end");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(noRun)).getMessage()
                .contains("WORKFLOW_ENTRYPOINT_REQUIRED"));

        Ast.Program duplicateRun = Parser.parse(
                "define workflow Checkout as run(int id) -> int { return id; } run(int id, int retry) -> int { return id; } end");
        assertThrows(IllegalArgumentException.class, () -> DurableDeclarationAnalyzer.analyze(duplicateRun));
    }

    @Test
    void workflowRejectsSuspensionAndRawExternalEffects() {
        Ast.Program suspends = Parser.parse(
                "define workflow Checkout as suspends run(int id) -> int { return id; } end");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(suspends)).getMessage()
                .contains("WORKFLOW_MEMBER_EFFECT_UNSUPPORTED"));

        Ast.Program rawIo = Parser.parse(
                "define workflow Checkout as run(int id) -> int { return print(id); } end");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(rawIo)).getMessage()
                .contains("WORKFLOW_UNRECORDED_CALL_FORBIDDEN"));
    }

    @Test
    void commandIdsAreUniqueAndActivityTargetsAreBound() {
        Ast.Program original = parseValidPair();
        Ast.WorkflowDecl old = workflow(original);
        Ast.WorkflowDecl duplicate = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.TimerCommand("step-1", new Ast.LiteralExpr(5L)),
                new Ast.WaitForEventCommand("step-1", "approved")));
        Ast.Program duplicateProgram = replaceWorkflow(original, duplicate);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(duplicateProgram)).getMessage()
                .contains("WORKFLOW_DUPLICATE_COMMAND_ID"));

        Ast.WorkflowDecl unknownTarget = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-1", "MissingProvider", "charge",
                        List.of(new Ast.LiteralExpr(100L)))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(original, unknownTarget)))
                .getMessage().contains("WORKFLOW_ACTIVITY_TARGET_UNKNOWN"));
    }

    @Test
    void activityStepRequiresExactOperationArityAndChildWorkflowMustResolve() {
        Ast.Program original = parseValidPair();
        Ast.WorkflowDecl old = workflow(original);
        Ast.WorkflowDecl badArity = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-1", "PaymentProvider", "charge", List.of())));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(original, badArity)))
                .getMessage().contains("WORKFLOW_ACTIVITY_OPERATION_UNKNOWN"));

        Ast.WorkflowDecl badChild = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ChildWorkflowCommand("child-1", "MissingWorkflow",
                        new Ast.LiteralExpr(1L))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(original, badChild)))
                .getMessage().contains("WORKFLOW_CHILD_TARGET_UNKNOWN"));
    }

    @Test
    void manifestBytesAreCanonicalAndCommandOrderIsPreserved() {
        Ast.Program original = parseValidPair();
        Ast.WorkflowDecl old = workflow(original);
        Ast.WorkflowDecl withCommands = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-1", "PaymentProvider", "charge",
                        List.of(new Ast.NameExpr("order_id"))),
                new Ast.TimerCommand("timer-1", new Ast.LiteralExpr(250L)),
                new Ast.WaitForEventCommand("approval-1", "approved")));
        Ast.Program program = replaceWorkflow(original, withCommands);

        DurableExecutionManifest first = DurableExecutionManifest.analyze(program);
        DurableExecutionManifest second = DurableExecutionManifest.analyze(program);

        assertArrayEquals(first.canonicalBytes(), second.canonicalBytes());
        assertEquals(first.sha256(), second.sha256());
        // Emit source-backed canonical metadata as a receipt in Surefire XML.
        // This is not a guest history or runtime replay receipt.
        System.out.println("DURABLE_M0_MANIFEST_SHA256=" + first.sha256());
        System.out.println("DURABLE_M0_MANIFEST_HEX="
                + java.util.HexFormat.of().formatHex(first.canonicalBytes()));
        String json = new String(first.canonicalBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"format\":\"atto.durable.manifest.v1\""));
        assertTrue(json.indexOf("\"id\":\"charge-1\"")
                < json.indexOf("\"id\":\"timer-1\""));
        assertTrue(json.indexOf("\"id\":\"timer-1\"")
                < json.indexOf("\"id\":\"approval-1\""));
    }

    @Test
    void queriesAreReadOnlyEndpointsAndAreNotAssignedHistoryCommandIds() {
        Ast.Program original = parseValidPair();
        Ast.WorkflowDecl old = workflow(original);
        Ast.WorkflowDecl query = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.QueryCommand("status",
                        List.of(new Ast.Param(Ast.TypeRef.simple("int"), "order_id")),
                        Ast.TypeRef.simple("int"))));
        DurableExecutionManifest manifest = DurableExecutionManifest.analyze(
                replaceWorkflow(original, query));
        String json = new String(manifest.canonicalBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"queries\":[{\"endpoint\":\"status\""));
        assertTrue(json.contains("\"commands\":[]"));
        assertFalse(json.contains("\"id\":\"query\""),
                "read-only queries do not occupy durable history or receive command IDs");
    }

    private static Ast.Program replaceWorkflow(
            Ast.Program original, Ast.WorkflowDecl replacement) {
        Ast.ModuleDecl module = original.modules().getFirst();
        List<Ast.Decl> declarations = module.declarations().stream()
                .map(declaration -> declaration instanceof Ast.WorkflowDecl ? replacement : declaration)
                .toList();
        Ast.ModuleDecl rewritten = new Ast.ModuleDecl(
                module.name(), module.annotations(), module.contracts(), declarations);
        return new Ast.Program(original.namespace(), original.imports(), List.of(rewritten));
    }

    @Test
    void recordedActivityPayloadIsTypeCheckedAgainstExactOperationParameters() {
        Ast.Program valid = parseValidPair();
        Ast.WorkflowDecl old = workflow(valid);
        Ast.WorkflowDecl command = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-order", "PaymentProvider", "charge",
                        List.of(new Ast.NameExpr("order_id")))));
        assertDoesNotThrow(() -> DurableDeclarationAnalyzer.analyze(
                replaceWorkflow(valid, command)));

        Ast.WorkflowDecl invalid = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-order", "PaymentProvider", "charge",
                        List.of(new Ast.LiteralExpr("not-an-int")))));
        assertThrows(IllegalArgumentException.class, () -> DurableDeclarationAnalyzer.analyze(
                replaceWorkflow(valid, invalid)));
    }

    @Test
    void signalAndUpdatePayloadsUseEndpointParameterTypes() {
        Ast.Program source = Parser.parse("""
                define workflow Checkout as
                    approve(int code) -> bool { return code > 0; }
                    run(int order_id) -> int { return order_id; }
                end
                """);
        Ast.WorkflowDecl old = workflow(source);
        Ast.WorkflowDecl valid = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.SignalCommand("signal-approve", "approve", List.of(new Ast.NameExpr("order_id"))),
                new Ast.UpdateCommand("update-approve", "approve", List.of(new Ast.LiteralExpr(7L)))));
        assertDoesNotThrow(() -> DurableDeclarationAnalyzer.analyze(
                replaceWorkflow(source, valid)));

        Ast.WorkflowDecl invalid = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.SignalCommand("signal-approve", "approve", List.of(new Ast.LiteralExpr("wrong")))));
        assertThrows(IllegalArgumentException.class, () -> DurableDeclarationAnalyzer.analyze(
                replaceWorkflow(source, invalid)));
    }

    @Test
    void timerDelayIsCheckedAsIntegerMilliseconds() {
        Ast.Program source = parseValidPair();
        Ast.WorkflowDecl old = workflow(source);
        Ast.WorkflowDecl valid = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.TimerCommand("timer-1", new Ast.LiteralExpr(250L))));
        assertDoesNotThrow(() -> DurableDeclarationAnalyzer.analyze(
                replaceWorkflow(source, valid)));

        Ast.WorkflowDecl invalid = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.TimerCommand("timer-1", new Ast.LiteralExpr("250ms"))));
        assertThrows(IllegalArgumentException.class, () -> DurableDeclarationAnalyzer.analyze(
                replaceWorkflow(source, invalid)));
    }

    @Test
    void ambiguousUnqualifiedActivityTargetsFailClosed() {
        Ast.Program source = Parser.parse("""
                define module Billing as
                    define activity PaymentProvider as
                        charge(int cents) -> int { return cents; }
                    end
                end
                define module Commerce as
                    define activity PaymentProvider as
                        charge(int cents) -> int { return cents; }
                    end
                end
                define module CheckoutModule as
                    define workflow Checkout as
                        run(int order_id) -> int { return order_id; }
                    end
                end
                """);
        Ast.ModuleDecl checkoutModule = source.modules().stream()
                .filter(m -> m.name().equals("CheckoutModule")).findFirst().orElseThrow();
        Ast.WorkflowDecl old = (Ast.WorkflowDecl) checkoutModule.declarations().getFirst();
        Ast.WorkflowDecl command = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-1", "PaymentProvider", "charge",
                        List.of(new Ast.LiteralExpr(1L)))));
        List<Ast.ModuleDecl> modules = source.modules().stream().map(module ->
                module == checkoutModule
                        ? new Ast.ModuleDecl(module.name(), module.annotations(), module.contracts(), List.of(command))
                        : module).toList();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(
                        new Ast.Program(source.namespace(), source.imports(), modules)))
                .getMessage().contains("WORKFLOW_ACTIVITY_TARGET_AMBIGUOUS"));
    }


    @Test
    void activityOperationAnnotationsAndPrivateOperationsFailClosed() {
        Ast.Program source = parseValidPair();
        Ast.ModuleDecl module = source.modules().getFirst();
        Ast.ActivityDecl old = activity(source);
        Ast.MethodDecl charge = old.body().methods().stream()
                .filter(method -> method.name().equals("charge")).findFirst().orElseThrow();

        Ast.MethodDecl annotatedCharge = copyMethod(
                charge, charge.visibility(), List.of(new Ast.Annotation("Retry", List.of())));
        Ast.ActivityDecl annotatedActivity = replaceActivityMethod(old, annotatedCharge);
        Ast.Program annotatedProgram = replaceActivity(source, annotatedActivity);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(annotatedProgram)).getMessage()
                .contains("ACTIVITY_OPERATION_ANNOTATIONS_UNSUPPORTED"));

        Ast.MethodDecl privateCharge = copyMethod(
                charge, Ast.Visibility.PRIVATE, charge.annotations());
        Ast.ActivityDecl privateActivity = replaceActivityMethod(old, privateCharge);
        Ast.Program privateProgram = replaceActivity(source, privateActivity);
        Ast.WorkflowDecl workflow = workflow(privateProgram);
        Ast.WorkflowDecl step = new Ast.WorkflowDecl(workflow.body(), workflow.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-1", "PaymentProvider", "charge",
                        List.of(new Ast.LiteralExpr(10L)))));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(privateProgram, step)))
                .getMessage().contains("WORKFLOW_ACTIVITY_OPERATION_VISIBILITY"));
    }

    @Test
    void readOnlyWorkflowEndpointsMustBePublicInstanceMethods() {
        Ast.Program source = parseValidPair();
        Ast.WorkflowDecl old = workflow(source);
        Ast.MethodDecl status = old.body().methods().stream()
                .filter(method -> method.name().equals("status")).findFirst().orElseThrow();
        Ast.MethodDecl privateStatus = copyMethod(
                status, Ast.Visibility.PRIVATE, status.annotations());
        List<Ast.MethodDecl> methods = old.body().methods().stream()
                .map(method -> method.name().equals("status") ? privateStatus : method)
                .toList();
        Ast.ClassDecl body = copyClassWithMethods(old.body(), methods);
        Ast.WorkflowDecl query = new Ast.WorkflowDecl(body, old.annotations(), List.of(
                new Ast.QueryCommand("status",
                        List.of(new Ast.Param(Ast.TypeRef.simple("int"), "order_id")),
                        Ast.TypeRef.simple("int"))));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(source, query)))
                .getMessage().contains("WORKFLOW_QUERY_ENDPOINT_VISIBILITY"));
    }

    @Test
    void manifestNormalizesLocalAndQualifiedActivityTargets() {
        Ast.Program source = parseValidPair();
        Ast.WorkflowDecl old = workflow(source);
        String qualified = source.modules().getFirst().name() + ".PaymentProvider";

        Ast.WorkflowDecl local = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-1", "PaymentProvider", "charge",
                        List.of(new Ast.NameExpr("order_id")))));
        Ast.WorkflowDecl explicit = new Ast.WorkflowDecl(old.body(), old.annotations(), List.of(
                new Ast.ActivityStepCommand("charge-1", qualified, "charge",
                        List.of(new Ast.NameExpr("order_id")))));

        DurableExecutionManifest localManifest =
                DurableExecutionManifest.analyze(replaceWorkflow(source, local));
        DurableExecutionManifest qualifiedManifest =
                DurableExecutionManifest.analyze(replaceWorkflow(source, explicit));
        assertArrayEquals(localManifest.canonicalBytes(), qualifiedManifest.canonicalBytes());
        assertEquals(localManifest.sha256(), qualifiedManifest.sha256());
        assertTrue(new String(localManifest.canonicalBytes(), StandardCharsets.UTF_8)
                .contains("\"target\":\"" + qualified + ".charge\""));
    }

    @Test
    void manifestIsIndependentOfDeclarationOrdering() {
        Ast.Program source = parseValidPair();
        Ast.ModuleDecl originalModule = source.modules().getFirst();
        List<Ast.Decl> reverse = new java.util.ArrayList<>(originalModule.declarations());
        java.util.Collections.reverse(reverse);
        Ast.Program reordered = new Ast.Program(source.namespace(), source.imports(), List.of(
                new Ast.ModuleDecl(originalModule.name(), originalModule.annotations(),
                        originalModule.contracts(), reverse)));
        DurableExecutionManifest first = DurableExecutionManifest.analyze(source);
        DurableExecutionManifest second = DurableExecutionManifest.analyze(reordered);
        assertArrayEquals(first.canonicalBytes(), second.canonicalBytes());
        assertEquals(first.sha256(), second.sha256());
    }

    @Test
    void duplicateReadOnlyQueryDescriptorsFailClosed() {
        Ast.Program source = parseValidPair();
        Ast.WorkflowDecl original = workflow(source);
        Ast.QueryCommand descriptor = new Ast.QueryCommand(
                "status", List.of(new Ast.Param(Ast.TypeRef.simple("int"), "order_id")),
                Ast.TypeRef.simple("int"));
        Ast.WorkflowDecl duplicate = new Ast.WorkflowDecl(
                original.body(), original.annotations(), List.of(descriptor, descriptor));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(source, duplicate)))
                .getMessage().contains("WORKFLOW_DUPLICATE_QUERY_ENDPOINT"));
    }

    @Test
    void workflowRawTimeEntropyInteropAndActivityCallsFailClosed() {
        Ast.Program source = parseValidPair();
        Ast.WorkflowDecl original = workflow(source);
        Ast.MethodDecl run = original.body().methods().stream()
                .filter(method -> method.name().equals("run")).findFirst().orElseThrow();
        List<Ast.Expr> forbidden = List.of(
                new Ast.CallExpr(new Ast.NameExpr("now"), List.of()),
                new Ast.CallExpr(new Ast.NameExpr("random"), List.of()),
                new Ast.CallExpr(new Ast.MemberExpr(
                        new Ast.NameExpr("PaymentProvider"), "charge"),
                        List.of(new Ast.LiteralExpr(5L))),
                new Ast.CallExpr(new Ast.MemberExpr(
                        new Ast.NameExpr("Java"), "currentTimeMillis"), List.of()),
                new Ast.RuntimeCallExpr("clock", List.of()),
                new Ast.AwaitExpr(new Ast.NameExpr("order_id")));
        for (Ast.Expr expression : forbidden) {
            Ast.MethodDecl altered = withBody(run, List.of(new Ast.ReturnStmt(expression)));
            Ast.ClassDecl changedBody = copyClassWithMethods(original.body(),
                    original.body().methods().stream()
                            .map(method -> method.name().equals("run") ? altered : method).toList());
            Ast.WorkflowDecl changed =
                    new Ast.WorkflowDecl(changedBody, original.annotations(), original.commands());
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(source, changed)))
                    .getMessage().contains("FORBIDDEN"), expression.toString());
        }
    }

    @Test
    void stateAndLiveBorrowAcrossDurableWaitRemainUnsupported() {
        Ast.Program source = parseValidPair();
        Ast.WorkflowDecl original = workflow(source);
        for (String typeName : List.of("int", "any")) {
            Ast.FieldDecl field = new Ast.FieldDecl("checkpoint", Ast.Visibility.PRIVATE,
                    Ast.BindingKind.CONST, Ast.TypeRef.simple(typeName),
                    new Ast.LiteralExpr(0L));
            Ast.ClassDecl changedBody = new Ast.ClassDecl(
                    original.body().name(), original.body().visibility(), original.body().isAbstract(),
                    original.body().actorKind(), original.body().genericParameters(),
                    original.body().parents(), original.body().interfaces(), List.of(field),
                    original.body().constructor(), original.body().methods(), original.body().actorConcurrency());
            Ast.WorkflowDecl changed =
                    new Ast.WorkflowDecl(changedBody, original.annotations(), original.commands());
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(source, changed)))
                    .getMessage().contains("WORKFLOW_STATE_SCHEMA_UNSUPPORTED"));
        }

        Ast.MethodDecl run = original.body().methods().stream()
                .filter(method -> method.name().equals("run")).findFirst().orElseThrow();
        Ast.MethodDecl borrowed = withBody(run, List.of(
                new Ast.UsingBorrowStmt(new Ast.NameExpr("order_id"), "held", List.of()),
                new Ast.ReturnStmt(new Ast.NameExpr("order_id"))));
        Ast.ClassDecl borrowedBody = copyClassWithMethods(original.body(),
                original.body().methods().stream()
                        .map(method -> method.name().equals("run") ? borrowed : method).toList());
        Ast.WorkflowDecl waiting = new Ast.WorkflowDecl(borrowedBody, original.annotations(),
                List.of(new Ast.WaitForEventCommand("wait-approval", "approved")));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DurableDeclarationAnalyzer.analyze(replaceWorkflow(source, waiting)))
                .getMessage().contains("WORKFLOW_CONCURRENCY_FORBIDDEN"));
    }

    private static Ast.MethodDecl withBody(Ast.MethodDecl original, List<Ast.Stmt> body) {
        return new Ast.MethodDecl(
                original.name(), original.visibility(), original.isStatic(), original.isAbstract(),
                original.async(), original.suspends(), original.structural(),
                original.explicitReceiverType(), original.genericParameters(),
                original.parameters(), original.returnType(), original.annotations(),
                body, original.effects());
    }

    private static Ast.MethodDecl copyMethod(
            Ast.MethodDecl original, Ast.Visibility visibility, List<Ast.Annotation> annotations) {
        return new Ast.MethodDecl(
                original.name(), visibility, original.isStatic(), original.isAbstract(),
                original.async(), original.suspends(), original.structural(),
                original.explicitReceiverType(), original.genericParameters(),
                original.parameters(), original.returnType(), annotations,
                original.body(), original.effects());
    }

    private static Ast.ClassDecl copyClassWithMethods(
            Ast.ClassDecl original, List<Ast.MethodDecl> methods) {
        return new Ast.ClassDecl(
                original.name(), original.visibility(), original.isAbstract(),
                original.actorKind(), original.genericParameters(), original.parents(),
                original.interfaces(), original.fields(), original.constructor(),
                methods, original.actorConcurrency());
    }

    private static Ast.ActivityDecl replaceActivityMethod(
            Ast.ActivityDecl original, Ast.MethodDecl replacement) {
        List<Ast.MethodDecl> methods = original.body().methods().stream()
                .map(method -> method.name().equals(replacement.name()) ? replacement : method)
                .toList();
        return new Ast.ActivityDecl(
                copyClassWithMethods(original.body(), methods), original.annotations());
    }

    private static Ast.Program replaceActivity(
            Ast.Program original, Ast.ActivityDecl replacement) {
        Ast.ModuleDecl module = original.modules().getFirst();
        List<Ast.Decl> declarations = module.declarations().stream()
                .map(declaration -> declaration instanceof Ast.ActivityDecl ? replacement : declaration)
                .toList();
        return new Ast.Program(original.namespace(), original.imports(), List.of(
                new Ast.ModuleDecl(module.name(), module.annotations(), module.contracts(), declarations)));
    }

}
