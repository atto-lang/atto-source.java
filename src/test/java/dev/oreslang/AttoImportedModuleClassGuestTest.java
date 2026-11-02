package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AttoMQ's 2026-11-02 exact-run guest diagnostic was
 * "unknown class transport_contract.Request", after --check succeeded.
 * These are real linked .atto guest tests, not host source-shape assertions.
 * Leave the positive module-qualified regression red until the source runtime
 * implements its documented public class/namespace semantics.
 */
final class AttoImportedModuleClassGuestTest {
    @TempDir Path temp;

    private String run(Path entry) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                err);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void canonicalAttoPublicFileRootClassImportsAndRuns() throws Exception {
        Files.writeString(temp.resolve("request.atto"), """
                pub define class Request as
                  pub val int value;
                  pub constructor(int value) { self.value = value; }
                end
                """);
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import class Request from "./request.atto";
                pub fnc main(): void {
                  val Request request = new Request(7);
                  stdio.stdout.write(request.value);
                  return;
                }
                """);
        assertEquals("7", run(entry));
    }

    @Test
    void canonicalAttoPublicClassInsideImportedModuleResolvesAtExecution() throws Exception {
        Files.writeString(temp.resolve("transport_contract.atto"), """
                define module transport_contract as
                  pub define class Request as
                    pub val int value;
                    pub constructor(int value) { self.value = value; }
                  end
                end
                """);
        Path entry = temp.resolve("main.atto");
        Files.writeString(entry, """
                import module transport_contract from "./transport_contract.atto";
                pub fnc main(): void {
                  val transport_contract.Request request = new transport_contract.Request(9);
                  stdio.stdout.write(request.value);
                  return;
                }
                """);
        assertEquals("9", run(entry));
    }
}
