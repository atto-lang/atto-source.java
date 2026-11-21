package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Owned bounded Stream<T> handoff through an awaited CPU Task. */
final class TaskStreamReturnTest {
    private static String execute(String source) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "task-stream.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(guest);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    void awaitedCpuTaskReturnsStreamWithLiveBufferAfterTaskCompletes() throws Exception {
        String source = """
                fnc calculate(): Stream<int> {
                    val Stream<int> source = Stream.new<int>(2);
                    val bool accepted = try writech(source, 42);
                    return source;
                }

                pub routine main(): void {
                    let Stream<int> stream = await cpu task {
                        return calculate();
                    };
                    let Option<int> item = try readch(stream);
                    stdio.stdout.write(item.unwrap());
                    stream.close();
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", execute(source));
    }

    @Test
    void awaitedFutureStreamUsesSameOwnedHandle() throws Exception {
        assertEquals("7", execute("""
                pub routine main(): void {
                    let Future<Stream<int>> pending = cpu task {
                        val Stream<int> output = Stream.new<int>(1);
                        val bool inserted = try writech(output, 7);
                        output.close();
                        return output;
                    };
                    let Stream<int> stream = await pending;
                    let Option<int> item = try readch(stream);
                    stdio.stdout.write(item.unwrap());
                    return;
                }
                """));
    }

    @Test
    void producerTaskCanShareSynchronizedHandleWithConsumer() throws Exception {
        assertEquals("23", execute("""
                pub routine main(): void {
                    val Stream<int> output = Stream.new<int>(2);
                    let Task<void> producer = cpu task {
                        val bool first = try writech(output, 2);
                        val bool second = try writech(output, 3);
                        return;
                    };
                    await producer;
                    let Option<int> first = try readch(output);
                    let Option<int> second = try readch(output);
                    stdio.stdout.write(first.unwrap());
                    stdio.stdout.write(second.unwrap());
                    output.close();
                    return;
                }
                """));
    }

    @Test
    void taskAndFunctionCannotSubstituteChannelForStream() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let Task<Stream<int>> future = cpu task {
                                return Channel.new<int>(1);
                            };
                            return;
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc wrong(): Stream<int> {
                            return Channel.new<int>(1);
                        }
                        pub routine main(): void { return; }
                        """));
    }

    @Test
    void sourceTaskCannotReturnDifferentStreamPayloadType() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            let Task<Stream<string>> future = cpu task {
                                return Stream.new<int>(1);
                            };
                            return;
                        }
                        """));
    }

    @Test
    void streamPayloadCannotBeFutureOrIteratorCapability() {
        for (String type : new String[] {"Future<int>", "Iterator<int>"}) {
            String source = "pub routine main(): void { "
                    + "let Stream<" + type + "> x = Stream.new<" + type + ">(1);"
                    + " return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), type);
        }
    }

    @Test
    void nestedTransportPayloadsDoNotAcquireAccidentalSendAuthority() {
        for (String type : new String[] {
                "Stream<int>", "Channel<int>",
                "Option<Stream<int>>", "Result<int, Channel<int>>",
                "List<Stream<int>>"}) {
            String source = "pub routine main(): void { "
                    + "let Stream<" + type + "> x = Stream.new<" + type + ">(1);"
                    + " return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), type);
        }
    }

    @Test
    void explicitStreamAndChannelAreNotImplicitlyInterchangeableTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main(): void {
                            val Channel<int> ordinary = Channel.new<int>(2);
                            let Stream<int> stream = ordinary;
                            return;
                        }
                        """));
    }

    @Test
    void bareOrBorrowedStreamTypesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                            val Stream stream = Stream.new<int>(2);
                            return;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                            let Stream<borrow int> stream = Stream.new<borrow int>(2);
                            return;
                        }
                        """)));
    }

    @Test
    void streamRequiresPositiveBoundedCapacityAtRuntime() {
        for (long capacity : new long[]{0L, -1L, 65537L}) {
            assertThrows(RuntimeException.class, () -> execute(
                    "pub routine main(): void { "
                            + "val Stream<int> stream = Stream.new<int>(" + capacity + "); "
                            + "return; }"), "capacity=" + capacity);
        }
    }
}
