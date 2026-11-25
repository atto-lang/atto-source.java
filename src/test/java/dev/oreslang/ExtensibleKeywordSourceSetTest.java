package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Compiler-only linked syntax and semantic controls, not runtime linker or AOT. */
final class ExtensibleKeywordSourceSetTest {
    private static final String PROVIDER =
        "define pub keyword entity as class end\ndefine pub class Control as end";
    private static final String USE =
        "import keyword entity from \"./provider.atto\";\ndefine entity Customer as end";

    @Test void explicitPublicProviderElaboratesToOrdinaryClassAndBindsSourceIdentity() {
        var linked = Parser.parseKeywordSourceSet(Map.of("provider.atto", PROVIDER, "main.atto", USE));
        var ordinary = Parser.parseStrictAtto("define class Customer as end");
        assertEquals(ordinary, linked.programs().get("main.atto"));
        assertDoesNotThrow(() -> TypeChecker.check(linked.programs().get("main.atto")));
        assertEquals(1, linked.bindings().size());
        var binding = linked.bindings().getFirst();
        assertEquals("provider.atto", binding.provider());
        assertEquals("entity", binding.name());
        assertEquals(linked.sourceSha256().get("provider.atto"), binding.providerSha256());
        assertEquals(64, binding.providerSha256().length());
    }

    @Test void providerChangesInvalidateBindingIdentityEvenWithSameConsumer() {
        var a = Parser.parseKeywordSourceSet(Map.of("provider.atto", PROVIDER, "main.atto", USE));
        var b = Parser.parseKeywordSourceSet(Map.of("provider.atto", PROVIDER + "\n// revision", "main.atto", USE));
        assertEquals(a.programs().get("main.atto"), b.programs().get("main.atto"));
        assertNotEquals(a.bindings(), b.bindings());
        assertEquals(a.sourceSha256().get("main.atto"), b.sourceSha256().get("main.atto"));
    }

    @Test void privateWrongCategoryAndAbsentProvidersAreDenied() {
        for (String provider : new String[]{
            PROVIDER.replace("pub keyword", "keyword"),
            "define pub class entity as end",
            "// define pub keyword entity as class end\ndefine class Control as end"
        }) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parseKeywordSourceSet(Map.of("provider.atto", provider, "main.atto", USE)));
            assertTrue(error.getMessage().contains("KIND_EXPORT_UNAVAILABLE"), error::getMessage);
        }
        assertThrows(IllegalArgumentException.class, () ->
            Parser.parseKeywordSourceSet(Map.of("main.atto", USE)));
    }

    @Test void duplicateAndTransitiveOrNonSiblingImportsRemainDenied() {
        for (String consumer : new String[]{
            "import keyword entity from \"./other.atto\";\n" + USE,
            USE.replace("./provider.atto", "../provider.atto"),
            USE + "\ndefine keyword entity as class end",
            USE + "\ndefine pub keyword other_kind as class end"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                Parser.parseKeywordSourceSet(Map.of("provider.atto", PROVIDER,
                    "other.atto", PROVIDER, "main.atto", consumer)));
        }
        assertThrows(IllegalArgumentException.class, () ->
            Parser.parseKeywordSourceSet(Map.of("provider.atto", USE.replace("provider.atto", "main.atto"),
                "main.atto", USE)));
    }

    @Test void importedContractAndActorAuthorityCannotBeManufactured() {
        for (String provider : new String[]{
            "define pub keyword entity as actor end\ndefine class Control as end",
            "define pub keyword entity as class requires Entity; end\ndefine interface Entity as end"
        }) {
            var error = assertThrows(IllegalArgumentException.class, () ->
                Parser.parseKeywordSourceSet(Map.of("provider.atto", provider, "main.atto", USE)));
            assertTrue(error.getMessage().contains("KIND_PUBLIC_CONTRACT_UNSUPPORTED"), error::getMessage);
        }
        // The existing general parser and executable linker are not silently enabled.
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(PROVIDER));
        assertThrows(IllegalArgumentException.class, () -> Parser.parseStrictAtto(USE));
    }
}
