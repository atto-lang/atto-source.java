package dev.oreslang;

import dev.oreslang.runtime.ActorRaceInstrumentation;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Interpreter field instrumentation acceptance. Run with
 * -Dores.test.race=true (or ORES_RACE_REQUIRED=1); ordinary CI skips it.
 */
final class SourceActorRaceProbeTest {
    @Test
    void compiledGuestActorFieldOperationsReachRuntimeMonitor() throws Exception {
        Assumptions.assumeTrue(ActorRaceInstrumentation.enabled(),
                "instrumented guest execution requires test-only race activation");

        ActorRaceInstrumentation.Snapshot before =
                ActorRaceInstrumentation.runtime().snapshot();
        long locationsBefore = ActorRaceInstrumentation.runtime().trackedLocationCount();
        long epochsBefore = ActorRaceInstrumentation.runtime().trackedAccessEpochCount();
        String guest = """
                define actor Counter as
                  let int count;

                  on_start(): void {
                    self.count = 0;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    self.count = self.count + 1;
                    self.send(self.count);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val counter = spawn Counter();
                  await counter.get_ready();
                  counter.send("count");
                  val reply = (await counter.outputs.next()).value.unwrap();
                  await counter.get_done();
                  return;
                }
                """;

        Source source = Source.newBuilder(OresLanguage.ID, guest, "race-actor-fields.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            context.eval(source);
        }
        ActorRaceInstrumentation.Snapshot after =
                ActorRaceInstrumentation.runtime().snapshot();
        assertTrue(after.accesses() - before.accesses() >= 4,
                "guest field initializer, update and read must produce actual access probes");
        assertTrue(after.actorChecks() - before.actorChecks() >= 2,
                "the guest actor must enter and exit an instrumented physical turn");
        assertEquals(before.memoryRaces(), after.memoryRaces(),
                "one actor must not race with its own serialized field operations");
        assertEquals(before.actorOverlapViolations(), after.actorOverlapViolations());
        assertEquals(locationsBefore,
                ActorRaceInstrumentation.runtime().trackedLocationCount(),
                "finalized actor must retire the exact probed object identity");
        assertEquals(epochsBefore,
                ActorRaceInstrumentation.runtime().trackedAccessEpochCount(),
                "actor shutdown must release all retained field access epochs");
    }
}
