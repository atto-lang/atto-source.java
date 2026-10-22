package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static source-import-to-closed-graph checks only. This is not executable
 * import linking and never allows guest Valid<T,S> construction.
 */
final class CompilerZordeParsedImportLinkTest {
    private static final String USERS =
            "namespace users; @MinLength(1) define pub newtype Username(string);";
    private static final String API =
            "namespace api; "
            + "import type Username from \"svc.users\"; "
            + "@Range(min=1,max=100) type Port = int;";

    private static CompilerZordeSchemaRegistry staged(String source, String unit) {
        return CompilerZordeSchemaRegistry.stageSourceWithParsedImportsForAnalysis(
                source, unit, 3);
    }

    private static Map<String, CompilerZordeSchemaRegistry> sources(String api) {
        return Map.of("svc.api", staged(api, "svc.api"),
                "svc.users", staged(USERS, "svc.users"));
    }

    private static Map<String, CompilerZordeLinkManifest.Pin> pinned(
            Map<String, CompilerZordeSchemaRegistry> source) {
        var api = source.get("svc.api");
        var users = source.get("svc.users");
        return Map.of(
                "svc.api", new CompilerZordeLinkManifest.Pin(
                        api.sourceDigest(), api.generation(),
                        Map.of("svc.users", users.sourceDigest())),
                "svc.users", new CompilerZordeLinkManifest.Pin(
                        users.sourceDigest(), users.generation(), Map.of()));
    }

    private static CompilerZordeLinkManifest verify(
            Map<String, CompilerZordeSchemaRegistry> candidates,
            Map<String, CompilerZordeLinkManifest.Pin> pins) {
        return CompilerZordeLinkManifest.verifyParsedTypeImportsForAnalysis(
                "java-dev-1", "graalvm-linux-arm64", candidates, pins,
                Set.of("svc.api"));
    }

