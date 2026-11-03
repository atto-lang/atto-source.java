package dev.oreslang.types;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SymbolTypingTest {
    @Test void typedSymbolLiteralAndReturnAreAccepted() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(
                Parser.parse("pub fnc tag() : Symbol { return #ready; }")));
    }

    @Test void quotedSymbolReturnHasNominalSymbolType() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(
                Parser.parse("pub fnc tag() : Symbol { return #\"http.ok\"; }")));
    }

    @Test void symbolsCannotBeImplicitlyConvertedToNumbersOrStrings() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("pub fnc bad() : int { return #ready; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("pub fnc bad() : String { return #ready; }")));
    }

    @Test void symbolAsExistingStructValueDoesNotChangeStringNamedField() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                "pub fnc tag() : Symbol { const r = infer struct{status: #ready}; return r.status; }")));
    }

    @Test void symbolIndexDoesNotImplicitlyAliasExistingTextualStructField() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "pub fnc bad() : int { const r = infer struct{status: 1}; return r[#status]; }")));
    }

    @Test void builtinSymbolTypeCannotBeShadowed() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("class Symbol { }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("define interface Symbol fnc run() => void; end")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("type Symbol = int;")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(
                Parser.parse("fnc identity<Symbol>(Symbol value) -> Symbol { return value; }")));
    }

    @Test void switchUsesNominalSymbolIdentityInsteadOfDisplayText() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                "pub fnc read(Symbol | String tag) : int { "
                + "switch tag case \"#foo\" -> { return 1; } "
                + "case #foo -> { return 2; } "
                + "default -> { return 0; } end }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "pub fnc bad(Symbol tag) : int { "
                + "switch tag case #ready -> { return 1; } "
                + "case #\"ready\" -> { return 2; } "
                + "default -> { return 0; } end }")));
    }

    @Test void historicalSymbolConstructorIsRejectedAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "pub fnc bad() : Symbol { return new Symbol(\"ready\"); }")));
    }
}
