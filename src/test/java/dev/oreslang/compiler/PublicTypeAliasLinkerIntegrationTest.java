package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Cross-file proof: export checking is insufficient without real alias expansion. */
final class PublicTypeAliasLinkerIntegrationTest {
    private static final String SOURCE = """
            define module products as
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
                                define module products as
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
                                define module products as
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

    @Test
    void standaloneCheckerCannotTreatUnlinkedAliasAsOpaqueNamedType() {
        for (String importKind : new String[]{"type", "types"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> dev.oreslang.types.TypeChecker.checkTypes(
                            dev.oreslang.parser.Parser.parse(
                                    "import " + importKind + " Pair from \"./library.ores\";"
                                            + "fnc bad(Pair<string,int> p): void { return; }")));
        }
    }

    @Test
    void repeatedAliasExpansionHasAGlobalBudget() {
        StringBuilder source = new StringBuilder("define module product as\n");
        source.append("type A0<T> = Tuple[T,T];\n");
        for (int i = 1; i <= 15; i++) {
            source.append("type A").append(i).append("<T> = Tuple[A")
                    .append(i - 1).append("<T>, A").append(i - 1).append("<T>];\n");
        }
        source.append("define pub type alias Large as A15<int>;\nend\n");
        var parsed = dev.oreslang.parser.Parser.parse(source.toString());
        var consumer = dev.oreslang.parser.Parser.parse(
                "import type Large from \"./library.ores\"; fnc run(): void { return; }");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PublicTypeAliasLinker.link("app.ores", consumer,
                        Map.of("app.ores", consumer, "library.ores", parsed), Map.of()));
        assertTrue(error.getMessage().contains("expansion exceeds"), error.getMessage());
    }

    @Test
    void nestedFiniteGenericAliasInstantiationsAreNotCycles() {
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(Map.of(
                "library.ores", """
                        define module products as
                            type Box<T> = Tuple[T];
                            define pub type alias Nested as Box<Box<int>>;
                        end
                        """,
                "app.ores", """
                        import type Nested from "./library.ores";
                        fnc unwrap(Nested value): int { return value[0][0]; }
                        """)));
    }

    @Test
    void exportedGenericCannotAccidentallyBindFreePrivateAliasParameter() {
        var library = dev.oreslang.parser.Parser.parse("""
                define module products as
                    type Broken = T;
                    define pub type alias Export<T> as Broken;
                end
                """);
        var importer = dev.oreslang.parser.Parser.parse("""
                import type Export from "./library.ores";
                fnc run(Export<int> value): void { return; }
                """);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PublicTypeAliasLinker.link("app.ores", importer,
                        Map.of("app.ores", importer, "library.ores", library), Map.of()));
        assertTrue(error.getMessage().contains("lacks a verified nominal/public ABI"),
                error.getMessage());
    }

    @Test
    void innerAliasGenericsShadowOuterAliasesWithoutCapture() {
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(Map.of(
                "library.ores", """
                        define module products as
                            type Inner<T> = Tuple[T];
                            define pub type alias Outer<T> as Inner<Inner<T>>;
                        end
                        """,
                "app.ores", """
                        import type Outer from "./library.ores";
                        fnc unwrap(Outer<string> value): string { return value[0][0]; }
                        """)));
    }

    private static final String CHAIN_LEAF = """
            define module leaf as
                type Hidden<K,V> = record{key: K, value: V};
                define pub type alias Leaf<K,V> as Hidden<K,V>;
            end
            """;
    private static final String CHAIN_MIDDLE = """
            import type Leaf as LocalLeaf from "./leaf.ores";
            define module middle as
                define pub type alias Public<K,V> as LocalLeaf<K,V>;
            end
            """;
    private static final String CHAIN_APP = """
            import type Public from "./middle.ores";
            fnc read(Public<string,int> entry): int { return entry.value; }
            """;

    @Test
    void threeFilePublicAliasChainPreservesGenericRecordFields() {
        var compiled = new IncrementalCompiler().compile(Map.of(
                "leaf.ores", CHAIN_LEAF, "middle.ores", CHAIN_MIDDLE,
                "app.ores", CHAIN_APP));
        assertTrue(compiled.units().containsKey("app.ores"));
        assertTrue(compiled.initializationOrder().indexOf("leaf.ores")
                < compiled.initializationOrder().indexOf("middle.ores"));
    }

    @Test
    void importedAliasRenamingDoesNotLeakOriginalNameInMiddleFile() {
        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(
                Map.of("leaf.ores", CHAIN_LEAF, "middle.ores", """
                        import type Leaf as LocalLeaf from "./leaf.ores";
                        define module middle as
                            define pub type alias Public<K,V> as Leaf<K,V>;
                        end
                        """, "app.ores", CHAIN_APP)));
    }

    @Test
    void multiHopAliasDoesNotAllowWrongMemberTypesOrWritableAccess() {
        for (String app : new String[]{
                """
                import type Public from "./middle.ores";
                fnc bad(Public<string,int> value): string { return value.value; }
                """,
                """
                import type Public from "./middle.ores";
                fnc bad(Public<string,int> value): int { return value.notAField; }
                """,
                """
                import type Public from "./middle.ores";
                fnc bad(Public<string,int> value): void {
                    value.value = 9;
                    return;
                }
                """
        }) {
            assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(
                    Map.of("leaf.ores", CHAIN_LEAF, "middle.ores", CHAIN_MIDDLE,
                            "app.ores", app)));
        }
    }

    @Test
    void crossFileAliasCycleFailsEvenWhenImportGraphCyclesAreLegal() {
        var files = Map.of(
                "a.ores", """
                        import type BType from "./b.ores";
                        define module a as
                            define pub type alias AType as BType;
                        end
                        """,
                "b.ores", """
                        import type AType from "./a.ores";
                        define module b as
                            define pub type alias BType as AType;
                        end
                        """,
                "app.ores", """
                        import type AType from "./a.ores";
                        fnc use(AType value): void { return; }
                        """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(files));
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    @Test
    void privateAliasNameCannotBeImportedThroughIntermediateFile() {
        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(Map.of(
                "leaf.ores", CHAIN_LEAF,
                "middle.ores", """
                        import type Hidden from "./leaf.ores";
                        define module middle as
                            define pub type alias Public<K,V> as Hidden<K,V>;
                        end
                        """,
                "app.ores", CHAIN_APP)));
    }

    @Test
    void genericArityIsEnforcedAtEachIntermediateHop() {
        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(Map.of(
                "leaf.ores", CHAIN_LEAF,
                "middle.ores", """
                        import type Leaf from "./leaf.ores";
                        define module middle as
                            define pub type alias Public<K,V> as Leaf<K>;
                        end
                        """,
                "app.ores", CHAIN_APP)));
    }

    @Test
    void privateLeafShapeChangeInvalidatesTransitiveImporter() {
        var compiler = new IncrementalCompiler();
        Map<String, String> files = Map.of("leaf.ores", CHAIN_LEAF,
                "middle.ores", CHAIN_MIDDLE, "app.ores", CHAIN_APP);
        var first = compiler.compile(files);
        assertEquals(3, first.rebuiltUnits().size());
        var second = compiler.compile(files);
        assertTrue(second.rebuiltUnits().isEmpty(), second.rebuiltUnits().toString());

        // A changed non-public alias affects the public transitive layout:
        // a previous reference to 'value' must not retain the old type.
        String modifiedLeaf = CHAIN_LEAF.replace("value: V", "payload: V");
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(Map.of("leaf.ores", modifiedLeaf,
                        "middle.ores", CHAIN_MIDDLE, "app.ores", CHAIN_APP)));
        assertNotNull(mismatch.getMessage());
        // A failed incremental build must not publish any partially updated cache.
        assertTrue(compiler.compile(files).rebuiltUnits().isEmpty());
    }

    @Test
    void typeFamilyImportAndTwoHopRenamingPreserveProvenance() {
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(Map.of(
                "leaf.ores", CHAIN_LEAF,
                "middle.ores", """
                        import types Leaf as PrivateLocal from "./leaf.ores";
                        define module middle as
                            define pub type alias Export<K,V> as PrivateLocal<K,V>;
                        end
                        """,
                "app.ores", """
                        import type Export as LocalEntry from "./middle.ores";
                        fnc read(LocalEntry<string,int> value): int { return value.value; }
                        """)));
    }

    @Test
    void reusedCompilationRejectsTransitivePrivateTypeChange() {
        var compiler = new IncrementalCompiler();
        String middle = """
                import type Leaf from "./leaf.ores";
                define module middle as
                    define pub type alias Export<K,V> as Leaf<K,V>;
                end
                """;
        String app = """
                import type Export from "./middle.ores";
                fnc extract(Export<string,int> value): int { return value.value; }
                """;
        var sources = Map.of("leaf.ores", CHAIN_LEAF, "middle.ores", middle, "app.ores", app);
        assertEquals(3, compiler.compile(sources).rebuiltUnits().size());
        assertTrue(compiler.compile(sources).rebuiltUnits().isEmpty());
        // Public declarations are unchanged; private storage changes must
        // still invalidate all dependent compilation-unit proofs.
        String changed = CHAIN_LEAF.replace("value: V", "payload: V");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(Map.of(
                "leaf.ores", changed, "middle.ores", middle, "app.ores", app)));
        assertTrue(compiler.compile(sources).rebuiltUnits().isEmpty(),
                "a failed build must retain the previous complete cache snapshot");
    }

    @Test
    void importedAliasCannotHideBorrowInItsDefiningLayout() {
        var library = dev.oreslang.parser.Parser.parse("""
                define module products as
                    define pub type alias HiddenLoan as Tuple[&int];
                end
                """);
        var app = dev.oreslang.parser.Parser.parse("""
                import type HiddenLoan from "./library.ores";
                fnc use(HiddenLoan x): void { return; }
                """);
        var e = assertThrows(IllegalArgumentException.class,
                () -> PublicTypeAliasLinker.link("app.ores", app,
                        Map.of("app.ores", app, "library.ores", library), Map.of()));
        assertTrue(e.getMessage().contains("borrowed ownership"), e.getMessage());
    }

    @Test
    void importedGenericAliasCannotConcealNestedBorrowArguments() {
        for (String argument : new String[]{"&int", "Option<&int>", "Tuple[&int]"}) {
            var source = Map.of(
                    "library.ores", """
                            define module products as
                                define pub type alias Wrap<T> as Tuple[T];
                            end
                            """,
                    "app.ores", """
                            import type Wrap from "./library.ores";
                            fnc read(Wrap<%s> value): void { return; }
                            """.formatted(argument));
            var error = assertThrows(IllegalArgumentException.class,
                    () -> new IncrementalCompiler().compile(source));
            assertTrue(error.getMessage().contains("borrowed generic arguments"),
                    error.getMessage());
        }
    }

    @Test
    void callerLocalBorrowAliasCannotBypassForeignOwnershipGate() {
        var source = Map.of(
                "library.ores", """
                        define module products as
                            define pub type alias Wrap<T> as Tuple[T];
                        end
                        """,
                "app.ores", """
                        import type Wrap from "./library.ores";
                        type LocalLoan = &int;
                        fnc consume(Wrap<LocalLoan> value): void { return; }
                        """);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(source));
        assertTrue(failure.getMessage().contains("borrowed ownership"),
                failure.getMessage());
    }
}
