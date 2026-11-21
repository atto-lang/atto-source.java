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

    private static Ast.Program withForgedExternal(Ast.ExternalActorMethodDecl method) {
        Ast.Program parsed = Parser.parse(ACTOR);
        Ast.ModuleDecl root = parsed.modules().getFirst();
        var rewritten = new Ast.ModuleDecl(root.name(), root.annotations(), root.contracts(),
                java.util.List.of(root.declarations().getFirst(), method));
        return new Ast.Program(parsed.namespace(), parsed.imports(), java.util.List.of(rewritten));
    }

    private static Ast.ExternalActorMethodDecl withParams(
            Ast.ExternalActorMethodDecl original, java.util.List<Ast.Param> parameters) {
        return new Ast.ExternalActorMethodDecl(
                original.actorName(), original.receiverName(), original.name(),
                original.visibility(), parameters, original.returnType(),
                original.body(), original.runtimeExternal());
    }

    private static void forgedRejected(Ast.ExternalActorMethodDecl method, String reason) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bind(withForgedExternal(method)));
        assertTrue(ex.getMessage().contains(reason), ex.getMessage());
    }

    @Test void constructedExternalCannotShadowActorReceiverOrDuplicateValueNames() {
        var method = method("pub external mtd (Foo f) ping(int first, int second): void { return; }");
        var scalar = Ast.TypeRef.simple("int");
        forgedRejected(withParams(method, java.util.List.of(
                new Ast.Param(scalar, "f"))), "receiver-shadowing");
        forgedRejected(withParams(method, java.util.List.of(
                new Ast.Param(scalar, "x"), new Ast.Param(scalar, "x"))), "duplicate");
        forgedRejected(withParams(method, java.util.List.of(
                new Ast.Param(scalar, null))), "invalid");
    }

    @Test void constructedExternalCannotSmuggleStructuralOrTypelessParameters() {
        var method = method("external mtd (Foo f) receive(ActorMail<int> mail): void { return; }");
        Ast.TypeRef envelope = method.parameters().getFirst().type();
        forgedRejected(withParams(method, java.util.List.of(
                new Ast.Param(envelope, "mail", true, false))), "structural");
        forgedRejected(withParams(method, java.util.List.of(
                new Ast.Param(null, "mail", false, false))), "invalid");
    }

    @Test void mailboxCannotReceiveMutableBindingEvenFromConstructedAst() {
        var method = method("external mtd (Foo f) receive(ActorMail<int> mail): void { return; }");
        var envelope = method.parameters().getFirst().type();
        forgedRejected(withParams(method, java.util.List.of(
                new Ast.Param(envelope, "mail", false, true))), "external receive must be private");
    }

    @Test void mailboxRejectsInferredBorrowedAndCallableNestedPayloads() {
        var method = method("external mtd (Foo f) receive(ActorMail<int> mail): void { return; }");
        var rejected = java.util.List.of(
                Ast.TypeRef.inferred(),
                Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), false),
                Ast.TypeRef.functionType(java.util.List.of(Ast.TypeRef.simple("int")),
                        Ast.TypeRef.simple("void")),
                new Ast.TypeRef("Option", java.util.List.of(
                        Ast.TypeRef.borrowed(Ast.TypeRef.simple("int"), true)), false),
                Ast.TypeRef.simple("void"));
        for (Ast.TypeRef unsafe : rejected) {
            var envelope = new Ast.TypeRef("ActorMail", java.util.List.of(unsafe), false);
            forgedRejected(withParams(method, java.util.List.of(
                    new Ast.Param(envelope, "mail"))),
                    "external receive must be private");
        }
    }

    @Test void mailboxPayloadShapeHasBoundedNonrecursiveAdmission() {
        var method = method("external mtd (Foo f) receive(ActorMail<int> mail): void { return; }");
        var nested = Ast.TypeRef.simple("int");
        for (int depth = 0; depth < 260; depth++) {
            nested = new Ast.TypeRef("Box", java.util.List.of(nested), false);
        }
        var envelope = new Ast.TypeRef("ActorMail", java.util.List.of(nested), false);
        forgedRejected(withParams(method, java.util.List.of(new Ast.Param(envelope, "mail"))),
                "external receive must be private");
    }

    @Test void constructedExternalCannotForgeInferredVoidReturnType() {
        var method = method("external mtd (Foo f) init(): void { return; }");
        var inferredVoid = new Ast.TypeRef("void", java.util.List.of(), true);
        var forged = new Ast.ExternalActorMethodDecl(
                method.actorName(), method.receiverName(), method.name(),
                method.visibility(), method.parameters(), inferredVoid,
                method.body(), true);
        forgedRejected(forged, "external lifecycle method");
    }

    @Test void explicitAstMarkerIsNeverExecutableRuntimeAuthority() {
        var method = method("external mtd (Foo f) receive(ActorMail<int> mail): void { return; }");
        // The boolean marker is constructible, not an unforgeable ActorContext.
        var synthetic = new Ast.ExternalActorMethodDecl(
                method.actorName(), method.receiverName(), method.name(),
                method.visibility(), method.parameters(), method.returnType(),
                method.body(), true);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(withForgedExternal(synthetic)));
        assertTrue(ex.getMessage().contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED")
                        || ex.getMessage().contains("EXTERNAL_ACTOR_RECEIVER_LOWERING_UNSUPPORTED"),
                ex.getMessage());
    }

    private static Map<String, Ast.Program> namespaceEntrypointSourceUnits(
            String firstMethod, String secondMethod) {
        return Map.of(
                "actor.atto", Parser.parse(ACTOR),
                "first.atto", Parser.parse("namespace foo; " + firstMethod),
                "second.atto", Parser.parse("namespace foo; " + secondMethod));
    }

    @Test void duplicateExternalInitAcrossFilesCannotOverrideActivationSlot() {
        String init = "external mtd (Foo f) init(): void { return; }";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("test-package",
                        namespaceEntrypointSourceUnits(init, init)));
        assertTrue(ex.getMessage().contains("duplicate external actor method slot"),
                ex.getMessage());
    }

    @Test void crossFileReceiveCannotOverloadOnDifferentMailPayloadTypes() {
        String integerMail = "external mtd (Foo f) receive(ActorMail<int> mail): void { return; }";
        String textMail = "external mtd (Foo f) receive(ActorMail<String> mail): void { return; }";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet("test-package",
                        namespaceEntrypointSourceUnits(integerMail, textMail)));
        assertTrue(ex.getMessage().contains("duplicate external actor method slot"),
                ex.getMessage());
    }

    @Test void declaredActorGuestFixturesAreParserOnlyAndFailClosed() throws IOException {
        Path root = Path.of("contracts", "actor-external-entrypoints-v1");
        Ast.Program parsed = Parser.parse(Files.readString(root.resolve("declared_actor.atto")));
        assertEquals(2, ActorReceiverBinder.bind(parsed).size());
        assertInstanceOf(Ast.StandaloneActorDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        IllegalArgumentException execution = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(execution.getMessage().contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED"),
                execution.getMessage());

        String forbidden = Files.readString(root.resolve("reject_declared_actor_alias.atto"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(forbidden));
    }

    @Test void declarePubActorHasNominalIdentityAndNoExecutableAuthority() {
        Ast.Program parsed = Parser.parse(
                "namespace foo; declare pub actor Foo implements ActorXYZ;\n"
                        + "external mtd (Foo f) init(): void { return; }\n"
                        + "external mtd (Foo f) receive(ActorMail<int> mail): void { return; }");
        var actor = assertInstanceOf(Ast.StandaloneActorDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        assertTrue(actor.declared());
        assertEquals("Foo", actor.name());
        assertEquals("$declared$Foo", actor.receiverName());
        assertEquals(Ast.Visibility.PUBLIC, actor.visibility());
        assertEquals("ActorXYZ", actor.implementsTypes().getFirst().name());
        assertEquals(2, ActorReceiverBinder.bind(parsed).size());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed));
        assertTrue(ex.getMessage().contains("STANDALONE_ACTOR_LOWERING_UNSUPPORTED"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("declared nominal actor"),
                ex.getMessage());
        assertFalse(ex.getMessage().contains("$declared$"),
                "compiler internal receiver sentinel must not be presented as guest syntax");
    }

    @Test void declaredActorCanBindNamespaceEntrypointFromSeparateSourceUnit() {
        Map<String, Ast.Program> units = Map.of(
                "actor.atto", Parser.parse("namespace foo; declare pub actor Foo;"),
                "entry.atto", Parser.parse("namespace foo; external mtd (Foo f) init(): void { return; }"));
        var bindings = ActorReceiverBinder.bindSourceSet("test-package", units);
        assertEquals(1, bindings.size());
        assertEquals("actor.atto", bindings.values().iterator().next().actorUnit());
    }

    @Test void declareActorRejectsLegacyAliasBodyGenericAndReversedModifiers() {
        for (String source : new String[]{
                "namespace foo; pub declare actor Foo;",
                "namespace foo; declare pub actor Foo as f;",
                "namespace foo; declare pub actor Foo<T>;",
                "namespace foo; declare pub actor Foo as end",
                "namespace foo; declare pub actor Foo extends Base;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source),
                    "declaration should fail closed: " + source);
        }
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
