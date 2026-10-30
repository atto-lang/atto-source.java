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
        source(project, "src/billing/charge.ores", "billing", """
                pub fnc charge(int value): int { return value; }
                pub routine notQualified(): int { return 77; }
                """);
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
    void tenFileThreeNamespacePackageExportsBillingViaOneCrossPackageImport() throws Exception {
        Path project = temp.resolve("checkout");
        StringBuilder manifest = new StringBuilder("""
                schema_version = "2"
                [project]
                root = "."
                [packages.checkout]
                files = [
                """);
        java.util.ArrayList<Path> allMembers = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String namespace = i < 4 ? "billing" : i < 7 ? "identity" : "telemetry";
            String relative = "src/unit" + i + ".ores";
            allMembers.add(source(project, relative, namespace,
                    "pub fnc function" + i + "(): int { return " + i + "; }"));
            manifest.append("  \"").append(relative).append("\",\n");
        }
        manifest.append("]\n");
        Path providerManifest = project.resolve(".ores.pkg.toml");
        Files.writeString(providerManifest, manifest.toString());

        Path client = temp.resolve("client");
        Path main = source(client, "src/main.ores", "app", """
                import namespace billing as payments from "checkout";
                pub fnc main(): void {
                  stdio.println(payments.function0());
                  stdio.println(payments.function1());
                  stdio.println(payments.function2());
                  stdio.println(payments.function3());
                  return;
                }
                """);
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
                PackageNamespaceWorkspace.sourceDigest(providerManifest, allMembers)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = run(main, out);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("0\n1\n2\n3"));
        assertEquals(11, build.units().size());
        assertEquals(4, build.units().get(main.toAbsolutePath().toString().replace('\\', '/'))
                .dependencies().size());
    }

    @Test
    void unsupportedRoutineCannotPoisonOtherNamespaceCallsOrBeCalled() throws Exception {
        Path project = temp.resolve("checkout");
        billing(project);
        Path main = source(project, "src/main.ores", "client", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  stdio.println(payments.notQualified());
                  return;
                }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, Files.readString(manifest).replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/main.ores\","));
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class, () -> LinkedProgramRunner.validate(main));
        assertTrue(rejected.getMessage().contains("namespace call does not resolve"));
        Files.writeString(main, """
                namespace client;
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  val callback = payments.charge;
                  return;
                }
                """);
        IllegalArgumentException extracted = assertThrows(
                IllegalArgumentException.class, () -> LinkedProgramRunner.validate(main));
        assertTrue(extracted.getMessage().contains("requires direct statically checked invocation"));
    }


    @Test
    void reachableNamespaceProviderInitializesBeforeMainExactlyOnce() throws Exception {
        Path project = temp.resolve("checkout");
        billing(project);
        Files.writeString(project.resolve("src/billing/refund.ores"), """
                namespace billing;
                pub fnc init(): void {
                  stdio.println("PROVIDER_INIT");
                  return;
                }
                pub fnc refund(): int { return 2; }
                """);
        Path main = source(project, "src/main.ores", "client", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  stdio.println(payments.refund());
                  stdio.println(payments.refund());
                  return;
                }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, Files.readString(manifest).replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/main.ores\","));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        var result = run(main, stdout);
        assertEquals(5, result.units().size());
        assertEquals("PROVIDER_INIT\n2\n2\n",
                stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unrelatedManifestNamespaceDoesNotInitialize() throws Exception {
        Path project = temp.resolve("checkout");
        billing(project);
        source(project, "src/unrelated.ores", "telemetry", """
                pub fnc init(): void {
                  stdio.println("UNRELATED_INIT_MUST_NOT_RUN");
                  return;
                }
                pub fnc observe(): int { return 99; }
                """);
        Path entry = source(project, "src/main.ores", "client", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  stdio.println(payments.refund());
                  return;
                }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, Files.readString(manifest).replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/main.ores\",\n  \"src/unrelated.ores\","));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = run(entry, out);
        assertEquals(6, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("2"));
        assertTrue(!out.toString(StandardCharsets.UTF_8)
                .contains("UNRELATED_INIT_MUST_NOT_RUN"));
    }

    @Test
    void lifecycleInitCannotBeCalledViaNamespaceQualification() throws Exception {
        Path project = temp.resolve("checkout");
        billing(project);
        Path init = source(project, "src/billing/startup.ores", "billing", """
                pub fnc init(): void {
                  stdio.println("INIT_ONLY_AT_STARTUP");
                  return;
                }
                """);
        Path entry = source(project, "src/main.ores", "client", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  payments.init();
                  return;
                }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, Files.readString(manifest).replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/billing/startup.ores\",\n  \"src/main.ores\","));
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class, () -> LinkedProgramRunner.validate(entry));
        assertTrue(denied.getMessage().contains("namespace call does not resolve"));
    }

    @Test
    void wrongArgumentTypeAndUnknownCallAreRejectedByStaticLinker() throws Exception {
        Path project = temp.resolve("checkout");
        billing(project);
        Path main = source(project, "src/main.ores", "client", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  stdio.println(payments.charge("wrong"));
                  return;
                }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, Files.readString(manifest).replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/main.ores\","));
        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(main));

        Files.writeString(main, """
                namespace client;
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  payments.missing();
                  return;
                }
                """);
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class, () -> LinkedProgramRunner.validate(main));
        assertTrue(missing.getMessage().contains("namespace call does not resolve"));
    }

    @Test
    void dependencyAliasCannotAdmitSiblingPackageFromOneManifest() throws Exception {
        Path provider = temp.resolve("checkout");
        Path manifest = billing(provider);
        Path rogue = source(provider, "src/rogue.ores", "internal", """
                pub fnc hidden(): int { return 999; }
                """);
        Files.writeString(manifest, Files.readString(manifest) + """
                [packages.rogue]
                files = ["src/rogue.ores"]
                """);
        Path client = temp.resolve("client");
        Path main = source(client, "src/main.ores", "app", """
                import namespace billing as payments from "checkout";
                pub fnc main(): void { stdio.println(payments.refund()); return; }
                """);
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
                PackageNamespaceWorkspace.sha256(Files.readAllBytes(manifest)),
                PackageNamespaceWorkspace.sourceDigest(manifest, List.of(
                        provider.resolve("src/billing/charge.ores"),
                        provider.resolve("src/billing/refund.ores"),
                        provider.resolve("src/billing/balance.ores"),
                        provider.resolve("src/billing/invoice.ores"), rogue))));
        SecurityException rejected = assertThrows(
                SecurityException.class, () -> LinkedProgramRunner.validate(main));
        assertTrue(rejected.getMessage().contains("exactly one package"));
    }

    @Test
    void manifestOwnedProviderCanImportDeclaredSiblingInSamePackage() throws Exception {
        Path project = temp.resolve("checkout");
        billing(project);
        source(project, "src/billing/local.ores", "billing", """
                define module Local as
                  pub fnc helper(): int { return 7; }
                end
                """);
        Files.writeString(project.resolve("src/billing/charge.ores"), """
                namespace billing;
                import module Local from "./local.ores";
                pub fnc charge(int value): int { return Local.helper(); }
                """);
        Path main = source(project, "src/main.ores", "client", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  stdio.println(payments.charge(1));
                  return;
                }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, Files.readString(manifest).replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/billing/local.ores\",\n  \"src/main.ores\","));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = run(main, out);
        assertEquals(6, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("7"));
    }

    @Test
    void manifestOwnedProviderCannotImportDeclaredSiblingFromAnotherPackage() throws Exception {
        Path project = temp.resolve("checkout");
        billing(project);
        source(project, "src/foreign.ores", "foreign", """
                define module Foreign as
                  pub fnc helper(): int { return 999; }
                end
                """);
        Files.writeString(project.resolve("src/billing/charge.ores"), """
                namespace billing;
                import module Foreign from "../foreign.ores";
                pub fnc charge(int value): int { return Foreign.helper(); }
                """);
        Path main = source(project, "src/main.ores", "client", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  stdio.println(payments.refund());
                  return;
                }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, Files.readString(manifest).replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/main.ores\",")
                + "\n[packages.foreign]\nfiles = [\"src/foreign.ores\"]\n");
        SecurityException denied = assertThrows(SecurityException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(denied.getMessage().contains("undeclared or foreign package source"));
    }

    @Test
    void manifestOwnedProviderCannotImportUnlistedSiblingSource() throws Exception {
        Path project = temp.resolve("checkout");
        billing(project);
        source(project, "src/billing/unlisted.ores", "billing", """
                define module hidden as
                  pub fnc secret(): int { return 999; }
                end
                """);
        Files.writeString(project.resolve("src/billing/charge.ores"), """
                namespace billing;
                import module hidden from "./unlisted.ores";
                pub fnc charge(int value): int { return value; }
                """);
        Path main = source(project, "src/main.ores", "client", """
                import namespace billing as payments from "+/";
                pub fnc main(): void {
                  stdio.println(payments.refund());
                  return;
                }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, Files.readString(manifest).replace(
                "  \"src/billing/invoice.ores\",",
                "  \"src/billing/invoice.ores\",\n  \"src/main.ores\","));
        // The sibling is real and parsable but is intentionally absent
        // from the manifest's authenticated package-member list.
        SecurityException denied = assertThrows(
                SecurityException.class, () -> LinkedProgramRunner.validate(main));
        assertTrue(denied.getMessage().contains("undeclared or foreign package source"));
    }

    @Test
    void pinnedDependencyManifestCannotEnterViaSymlinkedParentDirectory() throws Exception {
        Path provider = temp.resolve("checkout");
        Path providerManifest = billing(provider);
        Path aliasDirectory = temp.resolve("checkout-alias");
        try {
            Files.createSymbolicLink(aliasDirectory, provider);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not permit creating symlink test fixture");
        }

        Path client = temp.resolve("client");
        Path main = source(client, "src/main.ores", "client", """
                import namespace billing as payments from "checkout";
                pub fnc main(): void {
                  stdio.println(payments.refund());
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
                manifest = "../checkout-alias/.ores.pkg.toml"
                package = "checkout"
                manifest_sha256 = "%s"
                sources_sha256 = "%s"
                """.formatted(
                PackageNamespaceWorkspace.sha256(Files.readAllBytes(providerManifest)),
                PackageNamespaceWorkspace.sourceDigest(providerManifest, members)));
        SecurityException refused = assertThrows(SecurityException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(refused.getMessage().contains("symlinked namespace dependency path"));
    }


    @Test
    void pinnedManifestMutationMustFailBeforeGuestExecution() throws Exception {
        Path provider = temp.resolve("checkout");
        Path providerManifest = billing(provider);
        Path client = temp.resolve("client");
        Path main = source(client, "src/main.ores", "app", """
                import namespace billing as payments from "checkout";
                pub fnc main(): void {
                  stdio.println(payments.refund());
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

        // This edit preserves the TOML package inventory and every provider
        // source byte, but must still invalidate the authenticated manifest.
        Files.writeString(providerManifest,
                Files.readString(providerManifest) + "# modified after pinning\n");
        SecurityException denied = assertThrows(SecurityException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(denied.getMessage().contains("manifest pin mismatch"));
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
