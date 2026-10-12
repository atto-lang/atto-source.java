package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateContract;
import dev.oreslang.types.DeclaredEffectVerifier;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pre-1.0 unified authority: with declares effects, using acquires loans. */
final class WithStateCapabilityContractTest {
    private static String actor(String effect, String callback) {
        return "define actor Counter as\n"
            + "state { v: int = 0; }\n"
            + "get(): int " + effect + " { return " + callback + "; }\n"
            + "end\n";
    }
    private static final String READ =
        "self.handle_state(|s| -> { return s.v; })";
    private static final String WRITE =
        "self.handle_state(critical |s| -> { s.v = s.v + 1; return s.v; })";

    @Test void readDefaultWithExplicitEffect() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(actor("with state.read", READ))));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                actor("with state.read", "using state.read(|s| -> { return s.v; })"))));
    }
    @Test void criticalWriteRequiresReadAndWriteEffects() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                actor("with state.read, state.write", WRITE))));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(
                actor("with state.read, state.write",
                      "using state.write(critical |s| -> { s.v = s.v + 1; return s.v; })"))));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse(actor("with state.write", WRITE))));
    }
    @Test void usingAndWithCannotSubstituteForOneAnother() {
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(actor("using state.read", READ)));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse(actor("with state.read",
                      "using state.write(|s| -> { return s.v; })")));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.checkTypes(Parser.parse(actor("", READ))));
    }
    @Test void readLoanCannotWriteEvenWithDeclaredWriteEffect() {
        assertThrows(IllegalArgumentException.class, () ->
                ActorStateContract.verify(Parser.parse(actor("with state.read, state.write",
                    "self.handle_state(|s| -> { s.v = 3; return s.v; })"))));
    }
    @Test void noEscapeNoSuspensionNoNestedLoan() {
        assertThrows(IllegalArgumentException.class, () ->
                ActorStateContract.verify(Parser.parse(actor("with state.read",
                    "self.handle_state(|s| -> { return s; })"))));
        assertThrows(IllegalArgumentException.class, () ->
                ActorStateContract.verify(Parser.parse(actor("with state.read",
                    "self.handle_state(|s| -> { await task(); return s.v; })"))));
        assertThrows(IllegalArgumentException.class, () ->
                ActorStateContract.verify(Parser.parse(actor("with state.read",
                    "self.handle_state(|s| -> { self.handle_state(|t| -> { return t.v; }); return s.v; })"))));
    }
    @Test void actorClassStorageMustBeStateMap() {
        assertThrows(IllegalArgumentException.class, () ->
                ActorStateContract.verify(Parser.parse(
                    "define actor A as let v: int = 0; end")));
    }
    @Test void sealedCallbackEffectsFollowLoweringAndCannotBeForged() {
        Ast.Program p = Parser.parse(actor("with state.read",
                "using state.read(|s| -> { return s.v; })"));
        assertDoesNotThrow(() -> DeclaredEffectVerifier.verify(p));
        assertThrows(IllegalArgumentException.class, () -> DeclaredEffectVerifier.verify(
                Parser.parse(actor("with state.read",
                    "using state.write(critical |s| -> { s.v = 5; return s.v; })"))));
    }
}
