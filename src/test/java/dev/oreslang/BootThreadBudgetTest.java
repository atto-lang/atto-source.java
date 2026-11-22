package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Boot thread budget for one Oreslang context: two CONTROL workers, no actor
 * carriers until an actor exists, and at most the turn watchdog on top once
 * code has run. Counts only threads this context created (diffed by id), so
 * other contexts alive in the same JVM do not affect it.
 */
@Timeout(60)
final class BootThreadBudgetTest {
    private static final String MAIN = "pub fnc main(): int {\n  return 42;\n}\n";

    private static Set<Long> liveThreadIds() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive).map(Thread::threadId).collect(Collectors.toSet());
    }

    /** New Oreslang threads since the baseline, grouped by name with digits folded. */
    private static Map<String, Integer> newOresThreads(Set<Long> baseline) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isAlive() || baseline.contains(thread.threadId())) continue;
            if (!thread.getName().startsWith("ores-")) continue;
            counts.merge(thread.getName().replaceAll("\\d+", "#"), 1, Integer::sum);
        }
        return counts;
    }

    private static int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static int actorCarriers(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .filter(entry -> entry.getKey().contains("actor-dispatcher"))
                .mapToInt(Map.Entry::getValue).sum();
    }

    @Test
    void contextBootsWithTwoControlWorkersAndNoActorCarriers() throws Exception {
        // Warm once so one-time Truffle/JVM service threads are in the baseline.
        try (Context warm = Context.newBuilder("ores").allowAllAccess(false).build()) {
            assertEquals(42L, warm.eval(Source.newBuilder("ores", MAIN, "warm.ores").build()).asLong());
        }
        Set<Long> baseline = liveThreadIds();
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            context.initialize("ores");
            Map<String, Integer> boot = newOresThreads(baseline);
            assertEquals(2, boot.getOrDefault("ores-control-plane-#", 0), "CONTROL workers at boot: " + boot);
            assertEquals(0, actorCarriers(boot), "actor carriers at boot: " + boot);
            assertTrue(total(boot) <= 3, "Oreslang threads at boot: " + boot);

            assertEquals(42L, context.eval(Source.newBuilder("ores", MAIN, "main.ores").build()).asLong());
            Map<String, Integer> ran = newOresThreads(baseline);
            assertEquals(0, actorCarriers(ran), "a program without actors started actor carriers: " + ran);
            assertTrue(total(ran) <= 3, "Oreslang threads after main: " + ran);
        }
    }
}
