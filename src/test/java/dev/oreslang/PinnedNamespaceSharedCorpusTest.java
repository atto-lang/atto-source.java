package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.PinnedNamespaceProgramRunner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed Java/Graal regression target for common-source #197.
 * Authored source bytes transcribed from contract blob 34b2f245000bc5965371e0b03f64ea8af0e5d78f.
 *
 * IMPORTANT: This fixture is pinned-source IN-MEMORY compiler admission,
 * not independent manifest/lockfile verification or cross-backend parity.
 * A green JUnit run must additionally be bound to an exact PR head/tree
 * with nonzero executed CI steps before it counts as native guest evidence.
 */
final class PinnedNamespaceSharedCorpusTest {
    private static final String ENTRY = "app/src/main.ores";

    private record Corpus(Map<String, String> sources, Map<String, String> owners) {}

    private static Corpus sourceCorpus() {
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        sources.put("foo/src/compute/math.ores", "namespace a;\npub fnc add(int a, int b): int { return a + b; }\n");
        owners.put("foo/src/compute/math.ores", "foo@1");
        sources.put("foo/src/compute/stats.ores", "namespace a;\npub fnc double(int value): int { return add(value, value); }\n");
        owners.put("foo/src/compute/stats.ores", "foo@1");
        sources.put("foo/src/compute/aggregation.ores", "namespace a;\npub fnc aggregate(int value): int { return double(value) + 1; }\n");
        owners.put("foo/src/compute/aggregation.ores", "foo@1");
        sources.put("foo/src/data/models.ores", "namespace b;\npub fnc model_code(): int { return 1; }\n");
        owners.put("foo/src/data/models.ores", "foo@1");
        sources.put("foo/src/data/queries.ores", "namespace b;\npub fnc query_code(): int { return 2; }\n");
        owners.put("foo/src/data/queries.ores", "foo@1");
        sources.put("foo/src/data/storage.ores", "namespace b;\npub fnc storage_code(): int { return 3; }\n");
        owners.put("foo/src/data/storage.ores", "foo@1");
        sources.put("foo/src/data/validation.ores", "namespace b;\npub fnc validation_code(): int { return 4; }\n");
        owners.put("foo/src/data/validation.ores", "foo@1");
        sources.put("foo/src/api/routes.ores", "namespace c;\npub fnc route_ok(int code): bool { return code eq 43; }\n");
        owners.put("foo/src/api/routes.ores", "foo@1");
        sources.put("foo/src/api/handlers.ores", "namespace c;\npub fnc handler_ok(int code): bool { return route_ok(code); }\n");
        owners.put("foo/src/api/handlers.ores", "foo@1");
        sources.put("foo/src/api/responses.ores", "namespace c;\npub fnc response_delta(): int { return handler_ok(43) ? 0 : 1; }\n");
        owners.put("foo/src/api/responses.ores", "foo@1");
        sources.put("other/src/collision.ores", "namespace a;\npub fnc add(int a, int b): int { return 99; }\n");
        owners.put("other/src/collision.ores", "other@1");
        sources.put("app/src/main.ores", "import ns a from \"pkg:foo\";\nimport ns c from \"pkg:foo\";\npub fnc main(): int { return a.aggregate(21) + c.response_delta(); }\n");
        owners.put("app/src/main.ores", "app@1");
        assertEquals(12, sources.size());
        assertEquals(sources.keySet(), owners.keySet());
        return new Corpus(sources, owners);
    }

    private static int execute(Corpus corpus, String targetInstance) {
        return PinnedNamespaceProgramRunner.run(
                corpus.sources(), corpus.owners(),
                Map.of("app@1", Map.of("foo", targetInstance)), ENTRY,
                IsolatePolicy.developer(), ExecutionProfile.serverJit()).exitStatus();
    }

    @Test
    void identicalTwelveUnitGuestCorpusExecutesTo43() {
        Corpus corpus = sourceCorpus();
        assertEquals(43, execute(corpus, "foo@1"));
        // Reordering the closed package inventory must not select another
        // package's same-spelling namespace or change observable execution.
        var reversed = new ArrayList<>(corpus.sources().keySet());
        Collections.reverse(reversed);
        Map<String, String> shuffledSources = new LinkedHashMap<>();
        Map<String, String> shuffledOwners = new LinkedHashMap<>();
        for (String key : reversed) {
            shuffledSources.put(key, corpus.sources().get(key));
            shuffledOwners.put(key, corpus.owners().get(key));
        }
        assertEquals(43, execute(new Corpus(shuffledSources, shuffledOwners), "foo@1"));
    }

    @Test
    void otherPackageCannotSupplyMissingAggregateByNamespaceSpelling() {
        Corpus corpus = sourceCorpus();
        corpus.sources().put(ENTRY,
            "import ns a from \"pkg:foo\";\\n"
          + "pub fnc main(): int { return a.aggregate(21); }\\n");
        assertThrows(RuntimeException.class, () -> execute(corpus, "other@1"));
    }

    @Test
    void importedPublicMemberCannotBeMadePrivateAfterSourceVerification() {
        Corpus corpus = sourceCorpus();
        String id = "foo/src/compute/aggregation.ores";
        corpus.sources().put(id, corpus.sources().get(id)
                .replace("pub fnc aggregate", "fnc aggregate"));
        assertThrows(RuntimeException.class, () -> execute(corpus, "foo@1"));
    }

    @Test
    void wrongArityAndMissingAliasNeverProduceGuestExit() {
        Corpus wrongArity = sourceCorpus();
        wrongArity.sources().put(ENTRY, wrongArity.sources().get(ENTRY)
                .replace("a.aggregate(21)", "a.aggregate()"));
        assertThrows(RuntimeException.class, () -> execute(wrongArity, "foo@1"));
        Corpus missingPin = sourceCorpus();
        assertThrows(RuntimeException.class, () ->
            PinnedNamespaceProgramRunner.run(
                missingPin.sources(), missingPin.owners(), Map.of(), ENTRY,
                IsolatePolicy.developer(), ExecutionProfile.serverJit()));
    }
}
