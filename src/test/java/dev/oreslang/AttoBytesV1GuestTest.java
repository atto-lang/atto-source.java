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
 * Bytes v1 (spec chapter 26): immutable, move-only owned byte strings with
 * value equality, usable as Map keys and values. Executes real guests.
 */
final class AttoBytesV1GuestTest {

    private static String run(String program) throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "bytes_v1.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8).replace("\r", "").trim();
    }

    @Test
    void constructorsSliceSearchConcatEqualityAndUtf8() throws Exception {
        assertEquals(String.join("\n", "3", "key", "6", "true", "false", "0", "4", "true", "true"),
                run("""
                        pub fnc main(): void {
                          val a = Bytes.from_string("SET key");
                          val sp = a.index_of(32, 0);
                          val cmd = a.slice(0, sp);
                          val key = a.slice(sp + 1, a.size());
                          stdio.println(cmd.size());
                          stdio.println(key.to_utf8().unwrap());
                          stdio.println(cmd.concat(key).size());
                          stdio.println(cmd.equals(Bytes.from_list([83, 69, 84])));
                          stdio.println(cmd.equals(Bytes.from_list([83, 69])));
                          stdio.println(Bytes.empty().size());
                          stdio.println(Bytes.from_list([0, 255, 13, 10]).to_list().size);
                          stdio.println(Bytes.from_list([255, 254]).to_utf8().is_none());
                          stdio.println(a.index_of(33, 0) == -1);
                          return;
                        }
                        """));
    }

    @Test
    void bytesKeyedMapUsesContentEqualityAndReadsAreIndependentCopies() throws Exception {
        assertEquals(String.join("\n", "5", "1", "2", "true", "false", "true"),
                run("""
                        pub fnc main(): void {
                          let Map<Bytes, Bytes> m = ~map<Bytes, Bytes>();
                          m.set(Bytes.from_string("k"), Bytes.from_string("value"));
                          val first = m.get_optional(Bytes.from_string("k")).unwrap();
                          first.close();
                          val second = m.get_optional(Bytes.from_string("k")).unwrap();
                          stdio.println(second.size());
                          let Map<Bytes, int> counts = ~map<Bytes, int>();
                          counts.set(Bytes.from_string("a"), 1);
                          counts.set(Bytes.from_list([97]), 2);
                          stdio.println(counts.size);
                          stdio.println(counts.get_optional(Bytes.from_string("a")).unwrap());
                          stdio.println(m.delete(Bytes.from_string("k")));
                          stdio.println(m.delete(Bytes.from_string("k")));
                          val kept = Bytes.from_string("z");
                          m.set(kept.copy(), Bytes.from_string("1"));
                          stdio.println(m.get_optional(kept).is_some());
                          return;
                        }
                        """));
    }

    @Test
    void bytesMoveIntoAsyncCallsAndCollectionSizeBindsAsScalar() throws Exception {
        // Bytes (alone or inside a List) crosses an async call by move, and
        // `val int n = list.size` is a scalar read, not a mutable list alias.
        assertEquals(String.join("\n", "7", "2"), run("""
                pub async fnc total(List<Bytes> args): int {
                  val int count = args.size;
                  let int sum = 0;
                  let int i = 0;
                  loop {
                    if i == count then break; fi
                    sum = sum + args.get(i).copy().size();
                    i = i + 1;
                  }
                  return sum;
                }
                pub async fnc width(Bytes b): int { return b.size(); }
                pub async routine main(): void {
                  let mut List<Bytes> parts = [];
                  parts.add(Bytes.from_string("abc"));
                  parts.add(Bytes.from_string("defg"));
                  stdio.println(await total(parts));
                  stdio.println(await width(Bytes.from_list([1, 2])));
                  return;
                }
                """));
    }

    @Test
    void bytesAreMoveOnly() {
        RuntimeException moved = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          let Map<Bytes, Bytes> m = ~map<Bytes, Bytes>();
                          val v = Bytes.from_string("v");
                          m.set(Bytes.from_string("k"), v);
                          stdio.println(v.size());
                          return;
                        }
                        """));
        assertTrue(moved.getMessage().contains("use of moved value 'v'"), moved.getMessage());
    }

    @Test
    void invalidBytesFailClosedAtRuntime() {
        PolyglotException range = assertThrows(PolyglotException.class,
                () -> run("pub fnc main(): void { val b = Bytes.from_list([1, 256]); return; }"));
        assertTrue(range.getMessage().contains("must be an integer 0..255"), range.getMessage());
        PolyglotException slice = assertThrows(PolyglotException.class,
                () -> run("pub fnc main(): void { val b = Bytes.from_string(\"abc\"); val c = b.slice(2, 4); return; }"));
        assertTrue(slice.getMessage().contains("outside 0..3"), slice.getMessage());
    }

    @Test
    void unknownBytesConstructorIsRejected() {
        RuntimeException unknown = assertThrows(RuntimeException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "pub fnc main(): void { val b = Bytes.from_hex(\"00\"); return; }"));
        assertTrue(unknown.getMessage().contains("unknown Bytes constructor"), unknown.getMessage());
    }
}
