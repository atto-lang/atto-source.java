package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level admission proof for actor-only hot-reload generations.
 * Linking declarations must not execute module main or actor on_start.
 */
@Timeout(30)
final class HotReloadActorGenerationLinkTest {
    @Test
    void actorOnlyGenerationLinksReceiptsWithoutRunningMainAndPinsLifetime() {
        String source = """
                define actor Counter as
                  let int count;

                  on_start(): void {
                    self.count = 1;
                    return;
                  }

                  receive(ActorMail<int> mail): void {
                    self.count = self.count + mail.value;
                    return;
                  }
                end

                pub routine main(): void {
                  val should_not_run_for_actor_route = 101;
                  return;
                }
                """;

        try (var manager = new HotReloadManager(
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit())) {
            var generation =
                    manager.stageActorGeneration("counter-hot-v1.ores", source);
            assertFalse(generation.started());
            assertFalse(generation.closed());
            assertNull(manager.active("counter-hot-v1.ores"),
                    "actor-only staging must not publish the generic source route");

            var view = generation.linkActorGeneration("Counter");
            assertSame(generation, view.generation());
            assertSame(view.context().actors(), view.runtime());
            assertEquals("Counter", view.actorTypeName());
            assertFalse(generation.started(),
                    "LINK_ONLY must not execute module main");

            String code = view.executor().actorCodeSha256("Counter");
            assertEquals(generation.sha256(), code,
                    "actor code receipt must be the exact staged source digest");
            String schema = view.executor().actorStateSchemaSha256("Counter");
            String message = view.executor().actorMessageAbiSha256("Counter");
            assertEquals(64, code.length());
            assertEquals(64, schema.length());
            assertEquals(64, message.length());

            try (var routePin = generation.pinActorRoute()) {
                assertSame(generation, routePin.generation());
                assertThrows(IllegalStateException.class,
                        () -> manager.retire(generation.id()));
                assertThrows(IllegalStateException.class, manager::close);
                assertThrows(IllegalStateException.class, generation::close);
                assertThrows(IllegalStateException.class, generation::start,
                        "a route-pinned actor-only generation must never run main");
                assertFalse(generation.closed());
            }

            manager.retire(generation.id());
            assertTrue(generation.closed());
            assertEquals(0, manager.liveGenerations());
        }
    }

}
