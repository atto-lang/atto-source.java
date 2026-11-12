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

/** Real native .atto main() output projection through bounded ActorOutput. */
@Timeout(30)
final class NamespaceActorGuestOutputReadbackTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/unleased-source-output-readback.atto"));
    }

    private static long execute(String source) throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            var text = Source.newBuilder(OresLanguage.ID, source, "native-output-main.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            return context.eval(text).asLong();
        }
    }

    @Test
    void guestMainReadsActualActorOutputRatherThanHardcodedSourceReturn() throws Exception {
        assertEquals(41L, execute(source()));
        assertEquals(98L, execute(source().replace("a.send(41);", "a.send(98);")));
        assertEquals(7L, execute(source().replace(
                "return a.output_value();", "return b.output_value();")));
    }

    @Test
    void repeatedContextEvalRetiresActorsAndAllocatesFreshCodeImages() throws Exception {
        String actor = source();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Source unit = Source.newBuilder(OresLanguage.ID, actor, "repeated-output.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertEquals(41L, context.eval(unit).asLong());
            assertEquals(41L, context.eval(unit).asLong());
        }
    }

    @Test
    void outputProjectionRejectsMissingMessageForgedReceiverAndUnverifiedEffects()
            throws Exception {
        String original = source();
        String[] bad = {
                original.replace("a.send(41);", ""),
                original.replace("a.send(41);", "a.send(41); a.send(41);"),
                original.replace("return a.output_value();", "return a.output_value(1);"),
                original.replace("return a.output_value();", "return a.send(5);"),
                original.replace("return a.output_value();", "return other.output_value();"),
                original.replace("return a.output_value();", "return a.state.received;"),
                original.replace("return a.output_value();", "return a.output_value() + 1;"),
                original.replace("p.send(mail.value);", "p.send(1);"),
                original.replace("p.send(mail.value);", "p.send(p.state.received);"),
                original.replace("p.send(mail.value);", ""),
                original.replace("p.send(mail.value);", "other.send(mail.value);"),
                original.replace("p.debit(mail.value);\n    p.send(mail.value);",
                        "p.send(mail.value);\n    p.debit(mail.value);"),
                original.replace("const a = spawn Meter();", "let a = spawn Meter();"),
                original.replace("b.send(7);", "b.send(7); b.send(8);"),
                original.replace("return a.output_value();", "return a.output_value(); a.send(2);")
        };
        for (int i = 0; i < bad.length; i++) {
            final int idx = i;
            assertThrows(RuntimeException.class, () -> execute(bad[idx]),
                    "unverified guest output operation " + i);
        }
    }

    @Test
    void ordinaryTypeCheckerStaysFailClosedWithoutClosedWorldVerification()
            throws Exception {
        var denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source())));
        assertTrue(denied.getMessage().contains("LOWERING_UNSUPPORTED"),
                denied.getMessage());
    }
}
