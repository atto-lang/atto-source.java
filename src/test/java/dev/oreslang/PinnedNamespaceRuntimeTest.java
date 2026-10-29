package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.PinnedNamespaceProgramRunner;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class PinnedNamespaceRuntimeTest {
    private static final String FOO = "foo@sha256:verified";
    private static final String APP = "app@sha256:verified";
    private static final String ENTRY = "app/src/main.ores";

    private static final String[] NAMES = {
            "compute/math", "compute/stats", "compute/aggregation",
            "data/models", "data/queries", "data/storage", "data/validation",
            "api/routes", "api/handlers", "api/responses"
    };

    private static final String[] CODE = {
            "namespace a;\npub fnc add(int x, int y): int { return x + y; }\n",
            "namespace a;\npub fnc double(int x): int { return x * 2; }\nfnc hidden(): int { return 9; }\n",
            "namespace a;\npub fnc aggregate(int x): int { return x + 1; }\n",
            "namespace b;\npub fnc models(): int { return 1; }\n",
            "namespace b;\npub fnc queries(): int { return 1; }\n",
            "namespace b;\npub fnc storage(): int { return 1; }\n",
            "namespace b;\npub fnc validation(): int { return 1; }\n",
            "namespace c;\npub fnc routes(): int { return 1; }\n",
            "namespace c;\npub fnc handlers(): int { return 1; }\n",
            "namespace c;\npub fnc responses(): int { return 1; }\n"
    };

    private static Map<String, String> sources(String entry) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (int i = 0; i < NAMES.length; i++)
            sources.put("foo/src/" + NAMES[i] + ".ores", CODE[i]);
        sources.put(ENTRY, entry);
        return sources;
    }

    private static Map<String, String> owners() {
        Map<String, String> owners = new LinkedHashMap<>();
        for (String name : NAMES) owners.put("foo/src/" + name + ".ores", FOO);
        owners.put(ENTRY, APP);
        return owners;
    }

    private static final Map<String, Map<String, String>> PINS =
            Map.of(APP, Map.of("foo", FOO));

    private static int run(Map<String, String> source, Map<String, String> owners,
                           Map<String, Map<String, String>> pins) {
        return PinnedNamespaceProgramRunner.run(source, owners, pins, ENTRY,
                IsolatePolicy.developer(), ExecutionProfile.serverJit()).exitStatus();
    }

    @Test
    void executesRealTenFileThreeNamespaceGuestImportTo43() {
        var result = PinnedNamespaceProgramRunner.run(sources("""
                import ns a from 'pkg:foo';
                pub fnc main(): int {
                    return a.aggregate(a.double(a.add(20, 1)));
                }
                """), owners(), PINS, ENTRY,
                IsolatePolicy.developer(), ExecutionProfile.serverJit());
        assertEquals(43, result.exitStatus());
        assertEquals(11, result.build().units().size());
        assertEquals(10, result.build().initializationOrder().stream()
                .filter(unit -> unit.startsWith("foo/")).count());
    }

    @Test
    void runtimeRejectsMissingOrPrivateNamespaceMember() {
        for (String member : new String[]{"hidden", "absent"}) {
            Map<String, String> units = sources(
                    "import ns a from 'pkg:foo';\n"
                            + "pub fnc main(): int { return a." + member + "(); }\n");
            assertThrows(RuntimeException.class, () -> run(units, owners(), PINS));
        }
    }

    @Test
    void selectedPackageMustExistAndOwnTheNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> run(sources("""
                    import ns a from 'pkg:foo';
                    pub fnc main(): int { return a.add(21,21); }
                    """), owners(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> run(sources("""
                    import ns a from 'pkg:foo';
                    pub fnc main(): int { return a.add(21,21); }
                    """), owners(), Map.of(APP, Map.of("foo", "foo@wrong"))));
    }

    @Test
    void legacyNoPackageGraphCompilationStillFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(ENTRY, """
                    import ns a from 'pkg:foo';
                    pub fnc main(): int { return 43; }
                    """)));
    }

    @Test
    void sameSpellingCannotHijackPinnedNamespaceFromAnotherPackage() {
        Map<String, String> units = sources("""
                import ns a from 'pkg:foo';
                pub fnc main(): int { return a.only_other(); }
                """);
        Map<String, String> owned = owners();
        units.put("other/src/only.ores",
                "namespace a;\npub fnc only_other(): int { return 43; }\n");
        owned.put("other/src/only.ores", "other@sha256:verified");
        assertThrows(RuntimeException.class, () -> run(units, owned, PINS));
    }
}
