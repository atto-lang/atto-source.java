package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in, single-source stateless declared actor execution.
 * These cases do not establish stateful/cross-file/native-AOT parity.
 */
final class StatelessDeclaredActorExecutionTest {
    private static String actor(String initBody, String receiveBody) {
        return "namespace standalone;\n"
                + "declare actor Worker;\n"
                + "define external mtd (Worker w) init(): void { " + initBody + " }\n"
                + "define external mtd (Worker w) receive(ActorMail<String> mail): void { "
                + receiveBody + " }\n";
    }

    private static String source() {
        return actor("return;",
                "self.send(mail.value); self.end(); return;")
                + "pub async routine main(): void {\n"
                + "  val worker = spawn Worker();\n"
                + "  await worker.get_ready();\n"
                + "  worker.send(\"delivered\");\n"
                + "  val output = (await worker.outputs.next()).value.unwrap();\n"
                + "  stdio.stdout.write(output.value);\n"
                + "  await worker.get_done();\n"
                + "  return;\n"
                + "}\n";
    }

    @Test
    void canonicalActorRequiresOptInAndRemainsDeniedByDefaultCompiler() {
        var ordinary = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source()));
        assertTrue(ordinary.getMessage().contains(
                "EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED"), ordinary.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source())));
    }

    @Test
    void optInProducesCompleteExecutableActorClassAfterFullCompilerChecks() {
        Ast.Program checked = OresCompiler.parseAndTypeCheckStatelessDeclaredActors(source());
        var actors = checked.modules().stream().flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).toList();
        assertEquals(1, actors.size());
        var worker = actors.getFirst();
        assertEquals("Worker", worker.name());
        assertEquals(Ast.ActorKind.SHARED, worker.actorKind());
        assertTrue(worker.fields().isEmpty());
        assertEquals(
                java.util.List.of("on_start", "receive"),
                worker.methods().stream().map(Ast.MethodDecl::name).toList());
        assertTrue(checked.modules().stream().flatMap(m -> m.declarations().stream())
                .noneMatch(d -> d instanceof Ast.StandaloneActorDecl
                        || d instanceof Ast.ExternalActorMethodDecl));
    }

    @Test
    @Timeout(15)
    void verifiedStatelessDeclaredActorRunsOnTheRealMailboxDispatcher() throws Exception {
        String source = source();
        Ast.Program checked = OresCompiler.parseAndTypeCheckStatelessDeclaredActors(source);
        String codeUnit = "stateless-declared-actor.atto";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var lease = OresLanguage.openPrecheckedProgram(codeUnit, source, checked);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false)
                     .out(output)
                     .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, codeUnit)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        assertEquals("delivered", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void missingReceiveCannotCreateEmptyRunnableActor() {
        var source = "declare actor Worker;"
                + "define external mtd (Worker w) init(): void { return; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(source));
        assertTrue(error.getMessage().contains("STATELESS_ACTOR_INCOMPLETE"), error.getMessage());
    }

    @Test
    void absentInitUsesCompilerSynthesizedNoopButStillHasReadinessBarrier() {
        var source = "namespace standalone; declare actor Worker;"
                + "define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }";
        var checked = OresCompiler.parseAndTypeCheckStatelessDeclaredActors(source);
        var actor = checked.modules().stream().flatMap(m -> m.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl).map(d -> (Ast.ClassDecl) d)
                .findFirst().orElseThrow();
        assertEquals(java.util.List.of("on_start", "receive"),
                actor.methods().stream().map(Ast.MethodDecl::name).toList());
        var bootstrap = actor.methods().getFirst();
        assertEquals(Ast.Visibility.PRIVATE, bootstrap.visibility());
        assertTrue(bootstrap.body().getFirst() instanceof Ast.ReturnStmt returnStmt
                && returnStmt.value() == null);
    }

    @Test
    @Timeout(15)
    void omittedInitExecutesRealStatelessGuestReceiveOnActorLane() throws Exception {
        // The same guest as the explicit-init test, but the compiler must
        // synthesize the no-op startup hook before the runtime READY barrier.
        String source = source().replace(
                "define external mtd (Worker w) init(): void { return; }\\n", "");
        assertFalse(source.contains("init(): void"));
        Ast.Program checked = OresCompiler.parseAndTypeCheckStatelessDeclaredActors(source);
        String codeUnit = "stateless-declared-actor-no-init.atto";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var lease = OresLanguage.openPrecheckedProgram(codeUnit, source, checked);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, codeUnit)
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        assertEquals("delivered", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void statefulOrSideEffectingInitCannotMasqueradeAsInertBootstrap() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(
                        actor("self.send(\"before_ready\"); return;", "return;")));
        assertTrue(error.getMessage().contains("STATELESS_ACTOR_INIT_NOT_INERT"),
                error.getMessage());
    }

    @Test
    void initializersWithArgumentsRequireFutureTypedStateBuilderAbi() {
        String src = "declare actor Worker;"
                + "define external mtd (Worker w) init(int seed): void { return; }"
                + "define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(src));
        assertTrue(error.getMessage().contains("STATELESS_ACTOR_INIT_NOT_INERT"),
                error.getMessage());
    }

    @Test
    void ordinaryActorMethodAttachmentsAreNotExecutableThroughTheOptIn() {
        String src = actor("return;", "return;")
                + "define mtd (Worker w) extra(): void { return; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(src));
        assertTrue(error.getMessage().contains("STATELESS_ACTOR_UNVERIFIED_METHOD"),
                error.getMessage());
    }

    @Test
    void legacyStandaloneAliasCannotGainStatelessExecutionAuthority() {
        String src = "define actor Worker as w;"
                + "define external mtd (Worker w) init(): void { return; }"
                + "define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(src));
        assertTrue(error.getMessage().contains("STATELESS_ACTOR_LEGACY_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void arbitraryExternalRoleIsRejectedBeforeLowering() {
        String src = "declare actor Worker;"
                + "define external mtd (Worker w) ping(): void { return; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(src));
        assertTrue(error.getMessage().contains("ACTOR_EXTERNAL_ROLE_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void wrongReceiveMailboxTypeCannotBecomeExecutable() {
        String src = "declare actor Worker;"
                + "define external mtd (Worker w) init(): void { return; }"
                + "define external mtd (Worker w) receive(int payload): void { return; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(src));
        assertTrue(error.getMessage().contains("ActorMail<T>"),
                error.getMessage());
    }

    @Test
    void noCanonicalActorCannotEnableTheOptIn() {
        String src = "define actor Worker as receive(ActorMail<int> mail): void { return; } end";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(src));
        assertTrue(error.getMessage().contains("STATELESS_ACTOR_NOT_FOUND"),
                error.getMessage());
    }

    @Test
    void duplicateRoleIsDeniedBeforeAnyExecutableActorIsConstructed() {
        String src = actor("return;", "return;")
                + "define external mtd (Worker w) init(): void { return; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(src));
        assertTrue(error.getMessage().contains("ACTOR_DUPLICATE_INIT"),
                error.getMessage());
    }

    @Test
    void unknownSourceReceiverMustNotBecomeExecutableCapability() {
        String src = "declare actor Worker;"
                + "define external mtd (Other x) init(): void { return; }"
                + "define external mtd (Worker w) receive(ActorMail<int> mail): void { return; }";
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStatelessDeclaredActors(src));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
    }
}
