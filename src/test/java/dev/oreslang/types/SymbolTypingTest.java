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

    @Test void untrustedActorInitializersCannotMintHiddenSymbols() {
        assertThrows(SecurityException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "untrusted actor Guard as val state = #userland_secret; end")));
        assertThrows(SecurityException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "untrusted actor Guard as val Symbol state = #userland_secret; end")));
    }

    @Test void trustedActorsMayKeepPrivateUserlandSymbols() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                "isoactor Guard as val state = #userland_secret; end")));
    }

    @Test void publicCoreSymbolAllowedInUntrustedActorInitializer() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                "untrusted actor Guard as val Symbol state = #ready; end")));
    }

    @Test void historicalSymbolConstructorIsRejectedAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "pub fnc bad() : Symbol { return new Symbol(\"ready\"); }")));
    }
}
