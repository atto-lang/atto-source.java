package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler-checked native actor arithmetic result observed by guest main. */
@Timeout(30)
final class NamespaceActorComputedOutputGuestTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/checked-computed-output-readback.atto"));
    }

    private static long run(String code) throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            var input = Source.newBuilder(OresLanguage.ID, code, "computed-native-output.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            return context.eval(input).asLong();
        }
    }

    @Test
    void outputIsComputedInActorNotLiteralOrHostDiagnostic() throws Exception {
        assertEquals(42L, run(source()));
        assertEquals(99L, run(source().replace("a.send(41);", "a.send(98);")));
        assertEquals(8L, run(source().replace(
                "return a.output_value();", "return b.output_value();")));
    }

    @Test
    void sourceActorResultIsRecomputedOnFreshContextExecution() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Source input = Source.newBuilder(OresLanguage.ID, source(), "actor-recompute.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertEquals(42L, context.eval(input).asLong());
            assertEquals(42L, context.eval(input).asLong());
        }
    }

    @Test
    void invalidComputedOutputSourceDoesNotMintActorOutputAuthority() throws Exception {
        String source = source();
        String[] wrong = {
                source.replace("p.send(mail.value + 1);", "p.send(mail.value * 2);"),
                source.replace("p.send(mail.value + 1);", "p.send(mail.value + delta);"),
                source.replace("p.send(mail.value + 1);", "p.send(mail.value + p.state.received);"),
                source.replace("p.send(mail.value + 1);", "p.send(other.value + 1);"),
                source.replace("p.send(mail.value + 1);", "other.send(mail.value + 1);"),
                source.replace("p.send(mail.value + 1);", "p.send(mail.value + 1, 2);"),
                source.replace("p.send(mail.value + 1);", "p.send(mail.value + 1); p.send(mail.value);"),
                source.replace("p.send(mail.value + 1);", ""),
                source.replace("p.debit(mail.value);\n    p.send(mail.value + 1);",
                        "p.send(mail.value + 1);\n    p.debit(mail.value);")
        };
        for (int i = 0; i < wrong.length; i++) {
            int id = i;
            assertThrows(RuntimeException.class, () -> run(wrong[id]),
                    "mutant " + i + " must fail closed");
        }
    }

    @Test
    void unsafeExtraOutputStillRejectedByUnmodifiedTypeChecker() throws Exception {
        var denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source())));
        assertTrue(denied.getMessage().contains("LOWERING_UNSUPPORTED"),
                denied.getMessage());
    }
}
