package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial static-only tests for canonical actor declarations and out-of-line
 * methods. This does not assert executable actor or backend parity.
 */
final class DeclaredActorHardeningTest {

    private static String parserError(String source) {
        return assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source)).getMessage();
    }

    private static String bindError(String source) {
        return assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bind(Parser.parse(source))).getMessage();
    }

    private static String sourceIdError(String id) {
        Ast.Program program = Parser.parse("namespace vault; declare actor Vault;");
        return assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("pkg@1", Map.of(id, program)))
                .getMessage();
    }

    @Test
    void malformedAsyncActorUsesActorSpecificDiagnostic() {
        assertTrue(parserError("declare async actor Vault;")
                .contains("declared actors accept only private/pub"));
    }

    @Test
    void malformedSharedActorUsesActorSpecificDiagnostic() {
        assertTrue(parserError("declare shared actor Vault;")
                .contains("declared actors accept only private/pub"));
    }

    @Test
    void malformedStaticActorUsesActorSpecificDiagnostic() {
        assertTrue(parserError("declare static actor Vault;")
                .contains("declared actors accept only private/pub"));
    }

    @Test
    void duplicatePublicModifiersCannotBeSmuggledIntoDeclaration() {
        assertTrue(parserError("declare pub pub actor Vault;")
                .contains("duplicate/conflicting visibility"));
    }

    @Test
    void annotatedActorDeclarationsDoNotDiscardAttributes() {
        assertTrue(parserError("@unchecked declare actor Vault;")
                .contains("declare actor accepts visibility"));
    }

    @Test
    void declarationCannotClaimInheritanceWithoutTypedWitness() {
        assertTrue(parserError("declare actor Vault extends RootActor;")
                .contains("declare actor requires"));
    }

    @Test
    void inlineAndOutOfLineDefinitionsCannotShareNominalIdentity() {
        assertTrue(bindError("declare actor Vault; define actor Vault as end")
                .contains("duplicate actor/type identity"));
    }

    @Test
    void repeatedActorDeclarationCannotBeMisinterpretedAsPartialImplementation() {
        assertTrue(bindError("declare actor Vault; declare actor Vault;")
                .contains("duplicate actor/type identity"));
    }

    @Test
    void methodCannotAttachToAnUnrelatedActorOrUnknownType() {
        assertTrue(bindError("declare actor Vault;"
                + "define external mtd (Other v) init(): void { return; }")
                .contains("has no nominal actor"));
    }

    @Test
    void unmarkedInitCannotClaimCanonicalActorLifecycleSlot() {
        assertTrue(bindError("declare actor Vault;"
                + "define mtd (Vault v) init(): void { return; }")
                .contains("ACTOR_EXTERNAL_REQUIRED"));
    }

    @Test
    void unmarkedReceiveCannotClaimCanonicalActorMailboxIngress() {
        assertTrue(bindError("declare actor Vault;"
                + "define mtd (Vault v) receive(ActorMail<int> mail): void { return; }")
                .contains("ACTOR_EXTERNAL_REQUIRED"));
    }

    @Test
    void legacyActorDeclarationPreservesUnmarkedReceiveDuringMigration() {
        Ast.Program program = Parser.parse("define actor Vault as receiver;"
                + "define mtd (Vault receiver) receive(ActorMail<int> mail): void { return; }");
        assertEquals(1, ActorReceiverBinder.bind(program).size());
    }

    @Test
    void ordinaryUnmarkedMethodsRemainStaticMetadataOnDeclaredActors() {
        Ast.Program program = Parser.parse("declare actor Vault;"
                + "define mtd (Vault v) get_status(): int { return 1; }");
        assertEquals(1, ActorReceiverBinder.bind(program).size());
    }

    @Test
    void canonicalActorCannotUseLegacyOnStartAsRuntimeIngress() {
        assertTrue(bindError("declare actor Vault;"
                + "define mtd (Vault v) on_start(): void { return; }")
                .contains("ACTOR_LEGACY_START_UNSUPPORTED"));
    }

    @Test
    void canonicalActorCannotRegisterBothModernAndLegacyBootstrapHooks() {
        assertTrue(bindError("declare actor Vault;"
                + "define external mtd (Vault v) init(): void { return; }"
                + "define mtd (Vault v) on_start(): void { return; }")
                .contains("ACTOR_INIT_ON_START_CONFLICT"));
    }

    @Test
    void bootstrapConflictIsOrderIndependentWithinOneModule() {
        assertTrue(bindError("declare actor Vault;"
                + "define mtd (Vault v) on_start(): void { return; }"
                + "define external mtd (Vault v) init(): void { return; }")
                .contains("ACTOR_INIT_ON_START_CONFLICT"));
    }

    @Test
    void inlineLegacyHookConflictsWithAttachedExternalInit() {
        assertTrue(bindError("define actor Vault as on_start(): void { return; } end"
                + "define external mtd (Vault v) init(): void { return; }")
                .contains("ACTOR_INIT_ON_START_CONFLICT"));
    }

    @Test
    void legacyStandaloneOnStartRemainsAStaticMigrationAdapter() {
        Ast.Program parsed = Parser.parse("define actor Vault as v;"
                + "define mtd (Vault v) on_start(): void { return; }");
        assertEquals(1, ActorReceiverBinder.bind(parsed).size());
    }

    @Test
    void bootstrapConflictAcrossVerifiedSourceNamespaceIsRejected() {
        var files = new LinkedHashMap<String, Ast.Program>();
        files.put("src/actor.atto", Parser.parse("namespace vault; declare actor Vault;"));
        files.put("src/new.atto", Parser.parse("namespace vault;"
                + "define external mtd (Vault v) init(): void { return; }"));
        files.put("src/old.atto", Parser.parse("namespace vault;"
                + "define mtd (Vault v) on_start(): void { return; }"));
        String error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("pkg@1", files)).getMessage();
        assertTrue(error.contains("ACTOR_INIT_ON_START_CONFLICT"), error);
    }

    @Test
    void runtimeInitCannotUseUnverifiedStateTypeAsBuilderToken() {
        assertTrue(bindError("declare actor Vault;"
                + "define external mtd (Vault v) init(): state { return v; }")
                .contains("ACTOR_INIT_STATE_BUILDER_UNVERIFIED"));
    }

    @Test
    void runtimeInitCannotReturnArbitraryNominalType() {
        assertTrue(bindError("declare actor Vault;"
                + "define external mtd (Vault v) init(): Other { return v; }")
                .contains("ACTOR_INIT_STATE_BUILDER_UNVERIFIED"));
    }

    @Test
    void receiveMustAcceptExactlyOneTypedMailbox() {
        assertTrue(bindError("declare actor Vault;"
                + "define external mtd (Vault v) receive(int payload): void { return; }")
                .contains("ActorMail<T>"));
    }

    @Test
    void receiveCannotBeReboundByUnmarkedOverload() {
        // The second receive() is denied at the explicit runtime-role marker
        // boundary before its mailbox ABI shape can be considered.
        assertTrue(bindError("declare actor Vault;"
                + "define external mtd (Vault v) receive(ActorMail<int> mail): void { return; }"
                + "define mtd (Vault v) receive(): void { return; }")
                .contains("ACTOR_EXTERNAL_REQUIRED"));
    }

    @Test
    void conventionalReceiverAliasIsLocalToMethodNotNominalDeclaration() {
        Ast.Program program = Parser.parse("declare actor Vault;"
                + "define external mtd (Vault first) init(): void { return; }");
        assertEquals(1, ActorReceiverBinder.bind(program).size());
    }

    @Test
    void sourceIdentityCannotContainWindowsDriveColon() {
        assertTrue(sourceIdError("C:src/actor.atto")
                .contains("invalid package-relative"));
    }

    @Test
    void sourceIdentityCannotContainWindowsAlternateDataStream() {
        assertTrue(sourceIdError("src/actor:stream.atto")
                .contains("invalid package-relative"));
    }

    @Test
    void sourceIdentityCannotContainBidiControls() {
        assertTrue(sourceIdError("src/" + (char) 0x202E + "actor.atto")
                .contains("invalid package-relative"));
    }

    @Test
    void sourceIdentityCannotContainZeroWidthFormatCodePoints() {
        assertTrue(sourceIdError("src/" + (char) 0x200D + "actor.atto")
                .contains("invalid package-relative"));
    }

    @Test
    void sourceIdentityCannotContainParentTraversal() {
        assertTrue(sourceIdError("src/../actor.atto")
                .contains("invalid package-relative"));
    }

    @Test
    void sourceIdentityCannotContainPathSeparatorAliases() {
        assertTrue(sourceIdError("src\\\\actor.atto")
                .contains("invalid package-relative"));
    }

    @Test
    void crossFileDuplicateNominalDeclarationFailsBeforeBinding() {
        var files = new LinkedHashMap<String, Ast.Program>();
        files.put("src/a.atto", Parser.parse("namespace vault; declare actor Vault;"));
        files.put("src/b.atto", Parser.parse("namespace vault; declare actor Vault;"));
        String error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("pkg@1", files)).getMessage();
        assertTrue(error.contains("duplicate actor/type identity"), error);
    }

    @Test
    void sameNominalNameInDifferentNamespacesDoesNotAuthorizeCrossFileMethod() {
        var files = new LinkedHashMap<String, Ast.Program>();
        files.put("src/a.atto", Parser.parse("namespace alpha; declare actor Vault;"));
        files.put("src/b.atto", Parser.parse(
                "namespace beta; define external mtd (Vault v) init(): void { return; }"));
        String error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("pkg@1", files)).getMessage();
        assertTrue(error.contains("has no nominal actor"), error);
    }

    @Test
    void badPackageSourceMembershipIdIsRejectedEvenWithValidActors() {
        String error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet(" ", Map.of(
                        "src/a.atto", Parser.parse("namespace vault; declare actor Vault;"))))
                .getMessage();
        assertTrue(error.contains("package identity"), error);
    }
}
