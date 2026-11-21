package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Run ONLY in the host-only job, before building the native language image.
 * Do not include this inverted fixture in the real-isolate E2E gate.
 */
final class SharedUntrustedActorSupervisorNoImageTest {
    @Test
    void missingNativeIsolateNeverFallsBackToTrustedJvm() {
        String worker = """
                define untrusted actor Sandboxed as
                  receive(ActorMail<String> mail): void { self.end(); return; }
                end
                """;
        try (var host = new SharedUntrustedActorSupervisor(256L << 20, 1)) {
            assertThrows(RuntimeException.class,
                    () -> host.spawn(worker, "missing-image", "Sandboxed"));
            assertEquals(0, host.activeActors());
        }
    }
}
