package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only parses and checks nominal provenance; state loans and actor guest
 * execution are intentionally unsupported.
 */
final class NamespaceActorStateAdmissionTest {
    private static Ast.Program parse(String body) {
        return Parser.parse("namespace foo;\n" + body);
    }

    private static Map<String, Ast.Program> units(String actor, String schema) {
        Map<String, Ast.Program> source = new LinkedHashMap<>();
        source.put("src/state.atto", parse(schema));
        source.put("src/actor.atto", parse(actor));
        return source;
    }

    private static String actor() {
        return "pub define actor Foo;";
    }

    private static String state() {
        return "define state on actor Foo as { let int x: 5; }";
    }

    @Test
    void requestedSyntaxRetainsOnlyASealedStateSchema() {
        var program = parse(actor() + "\n" + state());
        var decls = program.modules().getFirst().declarations();
        assertEquals(2, decls.size());
        var state = assertInstanceOf(Ast.ActorStateDecl.class, decls.get(1));
        assertEquals("Foo", state.actorName());
        assertEquals("x", state.fields().getFirst().name());
        assertEquals("int", state.fields().getFirst().type().name());
        var literal = assertInstanceOf(Ast.LiteralExpr.class,
                state.fields().getFirst().initializer());
        assertEquals(5L, literal.value());
        assertThrows(UnsupportedOperationException.class, () -> state.fields().clear());
    }

    @Test
    void crossFileStateAttachesToExactlyOneNamespaceActor() {
        Map<String, Ast.Program> source = units(actor(), state());
        var bound = ActorStateBinder.bindSourceSet("demo@1.0.0", source);
        assertEquals(1, bound.size());
        var slot = new ActorStateBinder.Slot("demo@1.0.0", "foo", "Foo");
        assertEquals("src/actor.atto", bound.get(slot).actorUnit());
        assertEquals("src/state.atto", bound.get(slot).stateUnit());
        assertNotNull(ActorReceiverBinder.bindSourceSet("demo@1.0.0", source));
        assertThrows(UnsupportedOperationException.class, bound::clear);
    }

    @Test
    void stateDoesNotExposeMutableStateThroughOrdinaryMethods() {
        var program = parse(actor() + "\n" + state()
                + "\npub mtd (Foo f) read(): int { return 5; }");
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("LO WAY") == false);
        assertTrue(error.getMessage().contains("LOWERING_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void privateStateCannotHavePublicVisibility() {
        assertThrows(IllegalArgumentException.class,
                () -> parse(actor() + "\npub " + state()));
    }

    @Test
    void duplicateFieldIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse(actor()
                + "\ndefine state on actor Foo as { let int x: 5; let int x: 7; }"));
    }

    @Test
    void missingActorIsNotAStateOwner() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("demo@1.0.0",
                        Map.of("src/state.atto", parse(state()))));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
    }

    @Test
    void ordinaryClassCannotAcquireActorState() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("demo@1.0.0",
                        units("pub define class Foo as end", state())));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
    }

    @Test
    void wrongNamespaceCannotAttachStateToActor() {
        var source = new LinkedHashMap<String, Ast.Program>();
        source.put("src/actor.atto", parse(actor()));
        source.put("src/state.atto", Parser.parse(
                "namespace other; " + state()));
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("demo@1.0.0", source));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
    }

    @Test
    void duplicateSchemasAcrossFilesAreDenied() {
        var source = units(actor(), state());
        source.put("src/other-state.atto", parse(state()));
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("demo@1.0.0", source));
        assertTrue(error.getMessage().contains("duplicate attached state"), error.getMessage());
    }

    @Test
    void initializerMustBeLiteralSignedInt64() {
        assertThrows(IllegalArgumentException.class, () -> parse(actor()
                + "\ndefine state on actor Foo as { let int x: do_work(); }"));
        assertThrows(IllegalArgumentException.class, () -> parse(actor()
                + "\ndefine state on actor Foo as { let int x: 9223372036854775808; }"));
        assertThrows(IllegalArgumentException.class, () -> parse(actor()
                + "\ndefine state on actor Foo as { let string x: 5; }"));
        var signed = parse(actor()
                + "\ndefine state on actor Foo as { let int x: -9223372036854775808; }");
        var state = assertInstanceOf(Ast.ActorStateDecl.class,
                signed.modules().getFirst().declarations().get(1));
        assertEquals(Long.MIN_VALUE,
                assertInstanceOf(Ast.LiteralExpr.class, state.fields().getFirst().initializer()).value());
    }

    @Test
    void stateFieldCannotCollideWithActorMethod() {
        var source = units(actor(),
                state() + "\npub mtd (Foo f) x(): void { return; }");
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("demo@1.0.0", source));
        assertTrue(error.getMessage().contains("conflicts with actor method"), error.getMessage());
    }

    @Test
    void duplicateOrReservedStateNamesNeverCreateAuthority() {
        assertThrows(IllegalArgumentException.class, () -> parse(actor()
                + "\ndefine state on actor Foo as { let int handle_state: 5; }"));
        assertThrows(IllegalArgumentException.class, () -> parse(actor()
                + "\ndefine state on actor Foo as { let int state: 5; }"));
    }

    @Test
    void stateOnActorWithContractDoesNotSatisfyContract() {
        var source = units("pub define actor Foo implements ActorXYZ;", state());
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("demo@1.0.0", source));
        assertTrue(error.getMessage().contains("ACTOR_CONTRACT_WITNESS_UNSUPPORTED"),
                error.getMessage());
    }

    @Test
    void sourceMapOrderAndPackageIdentityDoNotChangeOwnership() {
        Map<String, Ast.Program> source = units(actor(), state());
        var first = ActorStateBinder.bindSourceSet("demo@1.0.0", source);
        Map<String, Ast.Program> reverse = new LinkedHashMap<>();
        reverse.put("src/actor.atto", source.get("src/actor.atto"));
        reverse.put("src/state.atto", source.get("src/state.atto"));
        assertEquals(first, ActorStateBinder.bindSourceSet("demo@1.0.0", reverse));
        assertFalse(ActorStateBinder.bindSourceSet("other@1.0.0", reverse)
                .containsKey(new ActorStateBinder.Slot("demo@1.0.0", "foo", "Foo")));
    }

    @Test
    void sourceIdTraversalAndCaseCollisionFailBeforeLinking() {
        var source = units(actor(), state());
        source.put("src/../bad.atto", parse(state()));
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("demo@1.0.0", source));
        source.remove("src/../bad.atto");
        source.put("SRC/STATE.ATTO", parse(state()));
        assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("demo@1.0.0", source));
    }
}
