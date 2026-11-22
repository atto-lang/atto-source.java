package dev.oreslang.launcher;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.runtime.ActorRuntime;
import java.util.Set;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
final class ThreadBudgetFlagsTest {
    private static final String CONTROL = "ores.runtime.control.parallelism";
    private static final String ACTOR_MAX = "ores.runtime.actor.max-carriers";
    private String previousControl;
    private String previousActorMax;

    @BeforeEach
    void save() {
        previousControl = System.getProperty(CONTROL);
        previousActorMax = System.getProperty(ACTOR_MAX);
    }

    @AfterEach
    void restore() {
        if (previousControl == null) System.clearProperty(CONTROL);
        else System.setProperty(CONTROL, previousControl);
        if (previousActorMax == null) System.clearProperty(ACTOR_MAX);
        else System.setProperty(ACTOR_MAX, previousActorMax);
    }

    @Test
    void flagsSetRuntimeProperties() {
        OresMain.applyThreadBudget("1", "4");
        assertEquals("1", System.getProperty(CONTROL));
        assertEquals("4", System.getProperty(ACTOR_MAX));
        var config = ActorRuntime.DispatcherConfig.defaults();
        assertEquals(1, config.privateParallelism());
        assertEquals(2, config.sharedParallelism());

        OresMain.applyThreadBudget(null, "auto");
        assertNull(System.getProperty(ACTOR_MAX), "auto restores the CPU-derived budget");
        assertEquals("1", System.getProperty(CONTROL), "an absent flag leaves its property alone");
    }

    @Test
    void invalidValuesAreUsageErrors() {
        for (String bad : new String[] { "0", "-1", "65", "two", "" }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresMain.applyThreadBudget(bad, null), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> OresMain.applyThreadBudget(null, "0"));
        assertThrows(IllegalArgumentException.class, () -> OresMain.applyThreadBudget(null, "1025"));
        assertNull(System.getProperty(ACTOR_MAX));
    }

    @Test
    void minimalModeRunsAProgramOnOneControlWorker() throws Exception {
        String main = "pub fnc main(): int {\n  return 7;\n}\n";
        try (Context warm = Context.newBuilder("ores").allowAllAccess(false).build()) {
            warm.eval(Source.newBuilder("ores", main, "warm.ores").build());
        }
        OresMain.applyThreadBudget("1", null);
        Set<Long> baseline = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive).map(Thread::threadId).collect(Collectors.toSet());
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            assertEquals(7L, context.eval(Source.newBuilder("ores", main, "minimal.ores").build()).asLong());
            long control = Thread.getAllStackTraces().keySet().stream()
                    .filter(Thread::isAlive)
                    .filter(thread -> !baseline.contains(thread.threadId()))
                    .filter(thread -> thread.getName().startsWith("ores-control-plane-"))
                    .count();
            assertEquals(1, control);
        }
    }
}
