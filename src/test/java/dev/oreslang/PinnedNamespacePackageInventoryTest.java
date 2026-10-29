package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.PinnedNamespacePackageInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class PinnedNamespacePackageInventoryTest {
    @TempDir Path temp;

    private static String digest(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    private record Pinned(String instance, PinnedNamespacePackageInventory.PackagePin pin) { }

    private Pinned emit(String name, Map<String, String> members) throws Exception {
        Path packageRoot = temp.resolve(name);
        Files.createDirectories(packageRoot);
        Map<String, String> pins = new LinkedHashMap<>();
        for (Map.Entry<String, String> file : members.entrySet()) {
            Path target = packageRoot.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
            pins.put(file.getKey(), digest(file.getValue()));
        }
        String manifest = "schema_version = \"2\"\n"
                + "[project]\nname = \"" + name + "\"\nroot = \".\"\n"
                + "[source]\nroots = [\"src\"]\n"
                + "[packages." + name + "]\n"
                + "files = [" + String.join(", ",
                        members.keySet().stream().map(s -> "\"" + s + "\"").toList()) + "]\n";
        Path path = packageRoot.resolve(".ores.pkg.toml");
        Files.writeString(path, manifest);
        return new Pinned(name + "@1",
                new PinnedNamespacePackageInventory.PackagePin(path, digest(manifest), pins));
    }

    private Pinned foo() throws Exception {
        Map<String, String> units = new LinkedHashMap<>();
        units.put("src/compute/math.ores",
                "namespace a;\npub fnc add(int x, int y): int { return x + y; }\n");
        units.put("src/compute/stats.ores",
                "namespace a;\npub fnc double(int x): int { return x * 2; }\n");
        units.put("src/extra/aggregation.ores",
                "namespace a;\npub fnc aggregate(int x): int { return x + 1; }\n");
        units.put("src/data/models.ores",
                "namespace b;\npub fnc models(): int { return 1; }\n");
        units.put("src/data/queries.ores",
                "namespace b;\npub fnc queries(): int { return 1; }\n");
        units.put("src/data/storage.ores",
                "namespace b;\npub fnc storage(): int { return 1; }\n");
        units.put("src/data/validation.ores",
                "namespace b;\npub fnc validation(): int { return 1; }\n");
        units.put("src/api/routes.ores",
                "namespace c;\npub fnc routes(): int { return 1; }\n");
        units.put("src/api/handlers.ores",
                "namespace c;\npub fnc handlers(): int { return 1; }\n");
        units.put("src/api/responses.ores",
                "namespace c;\npub fnc responses(): int { return 1; }\n");
        return emit("foo", units);
    }

    private Pinned app() throws Exception {
        return emit("app", Map.of("src/main.ores", """
                import ns a from 'pkg:foo';
                pub fnc main(): int {
                    return a.aggregate(a.double(a.add(20, 1)));
                }
                """));
    }

    @Test
    void readsRealTwoPackageManifestsAndExecutesTenSourceGuestTo43() throws Exception {
        Pinned foo = foo(), app = app();
        var verified = PinnedNamespacePackageInventory.load(
                Map.of(foo.instance(), foo.pin(), app.instance(), app.pin()),
                Map.of(app.instance(), Map.of("foo", foo.instance())));
        assertEquals(11, verified.sources().size());
        assertEquals(10, verified.sourceOwners().values().stream()
                .filter(foo.instance()::equals).count());
        String main = temp.resolve("app/src/main.ores").toRealPath().toString().replace('\\', '/');
        assertEquals(43, verified.run(main, IsolatePolicy.developer(),
                ExecutionProfile.serverJit()).exitStatus());
    }

    @Test
    void detectsHashMismatchAfterSourceMutation() throws Exception {
        Pinned foo = foo(), app = app();
        Files.writeString(temp.resolve("foo/src/compute/math.ores"), "malicious source\n");
        var pins = Map.of(foo.instance(), foo.pin(), app.instance(), app.pin());
        assertThrows(IllegalArgumentException.class, () ->
                PinnedNamespacePackageInventory.load(pins, Map.of(app.instance(),
                        Map.of("foo", foo.instance()))));
    }

    @Test
    void refusesManifestPathTraversalAndUnlistedHashEntries() throws Exception {
        Pinned foo = foo(), app = app();
        var extra = new LinkedHashMap<>(foo.pin().sourceSha256());
        extra.put("src/another.ores", "a".repeat(64));
        var broken = new PinnedNamespacePackageInventory.PackagePin(
                foo.pin().manifest(), foo.pin().manifestSha256(), extra);
        assertThrows(IllegalArgumentException.class, () ->
                PinnedNamespacePackageInventory.load(
                        Map.of(foo.instance(), broken, app.instance(), app.pin()),
                        Map.of(app.instance(), Map.of("foo", foo.instance()))));

        assertThrows(IllegalArgumentException.class, () ->
                new PinnedNamespacePackageInventory.PackagePin(
                        foo.pin().manifest(), foo.pin().manifestSha256(),
                        Map.of("../escaped.ores", "a".repeat(64))));
    }

    @Test
    void rejectsSymlinkedSourceEvenWithCorrectSha() throws Exception {
        Pinned foo = foo(), app = app();
        Path original = temp.resolve("foo/src/compute/math.ores");
        Path other = temp.resolve("somewhere-else.ores");
        Files.move(original, other);
        try {
            Files.createSymbolicLink(original, other);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException noSymlinks) {
            return; // system does not offer symbolic links to this test process
        }
        assertThrows(IllegalArgumentException.class, () ->
                PinnedNamespacePackageInventory.load(
                        Map.of(foo.instance(), foo.pin(), app.instance(), app.pin()),
                        Map.of(app.instance(), Map.of("foo", foo.instance()))));
    }

    @Test
    void rejectsSecondPackageNamespaceWithoutPinnedSourceInventory() throws Exception {
        Pinned foo = foo(), app = app();
        assertThrows(IllegalArgumentException.class, () ->
                PinnedNamespacePackageInventory.load(
                        Map.of(foo.instance(), foo.pin(), app.instance(), app.pin()),
                        Map.of(app.instance(), Map.of("foo", "missing@1"))));
    }
}
