package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime-owned mailbox receive is never a public actor receiver API. */
final class ActorMailboxContractShapeTest {
    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    private static String contract(String signature) {
        return "define contract MailboxApi as\n"
                + "  actor Worker;\n"
                + "  " + signature + "\n"
                + "end\n";
    }

    private static void rejects(String signature) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> check(contract(signature)));
        assertTrue(error.getMessage().contains("actor mailbox receiver contract 'receive'"),
                error.getMessage());
    }

    @Test
    void mailboxHookUsesPrivateActorMailAndVoid() {
        assertDoesNotThrow(() -> check(contract(
                "mtd receive(Worker worker)(ActorMail<int> mail): void;")));
    }

    @Test
    void publicReceiveCannotBecomeAnActorApi() {
        rejects("pub mtd receive(Worker worker)(ActorMail<int> mail): void;");
    }

    @Test
    void wrongMailboxPayloadTypeIsRejectedBeforeWitnessEmission() {
        rejects("mtd receive(Worker worker)(int value): void;");
    }

    @Test
    void missingMailboxParameterIsRejected() {
        rejects("mtd receive(Worker worker)(): void;");
    }

    @Test
    void mailboxHookCannotReturnAnAcknowledgementValue() {
        rejects("mtd receive(Worker worker)(ActorMail<int> mail): int;");
    }

    @Test
    void mailboxHookCannotAcceptMultipleMessagesAsArguments() {
        rejects("mtd receive(Worker worker)(ActorMail<int> first, ActorMail<int> second): void;");
    }

    @Test
    void runtimeMailboxConformanceChecksTheExistingActorMethod() {
        assertDoesNotThrow(() -> check(contract(
                "mtd receive(Worker worker)(ActorMail<int> mail): void;")
                + "define module workers conforms MailboxApi as\n"
                + "  pub define actor Worker as\n"
                + "    receive(ActorMail<int> mail): void { return; }\n"
                + "  end\n"
                + "end\n"));
    }
}
