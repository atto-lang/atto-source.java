package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class BytesSourceRuntimeTest {
    @Test
    void sourceBytesAreCompactIndexableAndValueSemantic() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Bytes bytes = [0, 127, 255] as Bytes;
                  val Bytes same = [0, 127, 255] as Bytes;
                  stdio.println(bytes.length);
                  stdio.println(bytes[0]);
                  stdio.println(bytes[1]);
                  stdio.println(bytes[2]);
                  stdio.println(bytes == same);
                  return;
                }
                """;

        assertEquals(
                "3\n0\n127\n255\ntrue\n",
                run(program, "bytes-basic.ores"));
    }

    @Test
    void privateActorMailboxCarriesSourceBytesWithoutListExpansion() throws Exception {
        String program = """
                define isoactor Worker as
                  receive(ActorMail<Bytes> mail): void {
                    stdio.println(mail.value.length);
                    stdio.println(mail.value[0]);
                    stdio.println(mail.value[2]);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  val Bytes payload = [1, 2, 255] as Bytes;
                  worker.send(payload);
                  await worker.get_done();
                  return;
                }
                """;

        assertEquals(
                "3\n1\n255\n",
                run(program, "bytes-private-actor.ores"));
    }

    @Test
    void sourceBytesRejectOutOfRangeOctetsAtRuntime() {
        String program = """
                pub fnc main(): void {
                  val Bytes invalid = [0, 256] as Bytes;
                  stdio.println(invalid.length);
                  return;
                }
                """;

        PolyglotException failure = assertThrows(
                PolyglotException.class,
                () -> run(program, "bytes-out-of-range.ores"));
        assertTrue(
                failure.getMessage().contains("0..255"),
                failure.getMessage());
    }

    @Test
    void sourceBytesRejectNonIntegerCollectionsStatically() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        """
                        pub fnc main(): void {
                          val Bytes invalid = ["not-a-byte"] as Bytes;
                          return;
                        }
                        """));
        assertTrue(
                failure.getMessage().contains("Bytes cast requires"),
                failure.getMessage());
    }

    @Test
    void sourceBytesRejectIndexedMutationStatically() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        """
                        pub fnc main(): void {
                          let Bytes bytes = [1, 2, 3] as Bytes;
                          bytes[0] = 9;
                          return;
                        }
                        """));
        assertTrue(
                failure.getMessage().contains("indexed assignment requires"),
                failure.getMessage());
    }

    @Test
    void optionalBytesCastIsPresentOnlyForValidOctets() throws Exception {
        String program = """
                pub fnc main(): void {
                  val good = [0, 255] as? Bytes;
                  val bad = [0, 256] as? Bytes;
                  stdio.println(good.is_some());
                  stdio.println(bad.is_some());
                  return;
                }
                """;

        assertEquals(
                "true\nfalse\n",
                run(program, "bytes-optional-cast.ores"));
    }

    private static String run(String sourceText, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, name)
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
