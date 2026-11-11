package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static-only v1 parser/binder admission. These tests do not prove guest
 * execution, actor bootstrap, a mailbox permit, or cross-backend parity.
 */
final class ActorExternalEntrypointModifierTest {
    private static final String ACTOR = "namespace test_entry; declare actor Worker;\n";

    private static Ast.Program parse(String method) {
        return Parser.parse(ACTOR + method);
    }

    private static Ast.ExternalActorMethodDecl method(String source) {
        return (Ast.ExternalActorMethodDecl)
                parse(source).modules().getFirst().declarations().get(1);
    }

    private static void bound(String methodSource) {
        ActorReceiverBinder.bind(parse(methodSource));
    }

    private static String failBinding(String methodSource) {
        return assertThrows(IllegalArgumentException.class,
                () -> bound(methodSource)).getMessage();
    }

    @Test
    void explicitExternalMarkerSurvivesTypedAst() {
        var declaration = method(
                "define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }");
        assertTrue(declaration.runtimeEntrypoint());
        assertEquals("Worker", declaration.actorName());
        assertEquals("receive", declaration.name());
        assertEquals(Ast.Visibility.PRIVATE, declaration.visibility());
    }

    @Test
    void legacyExternalMtdRemainsStaticOnlyWithoutEntrypointMark() {
        var declaration = method(
                "define mtd (Worker w) update(): void { return; }");
        assertFalse(declaration.runtimeEntrypoint());
        assertEquals("update", declaration.name());
    }

    @Test
    void privateReceiveBindsAsRuntimeEntrypointMetadataOnly() {
        bound("define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }");
    }

    @Test
    void privateInitBindsAsRuntimeEntrypointMetadataOnly() {
        bound("define external mtd (Worker w) init(): void { return; }");
    }

    @Test
    void staticallyTypedInitParamsCanBeRetainedWithoutDispatch() {
        var declaration = method(
                "define external mtd (Worker w) init(int seed): void { return; }");
        assertTrue(declaration.runtimeEntrypoint());
        assertEquals(1, declaration.parameters().size());
        bound("define external mtd (Worker w) init(int seed): void { return; }");
    }

    @Test
    void publicReceiveCannotGainMailboxAuthority() {
        assertTrue(failBinding(
                "define pub external mtd (Worker w) receive(ActorMail<int> mail): void { return; }")
                .contains("ACTOR_EXTERNAL_LIFECYCLE_PRIVATE"));
    }

    @Test
    void publicInitCannotGainCreationAuthority() {
        assertTrue(failBinding(
                "define pub external mtd (Worker w) init(): void { return; }")
                .contains("ACTOR_EXTERNAL_LIFECYCLE_PRIVATE"));
    }

    @Test
    void explicitExternalCannotExportAnArbitraryActorMethod() {
        assertTrue(failBinding(
                "define external mtd (Worker w) deposit(int value): void { return; }")
                .contains("ACTOR_EXTERNAL_ROLE_UNSUPPORTED"));
    }

    @Test
    void initCannotReturnRawActorOwner() {
        assertTrue(failBinding(
                "define external mtd (Worker w) init(): Worker { return w; }")
                .contains("ACTOR_INIT_OWNER_ESCAPE"));
    }

    @Test
    void annotatedLifecycleRolesCannotOverloadByArity() {
        assertTrue(failBinding(
                "define external mtd (Worker w) init(): void { return; }\n"
                        + "define external mtd (Worker w) init(int value): void { return; }")
                .contains("ACTOR_DUPLICATE_INIT"));
    }

    @Test
    void formerLifecycleNameIsNotAnAlternativeExternalEntrypoint() {
        assertTrue(failBinding(
                "define external mtd (Worker w) on_start(): void { return; }")
                .contains("ACTOR_EXTERNAL_ROLE_UNSUPPORTED"));
    }

    @Test
    void externalLoweringDiagnosticWinsRegardlessOfActorDeclarationOrder() {
        var forward = Parser.parse("namespace test_entry;"
                + "define external mtd (Worker w) init(): void { return; }"
                + "declare actor Worker;");
        var reversed = Parser.parse("namespace test_entry;"
                + "declare actor Worker;"
                + "define external mtd (Worker w) init(): void { return; }");
        for (Ast.Program program : java.util.List.of(forward, reversed)) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(program));
            assertTrue(error.getMessage().contains(
                    "EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED"), error.getMessage());
        }
    }

    @Test
    void canonicalDeclaredActorWithoutExternalMethodRetainsStandaloneBlocker() {
        var program = Parser.parse("declare actor Worker;");
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains(
                "STANDALONE_ACTOR_LOWERING_UNSUPPORTED"), error.getMessage());
    }

    @Test
    void parsedExternalReceiverStillFailsClosedBeforeGuestExecution() {
        var p = parse(
                "define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }");
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(p));
        assertTrue(error.getMessage().contains("EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED"),
                error.getMessage());
    }
}
