package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HotReloadMacroAotAdmissionTest {
    @Test
    void deniesRuntimeExpansionForAllMacroBuildPolicies() {
        for (String policy : new String[] {"", "@expand(aot)\n", "@expand(rebuildable)\n", "@expand(jit)\n"}) {
            String source = policy + """
                    macro_rules! identity { ($x:expr) => { $x }; }
                    pub fnc main(): int { return identity!(42); }
                    """;
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> HotReloadManager.rejectRuntimeMacroExpansion(source), policy);
            assertTrue(ex.getMessage().contains("runtime macro expansion forbidden"), ex.getMessage());
        }
    }

    @Test
    void deniesFromJsonAccessorGenerationDuringRuntimeSourceLoading() {
        String source = """
                define class Example as
                    @FromJson("external_id")
                    id: int;
                end
                """;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HotReloadManager.rejectRuntimeMacroExpansion(source));
        assertTrue(ex.getMessage().contains("runtime annotation code generation forbidden"),
                ex.getMessage());
        assertDoesNotThrow(() -> HotReloadManager.rejectRuntimeMacroExpansion("""
                // @FromJson("ignored")
                /* @FromJson("also_ignored") */
                pub fnc main(): int { return 42; }
                """));
    }

    @Test
    void ignoresMacroLikeTextInCommentsAndStringLiterals() {
        assertDoesNotThrow(() -> HotReloadManager.rejectRuntimeMacroExpansion("""
                // macro_rules! fake { ($x:expr) => { $x }; }
                /* macro_rules! hidden { () => { 12 }; } */
                pub fnc main(): int { return 42; }
                """));
        assertDoesNotThrow(() -> HotReloadManager.rejectRuntimeMacroExpansion(
                "fnc message(): String { return \"macro_rules! fake\"; }"));
    }

    @Test
    void doesNotMisclassifySeparatedOrdinaryIdentifiersAsMacroDefinitions() {
        assertDoesNotThrow(() -> HotReloadManager.rejectRuntimeMacroExpansion(
                "fnc macro_rules(): int { return 42; }"));
    }
}
