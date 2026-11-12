package dev.oreslang;

import dev.oreslang.compiler.ActorMethodIndex;
import dev.oreslang.compiler.ActorTypedMethodIR;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real compiler-owned AST -> typed PRE-HIR shape, deliberately non-executable.
 * These tests are AUTHORED, not passed, until JDK25 CI starts real steps.
 */
final class ActorTypedMethodIRTest {
    @TempDir Path root;

    private static final String NS = "namespace foo;\n";
    private static final String ACTOR =
            NS + "declare pub actor Foo implements ActorApi;\n";
    private static final String CONTRACT = NS
            + "define pub contract ActorApi for actor as mtd bar(int x): int; end\n";
    private static final String METHOD = NS
            + "pub mtd (Foo f) bar(int x): int { return x; }\n";
    private static final String STATE = NS
            + "define state on actor Foo as { let int count : 5; }\n";

    private void write(String path, String contents) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }

    private PackageNamespaceBuild packageBuild() throws IOException {
        write(".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "typed-demo"
                version = "0.1.0"
                root = "."
                [source]
                roots = ["src"]
                """);
        write("src/foo/actor.atto", ACTOR);
        write("src/foo/contract.atto", CONTRACT);
        write("src/foo/method.atto", METHOD);
        write("src/foo/state.atto", STATE);
        return new PackageNamespaceBuild(
                OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of()));
    }

    private static ActorMethodIndex.MethodKey slot() {
        return new ActorMethodIndex.MethodKey(
                new ActorMethodIndex.ActorKey("typed-demo@0.1.0", "foo", "Foo"),
                "bar", 1);
    }

    private static String method(String body) {
        return NS + "pub mtd (Foo f) bar(int x): int { " + body + " }\n";
    }

    private void expectRejectedBody(PackageNamespaceBuild build,
                                    String body, String expected) throws IOException {
        write("src/foo/method.atto", method(body));
        var error = assertThrows(IllegalArgumentException.class,
                build::typecheckDeclaredActorMethods);
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }

    @Test
    void typedActorPlanLinksDescriptorStateAndMethodWithoutCapabilities()
            throws IOException {
        var result = packageBuild().typecheckDeclaredActorMethods();
        var actorKey = new ActorMethodIndex.ActorKey("typed-demo@0.1.0", "foo", "Foo");
        var plan = result.actor(actorKey);
        assertNotNull(plan);
        assertFalse(plan.executable());
        assertEquals("SEALED_STATE_LOANS_UNLOWERED", plan.stateLoanStatus());
        assertEquals("MAILBOX_DISPATCH_UNLOWERED", plan.mailboxDispatchStatus());
        assertEquals("Foo", plan.descriptor().key().actorName());
        assertEquals("src/foo/state.atto", plan.descriptor().stateUnit());
        assertEquals("count", plan.descriptor().state().getFirst().name());
        assertEquals(1, plan.checkedMethods().size());
        assertEquals(slot(), plan.checkedMethods().getFirst().key());
        assertThrows(UnsupportedOperationException.class,
                () -> result.actors().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.checkedMethods().clear());
    }

    @Test
    void actorPlanCannotBeForgedThroughPublicConstructor() {
        assertEquals(0, ActorTypedMethodIR.ActorPlan.class.getConstructors().length);
        assertEquals(0, ActorTypedMethodIR.Result.class.getConstructors().length);
    }

    @Test
    void validParameterReturnIsTypedWithNominalActorIdentity() throws IOException {
        var result = packageBuild().typecheckDeclaredActorMethods();
        assertEquals(1, result.methods().size());
        var typed = result.method(slot());
        assertNotNull(typed);
        assertEquals("src/foo/method.atto", typed.methodSourceUnit());
        assertEquals("int", typed.terminator().returnType());
        assertInstanceOf(ActorTypedMethodIR.ParameterRead.class,
                typed.terminator().value());
        assertEquals("x", typed.parameters().getFirst().name());
        assertEquals(64, typed.sourceSha256().length());
    }

    @Test
    void voidMethodHasExplicitVoidTerminatorAndNoReceiverAccess() throws IOException {
        var build = packageBuild();
        write("src/foo/contract.atto", NS
                + "define pub contract ActorApi for actor as mtd ping(): void; end");
        write("src/foo/method.atto", NS
                + "pub mtd (Foo f) ping(): void { return; }");
        var key = new ActorMethodIndex.MethodKey(
                new ActorMethodIndex.ActorKey("typed-demo@0.1.0", "foo", "Foo"),
                "ping", 0);
        var typed = build.typecheckDeclaredActorMethods().method(key);
        assertNotNull(typed);
        assertEquals("void", typed.terminator().returnType());
        assertNull(typed.terminator().value());
    }

    @Test
    void booleanMethodReturnsOnlyBooleanParameter() throws IOException {
        var build = packageBuild();
        write("src/foo/contract.atto", NS
                + "define pub contract ActorApi for actor as mtd flag(bool value): bool; end");
        write("src/foo/method.atto", NS
                + "pub mtd (Foo f) flag(bool value): bool { return value; }");
        var key = new ActorMethodIndex.MethodKey(
                new ActorMethodIndex.ActorKey("typed-demo@0.1.0", "foo", "Foo"),
                "flag", 1);
        var typed = build.typecheckDeclaredActorMethods().method(key);
        assertNotNull(typed);
        assertEquals("bool", typed.terminator().value().typeName());
    }

    @Test
    void stringMethodCannotBeMisrepresentedAsActorReference() throws IOException {
        var build = packageBuild();
        write("src/foo/contract.atto", NS
                + "define pub contract ActorApi for actor as mtd echo(string value): string; end");
        write("src/foo/method.atto", NS
                + "pub mtd (Foo f) echo(string value): string { return value; }");
        var key = new ActorMethodIndex.MethodKey(
                new ActorMethodIndex.ActorKey("typed-demo@0.1.0", "foo", "Foo"),
                "echo", 1);
        var typed = build.typecheckDeclaredActorMethods().method(key);
        assertNotNull(typed);
        assertEquals("string", typed.terminator().value().typeName());
    }

    @Test
    void arithmeticIsMarkedCheckedNotExecutedOrConstantFolded() throws IOException {
        var build = packageBuild();
        write("src/foo/method.atto", method("return x + 1;"));
        var value = build.typecheckDeclaredActorMethods()
                .method(slot()).terminator().value();
        var checked = assertInstanceOf(
                ActorTypedMethodIR.CheckedIntBinary.class, value);
        assertEquals("+", checked.operator());
        assertInstanceOf(ActorTypedMethodIR.ParameterRead.class, checked.left());
        assertInstanceOf(ActorTypedMethodIR.ScalarLiteral.class, checked.right());
    }

    @Test
    void rawActorReceiverCanNeverBecomeOrdinaryReturnedValue() throws IOException {
        var build = packageBuild();
        expectRejectedBody(build, "return f;", "actor receiver is not an ordinary value");
    }

    @Test
    void freeGlobalNamesCannotBeSilentlyCaptured() throws IOException {
        var build = packageBuild();
        expectRejectedBody(build, "return secret;", "unbound name/capture");
    }

    @Test
    void methodCallsAreNotAdmittedWithoutEffectAndOwnershipLinkage() throws IOException {
        var build = packageBuild();
        expectRejectedBody(build, "return perform_io();", "CallExpr");
    }

    @Test
    void readingActorStateCannotEscapeAsUncheckedMemberAccess() throws IOException {
        var build = packageBuild();
        expectRejectedBody(build, "return f.count;", "MemberExpr");
    }

    @Test
    void incompatibleReturnTypeFailsBeforeRuntimeLowering() throws IOException {
        var build = packageBuild();
        expectRejectedBody(build, "return true;", "ACTOR_TYPED_IR_RETURN_MISMATCH");
    }

    @Test
    void arithmeticRejectsImplicitBooleanToIntegerConversion() throws IOException {
        var build = packageBuild();
        expectRejectedBody(build, "return x + true;", "integer arithmetic requires exactly int");
    }

    @Test
    void extraStatementsAreRejectedRatherThanSilentlyDiscarded() throws IOException {
        var build = packageBuild();
        expectRejectedBody(build, "foo(); return x;", "exactly one terminal return");
    }

    @Test
    void missingNonVoidReturnIsRejected() throws IOException {
        var build = packageBuild();
        expectRejectedBody(build, "", "exactly one terminal return");
    }

    @Test
    void changedBodyUpdatesTypedMethodSourceReceipt() throws IOException {
        var build = packageBuild();
        var first = build.typecheckDeclaredActorMethods().method(slot());
        write("src/foo/method.atto", method("return x + 1;"));
        var second = build.typecheckDeclaredActorMethods().method(slot());
        assertNotEquals(first.sourceSha256(), second.sourceSha256());
    }

    @Test
    void exposedTypedIrMapIsImmutable() throws IOException {
        var result = packageBuild().typecheckDeclaredActorMethods();
        assertThrows(UnsupportedOperationException.class,
                () -> result.methods().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> result.method(slot()).parameters().clear());
    }

    @Test
    void verifiedPrehirDoesNotAuthorizeExecutableActorCodegen() throws IOException {
        var build = packageBuild();
        assertNotNull(build.typecheckDeclaredActorMethods().method(slot()));
        var error = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(error.getMessage().contains("DECLARED_ACTOR_CODEGEN_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void unloweredPrivateLifecycleHookCannotDisappearFromTypedActorPlan()
            throws IOException {
        var build = packageBuild();
        write("src/foo/hook.atto", NS
                + "mtd (Foo f) on_start(): void { return; }");
        var error = assertThrows(IllegalArgumentException.class,
                build::typecheckDeclaredActorMethods);
        assertTrue(error.getMessage().contains("ACTOR_TYPED_IR_CONTROL_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void invalidPrehirBodyRejectsBeforeCodegenGate() throws IOException {
        var build = packageBuild();
        write("src/foo/method.atto", method("return raw_global;"));
        var error = assertThrows(IllegalArgumentException.class, build::build);
        assertTrue(error.getMessage().contains("ACTOR_TYPED_IR_UNSUPPORTED_BODY"),
                error.getMessage());
        assertFalse(error.getMessage().contains("CODEGEN_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void typedIrCannotSurviveRemovalOfItsRequiredMethod() throws IOException {
        var build = packageBuild();
        assertEquals(1, build.typecheckDeclaredActorMethods().methods().size());
        Files.delete(root.resolve("src/foo/method.atto"));
        var error = assertThrows(IllegalArgumentException.class,
                build::typecheckDeclaredActorMethods);
        assertTrue(error.getMessage().contains("missing actor contract implementation"),
                error.getMessage());
    }
}
