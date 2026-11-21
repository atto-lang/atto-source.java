package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Canonical nominal-only actor declaration. This is a STATIC parser/binder
 * contract, not evidence of compiled guest execution or actor context permits.
 */
final class DeclaredActorNominalSyntaxTest {

    private static Ast.Decl first(String source) {
        return Parser.parse(source).modules().getFirst().declarations().getFirst();
    }

    private static String rejected(String source) {
        return assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source)).getMessage();
    }

    @Test
    void canonicalDeclarationHasNoSyntheticReceiverAliasOrExecutableBody() {
        var actor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                first("namespace vault; declare actor Vault;"));
        assertEquals("Vault", actor.name());
        assertNull(actor.receiverName());
        assertEquals(Ast.ActorKind.SHARED, actor.actorKind());
        assertEquals(Ast.Visibility.PRIVATE, actor.visibility());
    }

    @Test
    void explicitPublicNominalActorSupportsExternalExportIdentity() {
        var actor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                first("declare pub actor Vault;"));
        assertEquals(Ast.Visibility.PUBLIC, actor.visibility());
    }

    @Test
    void declaredActorWorksInsideNamedModuleWithoutBecomingAClass() {
        var parsed = Parser.parse(
                "define module demo as\n"
                        + "  declare actor Vault;\n"
                        + "end");
        var actor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        assertEquals("Vault", actor.name());
    }

    @Test
    void outOfLinePrivateLifecycleEntriesBindToOneDeclaredActor() {
        var parsed = Parser.parse("namespace vault;\n"
                + "declare actor Vault;\n"
                + "define external mtd (Vault v) init(): void { return; }\n"
                + "define external mtd (Vault v) receive(ActorMail<int> mail): void { return; }\n");
        var bindings = ActorReceiverBinder.bind(parsed);
        assertEquals(2, bindings.size());
        assertTrue(bindings.values().stream().allMatch(
                b -> b.nominalActor() instanceof Ast.StandaloneActorDecl));
        assertTrue(bindings.values().stream().allMatch(
                b -> b.method().runtimeEntrypoint()));
    }

    @Test
    void crossFileNamespaceBinderAttachesMethodsWithoutAlias() {
        var sources = new LinkedHashMap<String, Ast.Program>();
        sources.put("src/actor.atto", Parser.parse("namespace vault; declare actor Vault;"));
        sources.put("src/init.atto", Parser.parse(
                "namespace vault; define external mtd (Vault v) init(): void { return; }"));
        var bindings = ActorReceiverBinder.bindSourceSet("test@1.0.0", sources);
        assertEquals(1, bindings.size());
        var binding = bindings.values().iterator().next();
        assertEquals("src/actor.atto", binding.actorUnit());
        assertEquals("src/init.atto", binding.methodUnit());
    }

    @Test
    void standaloneActorCannotExecuteBeforeCheckedLowering() {
        var parsed = Parser.parse("declare actor Vault;");
        String diagnostic = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed)).getMessage();
        assertTrue(diagnostic.contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED"),
                diagnostic);
    }

    @Test
    void inlineImplementationRemainsASeparateClassLikeActor() {
        assertInstanceOf(Ast.ClassDecl.class,
                first("define actor Vault as end"));
    }

    @Test
    void legacyStandaloneActorSpellingStillParsesForMigration() {
        var legacy = assertInstanceOf(Ast.StandaloneActorDecl.class,
                first("define actor Vault as v;"));
        assertEquals("v", legacy.receiverName());
    }

    @Test
    void declarationsWithBodiesAreRejectedRatherThanSynthesized() {
        assertTrue(rejected("declare actor Vault as end").contains("declare actor requires"));
    }

    @Test
    void declarationsWithReceiverAliasesAreRejected() {
        assertTrue(rejected("declare actor Vault as v;").contains("declare actor requires"));
    }

    @Test
    void declarationsWithGenericParametersAreRejected() {
        assertTrue(rejected("declare actor Vault<int>;").contains("declare actor requires"));
    }

    @Test
    void unsupportedInheritanceRequiresFutureContractWork() {
        assertTrue(rejected("declare actor Vault implements HasActor;").contains("declare actor requires"));
    }

    @Test
    void misplacedVisibilityIsRejected() {
        assertTrue(rejected("pub declare actor Vault;").contains("visibility only after"));
    }

    @Test
    void unrecognizedActorDeclarationModifierFailsClosed() {
        assertTrue(rejected("declare async actor Vault;").contains("declared actors"));
    }

    @Test
    void unresolvedForwardTypesAreStillCheckedIndependently() {
        assertTrue(rejected("declare type Missing;").contains("unresolved forward"));
    }

    @Test
    void duplicateDeclaredActorIdentityIsNotSilentlyAcceptedByBinder() {
        var parsed = Parser.parse("declare actor Vault; declare actor Vault;");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bind(parsed)).getMessage()
                .contains("duplicate actor/type identity"));
    }

    @Test
    void classCannotImpersonateDeclaredActor() {
        var parsed = Parser.parse("declare actor Vault; define class Vault as end");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bind(parsed)).getMessage()
                .contains("duplicate actor/type identity"));
    }
}
