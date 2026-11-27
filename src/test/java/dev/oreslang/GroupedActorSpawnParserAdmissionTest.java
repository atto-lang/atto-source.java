package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Parser stage only; no actor execution or option lowering is enabled. */
final class GroupedActorSpawnParserAdmissionTest {
    private static String program(String expr) {
        return "pub routine main(): void { val result = " + expr + "; return; }";
    }
    private static void reject(String expr, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class, () -> Parser.parse(program(expr)), expr);
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }

    @Test void wellFormedGroupsBindToSpawnButCannotExecuteYet() {
        for (String expr : new String[] {
            "await spawn MyActor() with { schedule { max_parallelism = 2; affinity = sticky; } resources { heap_size = 64 * 1024 * 1024; stack_size = 64 * 1024; mailbox_capacity = 1024; } init { name = \"worker-1\"; seed = 42; } }",
            "spawn Worker() with {}",
            "spawn Worker() with { init { seed = compute(42); } resources { stack_size = 64; } }",
            "spawn Worker() with { schedule { pool = shared; } }",
            "spawn Worker() /* comment */ with { init {} }",
            "spawn Worker() with { schedule {} resources {} init {} }"
        }) reject(expr, "E_SPAWN_OPTIONS_UNSUPPORTED");
    }

    @Test void malformedGroupsGetSpecificDiagnostics() {
        reject("spawn Worker() with { unknown {} }", "E_SPAWN_OPTION_SECTION");
        reject("spawn Worker() with { init {} init {} }", "E_SPAWN_OPTION_DUPLICATE");
        reject("spawn Worker() with { init { seed = 1; seed = 2; } }", "E_SPAWN_OPTION_DUPLICATE");
        reject("spawn Worker() with { schedule { bogus = 1; } }", "E_SPAWN_OPTION_KEY");
        reject("spawn Worker() with { resources { bogus = 1; } }", "E_SPAWN_OPTION_KEY");
        reject("spawn Worker() with { init { seed => 42; } }", "expected '=' after spawn option key");
        reject("spawn Worker() with { init { seed = 42 } }", "expected ';' after spawn option value");
    }

    @Test void unrelatedSyntaxIsNotCaptured() {
        assertDoesNotThrow(() -> Parser.parse(program("spawn Worker()")));
        assertDoesNotThrow(() -> Parser.parse(program("p with { x => 2 }")));
        assertDoesNotThrow(() -> Parser.parse(program("\"spawn Worker() with {}\"")));
    }
}
