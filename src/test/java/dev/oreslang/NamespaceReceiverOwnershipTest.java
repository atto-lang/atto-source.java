package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorReceiverBinder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guest-source parser plus compiler-owned, source-set-wide nominal binding. */
final class NamespaceReceiverOwnershipTest {
    private static final Path FIXTURES =
            Path.of("contracts", "namespace-receiver-ownership-v1");
    private static final String PACKAGE = "test@1";

    private static Map<String, Ast.Program> units(String... paths) throws IOException {
        Map<String, Ast.Program> parsed = new LinkedHashMap<>();
        for (String path : paths) {
            parsed.put("src/" + path, Parser.parse(Files.readString(FIXTURES.resolve(path))));
        }
        return parsed;
    }

    private static Map<ActorReceiverBinder.SourceSlot, ActorReceiverBinder.SourceBinding>
    bind(String... paths) throws IOException {
        return ActorReceiverBinder.bindSourceSet(PACKAGE, units(paths));
    }

    @Test
    void foreignActorMethodIsCompileTimeErrorEvenWithAnotherLocalActor() throws IOException {
        var error = assertThrows(IllegalArgumentException.class, () -> bind(
                "foo/worker1.atto", "bar/worker2.atto",
                "bar/invalid-worker1.atto", "bar/valid-worker2.atto"));
        assertTrue(error.getMessage().contains("EXTERNAL_RECEIVER_NAMESPACE_MISMATCH"),
                error.getMessage());
        assertTrue(error.getMessage().contains("namespace 'bar'"), error.getMessage());
        assertTrue(error.getMessage().contains("'Worker1'"), error.getMessage());
        assertTrue(error.getMessage().contains("[foo]"), error.getMessage());
    }

    @Test
    void localActorMethodCanAttachAcrossFilesUsingSelfReceiver() throws IOException {
        var result = bind("foo/worker1.atto", "bar/worker2.atto", "bar/valid-worker2.atto");
        var key = new ActorReceiverBinder.SourceSlot(PACKAGE, "bar", "Worker2", "big_ly", 0);
        assertEquals(1, result.size());
        assertEquals("src/bar/worker2.atto", result.get(key).actorUnit());
        assertEquals("src/bar/valid-worker2.atto", result.get(key).methodUnit());
        assertEquals("self", result.get(key).nominalBinding().method().receiverName());
    }

    @Test
    void sameSpellingInBothNamespacesBindsOnlyTheLocalNominalActor() throws IOException {
        var result = bind("foo/worker1.atto", "bar/worker1.atto", "bar/valid-worker1.atto");
        var key = new ActorReceiverBinder.SourceSlot(PACKAGE, "bar", "Worker1", "local_call", 0);
        assertEquals(1, result.size());
        assertEquals("src/bar/worker1.atto", result.get(key).actorUnit());
        assertEquals("bar", key.namespace());
    }

    @Test
    void foreignOrdinaryClassCannotBecomeAnActorReceiver() throws IOException {
        var error = assertThrows(IllegalArgumentException.class,
                () -> bind("foo/widget.atto", "bar/invalid-widget.atto"));
        assertTrue(error.getMessage().contains("EXTERNAL_RECEIVER_NAMESPACE_MISMATCH"),
                error.getMessage());
        assertTrue(error.getMessage().contains("'Widget'"), error.getMessage());
        assertTrue(error.getMessage().contains("[foo]"), error.getMessage());
    }

    @Test
    void UnknownReceiverCannotFallBackToAnotherNamespaceOrGlobalType() throws IOException {
        var error = assertThrows(IllegalArgumentException.class, () ->
                ActorReceiverBinder.bindSourceSet(PACKAGE, Map.of(
                        "src/unknown.atto", Parser.parse(
                                "use namespace bar; pub mtd (Missing self) big_ly(): void { return; }"))));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
    }

    @Test
    void secondNamespaceHeaderInOneSourceMustBeMovedToAnotherFile() {
        var error = assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "use namespace foo; declare actor Worker1; "
                        + "use namespace bar; declare actor Worker2;"));
        assertTrue(error.getMessage().contains("namespace header must occur once"),
                error.getMessage());
    }

    @Test
    void localTypeAliasCannotBeMistakenForForeignActorHomonym() {
        var sourceSet = Map.of(
                "src/foo.atto", Parser.parse(
                        "use namespace foo; declare actor Worker1;"),
                "src/bar.atto", Parser.parse(
                        "use namespace bar; define type Worker1 = int; "
                                + "pub mtd (Worker1 self) big_ly(): void { return; }"));
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorReceiverBinder.bindSourceSet(PACKAGE, sourceSet));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
        assertTrue(!error.getMessage().contains("EXTERNAL_RECEIVER_NAMESPACE_MISMATCH"),
                error.getMessage());
    }

}
