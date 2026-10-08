package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actor entry is a VM operation, never a public method or a super-call. */
final class ActorNoPublicMethodsTest {
    @Test
    void publicInstanceMethodIsRejected() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker as
                  pub fnc helper(): void { return; }
                  receive(ActorMail<String> mail): void { return; }
                end
                """));
        assertTrue(rejected.getMessage().contains("actor methods cannot be public"));
    }

    @Test
    void publicStaticMethodIsRejected() {
        var rejected = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker as
                  pub static fnc helper(): void { return; }
                  receive(ActorMail<String> mail): void { return; }
                end
                """));
        assertTrue(rejected.getMessage().contains("actor methods cannot be public"));
    }

    @Test
    void publicReceiveAndStartupHooksAreRejected() {
        for (String method : new String[]{
                "pub receive(ActorMail<String> mail): void { return; }",
                "pub on_start(): void { return; }"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    Parser.parse("define actor Worker as\n" + method
                            + "\nreceive(ActorMail<String> mail): void { return; }\nend"));
        }
    }

    @Test
    void privateHooksAndHelpersAreAllowed() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  on_start(): void { return; }
                  helper(): void { return; }
                  receive(ActorMail<String> mail): void {
                    self.helper();
                    return;
                  }
                end
                """)));
    }

    @Test
    void actorCannotCallSuper() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    super.receive(mail);
                    return;
                  }
                end
                """));
    }

    @Test
    void publicActorTypeIsNotAPublicMethod() {
        assertDoesNotThrow(() -> Parser.parse("""
                actor pub Worker {
                  receive(ActorMail<String> mail): void { return; }
                }
                """));
    }
}
