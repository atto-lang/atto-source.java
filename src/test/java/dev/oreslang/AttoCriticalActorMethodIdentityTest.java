package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static-only nominal actor/method identity admission.
 * Passing these tests is NOT executable state-loan or runtime ingress proof.
 */
final class AttoCriticalActorMethodIdentityTest {
    private static final String NAMESPACE = "use namespace rmq_transport;\n";
    private static final String ACTOR = "declare actor ChannelActor;\n";
    private static final String STATE =
            "define state on actor ChannelActor as { let int issued: 0; }\n";

    private static String critical(String name, String parameters) {
        return "critical mtd (ChannelActor c) " + name + "(" + parameters
                + "): int { return 1; }\n";
    }

    private static String ordinary(String name) {
        return "pub mtd (ChannelActor c) " + name + "(): int { return 2; }\n";
    }

    private static Map<String, dev.oreslang.ast.Ast.Program> twoUnits(
            String first, String second) {
        var units = new LinkedHashMap<String, dev.oreslang.ast.Ast.Program>();
        units.put("src/a.atto", Parser.parse(NAMESPACE + first));
        units.put("src/z.atto", Parser.parse(NAMESPACE + second));
        return units;
    }

    private static IllegalArgumentException denied(
            Map<String, dev.oreslang.ast.Ast.Program> units) {
        return assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("rmq@identity", units));
    }

    @Test
    void criticalAndOrdinaryMethodsCannotShareDispatchNameInEitherSourceOrder() {
        String critical = critical("reserve_delivery_tag", "");
        String ordinary = ordinary("reserve_delivery_tag");
        var forward = denied(twoUnits(ACTOR + STATE + critical, ordinary));
        assertTrue(forward.getMessage().contains("share dispatch name"),
                forward.getMessage());
        var reverse = denied(twoUnits(ACTOR + STATE + ordinary, critical));
        assertTrue(reverse.getMessage().contains("share dispatch name"),
                reverse.getMessage());
    }

    @Test
    void differentArityCannotBypassCriticalNameUniqueness() {
        var failure = denied(twoUnits(ACTOR + STATE,
                critical("reserve_delivery_tag", "")
                        + critical("reserve_delivery_tag", "int ignored")));
        assertTrue(failure.getMessage().contains("duplicate private critical"),
                failure.getMessage());
    }

    @Test
    void inlineActorCannotGainOutOfLineCriticalAuthority() {
        var failure = denied(twoUnits("define actor ChannelActor as end\n",
                critical("reserve_delivery_tag", "")));
        assertTrue(failure.getMessage().contains("requires 'declare actor'"),
                failure.getMessage());
    }

    @Test
    void wrongNamespaceOrAbsentActorCannotGainCriticalIdentity() {
        var units = new LinkedHashMap<String, dev.oreslang.ast.Ast.Program>();
        units.put("src/a.atto", Parser.parse(NAMESPACE + ACTOR));
        units.put("src/b.atto", Parser.parse(
                "use namespace other;\n" + critical("reserve_delivery_tag", "")));
        var failure = denied(units);
        assertTrue(failure.getMessage().contains("no nominal actor"),
                failure.getMessage());
    }

    @Test
    void criticalNameCannotCollideWithAttachedStateField() {
        var failure = denied(twoUnits(ACTOR + STATE,
                critical("issued", "")));
        assertTrue(failure.getMessage().contains("conflicts with actor method"),
                failure.getMessage());
    }

    @Test
    void criticalNamesAreNominallyScopedToEachDeclaredActor() {
        String other = "declare actor OtherActor;\n"
                + "critical mtd (OtherActor o) reserve_delivery_tag(): int { return 2; }\n";
        assertDoesNotThrow(() -> ActorStateBinder.bindSourceSet("rmq@identity",
                twoUnits(ACTOR + critical("reserve_delivery_tag", ""), other)));
    }

    @Test
    void runtimeLifecycleAndControlNamesAreNotCriticalMethods() {
        for (String name : new String[] {
                "init", "receive", "handle_state", "on_panic",
                "on_throw", "on_raise", "on_recover",
                "get_ready_signal", "get_done_signal"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(NAMESPACE + ACTOR + critical(name, "")),
                    "critical method must not shadow runtime hook " + name);
        }
    }

    @Test
    void AcceptedLexicalCriticalMethodStillCannotExecuteAsGuest() {
        var program = Parser.parse(NAMESPACE + ACTOR + STATE
                + critical("reserve_delivery_tag", ""));
        assertDoesNotThrow(() -> ActorStateBinder.bindSourceSet("rmq@identity",
                Map.of("src/channel.atto", program)));
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("LOWERING_UNSUPPORTED"),
                error.getMessage());
    }
}
