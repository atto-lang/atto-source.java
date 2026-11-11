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
 * Cross-file namespace receiver methods are static metadata, not actor authority.
 * These guards must hold before any executable mailbox/context lowering is added.
 */
final class NamespaceActorReceiverSecurityTest {
    private static final String NS = "namespace foo;\n";
    private static final String ACTOR = NS + "pub define actor Foo as end\n";

    private static Map<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding>
    bind(String actorSource, String methodSource) {
        Map<String, Ast.Program> units = new LinkedHashMap<>();
        units.put("src/actor.atto", Parser.parse(actorSource));
        units.put("src/method.atto", Parser.parse(methodSource));
        return ActorReceiverBinder.bindSourceSet("demo@1.0.0", units);
    }

    private static void rejects(String actorSource, String methodSource, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> bind(actorSource, methodSource));
        assertTrue(failure.getMessage().contains(diagnostic), failure.getMessage());
    }

    @Test
    void plainNamespaceMethodIsStillIndexedAsStaticMetadata() {
        var result = bind(ACTOR,
                NS + "define pub mtd (Foo f) bar(): void { return; }");
        assertEquals(1, result.size());
        assertEquals("src/actor.atto", result.values().iterator().next().actorUnit());
        assertEquals("src/method.atto", result.values().iterator().next().methodUnit());
    }

    @Test
    void inheritedActorCannotSilentlyOverrideAMailboxMethodAcrossFiles() {
        rejects(NS + "pub define actor Parent as bar(): void { return; } end\n"
                        + "pub define actor Foo extends Parent as end\n",
                NS + "define pub mtd (Foo f) bar(): void { return; }",
                "requires inherited actor slot coherence");
    }

    @Test
    void externalPublicInitCannotReturnRawActorSelf() {
        rejects(ACTOR,
                NS + "define pub mtd (Foo f) init(): Foo { return f; }",
                "external actor init is reserved");
    }

    @Test
    void evenPrivateVoidInitCannotBypassOneShotActorBootstrap() {
        rejects(ACTOR,
                NS + "define mtd (Foo f) init(): void { return; }",
                "external actor init is reserved");
    }

    @Test
    void legacyNameFirstInitAlsoStaysReserved() {
        rejects(ACTOR,
                NS + "define pub mtd init(Foo f)(): Foo { return f; }",
                "external actor init is reserved");
    }

    @Test
    void borrowedActorMailPayloadCannotBeAcceptedByNamespaceAttachment() {
        rejects(ACTOR,
                NS + "define mtd (Foo f) receive(ActorMail<borrow int> m): void { return; }",
                "external receive must be private");
    }

    @Test
    void closedStructuralActorMailPayloadCannotBeAcceptedWithoutSendabilityProof() {
        rejects(ACTOR,
                NS + "define mtd (Foo f) receive(ActorMail<record{value: int}> m): void { return; }",
                "external receive must be private");
    }
}
