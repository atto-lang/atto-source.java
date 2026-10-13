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

    @Test void emptyCallbackStillRequiresMatchingWithLoanEffect() {
        for (String callback : new String[] {
            "self.handle_state(|s| -> { return 7; })",
            "self.handle_state(critical read |s| -> { return 7; })",
            "using state.read(|s| -> { return 7; })",
            "using state.read(critical read |s| -> { return 7; })"
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(Parser.parse(actor("with state.write", callback))),
                "READ loan requires declared state.read even without field reads: " + callback);
            assertDoesNotThrow(() ->
                DeclaredEffectVerifier.verify(Parser.parse(actor("with state.read", callback))));
        }
        for (String callback : new String[] {
            "self.handle_state(critical |s| -> { return 7; })",
            "self.handle_state(critical write |s| -> { return 7; })",
            "using state.write(critical |s| -> { return 7; })",
            "using state.write(critical write |s| -> { return 7; })"
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> DeclaredEffectVerifier.verify(Parser.parse(actor("with state.read", callback))),
                "WRITE loan requires declared state.write even without field writes: " + callback);
            assertDoesNotThrow(() ->
                DeclaredEffectVerifier.verify(Parser.parse(actor("with state.write", callback))));
        }
    }

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
    @Test void actorSuperclassInheritanceCannotBypassSealedStateLayout() {
        // The parser accepts an extends clause, but no actor kind may
        // inherit unverified fields or callable state-bearing methods.
        for (String declaration : new String[] {
                "define actor Child extends Base as",
                "define serial actor Child extends Base as"
        }) {
            Ast.Program parsed = Parser.parse(declaration
                    + " ping(): int { return 1; } end");
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> ActorStateContract.verify(parsed), declaration);
            assertTrue(denied.getMessage().contains("cannot extend a class"),
                    denied::getMessage);
        }
        // Implemented interfaces do not contribute unchecked instance
        // storage; they remain distinct from superclass inheritance.
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse(
                "define actor Child from BaseContract as "
                + "ping(): int { return 1; } end")));
    }

    @Test void statelessActorMethodsStillReceiveSealedStateVerification() {
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse(
                "define actor Stateless as ping(): int { return 1; } end")));
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(Parser.parse(
                "define actor Stateless as handle_state(): int { return 1; } end")));
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(Parser.parse(
                "define actor Stateless as ping(): int { return self.state.v; } end")));
        assertThrows(IllegalArgumentException.class, () -> ActorStateContract.verify(Parser.parse(
                "define actor Stateless as ping(): int with state.read { "
                + "return self.handle_state(|s| -> { return s.v; }); } end")));
    }

    @Test void runtimeCooperateCannotSuspendAnActiveStateLoan() {
        for (String callback : new String[] {
                "self.handle_state(|s| -> { rt cooperate; return s.v; })",
                "using state.read(|s| -> { rt cooperate; return s.v; })",
                "self.handle_state(critical |s| -> { rt cooperate; s.v = 1; return s.v; })",
                "using state.write(critical |s| -> { rt cooperate; s.v = 1; return s.v; })"
        }) {
            String code = actor("with state.read, state.write, suspend", callback);
            assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(Parser.parse(code)), callback);
        }
    }

    @Test void actorStateCannotBeMovedOrSharedOutOfALiveLoan() {
        for (String op : new String[] { "take", "share", "borrow" }) {
            String viaIntrinsic = actor("with state.read",
                    "self.handle_state(|s| -> { rt " + op + " s.v; return s.v; })");
            Ast.Program parsedIntrinsic = Parser.parse(viaIntrinsic);
            var intrinsicFailure = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(parsedIntrinsic), op);
            assertTrue(intrinsicFailure.getMessage().contains("state-backed"), op);
            String viaUsing = actor("with state.read",
                    "using state.read(|s| -> { rt " + op + " s.v; return s.v; })");
            Ast.Program parsedUsing = Parser.parse(viaUsing);
            var usingFailure = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(parsedUsing), op);
            assertTrue(usingFailure.getMessage().contains("state-backed"), op);
        }
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
