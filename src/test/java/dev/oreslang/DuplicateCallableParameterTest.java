package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Every callable declaration must have an unambiguous parameter environment. */
final class DuplicateCallableParameterTest {
    @Test
    void rejectsDuplicateNamesAcrossAllParameterSpellings() {
        for (String declaration : new String[] {
                "fnc send(String id, String id): void { return; }",
                "fnc send(id: String, String id): void { return; }",
                "fnc send(structural String id, id: String): void { return; }",
                "define class Box as send(String id, String id): void { return; } end",
                "define class Box as constructor(String id, String id) { } end",
                "define interface Api as fnc send(String id, String id) => void; end",
                "define contract Api as fnc send(id: String, String id) => void; end",
                "fnc send = |String id, id: String| -> void { return; }"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(declaration), declaration);
        }
    }

    @Test
    void distinctParameterNamesRemainValid() {
        for (String declaration : new String[] {
                "fnc send(String left, String right): void { return; }",
                "define class Box as constructor(String left, String right) { } end",
                "define interface Api as fnc send(String left, String right) => void; end",
                "fnc send = |String left, String right| -> void { return; }"
        }) {
            assertDoesNotThrow(() -> Parser.parse(declaration), declaration);
        }
    }
}
