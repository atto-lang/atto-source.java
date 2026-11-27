package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler/static-linker admission only. A parsed out-of-line actor method is
 * not evidence of guest execution or sealed-state loan authorization.
 */
final class OptionalActorReceiverBindingTest {
    private static final String NS = "namespace workers;\n";
    private static final String ACTOR = NS + "declare pub actor Worker;";
    private static final String STATE = NS
            + "define state on actor Worker as { let int v : 5; }";

    private static Map<String, Ast.Program> sourceSet(String methods) {
        Map<String, Ast.Program> units = new LinkedHashMap<>();
        units.put("src/workers/actor.atto", Parser.parse(ACTOR));
        units.put("src/workers/state.atto", Parser.parse(STATE));
        units.put("src/workers/methods.atto", Parser.parse(NS + methods));
        return units;
    }

    private static ActorReceiverBinder.SourceSlot slot(String name) {
        return new ActorReceiverBinder.SourceSlot(
                "demo@1.0.0", "workers", "Worker", name, 0);
    }

    @Test
    void omittedAndUnderscoreReceiversAttachToTheSameNominalActorAcrossFiles() {
        String methods = """
                pub mtd (Worker) big_ly(): void {
                    process();
                }
                pub mtd (Worker _) process(): void {
                    handle_state(|s| -> { s.v = 6; });
                }
                pub mtd (Worker self) explicit(): void { return; }
                pub mtd (Worker worker) named(): void { return; }
                """;

        var bindings = ActorReceiverBinder.bindSourceSet(
                "demo@1.0.0", sourceSet(methods));
        assertEquals(4, bindings.size());
        assertEquals("self", bindings.get(slot("big_ly"))
                .nominalBinding().method().receiverName());
        assertEquals("self", bindings.get(slot("process"))
                .nominalBinding().method().receiverName());
        assertEquals("self", bindings.get(slot("explicit"))
                .nominalBinding().method().receiverName());
        assertEquals("worker", bindings.get(slot("named"))
                .nominalBinding().method().receiverName());
        assertEquals("src/workers/actor.atto",
                bindings.get(slot("process")).actorUnit());
        assertEquals("src/workers/methods.atto",
                bindings.get(slot("process")).methodUnit());
        assertEquals("Worker", bindings.get(slot("big_ly"))
                .nominalBinding().method().actorName());
    }

    @Test
    void legacyNameFirstSyntaxSupportsOmittedReceiverWithoutAddingAnArgument() {
        var bindings = ActorReceiverBinder.bindSourceSet("demo@1.0.0",
                sourceSet("pub mtd ping(Worker)(): void { return; }"));
        assertEquals(1, bindings.size());
        assertEquals("self", bindings.get(slot("ping"))
                .nominalBinding().method().receiverName());
    }

    @Test
    void anExplicitParameterCannotShadowTheHiddenSelfEvenWithNamedAlias() {
        for (String receiver : new String[]{"(Worker)", "(Worker _)",
                "(Worker self)", "(Worker worker)"}) {
            String code = NS + "pub mtd " + receiver
                    + " invalid(int self): void { return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(code), receiver);
        }
    }

    @Test
    void invalidReceiverHeadFailsInsteadOfParsingAsAFreeFunction() {
        for (String head : new String[]{
                "(Worker a b)", "(Worker, Other)", "()", "(Worker 7)"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(NS
                            + "pub mtd " + head + " bad(): void { return; }"),
                    head);
        }
    }

    @Test
    void duplicateSelectorsCannotBeDistinguishedByReceiverSpelling() {
        assertThrows(IllegalArgumentException.class, () ->
                ActorReceiverBinder.bindSourceSet("demo@1.0.0", sourceSet("""
                        pub mtd (Worker) update(): void { return; }
                        pub mtd (Worker named) update(): void { return; }
                        """)));
    }

