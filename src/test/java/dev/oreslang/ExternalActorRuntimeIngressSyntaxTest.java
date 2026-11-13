package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-only ingress provenance: explicit external runtime hooks never mint
 * a compiler/runtime capability merely by being represented in an AST.
 * Guest actor execution is NOT proven here.
 */
final class ExternalActorRuntimeIngressSyntaxTest {
    private static final String NS = "namespace counter;\n";

    private static Ast.ExternalActorMethodDecl method(String declaration) {
        return assertInstanceOf(Ast.ExternalActorMethodDecl.class,
                Parser.parse(NS + declaration).modules().getFirst()
                        .declarations().getFirst());
    }

    @Test
    void onlyExplicitPrivateRuntimeHooksCarryIngressMetadata() {
        var receive = method("external mtd (Counter c) receive(ActorMail<int> mail): void { return; }");
        assertEquals("receive", receive.name());
        assertEquals(Ast.Visibility.PRIVATE, receive.visibility());
        assertTrue(receive.runtimeOnly());

        var init = method("external mtd (Counter c) init(): void { return; }");
        assertTrue(init.runtimeOnly());
        assertTrue(init.parameters().isEmpty());

        var ordinary = method("mtd (Counter c) receive(ActorMail<int> mail): void { return; }");
        assertFalse(ordinary.runtimeOnly(), "ordinary mtd must not mint runtime ingress");
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(NS
                        + "external mtd (Counter c) receive(ActorMail<int> mail): void { return; }")));
    }

    @Test
    void nominalBindingDifferentiatesRuntimeIngressFromOrdinaryMethods() {
        var legal = Parser.parse(NS + """
                declare pub actor Counter;
                external mtd (Counter c) init(): void { return; }
                external mtd (Counter c) receive(ActorMail<int> mail): void { return; }
                pub mtd (Counter c) echo(int value): int { return value; }
                """);
        var declaredReceive = assertInstanceOf(Ast.ExternalActorMethodDecl.class,
                legal.modules().getFirst().declarations().get(2));
        assertEquals(Ast.Visibility.PRIVATE, declaredReceive.visibility());
        assertTrue(declaredReceive.runtimeOnly());
        assertEquals("ActorMail", declaredReceive.parameters().getFirst().type().name());
        assertEquals(1, declaredReceive.parameters().getFirst().type().arguments().size());
        assertEquals("int",
                declaredReceive.parameters().getFirst().type().arguments().getFirst().name());
        assertEquals("void", declaredReceive.returnType().name());
        var bound = ActorReceiverBinder.bind(legal);
        assertEquals(3, bound.size());
        assertTrue(bound.get(new ActorReceiverBinder.Slot(
                Parser.ROOT_MODULE, "Counter", "init", 0)).method().runtimeOnly());
        assertTrue(bound.get(new ActorReceiverBinder.Slot(
                Parser.ROOT_MODULE, "Counter", "receive", 1)).method().runtimeOnly());
        assertFalse(bound.get(new ActorReceiverBinder.Slot(
                Parser.ROOT_MODULE, "Counter", "echo", 1)).method().runtimeOnly());

        var spoof = Parser.parse(NS + """
                declare pub actor Counter;
                mtd (Counter c) receive(ActorMail<int> mail): void { return; }
                """);
        var denied = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bind(spoof));
        assertTrue(denied.getMessage().contains("runtime-only"), denied.getMessage());

        var rawInit = Parser.parse(NS + """
                declare pub actor Counter;
                mtd (Counter c) init(): void { return; }
                """);
        assertThrows(IllegalArgumentException.class, () -> ActorReceiverBinder.bind(rawInit));
    }

    @Test
    void runtimeHooksRejectPublicSpoofingAndInvalidSignatures() {
        for (String source : new String[] {
                "pub external mtd (Counter c) receive(ActorMail<int> mail): void { return; }",
                "external mtd (Counter c) echo(int n): int { return n; }",
                "external mtd (Counter c) init(int n): void { return; }",
                "external mtd (Counter c) receive(): void { return; }",
                "external mtd (Counter c) receive(int n): void { return; }",
                "external mtd (Counter c) receive(ActorMail<borrow int> mail): void { return; }",
                "external mtd (Counter c) receive(ActorMail<int> mail): int { return 0; }",
                "external fnc receive(): void { return; }"
        }) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(NS + source), source);
            assertFalse(failure.getMessage().isBlank());
        }
    }
}
