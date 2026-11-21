package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Eight exact source vectors from oreslang-platform-conformance #206.
 * The Git blob pins prohibit a JVM-only copy from silently drifting away from
 * the shared Future v2 corpus. These are real compiler/guest test methods
 * once JUnit executes, not evidence that this draft has run.
 *
 * This test is local to the source organization; no private cross-repository
 * checkout, mirroring, secret or service-token is necessary.
 */
final class FutureChainingV2PinnedGuestTest {
    @Test
    void flat_map_one_layer() throws Exception {
        String source = """
            pub async fnc seed(): int { return 21; }
            pub async fnc double_it(int number): int { return number * 2; }
            
            pub suspends fnc main(): void {
                val Future<int> result = seed().flat_map(|int number| -> {
                    return double_it(number);
                });
                stdio.stdout.write(await result);
                return;
            }
            """;
        assertEquals("db7006ae3b564c8ebcb80680ac113a72c065f507", git_blob_sha1(source), "must match conformance #206 exact fixture");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run_guest(source));
    }

    @Test
    void map_preserves_nested() throws Exception {
        String source = """
            pub async fnc seed(): int { return 21; }
            pub async fnc double_it(int number): int { return number * 2; }
            
            pub suspends fnc main(): void {
                val Future<Future<int>> nested = seed().map(|int number| -> {
                    return double_it(number);
                });
                val Future<int> inner = await nested;
                stdio.stdout.write(await inner);
                return;
            }
            """;
        assertEquals("c62fbae210bc2c501cb1b30a6887219086f78391", git_blob_sha1(source), "must match conformance #206 exact fixture");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run_guest(source));
    }

    @Test
    void flatten_one_layer() throws Exception {
        String source = """
            pub async fnc seed(): int { return 21; }
            pub async fnc double_it(int number): int { return number * 2; }
            
            pub suspends fnc main(): void {
                val Future<Future<int>> nested = seed().map(|int number| -> {
                    return double_it(number);
                });
                val Future<int> inner = nested.flatten();
                stdio.stdout.write(await inner);
                return;
            }
            """;
        assertEquals("ce032c78f574d0d61f0496a719d881af63dc580c", git_blob_sha1(source), "must match conformance #206 exact fixture");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("42", run_guest(source));
    }

    @Test
    void flat_map_preserves_deeper_nesting() throws Exception {
        String source = """
            pub async fnc seed(): int { return 21; }
            pub async fnc double_it(int number): int { return number * 2; }
            
            pub suspends fnc main(): void {
                val Future<Future<int>> nested = seed().flat_map(|int number| -> {
                    return double_it(number).map(|int doubled| -> {
                        return double_it(doubled);
                    });
                });
                val Future<int> inner = await nested;
                stdio.stdout.write(await inner);
                return;
            }
            """;
        assertEquals("2cf2bff1a2f516fde2b8f85272bacf52de180d55", git_blob_sha1(source), "must match conformance #206 exact fixture");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("84", run_guest(source));
    }

    @Test
    void reject_flat_map_scalar() throws Exception {
        String source = """
            pub async fnc seed(): int { return 21; }
            
            pub fnc main(): void {
                val Future<int> invalid = seed().flat_map(|int number| -> {
                    return number * 2;
                });
                return;
            }
            """;
        assertEquals("5f9f51d91f32bb1a7154f7bc1d1a4ece1015d94c", git_blob_sha1(source), "must match conformance #206 exact fixture");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("Future.flat_map callback must return Future<T>"), error::getMessage);
    }

    @Test
    void reject_implicit_map_flatten() throws Exception {
        String source = """
            pub async fnc seed(): int { return 21; }
            pub async fnc double_it(int number): int { return number * 2; }
            
            pub fnc main(): void {
                val Future<int> invalid = seed().map(|int number| -> {
                    return double_it(number);
                });
                return;
            }
            """;
        assertEquals("8a2f07c2453763ed585462556cf1c4c5330774fd", git_blob_sha1(source), "must match conformance #206 exact fixture");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("initializer for invalid has type"), error::getMessage);
        assertTrue(error.getMessage().contains("Future"), error::getMessage);
        assertTrue(error.getMessage().contains("but expected"), error::getMessage);
    }

    @Test
    void reject_one_await_flatten() throws Exception {
        String source = """
            pub async fnc seed(): int { return 21; }
            pub async fnc double_it(int number): int { return number * 2; }
            
            pub suspends fnc main(): void {
                val Future<Future<int>> nested = seed().map(|int number| -> {
                    return double_it(number);
                });
                val int invalid = await nested;
                return;
            }
            """;
        assertEquals("e99a49f2b30964cbdbba4cb6250859fbc1180fd6", git_blob_sha1(source), "must match conformance #206 exact fixture");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("initializer for invalid has type"), error::getMessage);
        assertTrue(error.getMessage().contains("Future"), error::getMessage);
        assertTrue(error.getMessage().contains("but expected"), error::getMessage);
    }

    @Test
    void reject_flat_map_deeper_nesting() throws Exception {
        String source = """
            pub async fnc seed(): int { return 21; }
            pub async fnc double_it(int number): int { return number * 2; }
            
            pub fnc main(): void {
                val Future<int> invalid = seed().flat_map(|int number| -> {
                    return double_it(number).map(|int doubled| -> {
                        return double_it(doubled);
                    });
                });
                return;
            }
            """;
        assertEquals("979ea56ccb11c8e90ca2dcc7029ff081364eb8e4", git_blob_sha1(source), "must match conformance #206 exact fixture");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(error.getMessage().contains("initializer for invalid has type"), error::getMessage);
        assertTrue(error.getMessage().contains("Future"), error::getMessage);
        assertTrue(error.getMessage().contains("but expected"), error::getMessage);
    }

    private static String git_blob_sha1(String source) throws Exception {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        sha1.update(bytes);
        return HexFormat.of().formatHex(sha1.digest());
    }

    private static String run_guest(String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID, source, "future-v2-pinned.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
