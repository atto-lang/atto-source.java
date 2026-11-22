package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bytes v1.1 codec helpers (spec chapter 26): big-endian integers, LEB128
 * and zigzag varints, CRC-32C/CRC-32 and single-pass join. Standard vectors.
 */
final class AttoBytesCodecGuestTest {

    private static String run(String program) throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "bytes_codec.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    @Test
    void bigEndianIntegersRoundTrip() throws Exception {
        assertEquals(String.join("\n", "18", "52", "4660", "-2", "4294967294", "-5", "-32768", "2147483647"),
                run("""
                        pub fnc main(): void {
                          val a = Bytes.from_u16_be(4660);
                          stdio.println(a.byte_at(0));
                          stdio.println(a.byte_at(1));
                          stdio.println(a.read_u16_be(0));
                          val neg = Bytes.from_list([255, 255, 255, 254]);
                          stdio.println(neg.read_i32_be(0));
                          stdio.println(neg.read_u32_be(0));
                          stdio.println(Bytes.from_i64_be(-5).read_i64_be(0));
                          stdio.println(Bytes.from_i16_be(-32768).read_i16_be(0));
                          stdio.println(Bytes.from_i32_be(2147483647).read_i32_be(0));
                          return;
                        }
                        """));
    }

    @Test
    void varintsMatchProtobufAndKafkaEncodings() throws Exception {
        assertEquals(String.join("\n", "172", "2", "300", "2", "1", "2", "127", "2", "-12345", "0"),
                run("""
                        pub fnc main(): void {
                          val v = Bytes.from_uvarint(300);
                          stdio.println(v.byte_at(0));
                          stdio.println(v.byte_at(1));
                          [const value, const next] = v.read_uvarint(0);
                          stdio.println(value);
                          stdio.println(next);
                          stdio.println(Bytes.from_zigzag_varint(-1).byte_at(0));
                          stdio.println(Bytes.from_zigzag_varint(1).byte_at(0));
                          stdio.println(Bytes.from_zigzag_varint(-64).byte_at(0));
                          stdio.println(Bytes.from_zigzag_varint(64).size());
                          [const zv, const zn] = Bytes.from_zigzag_varint(-12345).read_zigzag_varint(0);
                          stdio.println(zv);
                          [const zero, const after] = Bytes.from_uvarint(0).read_uvarint(0);
                          stdio.println(zero);
                          return;
                        }
                        """));
    }

    @Test
    void checksumsAndJoinMatchStandardVectors() throws Exception {
        // "123456789": CRC-32C 0xE3069283, CRC-32 0xCBF43926.
        assertEquals(String.join("\n", "3808858755", "3421780262", "abcd", "0"),
                run("""
                        pub fnc main(): void {
                          val digits = Bytes.from_string("123456789");
                          stdio.println(digits.crc32c(0, 9));
                          stdio.println(digits.crc32(0, 9));
                          let mut List<Bytes> parts = [];
                          parts.add(Bytes.from_string("ab"));
                          parts.add(Bytes.from_string("cd"));
                          stdio.println(Bytes.join(parts).to_utf8().unwrap());
                          let mut List<Bytes> none = [];
                          stdio.println(Bytes.join(none).size());
                          return;
                        }
                        """));
    }

    @Test
    void codecsFailClosed() {
        String[][] cases = {
                {"pub fnc main(): void { val b = Bytes.from_u16_be(70000); return; }", "outside 0..65535"},
                {"pub fnc main(): void { val b = Bytes.from_u8(-1); return; }", "outside 0..255"},
                {"pub fnc main(): void { val n = Bytes.from_list([1, 2, 3]).read_u32_be(0); return; }", "needs 4 bytes"},
                {"pub fnc main(): void { [const v, const n] = Bytes.from_list([128]).read_uvarint(0); return; }", "truncated"},
                {"pub fnc main(): void { val n = Bytes.from_string(\"ab\").crc32c(1, 3); return; }", "crc32c(1, 3)"},
                {"pub fnc main(): void { val b = Bytes.from_uvarint(-1); return; }", "non-negative"},
        };
        for (String[] c : cases) {
            PolyglotException failure = assertThrows(PolyglotException.class, () -> run(c[0]), c[0]);
            assertTrue(failure.getMessage().contains(c[1]), c[0] + " -> " + failure.getMessage());
        }
    }
}
