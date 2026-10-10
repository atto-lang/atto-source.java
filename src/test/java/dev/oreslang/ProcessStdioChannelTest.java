package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
final class ProcessStdioChannelTest {
    @Test void aliasesAreIdenticalAndRouteBothOutputStreams() throws Exception {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        run("""
            pub async routine main(): void {
                if process.stdout != stdio.out {
                    stdio.stdout.log("stdout alias mismatch");
                    return;
                }
                if process.stderr != stdio.err {
                    stdio.stdout.log("stderr alias mismatch");
                    return;
                }
                if process.stdin != stdio.in {
                    stdio.stdout.log("stdin alias mismatch");
                    return;
                }
                await nb writech stdio.out, "one";
                await nb writech process.stdout, "two";
                await nb writech process.stderr, "diagnostic";
                await stdio.flush_async_logs();
                return;
            }
            """, "", output, error);
        assertEquals("onetwo", output.toString(StandardCharsets.UTF_8));
        assertEquals("diagnostic", error.toString(StandardCharsets.UTF_8));
    }

    @Test void stdinIsReadableAsChannelAndStream() throws Exception {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        run("""
            pub async routine main(): void {
                val first = readch stdio.in;
                val source = process.stdin.stream();
                val reader = source.get_reader();
                val second = await reader.read_async();
                reader.release_lock();
                stdio.stdout.write(first);
                stdio.stdout.write(second);
                return;
            }
            """, "alpha\nbeta\n", output, error);
        assertEquals("alphabeta", output.toString(StandardCharsets.UTF_8));
        assertEquals("", error.toString(StandardCharsets.UTF_8));
    }

    @Test void oppositeDirectionsAreRejectedWithoutGrantingHostIo() {
        assertThrows(Exception.class, () -> run("""
            pub routine main(): void {
                readch process.stdout;
                return;
            }
            """, "", new ByteArrayOutputStream(), new ByteArrayOutputStream()));

        assertThrows(Exception.class, () -> run("""
            pub routine main(): void {
                writech process.stdin, "wrong";
                return;
            }
            """, "", new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }

    @Test void aliasesTypeCheckAsChannelsAndExposeReadableStream() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
            pub async routine main(): void {
                val Channel<String> o = process.stdout;
                val Channel<String> e = stdio.err;
                val Channel<String> i = stdio.in;
                val ReadableStream<String> stream = i.stream();
                val ReadableStreamReader<String> reader = stream.get_reader();
                reader.release_lock();
                return;
            }
            """)));
    }

    private static void run(String program, String input, ByteArrayOutputStream out,
                            ByteArrayOutputStream err) throws Exception {
        var source = Source.newBuilder(OresLanguage.ID, program, "process-stdio-channels.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))
                .out(out)
                .err(err)
                .build()) {
            context.eval(source);
        }
    }
}
