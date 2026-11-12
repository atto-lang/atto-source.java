package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ActorMethodIndex;
import dev.oreslang.compiler.PackageNamespaceBuild;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorContractWitness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real, physical multi-file namespaces: one namespace statement per source
 * file, but a single nominal actor identity per package and namespace.
 *
 * These are static compiler/manifest conformance tests, NOT evidence that
 * external mtd bodies are typechecked, executable, or mailbox dispatched.
 */
final class SameNamespaceDeclaredActorPackageTest {
    @TempDir Path root;

    private static final String NAMESPACE = "namespace foo;\n";
    private static final String ACTOR =
            NAMESPACE + "declare pub actor Counter implements CounterApi, ResetApi;";
    private static final String COUNTER_API =
            NAMESPACE + "define pub contract CounterApi for actor as "
                    + "mtd increment(int delta): int; "
                    + "mtd get_count(): int; end";
    private static final String RESET_API =
            NAMESPACE + "define pub contract ResetApi for actor as "
                    + "mtd reset(): void; end";
    private static final String INCREMENT =
            NAMESPACE + "pub mtd (Counter c) increment(int delta): int { return delta; }";
    private static final String GET_COUNT =
            NAMESPACE + "pub mtd (Counter c) get_count(): int { return 0; }";
    private static final String RESET =
            NAMESPACE + "pub mtd (Counter c) reset(): void { return; }";
    private static final String STATE =
            NAMESPACE + "define state on actor Counter as "
                    + "{ let int count : 0; let bool ready : true; }";

    // Deliberately identical basenames in different directories. Namespace
    // binding uses parsed namespace and proven source-unit identity, not path.
    private static final String ACTOR_PATH = "src/declarations/counter.atto";
    private static final String COUNTER_API_PATH = "src/contracts/counter.atto";
    private static final String RESET_API_PATH = "src/contracts/reset.ores";
    private static final String INCREMENT_PATH = "src/methods/counter.atto";
    private static final String GET_COUNT_PATH = "src/methods/read.atto";
    private static final String RESET_PATH = "src/methods/reset.ores";
    private static final String STATE_PATH = "src/state/counter.atto";

    private void write(String relative, String source) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private void baseSources() throws IOException {
        write(".atto.pkg.toml", """
                schema_version = "1"
                [project]
                name = "multi-file"
                version = "1.2.3"
                root = "."
                [source]
                roots = ["src"]
                """);
        write(ACTOR_PATH, ACTOR);
        write(COUNTER_API_PATH, COUNTER_API);
        write(RESET_API_PATH, RESET_API);
        write(INCREMENT_PATH, INCREMENT);
        write(GET_COUNT_PATH, GET_COUNT);
        write(RESET_PATH, RESET);
        write(STATE_PATH, STATE);
    }

    private PackageNamespaceBuild manifestBuild() throws IOException {
        return new PackageNamespaceBuild(
                OresProjectConfig.load(root.resolve(".atto.pkg.toml"), Map.of()));
    }

    private PackageNamespaceBuild.ActorAudit audit() throws IOException {
        return manifestBuild().auditDeclaredActors();
    }

    private static ActorMethodIndex.ActorKey key(String namespace, String actor) {
        return new ActorMethodIndex.ActorKey("multi-file@1.2.3", namespace, actor);
    }

    private static void assertRejected(IllegalArgumentException error, String token) {
        assertTrue(error.getMessage().contains(token), error.getMessage());
    }

    private static Map<String, Ast.Program> parsed(Map<String, String> sourceFiles) {
        Map<String, Ast.Program> result = new LinkedHashMap<>();
        for (var file : sourceFiles.entrySet()) {
            result.put(file.getKey(), Parser.parse(file.getValue()));
        }
        return result;
    }

