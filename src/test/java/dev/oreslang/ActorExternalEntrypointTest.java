package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parser and static actor binder evidence, NOT guest runtime execution. */
final class ActorExternalEntrypointTest {
    private static final String ACTOR = "namespace foo; pub define actor Foo as f;\n";

    private static Ast.ExternalActorMethodDecl method(String source) {
        return assertInstanceOf(Ast.ExternalActorMethodDecl.class,
                Parser.parse(source).modules().getFirst().declarations().getFirst());
    }

    private static void rejects(String method, String reason) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bind(Parser.parse(ACTOR + method)));
        assertTrue(ex.getMessage().contains(reason), ex.getMessage());
    }

    @Test void bareReceiverFirstInitIsExplicitlyExternal() {
        var init = method("external mtd (Foo f) init(): void { return; }");
        assertEquals("Foo", init.actorName());
        assertEquals("init", init.name());
        assertTrue(init.runtimeExternal());
        assertEquals(0, init.parameters().size());
    }

    @Test void definedReceiverFirstReceiveHasExplicitIngressMarker() {
        var receive = method(
                "define external mtd (Foo f) receive(ActorMail<int> mail): void { return; }");
        assertTrue(receive.runtimeExternal());
        assertEquals(1, receive.parameters().size());
        assertEquals(Ast.Visibility.PRIVATE, receive.visibility());
    }

    @Test void publicVisibilityIsDistinctFromIngress() {
        var pubExternal = method("pub external mtd (Foo f) ping(): void { return; }");
        assertEquals(Ast.Visibility.PUBLIC, pubExternal.visibility());
        assertTrue(pubExternal.runtimeExternal());
        var normal = method("pub mtd (Foo f) bar(): void { return; }");
        assertEquals(Ast.Visibility.PUBLIC, normal.visibility());
        assertFalse(normal.runtimeExternal());
        assertFalse(method("define mtd bar(Foo f)(): void { return; }").runtimeExternal());
    }

    @Test void unmarkedHooksFailAtParserBoundary() {
        for (String name : new String[]{
                "init(): void", "receive(ActorMail<int> mail): void"}) {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("mtd (Foo f) " + name + " { return; }"));
            assertTrue(ex.getMessage().contains("ACTOR_ENTRYPOINT_EXTERNAL_REQUIRED"),
                    ex.getMessage());
        }
    }

    @Test void binderChecksIngressSignaturesAndPrivacy() {
        String init = "external mtd (Foo f) init(): void { return; }\n";
        String receive = "external mtd (Foo f) receive(ActorMail<int> mail): void { return; }\n";
        assertEquals(2, ActorReceiverBinder.bind(Parser.parse(ACTOR + init + receive)).size());
        rejects("pub external mtd (Foo f) init(): void { return; }", "external lifecycle method");
        rejects("external mtd (Foo f) init(int value): void { return; }", "external lifecycle method");
        rejects("external mtd (Foo f) init(): int { return 1; }", "external lifecycle method");
        rejects("pub external mtd (Foo f) receive(ActorMail<int> mail): void { return; }",
                "external receive must be private");
        rejects("external mtd (Foo f) receive(int x): void { return; }",
                "external receive must be private");
    }

    @Test void duplicateReceiveWithDistinctPayloadIsStillDuplicate() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bind(Parser.parse(ACTOR
                        + "external mtd (Foo f) receive(ActorMail<int> x): void { return; }\n"
                        + "external mtd (Foo f) receive(ActorMail<String> x): void { return; }")));
        assertTrue(ex.getMessage().contains("duplicate external actor method slot"),
                ex.getMessage());
    }

    @Test void forgedAstCannotClaimIngressWithoutExplicitMarker() {
        var valid = method("external mtd (Foo f) init(): void { return; }");
        var forged = new Ast.ExternalActorMethodDecl(
                valid.actorName(), valid.receiverName(), valid.name(),
                valid.visibility(), valid.parameters(), valid.returnType(), valid.body());
        assertFalse(forged.runtimeExternal());
        Ast.Program parsed = Parser.parse(ACTOR);
        Ast.ModuleDecl root = parsed.modules().getFirst();
        var adjusted = new Ast.ModuleDecl(root.name(), root.annotations(), root.contracts(),
                java.util.List.of(root.declarations().getFirst(), forged));
        var program = new Ast.Program(parsed.namespace(), parsed.imports(),
                java.util.List.of(adjusted));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bind(program));
        assertTrue(ex.getMessage().contains("ACTOR_ENTRYPOINT_EXTERNAL_REQUIRED"),
                ex.getMessage());
    }

    @Test void syntaxAndNominalBindingAreNotExecutableActorIngress() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        ACTOR + "external mtd (Foo f) init(): void { return; }")));
        assertTrue(ex.getMessage().contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED")
                        || ex.getMessage().contains("EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED"),
                ex.getMessage());
    }

    @Test void crossFileNamespaceRetainsSourceProvenanceAndMarker() throws IOException {
        Path root = Path.of("contracts", "actor-external-entrypoints-v1");
        var sources = Map.of(
                "actor.atto", Parser.parse(Files.readString(root.resolve("actor.atto"))),
                "entrypoints.atto", Parser.parse(Files.readString(root.resolve("entrypoints.atto"))));
        var resolved = ActorReceiverBinder.bindSourceSet("test-package", sources);
        assertEquals(3, resolved.size());
        assertTrue(resolved.entrySet().stream().anyMatch(e -> e.getKey().name().equals("init")
                && e.getValue().nominalBinding().method().runtimeExternal()));
        assertTrue(resolved.entrySet().stream().anyMatch(e -> e.getKey().name().equals("receive")
                && e.getValue().nominalBinding().method().runtimeExternal()));
    }
}
