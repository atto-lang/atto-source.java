package dev.oreslang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.ExecutionProfile;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ActorNativeObjectAllocationTest {
    @TempDir Path temp;
    @Test
    void actorStateAndBothConstructorPathsSurviveSuspensionAndMutation() throws Exception {
        for (String actorKind : new String[] {"actor", "isoactor"}) {
            Files.writeString(temp.resolve("model.ores"), """
                    define pub class ArenaLeaf as
                      pub let int value;
                      pub constructor(int value) { self.value = value; }
                    end
                    define pub class ArenaEnvelope as
                      pub val ArenaLeaf leaf;
                      pub val string label;
                    end
                    """);
            String program = """
                    import class ArenaLeaf from "./model.ores";
                    import class ArenaEnvelope from "./model.ores";
                    define %s Worker as
                      let mut ArenaEnvelope state;
                      on_start(): void {
                        self.state = new ArenaEnvelope(new ArenaLeaf(40), "native");
                        return;
                      }
                      receive(ActorMail<String> mail): void {
                        self.state.leaf.value = self.state.leaf.value + 1;
                        rt cooperate;
                        self.state.leaf.value = self.state.leaf.value + 1;
                        self.send(self.state.leaf.value);
                        self.end();
                        return;
                      }
                    end
                    pub async routine main(): void {
                      val worker = await spawn Worker();
                      worker.send("go");
                      val result = (await worker.outputs.next()).value.unwrap();
                      stdio.stdout.write(result.value);
                      await worker.get_done();
                      return;
                    }
                    """.formatted(actorKind);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Path entry = temp.resolve("main.ores");
            Files.writeString(entry, program);
            LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    Set.of(), Map.of(), output, new ByteArrayOutputStream());
            assertEquals("42", output.toString(StandardCharsets.UTF_8), actorKind);
        }
    }
    @Test
    void actorMayUseSharedHelperCodeAndImmutableModuleData() throws Exception {
        String program = """
                define module constants
                  pub val int answer = 42;
                end

                fnc shared_helper(): int {
                  return constants.answer;
                }

                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send(shared_helper());
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  val result = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(result.value);
                  await worker.get_done();
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Path entry = temp.resolve("immutable-module.ores");
        Files.writeString(entry, program);
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), output, new ByteArrayOutputStream());
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void helperCalledByActorCannotAcquireMutableModuleBinding() throws Exception {
        String program = """
                define module ambient
                  pub let int counter = 41;
                end

                fnc shared_helper(): int {
                  return ambient.counter;
                }

                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send(shared_helper());
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """;
        Path entry = temp.resolve("mutable-module.ores");
        Files.writeString(entry, program);

        RuntimeException denied = assertThrows(RuntimeException.class, () ->
                LinkedProgramRunner.run(
                        entry,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(
                messageChainContains(denied, "actor execution cannot access mutable module binding"),
                denied.toString());
    }

    private static boolean messageChainContains(Throwable failure, String needle) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(needle)) return true;
        }
        return false;
    }

}
