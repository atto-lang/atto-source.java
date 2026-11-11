package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual .atto parser/ownership and Graal guest verification, not a
 * source-free model or host-only allocation oracle.
 */
final class ActorStateContainerOwnershipCompilerTest {
    private static String fixture(String name) throws Exception {
        String resource = "/ownership/actor-state/" + name;
        try (InputStream stream =
                ActorStateContainerOwnershipCompilerTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("missing mandatory .atto source fixture: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static IllegalArgumentException rejected(String name) throws Exception {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(fixture(name))));
    }

    @Test
    void stateGroupIsLoweredIntoPrivateActorOwnedAstFields() throws Exception {
        Ast.Program program = Parser.parse(fixture("valid-counter.atto"));
        Ast.ClassDecl actor = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .filter(declaration -> declaration.name().equals("Counter"))
                .findFirst().orElseThrow();
        assertNotEquals(Ast.ActorKind.NONE, actor.actorKind());
        assertEquals(1, actor.fields().size());
        assertEquals("count", actor.fields().getFirst().name());
        assertEquals(Ast.Visibility.PRIVATE, actor.fields().getFirst().visibility(),
                "state members cannot silently become public cross-actor aliases");
    }

    @Test
    void groupedActorStateIsAcceptedByActualOwnershipCompiler() throws Exception {
        assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse(fixture("valid-counter.atto"))));
    }

    @Test
    void groupedActorStateExecutesAsIndependentGuestActorState() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, fixture("valid-counter.atto"), "valid-counter.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("1", output.toString(StandardCharsets.UTF_8),
                "the parser group must execute through the real actor guest path");
    }

    @Test
    void separateCompiledActorsDoNotShareGroupedMutableState() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, fixture("valid-two-actors.atto"),
                        "valid-two-actors.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("1|1", output.toString(StandardCharsets.UTF_8),
                "two actors sharing immutable code must never share mutable state slots");
    }

    @Test
    void fixedWidthI64ActorCounterCompilesAndExecutesThroughRealGuest() throws Exception {
        String program = fixture("valid-primitive-i64-counter.atto");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "valid-primitive-i64-counter.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("2", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void copyScalarCannotBeTakenOutOfActorOwnedState() throws Exception {
        var error = rejected("reject-take-field.atto");
        assertTrue(error.getMessage().contains("rt take cannot transfer actor-owned state"),
                error.getMessage());
    }

    @Test
    void publicActorStateFieldIsRejectedAtParseTime() throws Exception {
        var error = rejected("reject-public-field.atto");
        assertTrue(error.getMessage().contains("actor state fields are private"),
                error.getMessage());
    }

    @Test
    void duplicateStateContainersCannotSplitActorOwnership() throws Exception {
        var error = rejected("reject-two-state-blocks.atto");
        assertTrue(error.getMessage().contains("one state container"),
                error.getMessage());
    }

    @Test
    void bareFieldsBeforeStateContainerCannotBypassCanonicalFieldAdmission() throws Exception {
        var error = rejected("reject-mixed-bare-before-state.atto");
        assertTrue(error.getMessage().contains("cannot mix bare fields and a state container"),
                error.getMessage());
    }

    @Test
    void bareFieldsAfterStateContainerCannotBypassCanonicalFieldAdmission() throws Exception {
        var error = rejected("reject-mixed-state-before-bare.atto");
        assertTrue(error.getMessage().contains("cannot mix a state container and bare fields"),
                error.getMessage());
    }

    @Test
    void actorStateContainerCannotBePublicOrModifierDecorated() throws Exception {
        var error = rejected("reject-state-container-modifiers.atto");
        assertTrue(error.getMessage().contains("state container does not accept modifiers"),
                error.getMessage());
    }

    @Test
    void legacyUngroupedActorFieldsStillParseWithoutAContainer() {
        String legacy = """
                define actor LegacyCounter as
                  let int count;
                  receive(ActorMail<String> mail): void { return; }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(legacy)),
                "the .ores era field shape remains supported if no canonical state block exists");
    }

    @Test
    void stateContainerRejectsMethodsAndNestedDeclarations() throws Exception {
        var error = rejected("reject-method-in-state.atto");
        assertTrue(error.getMessage().contains("private fields only"),
                error.getMessage());
    }
}
