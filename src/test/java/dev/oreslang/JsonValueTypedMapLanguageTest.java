package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JsonValue remains non-ambient. The canonical alloc/json import may expose it
 * only as the reified value domain of Map<String, JsonValue> in this staged
 * slice. Standalone JsonValue declarations and owned JsonValue reinsertion
 * remain gated until rollback-safe ownership lowering lands.
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
    void jsonMapReadByValueRemainsDisabledToPreventMoveOnlyAliasing() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        import module Json from "alloc/json";

                        pub fnc main(): void {
                          val Map<String, JsonValue> object =
                              new Map<String, JsonValue>();
                          object["name"] = "Ada";
                          const value = object["name"];
                          return;
                        }
                        """));

        assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        import module Json from "alloc/json";

                        pub fnc main(): void {
                          val Map<String, JsonValue> object =
                              new Map<String, JsonValue>();
                          object["name"] = "Ada";
                          const value = object.get("name");
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
