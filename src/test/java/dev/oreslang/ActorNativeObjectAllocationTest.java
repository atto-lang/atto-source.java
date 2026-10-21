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
}
