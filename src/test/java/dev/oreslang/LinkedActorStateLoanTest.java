package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class LinkedActorStateLoanTest {
    @TempDir Path temp;

    @Test
    void handleStateCannotEscapeAsFirstClassMemberValue() throws Exception {
        Path entry = temp.resolve("capture-state-loan.ores");
        Files.writeString(entry, """
                define actor CaptureStateActor as
                  state {
                    revision: int = 0;
                  }

                  receive(ActorMail<String> mail): void {
                    const escaped = self.handle_state;
                    return;
                  }
                end
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains(
                "self.handle_state is a direct-call-only sealed state-loan intrinsic"),
                failure.getMessage());
    }

    @Test
    void linkedActorCanUseImportedCallableInsideHandleStateLoan() throws Exception {
        Path src = temp.resolve("src");
        Path examples = temp.resolve("examples");
        Files.createDirectories(src);
        Files.createDirectories(examples);

        Files.writeString(src.resolve("helper.ores"), """
                define module helper as
                  pub fnc next_revision(int current): int {
                    return current + 1;
                  }
                end
                """);

        Path entry = examples.resolve("linked-state-loan.ores");
        Files.writeString(entry, """
                import fnc {next_revision} from "../src/helper.ores";

                define actor LinkedStateActor as
                  state {
                    revision: int = 0;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "commit"; then
                      self.handle_state(|state| -> {
                        state.revision = next_revision(state.revision);
                      });
                    elif mail.value == "verify"; then
                      self.handle_state(|state| -> {
                        if state.revision == 1; do
                          stdio.stdout.write("linked-state-loan-ok");
                        fi
                      });
                    elif mail.value == "shutdown"; then
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val actor_ref = spawn LinkedStateActor();
                  await actor_ref.get_ready();
                  actor_ref.send("commit");
                  actor_ref.send("verify");
                  actor_ref.send("shutdown");
                  await actor_ref.get_done();
                  return;
                }
                """);

        IncrementalCompiler.BuildResult checked = LinkedProgramRunner.validate(entry);
        assertEquals(2, checked.units().size());

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        IncrementalCompiler.BuildResult executed = LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                errors);

        assertEquals(2, executed.units().size());
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
        assertEquals("linked-state-loan-ok", output.toString(StandardCharsets.UTF_8));
    }
}
