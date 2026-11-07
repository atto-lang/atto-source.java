package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class OresSymbolLiteralSourceTest {
    @Test void lexerTokensDetermineTheImageNotCommentsOrOrdinaryStrings() {
        List<OresSymbol> literals = OresSymbolLiteralSource.collect("""
                // #ignored
                pub routine main(): void {
                  const Symbol a = #ready;
                  const Symbol b = #"ready foo";
                  const Symbol c = #'ready foo';
                  const string text = "literal #ignored too";
                  /* #also_ignored */
                  return;
                }
                """);
        assertEquals(List.of(
                OresSymbol.process("ready"),
                OresSymbol.process("ready foo")), literals);
        assertThrows(UnsupportedOperationException.class,
                () -> literals.add(OresSymbol.process("mutation")));
    }

    @Test void canonicalNfcEquivalentsShareAStaticSlot() {
        String composed = "caf\u00e9";
        String decomposed = "cafe\u0301";
        List<OresSymbol> literals = OresSymbolLiteralSource.collect(
                "pub routine main(): void { const a = #\"" + composed
                        + "\"; const b = #\"" + decomposed + "\"; return; }");
        assertEquals(List.of(OresSymbol.process(composed)), literals);
    }

    @Test void invalidOrOversizedLexicalSymbolsCannotCreateImages() {
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbolLiteralSource.collect("#"));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbolLiteralSource.collect("#123"));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbolLiteralSource.collect("#\"" + "x".repeat(257) + "\""));
        assertThrows(IllegalArgumentException.class,
                () -> OresSymbolLiteralSource.collect("#" + "a".repeat(129)));
    }

    @Test void duplicateSymbolsDoNotConsumeSeparateSlots() {
        assertEquals(List.of(OresSymbol.process("ready")),
                OresSymbolLiteralSource.collect("#ready #ready #\"ready\" #'ready'"));
    }
}
