package dev.oreslang.runtime;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NamespaceRuntimeIntegrationTest {
    @TempDir Path temp;

    private Path source(Path project, String relative, String namespace, String body) throws Exception {
        Path path = project.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "namespace " + namespace + ";\n" + body + "\n");
        return path;
    }

    private Path billing(Path project) throws Exception {
        source(project, "src/billing/charge.ores", "billing",
                "pub fnc charge(int value): int { return value; }");
        source(project, "src/billing/refund.ores", "billing",
                "pub fnc refund(): int { return 2; }");
        source(project, "src/billing/balance.ores", "billing",
                "pub fnc balance(): int { return 3; }");
        source(project, "src/billing/invoice.ores", "billing",
                "pub fnc invoice(): int { return 1; }");
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, """
                schema_version = "2"
                [project]
                root = "."
                [packages.checkout]
                files = [
                  "src/billing/charge.ores",
                  "src/billing/refund.ores",
                  "src/billing/balance.ores",
                  "src/billing/invoice.ores",
                ]
                """);
        return manifest;
    }

    private IncrementalCompiler.BuildResult run(Path main, ByteArrayOutputStream stdout) throws Exception {
        return LinkedProgramRunner.run(
                main, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), stdout, new ByteArrayOutputStream());
    }

    @Test
    void samePackageSingleNamespaceImportDispatchesAcrossFourFiles() throws Exception {
        Path root = temp.resolve("checkout");
        billing(root);
        Path main = source(root, "src/client.ores", "identity", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  stdio.println(payments.charge(4));
                  stdio.println(payments.refund());
                  stdio.println(payments.balance());
                  stdio.println(payments.invoice());
                  return;
                }
                """);
        Path manifest = root.resolve(".ores.pkg.toml");
        String previous = Files.readString(manifest);
        Files.writeString(manifest, previous.replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/client.ores\","));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        var build = run(main, stdout);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("4\n2\n3\n1"));
        assertEquals(5, build.units().size());
        assertEquals(4, build.units().get(main.toAbsolutePath().toString().replace('\\', '/'))
                .dependencies().size());
    }

    @Test
    void externalPinnedPackageImportDispatchesAcrossFourFiles() throws Exception {
        Path provider = temp.resolve("checkout");
        Path providerManifest = billing(provider);
        Path client = temp.resolve("client");
        Path main = source(client, "src/main.ores", "app", """
                import namespace billing as payments from "checkout";
                pub fnc main(): void {
                  stdio.println(payments.charge(8));
                  stdio.println(payments.refund());
                  stdio.println(payments.balance());
                  stdio.println(payments.invoice());
                  return;
                }
                """);
        List<Path> members = List.of(
                provider.resolve("src/billing/charge.ores"),
                provider.resolve("src/billing/refund.ores"),
                provider.resolve("src/billing/balance.ores"),
                provider.resolve("src/billing/invoice.ores"));
        Files.writeString(client.resolve(".ores.pkg.toml"), """
                schema_version = "2"
                [project]
                root = "."
                [packages.client]
                files = ["src/main.ores"]
                [namespace_dependencies.checkout]
                manifest = "../checkout/.ores.pkg.toml"
                package = "checkout"
                manifest_sha256 = "%s"
                sources_sha256 = "%s"
                """.formatted(
                PackageNamespaceWorkspace.sha256(Files.readAllBytes(providerManifest)),
                PackageNamespaceWorkspace.sourceDigest(providerManifest, members)));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        var build = run(main, stdout);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("8\n2\n3\n1"));
        assertEquals(5, build.units().size());
    }

    @Test
    void pinnedSourceMutationMustFailBeforeGuestExecution() throws Exception {
        Path provider = temp.resolve("checkout");
        Path providerManifest = billing(provider);
        Path client = temp.resolve("client");
        Path main = source(client, "src/main.ores", "app", """
                import namespace billing from "checkout";
                pub fnc main(): void { stdio.println(billing.refund()); return; }
                """);
        List<Path> members = List.of(
                provider.resolve("src/billing/charge.ores"),
                provider.resolve("src/billing/refund.ores"),
                provider.resolve("src/billing/balance.ores"),
                provider.resolve("src/billing/invoice.ores"));
        Files.writeString(client.resolve(".ores.pkg.toml"), """
                schema_version = "2"
                [project]
                [packages.client]
                files = ["src/main.ores"]
                [namespace_dependencies.checkout]
                manifest = "../checkout/.ores.pkg.toml"
                package = "checkout"
                manifest_sha256 = "%s"
                sources_sha256 = "%s"
                """.formatted(
                PackageNamespaceWorkspace.sha256(Files.readAllBytes(providerManifest)),
                PackageNamespaceWorkspace.sourceDigest(providerManifest, members)));
        Files.writeString(provider.resolve("src/billing/refund.ores"),
                "namespace billing;\npub fnc refund(): int { return 999; }\n");
        assertThrows(SecurityException.class, () -> LinkedProgramRunner.validate(main));
    }
}
