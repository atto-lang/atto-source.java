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

    @Test
    void qualifiedConstructorCannotBindToUnrelatedLocalHomonym() throws Exception {
        Files.writeString(temp.resolve("transport_contract.atto"), """
                define module transport_contract as
                  pub define class Request as
                    pub val int value;
                    pub constructor(int value) { self.value = value; }
                  end
                end
                """);
        Path entry = temp.resolve("homonym.atto");
        Files.writeString(entry, """
                import module transport_contract from "./transport_contract.atto";
                define class Request as
                  pub val int value;
                  constructor(int value) { self.value = 999; }
                end
                pub fnc main(): void {
                  val transport_contract.Request request = new transport_contract.Request(42);
                  stdio.stdout.write(request.value);
                  return;
                }
                """);
        assertEquals("42", run(entry));
    }

    @Test
    void privateClassInImportedModuleIsNotConstructible() throws Exception {
        Files.writeString(temp.resolve("transport_contract.atto"), """
                define module transport_contract as
                  define class Hidden as
                    constructor() { return; }
                  end
                end
                """);
        Path entry = temp.resolve("private.atto");
        Files.writeString(entry, """
                import module transport_contract from "./transport_contract.atto";
                pub fnc main(): void {
                  val hidden = new transport_contract.Hidden();
                  return;
                }
                """);
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> run(entry));
    }

    @Test
    void unknownModuleAliasNeverResolvesLocalClass() throws Exception {
        Files.writeString(temp.resolve("transport_contract.atto"), """
                define module transport_contract as
                  pub define class Request as
                    pub val int value;
                  end
                end
                """);
        Path entry = temp.resolve("unknown-owner.atto");
        Files.writeString(entry, """
                import module transport_contract from "./transport_contract.atto";
                define class Request as
                  val int value;
                end
                pub fnc main(): void {
                  val request = new unrelated.Request(9);
                  return;
                }
                """);
        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> run(entry));
    }
}
