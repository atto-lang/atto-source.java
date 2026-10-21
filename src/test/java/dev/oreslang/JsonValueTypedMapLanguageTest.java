package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Source-level JsonValue is intentionally unavailable until the alloc/json
 * module and resolver expose it. The runtime descriptor landing must not make
 * an allocator-backed library type globally visible as a core builtin.
 */
final class JsonValueTypedMapLanguageTest {
    @Test
    void sourceJsonValueRemainsFailClosedWithoutAllocJsonModule() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<String, JsonValue> object =
                              new Map<String, JsonValue>();
                          return;
                        }
                        """));
    }

    @Test
    void genericJsonValueSpellingCannotSmugglePastTheGate() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub fnc main(): void {
                          val Map<String, JsonValue<String>> object =
                              new Map<String, JsonValue<String>>();
                          return;
                        }
                        """));
    }

    @Test
    void arbitraryNominalMapValuesRemainRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Payload as
                          const int value: 1;
                        end

                        pub fnc main(): void {
                          val Map<String, Payload> bad =
                              new Map<String, Payload>();
                          return;
                        }
                        """));
    }
}