    @Test
    void omittedReceiverDoesNotRelaxNamespaceActorOwnership() {
        Map<String, Ast.Program> units = new LinkedHashMap<>();
        units.put("src/workers/actor.atto", Parser.parse(ACTOR));
        units.put("src/other/method.atto",
                Parser.parse("namespace other; pub mtd (Worker) run(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                ActorReceiverBinder.bindSourceSet("demo@1.0.0", units));
    }

    @Test
    void reservedActorCapabilityAliasesAreRejectedBeforeBinderAdmission() {
        for (String alias : new String[]{
                "state", "handle_state", "on_start", "on_stop", "on_panic",
                "on_throw", "on_raise", "on_recover", "receive", "init"}) {
            String body = NS + "pub mtd (Worker " + alias
                    + ") helper(): void { return; }";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(body), alias);
            assertTrue(error.getMessage().contains("receiver alias"), alias);
        }
    }

    @Test
    void receiverTypeCannotBypassNominalActorConstraints() {
        for (String receiver : new String[]{
                "(borrow Worker)", "(Worker<int>)", "(Worker, Other)",
                "(Worker named extra)", "(Worker self extra)"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                    NS + "pub mtd " + receiver + " helper(): void { return; }"),
                    receiver);
        }
    }

    @Test
    void cannotExposePrivateActorWithPublicUnnamedReceiverMethod() {
        Map<String, Ast.Program> units = new LinkedHashMap<>();
        units.put("src/workers/actor.atto",
                Parser.parse(NS + "declare actor Worker;"));
        units.put("src/workers/method.atto",
                Parser.parse(NS + "pub mtd (Worker) expose(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                ActorReceiverBinder.bindSourceSet("demo@1.0.0", units));
    }

    @Test
    void forgedAstReceiversCannotBypassParserNormalizationOrReservedNames() {
        Ast.ExternalActorMethodDecl normalized =
                new Ast.ExternalActorMethodDecl(
                        "Worker", "_", "hello", Ast.Visibility.PRIVATE,
                        List.of(), Ast.TypeRef.simple("void"), List.of());
        assertEquals("self", normalized.receiverName(),
                "a bypass of the parser must not retain a usable underscore name");

        for (String alias : new String[]{"handle_state", "state",
                "on_panic", "on_throws", "on_restart", "tear_down"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.ExternalActorMethodDecl(
                            "Worker", alias, "hello", Ast.Visibility.PRIVATE,
                            List.of(), Ast.TypeRef.simple("void"), List.of()),
                    alias);
        }
        for (String alias : new String[]{"self", "named", "_"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.ExternalActorMethodDecl(
                            "Worker", alias, "hello", Ast.Visibility.PRIVATE,
                            List.of(new Ast.Param(Ast.TypeRef.simple("int"), "self")),
                            Ast.TypeRef.simple("void"), List.of()), alias);
        }
    }

    @Test
    void programmaticActorReceiversMustMatchLexerIdentifierBoundaries() {
        for (String forged : new String[]{
                "other.member", "other/name", "two tokens", "$compiler",
                "#symbol", "9worker", "a-b", "a::b"}) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> new Ast.ExternalActorMethodDecl(
                            "Worker", forged, "method", Ast.Visibility.PRIVATE,
                            List.of(), Ast.TypeRef.simple("void"), List.of()),
                    forged);
            assertTrue(error.getMessage().contains("source identifier"), forged);
        }
        Ast.ExternalActorMethodDecl unicode =
                new Ast.ExternalActorMethodDecl(
                        "Worker", "wörker_2", "method", Ast.Visibility.PRIVATE,
                        List.of(), Ast.TypeRef.simple("void"), List.of());
        assertEquals("wörker_2", unicode.receiverName());
    }

    @Test
    void declaredActorContractHeaderAcceptsAnonymousReceiverMethodsAtParseTime() {
        Ast.Program program = Parser.parse(NS + """
                declare actor Worker implements Actor;
                pub mtd (Worker) big_ly(): void { process(); }
                pub mtd (Worker _) process(): void { return; }
                """);
        assertNotNull(program);
    }
}
