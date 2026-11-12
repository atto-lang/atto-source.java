package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler/static-linker admission only. A parsed out-of-line actor method is
 * not evidence of guest execution or sealed-state loan authorization.
 */
final class OptionalActorReceiverBindingTest {
    private static final String NS = "namespace workers;\n";
    private static final String ACTOR = NS + "declare actor Worker;";
    private static final String STATE = NS
            + "define state on actor Worker as { let int v = 5; }";

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
    void declaredActorContractHeaderAcceptsAnonymousReceiverMethodsAtParseTime() {
        Ast.Program program = Parser.parse(NS + """
                declare actor Worker implements Actor;
                pub mtd (Worker) big_ly(): void { process(); }
                pub mtd (Worker _) process(): void { return; }
                """);
        assertNotNull(program);
    }
}
