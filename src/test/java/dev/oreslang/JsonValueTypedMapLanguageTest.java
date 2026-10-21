package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Source-level JsonValue is intentionally unavailable until the alloc/json
 * module and resolver expose it. The runtime descriptor landing must not make
 * an allocator-backed library type globally visible as a core builtin.
 */
final class JsonValueTypedMapLanguageTest {
    @Test
    void explicitAllocJsonImportEnablesStringKeyedJsonMapAndScalarWrites() {
        assertDoesNotThrow(
                () -> OresCompiler.parseAndTypeCheck("""
                        import module Json from "alloc/json";

                        pub fnc main(): void {
                          val Map<String, JsonValue> object =
                              new Map<String, JsonValue>();
                          object["name"] = "Ada";
                          object["age"] = 37;
                          object["active"] = true;
                          object["score"] = 1.25;
                          object.put("age", 38);
                          return;
                        }
                        """));
    }

    @Test
    void jsonMapRequiresStringKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        import module Json from "alloc/json";

                        pub fnc main(): void {
                          val Map<Symbol, JsonValue> object =
                              new Map<Symbol, JsonValue>();
                          return;
                        }
                        """));
    }

    @Test
    void standaloneJsonValueRemainsGatedUntilOwnedLoweringLands() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        import module Json from "alloc/json";

                        pub fnc main(): void {
                          const JsonValue value = "Ada";
                          return;
                        }
                        """));
    }

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