    @Test void parsedNamedTypeImportsResolveExactlyAgainstPinnedExports() {
        var candidates = sources(API);
        assertEquals(Map.of("svc.users", Set.of("Username")),
                candidates.get("svc.api").parsedTypeImports());
        assertEquals(Map.of(), candidates.get("svc.users").parsedTypeImports());

        var manifest = verify(candidates, pinned(candidates));
        assertEquals(List.of("svc.users", "svc.api"),
                manifest.dependencyFirstOrder());
        assertEquals(1, manifest.schemaVersion("svc.users", "users.Username"));
        var checked = manifest.checkedTypeForLinkedAnalysis("svc.api", "api.Port", 5L);
        assertTrue(Types.containsCompilerCheckedScalar(checked));
        assertNotNull(manifest.checkedTypeForLinkedAnalysis(
                "svc.users", "users.Username", "🦋"));
        assertEquals(64, manifest.digest().length());
        assertThrows(IllegalArgumentException.class,
                () -> manifest.checkedTypeForLinkedAnalysis(
                        "svc.api", "users.Username", "a"));

        // Source registries with imports MUST NOT use the old caller-graph
        // entrypoint, even if its dependency pins happen to match.
        assertThrows(IllegalArgumentException.class, () ->
                CompilerZordeLinkManifest.verifyForAnalysis(
                        "java-dev-1", "graalvm-linux-arm64",
                        candidates, pinned(candidates), Set.of("svc.api")));
        assertThrows(IllegalArgumentException.class, () ->
                CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                        API, "svc.api", 3));
    }

    @Test void callerCannotInventOrDeleteParsedSourceDependencies() {
        var candidates = sources(API);
        var api = candidates.get("svc.api");
        var users = candidates.get("svc.users");
        var noEdge = Map.of(
                "svc.api", new CompilerZordeLinkManifest.Pin(
                        api.sourceDigest(), api.generation(), Map.of()),
                "svc.users", new CompilerZordeLinkManifest.Pin(
                        users.sourceDigest(), users.generation(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> verify(candidates, noEdge));

        var badHash = new HashMap<>(pinned(candidates));
        badHash.put("svc.api", new CompilerZordeLinkManifest.Pin(
                api.sourceDigest(), api.generation(),
                Map.of("svc.users", "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> verify(candidates, badHash));

        var extra = new HashMap<>(pinned(candidates));
        extra.put("svc.users", new CompilerZordeLinkManifest.Pin(
                users.sourceDigest(), users.generation(),
                Map.of("svc.api", api.sourceDigest())));
        assertThrows(IllegalArgumentException.class, () -> verify(candidates, extra));

        var stale = new HashMap<>(pinned(candidates));
        stale.put("svc.users", new CompilerZordeLinkManifest.Pin(
                users.sourceDigest(), users.generation() + 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> verify(candidates, stale));
        assertThrows(IllegalArgumentException.class, () ->
                verify(Map.of("svc.api", api), Map.of("svc.api",
                        pinned(candidates).get("svc.api"))));
    }

    @Test void unknownImportedSymbolsAreNotApprovedJustByPinnedPaths() {
        String unknown = API.replace("import type Username", "import type Missing");
        var candidates = sources(unknown);
        assertThrows(IllegalArgumentException.class,
                () -> verify(candidates, pinned(candidates)));

        String misspelledUnit = API.replace("\"svc.users\"", "\"svc.unknown\"");
        var unresolved = sources(misspelledUnit);
        var wrongPin = Map.of(
                "svc.api", new CompilerZordeLinkManifest.Pin(
                        unresolved.get("svc.api").sourceDigest(), 3,
                        Map.of("svc.unknown", "0".repeat(64))),
                "svc.users", new CompilerZordeLinkManifest.Pin(
                        unresolved.get("svc.users").sourceDigest(), 3, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> verify(unresolved, wrongPin));
    }

    @Test void aPrivateSchemaCannotBeImportedAcrossSourceUnits() {
        String privateSource =
                "namespace users; @MinLength(1) define newtype Username(string);";
        var candidates = Map.of(
                "svc.api", staged(API, "svc.api"),
                "svc.users", staged(privateSource, "svc.users"));
        assertTrue(candidates.get("svc.users")
                .schemaEntriesForLinker().containsKey("users.Username"));
        assertFalse(candidates.get("svc.users")
                .publicSchemaNames().contains("users.Username"));
        assertThrows(IllegalArgumentException.class,
                () -> verify(candidates, pinned(candidates)));
    }

    @Test void dynamicWildcardAliasAndDuplicateImportsFailClosed() {
        for (String declaration : List.of(
                "import * as Mod from \"svc.users\";",
                "import type * as Names from \"svc.users\";",
                "import type Username as Alias from \"svc.users\";",
                "import type Username from \"svc.users\"; import type Username from \"svc.users\";",
                "import type Username from \"../escape\";",
                "import type Username from \"svc/../users\";",
                "import type Username from \"svc.api\";",
                "import type Port from \"svc.users\";",
                "import fnc get_user from \"svc.users\";")) {
            String source = "namespace api; " + declaration
                    + " @Range(min=1,max=100) type Port = int;";
            assertThrows(RuntimeException.class, () ->
                    staged(source, "svc.api"), source);
        }
    }

    @Test void singleValidImportStillCannotInventRuntimeProofAuthority() {
        var candidates = sources(API);
        var accepted = verify(candidates, pinned(candidates));
        var otherCandidates = sources(API);
        var other = verify(otherCandidates, pinned(otherCandidates));
        assertEquals(accepted.digest(), other.digest());
        assertFalse(accepted.sameLinkInstance(other));
        var a = accepted.checkedTypeForLinkedAnalysis("svc.api", "api.Port", 7L);
        var b = other.checkedTypeForLinkedAnalysis("svc.api", "api.Port", 7L);
        assertFalse(Types.isAssignable(a, b));
        assertFalse(Types.isAssignable(b, a));
    }
}
