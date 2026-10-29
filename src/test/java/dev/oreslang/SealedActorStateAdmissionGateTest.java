package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SealedActorStateAdmissionGateTest {

    @Test
    void rejectedUntilStateSchemasAreLoweredNotMisparsedAsMethods() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define isolated actor Coordinator as
                          state {
                            phase: String = "created";
                          }
                          receive(ActorMail<String> mail): void {
                            val response = self.handle_state(critical |s| -> {
                              s.phase = "running";
                              return s.phase;
                            });
                            self.send(response);
                            return;
                          }
                        end
                        """));
        assertTrue(failure.getMessage().contains("requires sealed-state"), failure.getMessage());
        assertTrue(failure.getMessage().contains("not yet supported"), failure.getMessage());
    }

    @Test
    void actorCannotForgeRuntimeSealedStateIntrinsic() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define isolated actor Forged as
                          pub handle_state(): bool { return true; }
                        end
                        """));
        assertTrue(failure.getMessage().contains("sealed runtime actor intrinsic"),
                failure.getMessage());
    }

    @Test
    void statelessActorsRemainAdmittedWhileStateLoanIsUnimplemented() {
        assertDoesNotThrow(() -> Parser.parse("""
                define isolated actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send(mail.value);
                    return;
                  }
                end
                """));
    }
}
