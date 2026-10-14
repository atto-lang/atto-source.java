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

    private static String actorWithReferenceState(String expression) {
        return """
                define actor Counter as
                  state { n: int = 0; cache: Map<string, int>; label: string; }
                  probe(): int with state.read {
                    return self.handle_state(|s| -> {
                      %s
                    });
                  }
                end
                """.formatted(expression);
    }

    @Test void slimArrowStateCallbackCannotImplicitlyReturnMutableReference() {
        for (String callback : new String[] {
                "self.handle_state(|s| -> s.cache)",
                "using state.read(|s| -> s.cache)"
        }) {
            String source = """
                    define actor Counter as
                      state { n: int = 0; cache: Map<string, int>; }
                      probe(): Map<string, int> with state.read {
                        return %s;
                      }
                    end
                    """.formatted(callback);
            Ast.Program parsed = Parser.parse(source);
            var rejected = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(parsed), callback);
            assertTrue(rejected.getMessage().contains("expression-bodied"),
                    rejected.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(parsed), callback);
        }
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse("""
                define actor Counter as
                  state { n: int = 2; }
                  probe(): int with state.read {
                    return using state.read(|s| -> s.n);
                  }
                end
                """)));
    }

    @Test void nestedReferenceStateOperationsFailClosedWithoutGraphOwnershipProof() {
        // Direct s.cache egress was already rejected. Member/index
        // operations on s.cache are more subtle: they access state-owned
        // heap identity while allowing method effects and nested writes.
        for (String statement : new String[] {
                "s.cache.item = 1; return s.n;",
                "s.cache.put(\"item\", 1); return s.n;",
                "return s.cache[\"item\"];",
                "return s.cache.item;",
                "return (s.cache).item;"
        }) {
            for (String acquisition : new String[] {
                    "self.handle_state(critical |s| -> {",
                    "using state.write(critical |s| -> {"
            }) {
                String source = actorWithReferenceState(statement)
                        .replace("with state.read", "with state.read, state.write")
                        .replace("self.handle_state(|s| -> {", acquisition);
                Ast.Program parsed = Parser.parse(source);
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> ActorStateContract.verify(parsed),
                        acquisition + " " + statement);
                assertTrue(failure.getMessage().contains("nested actor state reference"),
                        failure.getMessage());
            }
        }
    }

    @Test void referenceDefaultsCannotImportUnprovenExternalHeapObjects() {
        for (String declaration : new String[] {
                "cache: Map<string, int> = external;",
                "cache: Map<string, int> = 1;",
                "cache: Map<string, int> = factory();"
        }) {
            Ast.Program parsed = Parser.parse(
                    "define actor Bad as state { " + declaration + " } end");
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(parsed), declaration);
            assertTrue(failure.getMessage().contains("reference default"),
                    failure.getMessage());
        }
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse(
                "define actor Good as "
                + "state { n: int = 3; label: string = \"local\"; } "
                + "get(): int with state.read { "
                + "return self.handle_state(|s| -> { return s.n; }); } end")));
    }

    @Test void referenceValuedStateProjectionCannotEscapeLoan() {
        for (String body : new String[] {
                "return s.cache;",
                "val alias = s.cache; return s.n;",
                "val (alias) = (s.cache); return s.n;",
                "out = s.cache; return s.n;",
                "sink(s.cache); return s.n;"
        }) {
            var parsed = Parser.parse(actorWithReferenceState(body));
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(parsed), body);
            assertTrue(failure.getMessage().contains("reference projection"),
                    failure.getMessage());
        }
    }

    @Test void fullTypeCheckerRejectsReferenceEscapeThroughBothStateForms() {
        for (String callback : new String[] {
                "self.handle_state(|s| -> { return s.cache; })",
                "using state.read(|s| -> { return s.cache; })"
        }) {
            String source = actorWithReferenceState("return s.cache;")
                    .replace("probe(): int", "probe(): Map<string, int>")
                    .replace("self.handle_state(|s| -> {\n      return s.cache;\n    })", callback);
            // Use the real typechecker entrypoint, not a direct-only AST
            // contract call, to ensure the mandatory pass is wired in.
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), callback);
            assertTrue(failure.getMessage().contains("reference projection"),
                    failure.getMessage());
        }
    }

    @Test void mutableExternalReferenceCannotEnterActorStateThroughWriteLoan() {
        for (String statement : new String[] {
                "s.cache = external;",
                "s.cache = s.cache;",
                "s.cache = factory();"
        }) {
            String source = actorWithReferenceState(statement + " return s.n;")
                    .replace("probe(): int with state.read",
                             "probe(Map<string, int> external): int with state.read, state.write")
                    .replace("self.handle_state(|s|",
                             "self.handle_state(critical |s|");
            Ast.Program parsed = Parser.parse(source);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ActorStateContract.verify(parsed), statement);
            assertTrue(failure.getMessage().contains("ownership transfer"),
                    failure.getMessage());
        }
        assertDoesNotThrow(() -> ActorStateContract.verify(Parser.parse(
                actor("with state.read, state.write",
                        "self.handle_state(critical |s| -> { "
                        + "s.v = s.v + 1; return s.v; })"))));
    }

    @Test void detachedPrimitiveAndImmutableTextReadsStayLegal() {
        assertDoesNotThrow(() -> ActorStateContract.verify(
                Parser.parse(actorWithReferenceState("return s.n;"))));
        assertDoesNotThrow(() -> ActorStateContract.verify(
                Parser.parse(actorWithReferenceState(
                        "val copied = s.n; return copied;"))));
        assertDoesNotThrow(() -> ActorStateContract.verify(
                Parser.parse(actorWithReferenceState(
                        "val immutableText = s.label; return s.n;"))));
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
        for (String op : new String[] { "take", "share", "borrow", "copy" }) {
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
