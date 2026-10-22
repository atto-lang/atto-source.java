package dev.oreslang.types;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Analysis-only linker manifest; no code emission or guest proof minting. */
final class CompilerZordeLinkManifestTest {
    private static final String API_SOURCE =
            "namespace api; @Range(min=1,max=100) type Port = int;";
    private static final String USERS_SOURCE =
            "namespace users; @MinLength(1) define newtype Username(string);";

    private static Map<String, CompilerZordeSchemaRegistry> registries() {
        var api = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                API_SOURCE, "svc.api", 3);
        var users = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                USERS_SOURCE, "svc.users", 5);
        return Map.of("svc.api", api, "svc.users", users);
    }

    private static CompilerZordeLinkManifest.Pin pin(
            CompilerZordeSchemaRegistry registry, Map<String, String> dependencies) {
        return new CompilerZordeLinkManifest.Pin(
                registry.sourceDigest(), registry.generation(), dependencies);
    }

    private static Map<String, CompilerZordeLinkManifest.Pin> pins(
            Map<String, CompilerZordeSchemaRegistry> units) {
        return Map.of(
                "svc.api", pin(units.get("svc.api"),
                        Map.of("svc.users", units.get("svc.users").sourceDigest())),
                "svc.users", pin(units.get("svc.users"), Map.of()));
    }

    private static CompilerZordeLinkManifest link(
            Map<String, CompilerZordeSchemaRegistry> candidates,
            Map<String, CompilerZordeLinkManifest.Pin> pins,
            String compiler, String abi) {
        return CompilerZordeLinkManifest.verifyForAnalysis(
                compiler, abi, candidates, pins, Set.of("svc.api"));
    }

    @Test void completePinnedGraphProducesStableLengthPrefixedManifest() {
        var candidates = registries();
        var policy = pins(candidates);
        var a = link(candidates, policy, "java-dev-1", "graalvm-linux-arm64");
        var b = link(new LinkedHashMap<>(Map.of(
                "svc.users", candidates.get("svc.users"),
                "svc.api", candidates.get("svc.api"))),
                policy, "java-dev-1", "graalvm-linux-arm64");
        assertEquals(1, CompilerZordeLinkManifest.FORMAT_VERSION);
        assertEquals(2, a.unitCount());
        assertEquals(Set.of("svc.api"), a.roots());
        assertEquals(List.of("svc.users", "svc.api"), a.dependencyFirstOrder());
        assertEquals(64, a.digest().length());
        assertEquals(a.digest(), b.digest());
        // Identical deterministic manifest hash is NOT an authority token.
        assertFalse(a.sameLinkInstance(b));
        assertTrue(a.sameLinkInstance(a));
        a.requireMatchingBuild("java-dev-1", "graalvm-linux-arm64", b.digest());
        assertThrows(IllegalArgumentException.class,
                () -> a.requireMatchingBuild("java-dev-2", "graalvm-linux-arm64", a.digest()));
        assertThrows(IllegalArgumentException.class,
                () -> a.requireMatchingBuild("java-dev-1", "graalvm-linux-x86_64", a.digest()));
        assertThrows(IllegalArgumentException.class,
                () -> a.requireMatchingBuild("java-dev-1", "graalvm-linux-arm64",
                        "0".repeat(64)));
        assertEquals("java-dev-1", a.compilerBuild());
        assertEquals("graalvm-linux-arm64", a.targetAbi());
        assertFalse(Modifier.isPublic(CompilerZordeLinkManifest.class.getModifiers()));
        assertTrue(java.util.Arrays.stream(
                CompilerZordeLinkManifest.class.getDeclaredConstructors())
                .allMatch(c -> Modifier.isPrivate(c.getModifiers())));
    }

    @Test void linkedAnalysisTypeRequiresExactRegisteredUnitAndScalar() {
        var candidates = registries();
        var linked = link(candidates, pins(candidates),
                "java-dev-1", "graalvm-linux-arm64");
        var checked = linked.checkedTypeForLinkedAnalysis("svc.api", "api.Port", 7L);
        var same = linked.checkedTypeForLinkedAnalysis("svc.api", "api.Port", 9L);
        assertEquals(checked, same);
        assertTrue(Types.containsCompilerCheckedScalar(checked));
        assertFalse(Types.isAssignable(Types.Primitive.INT, checked));
        assertNotNull(linked.checkedTypeForLinkedAnalysis(
                "svc.users", "users.Username", "😀"));
        assertThrows(IllegalArgumentException.class, () ->
                linked.checkedTypeForLinkedAnalysis("svc.api", "users.Username", "ok"));
        assertThrows(IllegalArgumentException.class, () ->
                linked.checkedTypeForLinkedAnalysis("svc.rogue", "api.Port", 7L));
        assertThrows(IllegalArgumentException.class, () ->
                linked.checkedTypeForLinkedAnalysis("svc.api", "api.Port", 200L));
        assertThrows(IllegalArgumentException.class, () ->
                linked.checkedTypeForLinkedAnalysis("svc.api", "api.Port", 7.0));

        // Identical canonical manifests assembled from independent compilation
        // sessions must not issue interchangeable internal type identities.
        var separate = registries();
        var other = link(separate, pins(separate),
                "java-dev-1", "graalvm-linux-arm64");
        assertEquals(linked.digest(), other.digest());
        var foreign = other.checkedTypeForLinkedAnalysis("svc.api", "api.Port", 7L);
        assertFalse(Types.isAssignable(checked, foreign));
        assertFalse(Types.isAssignable(foreign, checked));
    }

    @Test void mismatchedRootSourceOrGenerationOrCandidateIsDenied() {
        var candidates = registries();
        var policy = pins(candidates);
        var api = candidates.get("svc.api");
        var users = candidates.get("svc.users");
        var wrongSource = new HashMap<>(policy);
        wrongSource.put("svc.api", new CompilerZordeLinkManifest.Pin(
                "0".repeat(64), api.generation(),
                Map.of("svc.users", users.sourceDigest())));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, wrongSource, "java-dev-1", "graalvm-linux-arm64"));

        var staleGeneration = new HashMap<>(policy);
        staleGeneration.put("svc.api", new CompilerZordeLinkManifest.Pin(
                api.sourceDigest(), api.generation() + 1,
                Map.of("svc.users", users.sourceDigest())));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, staleGeneration, "java-dev-1", "graalvm-linux-arm64"));

        var missingPin = Map.of("svc.api", policy.get("svc.api"));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, missingPin, "java-dev-1", "graalvm-linux-arm64"));

        var masquerade = Map.of("svc.api", users, "svc.users", api);
        assertThrows(IllegalArgumentException.class, () ->
                link(masquerade, policy, "java-dev-1", "graalvm-linux-arm64"));

        assertThrows(IllegalArgumentException.class, () ->
                CompilerZordeLinkManifest.verifyForAnalysis("java-dev-1",
                        "graalvm-linux-arm64", candidates, policy, Set.of("missing")));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, policy, "", "graalvm-linux-arm64"));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, policy, "java-dev-1", "graalvm/linux/arm64"));
    }

    @Test void wrongDependencyDigestMissingEdgeOrCycleCannotLink() {
        var candidates = registries();
        var api = candidates.get("svc.api");
        var users = candidates.get("svc.users");
        var badDependency = new HashMap<>(pins(candidates));
        badDependency.put("svc.api", pin(api, Map.of("svc.users", "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, badDependency, "java-dev-1", "graalvm-linux-arm64"));

        var unknown = new HashMap<>(pins(candidates));
        unknown.put("svc.api", pin(api, Map.of("svc.external", "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, unknown, "java-dev-1", "graalvm-linux-arm64"));

        // No unreferenced or orphan unit is silently admitted.
        var orphan = Map.of(
                "svc.api", pin(api, Map.of()),
                "svc.users", pin(users, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, orphan, "java-dev-1", "graalvm-linux-arm64"));

        var cycle = new HashMap<>(pins(candidates));
        cycle.put("svc.users", pin(users, Map.of("svc.api", api.sourceDigest())));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, cycle, "java-dev-1", "graalvm-linux-arm64"));

        var self = new HashMap<>(pins(candidates));
        self.put("svc.api", pin(api, Map.of("svc.api", api.sourceDigest())));
        assertThrows(IllegalArgumentException.class, () ->
                link(candidates, self, "java-dev-1", "graalvm-linux-arm64"));
    }

    @Test void duplicatedPublicSchemaNameCannotExportAcrossUnits() {
        var a = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                "namespace app; @Range(min=1,max=10) type Port = int;", "unit.a", 1);
        var b = CompilerZordeSchemaRegistry.stageSourceForAnalysis(
                "namespace app; @Range(min=2,max=9) type Port = int;", "unit.b", 1);
        Map<String, CompilerZordeSchemaRegistry> candidates =
                Map.of("unit.a", a, "unit.b", b);
        Map<String, CompilerZordeLinkManifest.Pin> policy = Map.of(
                "unit.a", pin(a, Map.of("unit.b", b.sourceDigest())),
                "unit.b", pin(b, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                CompilerZordeLinkManifest.verifyForAnalysis(
                        "java-dev-1", "graalvm-linux-arm64",
                        candidates, policy, Set.of("unit.a")));
    }

    @Test void formatAndTargetAndGraphEdgesAreCommittedToDigest() {
        var candidates = registries();
        var policy = pins(candidates);
        var arm = link(candidates, policy, "java-dev-1", "graalvm-linux-arm64");
        var x86 = link(candidates, policy, "java-dev-1", "graalvm-linux-x86_64");
        var newCompiler = link(candidates, policy, "java-dev-2", "graalvm-linux-arm64");
        assertNotEquals(arm.digest(), x86.digest());
        assertNotEquals(arm.digest(), newCompiler.digest());

        var detachedUsers = Map.of(
                "svc.api", pin(candidates.get("svc.api"), Map.of()),
                "svc.users", pin(candidates.get("svc.users"), Map.of()));
        var independentRoots = CompilerZordeLinkManifest.verifyForAnalysis(
                "java-dev-1", "graalvm-linux-arm64", candidates, detachedUsers,
                Set.of("svc.api", "svc.users"));
        assertNotEquals(arm.digest(), independentRoots.digest());
        assertFalse(arm.sameLinkInstance(independentRoots));
    }
}
