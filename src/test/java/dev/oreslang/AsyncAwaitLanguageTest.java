package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AsyncAwaitLanguageTest {
    @Test
    void asyncMainAndNestedAsyncFunctionComposeThroughAwait() throws Exception {
        String program = """
                define module app
                  async fnc answer(): int {
                    return 42;
                  }

                  pub async fnc main(): void {
                    val result = await answer();
                    stdio.println(result);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async-await.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("42"));
    }

    @Test
    void asyncCompositionRequiresAwaitRatherThanDirectTailTransfer() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc inner(): int {
                          return 7;
                        }

                        async fnc bad_outer(): int {
                          return inner();
                        }
                        """));

        String program = """
                async fnc inner(): int {
                  return 7;
                }

                async fnc outer(): int {
                  return await inner();
                }

                pub routine main(): void {
                  stdio.stdout.write(await outer());
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async-tail-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("7"));
    }

    @Test
    void asyncTaskMovesOwnedArgumentsAndReturnsDetachedResult() throws Exception {
        String program = """
                define module app
                  define class Box as
                    pub let int value = 1;
                  end

                  async fnc change(Box mut box): Box {
                    box.value = 99;
                    return box;
                  }

                  pub fnc main(): void {
                    let original = new Box();
                    val changed = await change(original);
                    stdio.println(changed.value);
                    return;
                  }
                end
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "async-owned-boundary.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("99"));
    }
    @Test
    void awaitRejectsNonFutureExpressions() {
        String program = """
                define module app
                  pub fnc main(): void {
                    val value = await 42;
                    return;
                  }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }


    @Test
    void ioNaturalAndSyncExplicitNamesAreBothAwaitableFutures() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc probe(): void {
                  val String natural = await fs.read_text("./natural.txt");
                  val String explicit = await fs.read_text_sync("./sync.txt");
                  val bool exists = await fs.exists("./natural.txt");
                  val TcpConnection socket = await network.connect("127.0.0.1", 9000);
                  val Option<String> line = await socket.read_line();
                  await socket.write_text("ok\\n");
                  await socket.close();
                  val String body = await http.get_text("http://127.0.0.1:9000/");
                  stdio.println(natural + explicit + body);
                  stdio.println(exists);
                  stdio.println(line);
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc bad(): void {
                          val String text = fs.read_text("./must-await.txt");
                          return;
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc bad_sync_spelling(): void {
                          val String text = fs.read_text_sync("./still-must-await.txt");
                          return;
                        }
                        """));
    }


    @Test
    void asyncActorCallableIsRejectedUntilMailboxContinuationLoweringExists() {
        String program = """
                define module app
                  pub async actor fnc burn(int n): int {
                    return n;
                  }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

    @Test
    void asyncMoveBoundaryRejectsCallerUseAfterTransfer() {
        String program = """
                define module app
                  define class Box as
                    pub let int value = 1;
                  end

                  async fnc change(Box mut box): int {
                    box.value = 99;
                    return box.value;
                  }

                  pub fnc main(): void {
                    let original = new Box();
                    val changed = await change(original);
                    stdio.println(original.value);
                    stdio.println(changed);
                    return;
                  }
                end
                """;

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
    }

}