package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Static lexical acceptance is not a borrow-checked executable actor. */
final class AttoCriticalActorStaticAdmissionTest {
    private static final String HEADER = """
            use namespace rmq_transport;
            declare actor ChannelActor;
            define state on actor ChannelActor as {
                let int next_delivery_tag : 0;
                let bool closing : false;
            }
            external mtd (ChannelActor c) init(): void { return; }
            external mtd (ChannelActor c) receive(ActorMail<int> mail): void { return; }
            """;
    private static final String CRITICAL = """
            critical mtd (ChannelActor c) reserve_delivery_tag(): int {
                let int issued = 0;
                using state.write(critical |s| -> {
                    if !s.closing then
                        s.next_delivery_tag = s.next_delivery_tag + 1;
                        issued = s.next_delivery_tag;
                    fi
                });
                return issued;
            }
            """;

    @Test
    void parsesPrivateOutOfLineCriticalAndRetainsCompleteBodyProvenance() {
        var p = Parser.parse(HEADER + CRITICAL);
        var critical = p.modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.CriticalActorMethodDecl)
                .map(d -> (Ast.CriticalActorMethodDecl) d)
                .findFirst().orElseThrow();
        assertEquals("ChannelActor", critical.actorName());
        assertEquals("reserve_delivery_tag", critical.name());
        assertEquals(Ast.Visibility.PRIVATE, critical.visibility());
        assertEquals("int", critical.returnType().name());
        assertEquals(critical.bodyTokens().size(), critical.bodyTokenKinds().size());
        assertTrue(critical.bodyTokens().contains("using"));
        assertTrue(critical.bodyTokens().contains("critical"));
        assertThrows(UnsupportedOperationException.class, () -> critical.bodyTokens().clear());
        var binding = ActorStateBinder.bindSourceSet("atto-rmq@critical",
                Map.of("src/channel.atto", p));
        assertEquals(1, binding.size());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(p)).getMessage().contains("LOWERING_UNSUPPORTED"));
    }

    @Test
    void fullCriticalDeclWithoutStateStillCannotExecute() {
        var source = "use namespace rmq_transport;\ndeclare actor ChannelActor;\n" + CRITICAL;
        var p = Parser.parse(source);
        var failure = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(p));
        assertTrue(failure.getMessage().contains("CRITICAL_ACTOR_STATE_LOWERING_UNSUPPORTED"),
                failure.getMessage());
    }

    @Test
    void rejectsPublicCriticalGuestIngressAndActorStateSuspension() {
        for (String mutated : new String[] {
                "pub " + CRITICAL,
                CRITICAL.replace("reserve_delivery_tag", "receive"),
                CRITICAL.replace("using state.write", "await socket.read_bytes_async(8); using state.write"),
                CRITICAL.replace("using state.write", "spawn OtherActor(); using state.write"),
                CRITICAL.replace("using state.write", "unsafe; using state.write"),
                CRITICAL.replace("using state.write", "super.call(); using state.write"),
                CRITICAL.substring(0, CRITICAL.length() - 2)
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(HEADER + mutated));
        }
    }

    @Test
    void missingOrWrongNamespaceActorCannotAcquireCriticalStateIdentity() {
        var p = Parser.parse("use namespace other;\n" + CRITICAL);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("atto-rmq@critical",
                        Map.of("src/critical.atto", p)));
        assertTrue(error.getMessage().contains("no nominal actor"), error.getMessage());
    }
    @Test
    void duplicateCriticalNameAndArityInOneActorIsRejected() {
        var p = Parser.parse(HEADER + CRITICAL + CRITICAL);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("atto-rmq@critical",
                        Map.of("src/channel.atto", p)));
        assertTrue(error.getMessage().contains("duplicate private critical actor method slot"),
                error.getMessage());
    }


    @Test
    void rejectsStateLoanEscapeUnboundedEffectsAndUncheckedCalls() {
        for (String mutated : new String[] {
                CRITICAL.replace("issued = s.next_delivery_tag;",
                        "return s.next_delivery_tag;"),
                CRITICAL.replace("issued = s.next_delivery_tag;", "issued = s;"),
                CRITICAL.replace("return issued;",
                        "let int escaped = s.next_delivery_tag; return issued;"),
                CRITICAL.replace("using state.write", "using state.read"),
                CRITICAL.replace("issued = s.next_delivery_tag;", "issued = hostcall();"),
                CRITICAL.replace("issued = s.next_delivery_tag;", "yield;"),
                CRITICAL.replace("issued = s.next_delivery_tag;", "defer issued = 1;"),
                CRITICAL.replace("issued = s.next_delivery_tag;",
                        "while true do issued = s.next_delivery_tag; done"),
                CRITICAL.replace("issued = s.next_delivery_tag;",
                        "using state.write(critical |t| -> { issued = t.next_delivery_tag; });"),
                CRITICAL.replace("return issued;", "state.read(); return issued;"),
                CRITICAL.replace("return issued;", "c.close(); return issued;"),
                CRITICAL.replace("issued = s.next_delivery_tag;",
                        "let int f = |x| -> { return x; }; issued = s.next_delivery_tag;")
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(HEADER + mutated),
                    "unsafe critical body must fail admission");
        }
    }

    @Test
    void admitsTwoSequentialExplicitLoansButNeverExecutesThem() {
        var twoLoans = CRITICAL.replace("return issued;", """
                using state.write(critical |t| -> {
                    issued = t.next_delivery_tag;
                });
                return issued;
                """);
        var parsed = Parser.parse(HEADER + twoLoans);
        long criticalMethods = parsed.modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.CriticalActorMethodDecl).count();
        assertEquals(1, criticalMethods);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed)).getMessage()
                .contains("CRITICAL_ACTOR_STATE_LOWERING_UNSUPPORTED"));
    }
}
