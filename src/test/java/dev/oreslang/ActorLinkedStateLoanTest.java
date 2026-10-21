package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ActorLinkedStateLoanTest {
    @TempDir Path root;

    @Test
    void linkedImportedGuardCanRunInsideSchedulerDispatchedStateLoan()
            throws Exception {
        Path helper = root.resolve("guard.ores");
        Path main = root.resolve("main.ores");
        Files.writeString(helper, """
                pub fnc admitted(int revision): bool {
                  return revision == 0;
                }
                """);
        Files.writeString(main, """
                import fnc admitted from "./guard.ores";

                define actor StateAuthority as
                  state {
                    revision: int = 0;
                  }

                  receive(ActorMail<String> mail): void {
                    self.handle_state(|state| -> {
                      if admitted(state.revision); do
                        state.revision = state.revision + 1;
                      fi
                    });
                    self.handle_state(|state| -> {
                      if state.revision == 1; do
                        stdio.stdout.write("linked-state-loan-ok");
                      fi
                    });
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val state = spawn StateAuthority();
                  await state.get_ready();
                  state.send("commit");
                  await state.get_done();
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertEquals(
                "linked-state-loan-ok",
                out.toString(StandardCharsets.UTF_8));
    }
}
