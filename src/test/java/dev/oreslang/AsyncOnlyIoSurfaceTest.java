package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class AsyncOnlyIoSurfaceTest {
    private static final List<String> LEGACY_IO_NAMES = List.of(
            "read_text_async", "write_text_async", "append_text_async",
            "exists_async", "remove_async", "mkdir_all_async",
            "connect_async", "read_line_async", "close_async",
            "get_text_async", "post_text_async");

    @Test
    void plainIoOperationsAreTypedAsFutures() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                pub async fnc probe(): void {
                  let Future<String> read = fs.read_text("./item.txt");
                  let Future<bool> exists = fs.exists("./item.txt");
                  let Future<void> write = fs.write_text("./item.txt", "value");
                  let Future<void> out = stdio.println("value");
                  let Future<Option<String>> input = stdio.stdin.read_line();
                  let Future<String> get = http.get_text("http://127.0.0.1/");
                  await read;
                  await exists;
                  await write;
                  await out;
                  await get;
                  return;
                }
                """));
    }

    @Test
    void plainIoCannotMasqueradeAsSynchronousReturnValues() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc bad(): void {
                  let String value = fs.read_text("./item.txt");
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc bad(): void {
                  let bool value = fs.exists("./item.txt");
                  return;
                }
                """));
    }

    @Test
    void legacyAsyncSuffixIoNamesAreRejected() {
        for (String name : LEGACY_IO_NAMES) {
            String call = switch (name) {
                case "write_text_async", "append_text_async" ->
                        "fs." + name + "(\"./item.txt\", \"x\")";
                case "read_text_async", "exists_async", "remove_async", "mkdir_all_async" ->
                        "fs." + name + "(\"./item.txt\")";
                case "connect_async" -> "network.connect_async(\"127.0.0.1\", 1)";
                case "read_line_async", "close_async" ->
                        // Member-level aliases are guarded by the source-policy test below;
                        // no synthetic TcpConnection constructor is exposed to source.
                        null;
                case "get_text_async" -> "http.get_text_async(\"http://127.0.0.1/\")";
                case "post_text_async" ->
                        "http.post_text_async(\"http://127.0.0.1/\", \"x\")";
                default -> throw new AssertionError(name);
            };
            if (call == null) continue;
            String source = "pub fnc bad(): void { " + call + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), name);
        }
    }

    @Test
    void dispatchAndTypeTablesContainNoLegacyIoAliases() throws Exception {
        for (Path path : List.of(
                Path.of("src/main/java/dev/oreslang/nodes/OresEvalRootNode.java"),
                Path.of("src/main/java/dev/oreslang/types/TypeChecker.java"))) {
            String source = Files.readString(path);
            for (String name : LEGACY_IO_NAMES) {
                assertFalse(source.contains(""" + name + """),
                        () -> path + " reintroduced legacy guest I/O alias " + name);
            }
        }
    }
}