    private Map<String, String> readSources() throws IOException {
        Map<String, String> units = new TreeMap<>();
        try (var files = Files.walk(root.resolve("src"))) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                if (path.toString().endsWith(".atto") || path.toString().endsWith(".ores")) {
                    units.put(root.relativize(path).toString().replace('\\', '/'),
                            Files.readString(path));
                }
            }
        }
        return units;
    }

    @Test
    void sevenPhysicalFilesShareOneNominalActorWithoutSourceCoLocation()
            throws IOException {
        baseSources();
        var result = audit();
        assertEquals(7, result.sourceSha256().size());
        assertEquals(1, result.contractWitness().actors().size());
        assertEquals(1, result.descriptors().size());
        var descriptor = result.descriptors().get(key("foo", "Counter"));
        assertNotNull(descriptor);
        assertEquals(ACTOR_PATH, descriptor.actorUnit());
        assertEquals(List.of("count", "ready"),
                descriptor.sealedFields().stream().map(f -> f.name()).toList());
        assertEquals(List.of("0", "true"),
                descriptor.sealedFields().stream().map(f -> f.initialValue()).toList());
        assertEquals(List.of("get_count", "increment", "reset"),
                descriptor.requiredMethods().stream().map(m -> m.name()).toList());
        assertEquals(List.of(COUNTER_API_PATH, COUNTER_API_PATH, RESET_API_PATH),
                descriptor.requiredMethods().stream().map(m -> m.contractUnit()).toList());
        assertEquals(List.of(GET_COUNT_PATH, INCREMENT_PATH, RESET_PATH),
                descriptor.requiredMethods().stream().map(m -> m.methodUnit()).toList());
        var index = result.actorMethods();
        assertEquals(ACTOR_PATH, index.declaringUnit(key("foo", "Counter")));
        assertEquals(INCREMENT_PATH, index.method(
                new ActorMethodIndex.MethodKey(key("foo", "Counter"), "increment", 1))
                .declaringUnit());
        assertEquals(RESET_PATH, index.method(
                new ActorMethodIndex.MethodKey(key("foo", "Counter"), "reset", 0))
                .declaringUnit());
    }

    @Test
    void lexicalSourceAndDiscoveryOrderNeverChangeNamespaceBindings()
            throws IOException {
        baseSources();
        var sources = readSources();
        var files = new ArrayList<>(sources.keySet());
        var reference = ActorContractWitness.linkSourceSet("multi-file@1.2.3",
                parsed(sources));
        var expected = audit().descriptors().get(key("foo", "Counter")).descriptorSha256();
        for (int i = 0; i < files.size() * 2; i++) {
            Collections.rotate(files, 1);
            if ((i & 1) != 0) Collections.reverse(files);
            Map<String, String> shuffled = new LinkedHashMap<>();
            for (String file : files) shuffled.put(file, sources.get(file));
            var actual = ActorContractWitness.linkSourceSet("multi-file@1.2.3",
                    parsed(shuffled));
            assertEquals(reference.actors(), actual.actors());
            assertEquals(reference.boundMethods().keySet(), actual.boundMethods().keySet());
            assertEquals(expected,
                    audit().descriptors().get(key("foo", "Counter")).descriptorSha256());
        }
    }

    @Test
    void sameNamespaceDifferentActorsMayHaveIdenticalMethodSelectors()
            throws IOException {
        baseSources();
        write("src/other-actor/counter.atto", NAMESPACE
                + "declare pub actor Logger implements LoggerApi;");
        write("src/other-contract/counter.atto", NAMESPACE
                + "define pub contract LoggerApi for actor as "
                + "mtd get_count(): int; end");
        write("src/other-method/counter.atto", NAMESPACE
                + "pub mtd (Logger logger) get_count(): int { return 99; }");
        var result = audit();
        assertEquals(2, result.descriptors().size());
        assertTrue(result.descriptors().containsKey(key("foo", "Counter")));
        assertTrue(result.descriptors().containsKey(key("foo", "Logger")));
        assertEquals(GET_COUNT_PATH, result.actorMethods().method(
                new ActorMethodIndex.MethodKey(key("foo", "Counter"), "get_count", 0))
                .declaringUnit());
        assertEquals("src/other-method/counter.atto", result.actorMethods().method(
                new ActorMethodIndex.MethodKey(key("foo", "Logger"), "get_count", 0))
                .declaringUnit());
    }

    @Test
    void equalActorNamesInDifferentNamespacesNeverMerge()
            throws IOException {
        baseSources();
        write("src/foreign/actor.atto", "namespace other;\n"
                + "declare pub actor Counter implements OtherApi;");
        write("src/foreign/contract.atto", "namespace other;\n"
                + "define pub contract OtherApi for actor as mtd reset(): void; end");
        write("src/foreign/method.atto", "namespace other;\n"
                + "pub mtd (Counter c) reset(): void { return; }");
        var result = audit();
        assertEquals(2, result.descriptors().size());
        assertTrue(result.descriptors().containsKey(key("foo", "Counter")));
        assertTrue(result.descriptors().containsKey(key("other", "Counter")));
        assertEquals("src/foreign/actor.atto",
                result.descriptors().get(key("other", "Counter")).actorUnit());
    }

    @Test
    void twoActorsInSameNamespaceHaveIndependentStateLayouts()
            throws IOException {
        baseSources();
        write("src/other-actor/logger.atto", NAMESPACE
                + "declare pub actor Logger;");
        write("src/other-state/logger.atto", NAMESPACE
                + "define state on actor Logger as { let bool active : false; }");
        var result = audit();
        assertEquals(2, result.descriptors().size());
        assertEquals(List.of("count", "ready"),
                result.descriptors().get(key("foo", "Counter")).sealedFields()
                        .stream().map(f -> f.name()).toList());
        assertEquals(List.of("active"),
                result.descriptors().get(key("foo", "Logger")).sealedFields()
                        .stream().map(f -> f.name()).toList());
    }

    @Test
    void addingIndependentSameNamespaceSourceInvalidatesSnapshotFingerprint()
            throws IOException {
        baseSources();
        var before = audit().descriptors().get(key("foo", "Counter"));
        write("src/helpers/helper.atto", NAMESPACE
                + "pub fnc helper(): int { return 22; }");
        var after = audit().descriptors().get(key("foo", "Counter"));
        assertEquals(before.requiredMethods(), after.requiredMethods());
        assertEquals(before.sealedFields(), after.sealedFields());
        assertNotEquals(before.sourceSetSha256(), after.sourceSetSha256());
        assertNotEquals(before.descriptorSha256(), after.descriptorSha256());
    }

    @Test
    void sameNamespaceReceiverFirstAndNameFirstMtdBindToSameActor()
            throws IOException {
        baseSources();
        write(GET_COUNT_PATH, NAMESPACE
                + "pub mtd get_count(Counter c)(): int { return 0; }");
        var audit = audit();
        assertEquals(GET_COUNT_PATH, audit.actorMethods().method(
                new ActorMethodIndex.MethodKey(key("foo", "Counter"), "get_count", 0))
                .declaringUnit());
        assertEquals(3, audit.descriptors().get(key("foo", "Counter"))
                .requiredMethods().size());
    }

    @Test
    void duplicateActorAcrossFilesOfSameNamespaceIsNotPartialDefinition()
            throws IOException {
        baseSources();
        write("src/rogue/counter.atto", ACTOR);
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "duplicate actor/type identity");
    }

    @Test
    void duplicateContractAcrossFilesOfSameNamespaceFails()
            throws IOException {
        baseSources();
        write("src/rogue/contract.atto", COUNTER_API);
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "duplicate actor/type identity");
    }

    @Test
    void duplicateMethodSelectorAcrossFilesOfSameNamespaceFails()
            throws IOException {
        baseSources();
        write("src/rogue/counter.atto", INCREMENT);
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "duplicate external actor method slot");
    }

    @Test
    void duplicateStateAcrossFilesOfSameNamespaceFails()
            throws IOException {
        baseSources();
        write("src/rogue/counter.atto", STATE);
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "duplicate attached actor state schema");
    }

    @Test
    void methodInOtherNamespaceCannotSatisfySameNamedContract()
            throws IOException {
        baseSources();
        write(INCREMENT_PATH, "namespace other;\n"
                + "pub mtd (Counter c) increment(int delta): int { return delta; }");
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "has no nominal actor");
    }

    @Test
    void removingOneMethodFileRevokesOtherwiseCompleteNamespace()
            throws IOException {
        baseSources();
        var build = manifestBuild();
        assertEquals(3, build.auditDeclaredActors().descriptors()
                .get(key("foo", "Counter")).requiredMethods().size());
        Files.delete(root.resolve(GET_COUNT_PATH));
        assertRejected(assertThrows(IllegalArgumentException.class,
                build::auditDeclaredActors), "missing actor contract implementation");
    }

    @Test
    void removingContractFileDoesNotFallBackToOtherNamespace()
            throws IOException {
        baseSources();
        Files.delete(root.resolve(RESET_API_PATH));
        write("src/other/reset.ores", "namespace other;\n"
                + "define pub contract ResetApi for actor as mtd reset(): void; end");
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "missing nominal actor interface contract");
    }

    @Test
    void crossFileSignatureMismatchIsRejectedBeforeCodeGeneration()
            throws IOException {
        baseSources();
        write(GET_COUNT_PATH, NAMESPACE
                + "pub mtd (Counter c) get_count(): bool { return true; }");
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "signature mismatch");
    }

    @Test
    void privateMethodInSeparateFileCannotWitnessPublicContract()
            throws IOException {
        baseSources();
        write(RESET_PATH, NAMESPACE
                + "mtd (Counter c) reset(): void { return; }");
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "must be public");
    }

    @Test
    void sameNamedUntrackedMethodOutsideManifestRootsIsNotLinked()
            throws IOException {
        baseSources();
        Files.delete(root.resolve(INCREMENT_PATH));
        write("untrusted/counter.atto", INCREMENT);
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "missing actor contract implementation");
    }

    @Test
    void missingNamespaceInMethodFileDoesNotJoinSiblingNamespace()
            throws IOException {
        baseSources();
        write(RESET_PATH, "pub mtd (Counter c) reset(): void { return; }");
        assertRejected(assertThrows(IllegalArgumentException.class, this::audit),
                "require an explicit source namespace");
    }

    @Test
    void validCrossFileNamespaceIsStillNotExecutableWithoutMtdLowering()
            throws IOException {
        baseSources();
        var build = manifestBuild();
        assertEquals(1, build.auditDeclaredActors().descriptors().size());
        assertRejected(assertThrows(IllegalArgumentException.class,
                build::build), "DECLARED_ACTOR_CODEGEN_UNSUPPORTED");
    }
}
