package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.HotReloadManager;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL .atto guest execution (Truffle/GraalVM) plus loader publication checks.
 * Each version runs its own actor and verifies that actor's output. These
 * tests do NOT claim one ActorRef survives a replacement; that requires the
 * logical mailbox/physical ActorRuntime cutover to be wired separately.
 */
final class HotReloadServingGuestCurrentMainTest {
    private static final ExecutionProfile PROFILE = ExecutionProfile.serverJit();
    private static final String CODE_UNIT = "counter.atto";
    private static final String FAILING = """
            pub routine main(): void {
              val values = arr[1];
              val bad = values[99];
              return;
            }
            """;

    private static String example(String name) throws Exception {
        return Files.readString(Path.of("examples", "hot-upgrade", name));
    }

    @Test
    void realAttoActorGenerationsExecuteAndOnlyStartedGenerationServes()
            throws Exception {
        String v1 = example("counter-v1.atto");
        String v2 = example("counter-v2.atto");
        assertNotEquals(v1, v2);

        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var old = manager.loadAndStart(CODE_UNIT, v1);
            assertTrue(old.started());
            assertFalse(old.closed());
            assertSame(old, manager.serving());
            assertSame(old, manager.serving(CODE_UNIT));

            var proposed = manager.load(CODE_UNIT, v2);
            assertFalse(proposed.started());
            assertSame(proposed, manager.active(CODE_UNIT));
            assertSame(old, manager.serving(CODE_UNIT),
                    "a staged but not yet started code image cannot serve guest work");
            assertEquals(2, manager.liveGenerations());

            proposed.start(); // Executes real Counter v2 actor to completion.
            assertSame(proposed, manager.serving(CODE_UNIT));
            assertNotSame(old.context(), proposed.context());
            assertNotEquals(old.sha256(), proposed.sha256());
            old.close();
            assertSame(proposed, manager.serving(CODE_UNIT),
                    "retiring v1 cannot erase v2's newer publication");
            assertEquals(1, manager.liveGenerations());

            proposed.close();
            assertNull(manager.serving(CODE_UNIT),
                    "closing published v2 must not automatically resurrect retired v1");
            assertTrue(manager.servingGenerations().isEmpty());
        }
    }

    @Test
    void failingGuestStartupNeverReplacesHealthyServingActorImage()
            throws Exception {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var healthy = manager.loadAndStart(CODE_UNIT, example("counter-v1.atto"));
            assertThrows(RuntimeException.class, () ->
                    manager.loadAndStart(CODE_UNIT, FAILING));
            assertSame(healthy, manager.serving(CODE_UNIT));
            assertFalse(healthy.closed());
            assertEquals(1, manager.liveGenerations());

            var unstarted = manager.load(CODE_UNIT, example("counter-v2.atto"));
            assertSame(healthy, manager.serving(CODE_UNIT));
            unstarted.close();
            assertSame(healthy, manager.serving(CODE_UNIT));
            assertEquals(1, manager.liveGenerations());
        }
    }

    @Test
    void failedFirstStartupNeverPublishesServingRoute() {
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            assertThrows(RuntimeException.class, () -> manager.loadAndStart(CODE_UNIT, FAILING));
            assertNull(manager.serving());
            assertNull(manager.serving(CODE_UNIT));
            assertTrue(manager.servingGenerations().isEmpty());
            assertEquals(0, manager.liveGenerations());
        }
    }

    @Test
    void independentCodeUnitsHaveIndependentServingAuthority() {
        String source = "pub routine main(): void { return; }";
        try (var manager = new HotReloadManager(IsolatePolicy.developer(), PROFILE)) {
            var a = manager.loadAndStart("a.atto", source);
            var b = manager.loadAndStart("b.atto", source);
            assertEquals(2, manager.servingGenerations().size());
            a.close();
            assertNull(manager.serving("a.atto"));
            assertSame(b, manager.serving("b.atto"));
            b.close();
            assertNull(manager.serving());
            assertTrue(manager.servingGenerations().isEmpty());
        }
    }
}
