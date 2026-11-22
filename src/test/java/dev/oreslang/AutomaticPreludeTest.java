package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.AutomaticPrelude;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

/** Regression gate for compiler-owned implicit core bindings and affine loans. */
final class AutomaticPreludeTest {
    @Test
    void coreNamesResolveWithoutAllocAndSpecializedCollectionsRemainExplicit() {
        for (String name : new String[]{"Option", "Result", "Some", "None", "Ok", "Err",
                "Array", "Slice", "Tuple", "Error", "Pair"}) {
            var binding = AutomaticPrelude.lookup(name, AutomaticPrelude.Profile.CORE);
            assertTrue(binding.isPresent(), "missing core prelude " + name);
            assertTrue(binding.get().canonical().startsWith("core/"));
        }
        for (String name : new String[]{"String", "List", "Map", "Set", "Vec", "Box"}) {
            assertTrue(AutomaticPrelude.lookup(name, AutomaticPrelude.Profile.CORE).isEmpty(), name);
            assertEquals(AutomaticPrelude.Scope.ALLOC,
                    AutomaticPrelude.lookup(name, AutomaticPrelude.Profile.HOSTED)
                            .orElseThrow().scope(), name);
        }
        for (String name : new String[]{"HashMap", "HashSet", "Entry"}) {
            assertTrue(AutomaticPrelude.lookup(name, AutomaticPrelude.Profile.HOSTED).isEmpty(), name);
        }
        assertTrue(AutomaticPrelude.lookup("Object", AutomaticPrelude.Profile.HOSTED).isEmpty());
        assertEquals(AutomaticPrelude.Profile.CORE, AutomaticPrelude.Profile.CORE);
    }

    @Test
    void catalogIsUniqueImmutableAndCanonical() {
        var all = AutomaticPrelude.catalog();
        assertEquals(all.size(), new HashSet<>(all.keySet()).size());
        for (var entry : all.values()) {
            assertTrue(entry.canonical().endsWith("/" + entry.name()));
            assertFalse(entry.canonical().contains(".."));
        }
        assertThrows(UnsupportedOperationException.class, () -> all.clear());
    }

    @Test
    void coreVariantNamesAndCarrierArityAreCompilerOwned() {
        for (String name : new String[]{"Some", "None", "Ok", "Err"}) {
            assertTrue(AutomaticPrelude.coreConstructor(name));
            assertTrue(AutomaticPrelude.protectedIdentity(name));
        }
        assertFalse(AutomaticPrelude.coreConstructor("Result"));
        assertTrue(AutomaticPrelude.coreSumCarrier("Option", 1));
        assertTrue(AutomaticPrelude.coreSumCarrier("Result", 2));
        assertFalse(AutomaticPrelude.coreSumCarrier("Option", 2));
        assertFalse(AutomaticPrelude.coreSumCarrier("Result", 1));
        assertFalse(AutomaticPrelude.coreSumCarrier("Object", 1));
    }

    private static void assertPreludeSpoofRejected(String source) {
        var program = assertDoesNotThrow(() -> Parser.parse(source),
                "must reach the real typechecker, not be merely syntactically rejected");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("E_PRELUDE_SPOOF"), error::getMessage);
    }

    @Test
    void nominalDeclarationsCannotForgeTheCoreSum() {
        assertPreludeSpoofRejected("""
                define class Option as
                  val int value = 1;
                end
                """);
        assertPreludeSpoofRejected("""
                define class Result as
                  val int value = 1;
                end
                """);
    }

    @Test
    void aUserFunctionCannotReplaceAnAdtConstructor() {
        assertPreludeSpoofRejected("""
                fnc Some(int value): int { return value; }
                """);
        assertPreludeSpoofRejected("""
                fnc Err(int value): int { return value; }
                """);
    }

    @Test
    void unqualifiedImportAliasCannotForgeAnAdtType() {
        assertPreludeSpoofRejected("""
                import module Result from "./foreign.atto";
                pub fnc main(): int { return 42; }
                """);
    }
}
