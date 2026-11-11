package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-file actor attachment is a static declaration-index proof, not
 * executable guest dispatch or permission to borrow actor-owned state.
 */
final class CrossFileActorReceiverBindingTest {
    private static final Path FIXTURES =
            Path.of("contracts", "cross-file-actor-mtd-v1");

    private static String source(String name) throws IOException {
        return Files.readString(FIXTURES.resolve(name));
    }

    private static Map<String, Ast.Program> parsed(String file1, String file2) {
        Map<String, Ast.Program> units = new LinkedHashMap<>();
        units.put("src/foo/file2.atto", Parser.parse(file2));
        units.put("src/foo/file1.atto", Parser.parse(file1));
        return units;
    }

    private static Map<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding>
    resolve(String file1, String file2) {
        return ActorReceiverBinder.bindSourceSet("demo@1.0.0", parsed(file1, file2));
    }

    private static ActorReceiverBinder.SourceSlot slot(String method) {
        return new ActorReceiverBinder.SourceSlot("demo@1.0.0", "foo", "Bar", method, 0);
    }

    @Test
    void exactTwoFileNamespaceAttachesBothExternalMethodsToTheSameActor() throws IOException {
        var bindings = resolve(source("file1.atto"), source("file2.atto"));
        assertEquals(2, bindings.size());
        assertEquals("src/foo/file1.atto", bindings.get(slot("do_x")).actorUnit());
        assertEquals("src/foo/file1.atto", bindings.get(slot("do_x")).methodUnit());
        assertEquals("src/foo/file1.atto", bindings.get(slot("do_y")).actorUnit());
        assertEquals("src/foo/file2.atto", bindings.get(slot("do_y")).methodUnit());
        assertEquals("Bar", bindings.get(slot("do_y")).nominalBinding().method().actorName());
        assertEquals("b", bindings.get(slot("do_y")).nominalBinding().method().receiverName());
        assertEquals(Ast.Visibility.PRIVATE,
                bindings.get(slot("do_y")).nominalBinding().method().visibility());
        assertThrows(UnsupportedOperationException.class, bindings::clear);
    }

    @Test
    void receiverFirstAndLegacyNameFirstSpellingsHaveIdenticalSlots() {
        String actor = "namespace foo; define actor Bar as end";
        String receiverFirst = "namespace foo; define mtd (Bar b) do_x(): void { return; }";
        String legacy = "namespace foo; define mtd do_x(Bar b)(): void { return; }";
        var canonical = resolve(actor, receiverFirst);
        var old = resolve(actor, legacy);
        assertEquals(canonical.keySet(), old.keySet());
        assertEquals(
                canonical.get(slot("do_x")).nominalBinding().method().parameters().size(),
                old.get(slot("do_x")).nominalBinding().method().parameters().size());
    }

    @Test
    void sameSpellingInDifferentNamespaceDoesNotSeeActor() {
        var error = assertThrows(IllegalArgumentException.class, () -> resolve(
                "namespace foo; define actor Bar as end",
                "namespace other; define mtd (Bar b) do_x(): void { return; }"));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
    }

    @Test
    void duplicateExternalMethodAcrossFilesIsACompileTimeError() {
        var error = assertThrows(IllegalArgumentException.class, () -> resolve(
                "namespace foo; define actor Bar as end\n"
                        + "define mtd (Bar b) do_x(): void { return; }",
                "namespace foo; define mtd (Bar b) do_x(): void { return; }"));
        assertTrue(error.getMessage().contains("duplicate external actor method slot"),
                error.getMessage());
    }

    @Test
    void inActorMethodConflictsWithCrossFileExternalMethod() {
        var error = assertThrows(IllegalArgumentException.class, () -> resolve(
                "namespace foo; define actor Bar as do_x(): void { return; } end",
                "namespace foo; define mtd (Bar b) do_x(): void { return; }"));
        assertTrue(error.getMessage().contains("conflicts with an in-class actor method"),
                error.getMessage());
    }

    @Test
    void ordinaryClassDoesNotBecomeAnActorByNamespaceCoincidence() {
        var error = assertThrows(IllegalArgumentException.class, () -> resolve(
                "namespace foo; define class Bar as end",
                "namespace foo; define mtd (Bar b) do_x(): void { return; }"));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
    }

    @Test
    void publicExternalMethodCannotLeakPrivateActorAcrossFiles() {
        var error = assertThrows(IllegalArgumentException.class, () -> resolve(
                "namespace foo; define actor Bar as end",
                "namespace foo; define pub mtd (Bar b) do_x(): void { return; }"));
        assertTrue(error.getMessage().contains("cannot expose private actor"), error.getMessage());
    }

    @Test
    void namedModulesNeverMergeThroughAnEqualTextualNamespace() {
        var error = assertThrows(IllegalArgumentException.class, () -> resolve(
                "namespace foo; define actor Bar as end",
                "namespace foo; define module other as\n"
                        + " define mtd (Bar b) do_x(): void { return; }\nend"));
        assertTrue(error.getMessage().contains("named modules do not share"), error.getMessage());
    }

    @Test
    void packageIdentityIsPartOfEverySlot() {
        String actor = "namespace foo; define actor Bar as end";
        String method = "namespace foo; define mtd (Bar b) do_x(): void { return; }";
        var units = parsed(actor, method);
        var first = ActorReceiverBinder.bindSourceSet("demo@1.0.0", units);
        var second = ActorReceiverBinder.bindSourceSet("different@1.0.0", units);
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertFalse(second.containsKey(slot("do_x")));
        assertNotNull(first.get(slot("do_x")));
    }

    @Test
    void reverseInputOrderCannotChangeBindings() {
        var first = parsed(
                "namespace foo; define actor Bar as end",
                "namespace foo; define mtd (Bar b) do_x(): void { return; }");
        var reverse = new LinkedHashMap<String, Ast.Program>();
        reverse.put("src/foo/file1.atto", first.get("src/foo/file1.atto"));
        reverse.put("src/foo/file2.atto", first.get("src/foo/file2.atto"));
        assertEquals(
                ActorReceiverBinder.bindSourceSet("demo@1.0.0", first),
                ActorReceiverBinder.bindSourceSet("demo@1.0.0", reverse));
    }

    @Test
    void aStaticallyBoundExternalMethodStillFailsClosedForGuestExecution() {
        String actor = "namespace foo; define actor Bar as end";
        String method = "namespace foo; define mtd (Bar b) do_x(): void { return; }";
        assertEquals(1, resolve(actor, method).size());
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(method)),
                "nominal metadata must not silently authorize mailbox execution");
    }
}
