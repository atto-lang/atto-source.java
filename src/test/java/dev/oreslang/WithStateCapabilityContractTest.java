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
    @Test void referenceBearingActorStateResultsNeedProvenDetachedSnapshot() {
        for (String callback : new String[] {
            "self.handle_state(|s| -> { return s.items; })",
            "using state.read(|s| -> { return s.items; })",
            "self.handle_state(critical read |s| -> { return s.items; })",
            "using state.read(critical read |s| -> { return s.items; })"
        }) {
            String source = "define actor Counter as "
                    + "state { items: List[int] = [1, 2]; } "
                    + "get(): List[int] with state.read { return " + callback + "; } end";
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), callback);
            assertTrue(denied.getMessage().contains("verified detached snapshot"),
                    denied::getMessage);
        }
    }

    @Test void scalarCallbackResultsRemainSupported() {
        for (String callback : new String[] {
            "self.handle_state(|s| -> { return s.v; })",
            "using state.read(|s| -> { return s.v; })",
            "self.handle_state(critical read |s| -> { return s.v; })"
        }) {
            assertDoesNotThrow(() -> TypeChecker.checkTypes(
                    Parser.parse(actor("with state.read", callback))), callback);
        }
    }

    @Test void referenceResultThroughLocalAliasAlsoFailsClosed() {
        for (String callback : new String[] {
            "self.handle_state(|s| -> { const alias = s.items; return alias; })",
            "using state.read(|s| -> { const alias = s.items; return alias; })"
        }) {
            String source = "define actor Counter as "
                    + "state { items: List[int] = [1, 2]; } "
                    + "get(): List[int] with state.read { return " + callback + "; } end";
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), callback);
        }
    }

    @Test void actorClassStorageMustBeStateMap() {
        assertThrows(IllegalArgumentException.class, () ->
                ActorStateContract.verify(Parser.parse(
                    "define actor A as let v: int = 0; end")));
    }
    @Test void actorSuperclassInheritanceCannotBypassSealedStateLayout() {
        // Preserve explicit method-only same-kind actor inheritance.
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse(
                "shared actor Parent { pub fnc inherited(): int { return 1; } } "
                + "shared actor Child extends Parent { "
                + "pub fnc current(): int { return self.inherited(); } }")));

        // Unknown or non-actor parents must never supply hidden instance
        // storage, even when a separate type checker has not run yet.
        Ast.Program unresolved = Parser.parse(
                "define actor Child extends Base as ping(): int { return 1; } end");
        var unresolvedError = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(unresolved));
        assertTrue(unresolvedError.getMessage().contains("same-module actor"));

        Ast.Program inheritedState = Parser.parse(
                "shared actor Parent { state { v: int = 1; } } "
                + "shared actor Child extends Parent { pub fnc current(): int { return 1; } }");
        var storageError = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(inheritedState));
        assertTrue(storageError.getMessage().contains("inherited storage"),
                storageError::getMessage);

        Ast.Program serial = Parser.parse(
                "define serial actor Child extends Base as "
                + "ping(): int { return 1; } end");
        var serialError = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(serial));
        assertTrue(serialError.getMessage().contains("serial actor superclass"),
                serialError::getMessage);

        Ast.Program mismatch = Parser.parse(
                "isoactor Parent { } shared actor Child extends Parent { }");
        var kindError = assertThrows(IllegalArgumentException.class,
                () -> ActorStateContract.verify(mismatch));
        assertTrue(kindError.getMessage().contains("isolation kind"),
                kindError::getMessage);

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

    @Test void unaryAndMutableBorrowCannotEscapeActorStateLoan() {
        // 'rt borrow mut' lowers to UnaryExpr("&mut") rather than a
        // RuntimeCallExpr("borrow"), so both AST spellings need a sealed gate.
        for (String borrow : new String[] {
            "rt borrow mut s.v", "&mut s.v", "&s.v"
        }) {
            for (String callback : new String[] {
                "self.handle_state(|s| -> { " + borrow + "; return s.v; })",
                "using state.read(|s| -> { " + borrow + "; return s.v; })",
                "self.handle_state(critical |s| -> { " + borrow + "; return s.v; })",
                "using state.write(critical |s| -> { " + borrow + "; return s.v; })"
            }) {
                String code = actor("with state.read, state.write", callback);
                Ast.Program program = Parser.parse(code);
                IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(program), callback);
                assertTrue(denied.getMessage().contains("state-backed borrow"),
                    denied::getMessage);
            }
        }
    }

    @Test void scopedStateLoanDoesNotRejectUnrelatedLocalBorrow() {
        for (String callback : new String[] {
            "self.handle_state(|s| -> { const local = 7; &local; return s.v; })",
            "using state.read(|s| -> { const local = 7; &local; return s.v; })"
        }) {
            assertDoesNotThrow(() -> ActorStateContract.verify(
                Parser.parse(actor("with state.read", callback))), callback);
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
