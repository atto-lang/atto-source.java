package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-file proof: export checking is insufficient without real alias expansion. */
final class PublicTypeAliasLinkerIntegrationTest {
    private static final String SOURCE = """
            define module products
                type Internal<K,V> = record{key: K, value: V};
                define pub type alias Pair<K,V> as Internal<K,V>;
            end
            """;

    @Test
    void privateHelperExpandsIntoImportedReadonlyFieldContract() {
        var result = new IncrementalCompiler().compile(Map.of(
                "library.ores", SOURCE,
                "app.ores", """
                        import type Pair from "./library.ores";
                        fnc extract(Pair<string,int> pair): int {
                            return pair.value;
                        }
                        """));
        assertTrue(result.units().containsKey("app.ores"));
    }

    @Test
    void typesImportAlsoProvidesActualAliasStructure() {
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(Map.of(
                "library.ores", SOURCE,
                "app.ores", """
                        import types Pair from "./library.ores";
                        fnc extract(Pair<string,int> pair): string {
                            return pair.key;
                        }
                        """)));
    }

    @Test
    void importerCannotForgeStorageConversionOrMemberNames() {
        for (String source : new String[]{
                """
                import type Pair from "./library.ores";
                fnc bad(Pair<string,int> value): float {
                    return value.missing;
                }
                """,
                """
                import type Pair from "./library.ores";
                fnc bad(Pair<string,int> value): string {
                    return value.value;
                }
                """,
                """
                import type Pair from "./library.ores";
                fnc bad(Pair<string,int> value): void {
                    value.value = 8;
                    return;
                }
                """
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    new IncrementalCompiler().compile(Map.of(
                            "library.ores", SOURCE, "app.ores", source)));
        }
    }

    @Test
    void importedAliasCannotUseUnverifiedForeignNominalType() {
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "library.ores", """
                                define module products
                                    define pub type alias Handle as PrivateClass;
                                end
                                """,
                        "app.ores", """
                                import type Handle from "./library.ores";
                                fnc use(Handle handle): void { return; }
                                """)));
    }

    @Test
    void cycleAcrossPrivateAliasesIsRejectedAtImportBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "library.ores", """
                                define module products
                                    type A = B;
                                    type B = A;
                                    define pub type alias Public as A;
                                end
                                """,
                        "app.ores", """
                                import type Public from "./library.ores";
                                fnc use(Public thing): void { return; }
                                """)));
    }

    @Test
    void importedGenericArityAndAliasSymbolStaySeparateFromValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "library.ores", SOURCE,
                        "app.ores", """
                                import type Pair from "./library.ores";
                                fnc bad(Pair<int> item): void { return; }
                                """)));
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "library.ores", SOURCE,
                        "app.ores", """
                                import type Pair from "./library.ores";
                                fnc bad(): void { Pair(); return; }
                                """)));
    }
}
