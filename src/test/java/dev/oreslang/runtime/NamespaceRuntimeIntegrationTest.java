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
    void manifestProviderPathAliasesRejectBeforeRootResolution() throws Exception {
        for (String noncanonical : new String[] {
                "./src/billing/charge.ores",
                "src//billing/charge.ores",
                "src/billing/./charge.ores",
                "src/billing/../billing/charge.ores"
        }) {
            Path project = temp.resolve("alias-" + Integer.toHexString(noncanonical.hashCode()));
            billing(project);
            Path manifest = project.resolve(".ores.pkg.toml");
            String original = Files.readString(manifest);
            Files.writeString(manifest,
                    original.replace("src/billing/charge.ores", noncanonical));
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> PackageNamespaceWorkspace.discover(
                            project.resolve("src/billing/charge.ores")), noncanonical);
            assertTrue(denied.getMessage().contains(
                    "noncanonical namespace provider filename"), denied.getMessage());
        }
    }

    @Test
    void symlinkedEntrySourceCannotBeCanonicalizedIntoAnotherManifestMember() throws Exception {
        Path project = temp.resolve("entry-alias");
        billing(project);
        Path genuine = source(project, "src/main.ores", "app", """
                pub fnc main(): void { return; }
                """);
        Path alias = genuine.resolveSibling("entry-alias.ores");
        Files.createSymbolicLink(alias, genuine.getFileName());
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.discover(alias));
        assertTrue(denied.getMessage().contains(
                "symlinked namespace entry source is forbidden"));
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
    void pinnedDependencyProviderCannotReuseRootNamespaceAlias() throws Exception {
        Path provider = temp.resolve("checkout");
        Path providerManifest = billing(provider);
        // The root client owns "checkout". The pinned provider must not
        // inherit that alias, even when all dependency hashes are valid.
        source(provider, "src/billing/charge.ores", "billing", """
                import namespace billing as lifted from "checkout";
                pub fnc charge(int value): int { return value; }
                """);
        List<Path> members = List.of(
                provider.resolve("src/billing/charge.ores"),
                provider.resolve("src/billing/refund.ores"),
                provider.resolve("src/billing/balance.ores"),
                provider.resolve("src/billing/invoice.ores"));
        Path client = temp.resolve("client");
        Path main = source(client, "src/main.ores", "app", """
                import namespace billing as payments from "checkout";
                pub fnc main(): void {
                  stdio.println(payments.refund());
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
                PackageNamespaceWorkspace.sourceDigest(providerManifest, members)));
        SecurityException denied = assertThrows(SecurityException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(denied.getMessage().contains(
                "namespace dependency alias is not authorized for source"));
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
    void nestedManifestAtExplicitProjectRootCannotClaimParentPackage() throws Exception {
        Path project = temp.resolve("root-boundary");
        Path entry = source(project, "entry.ores", "app",
                "pub fnc main(): void { return; }");
        Path provider = source(project, "src/worker.ores", "app",
                "pub fnc value(): int { return 7; }");
        Files.writeString(project.resolve(".ores.pkg.toml"), """
                schema_version = "2"
                [project]
                root = "src"
                [packages.app]
                files = ["worker.ores"]
                """);

        // A deliberate subdirectory root is permitted when no other
        // package manifest owns that directory.
        var admitted = PackageNamespaceWorkspace.discover(entry);
        assertTrue(admitted.files().contains(provider.toRealPath()));

        // The nearest manifest for src/worker.ores would now differ from
        // the parent manifest claiming the provider; fail before guest code.
        Files.writeString(project.resolve("src/.ores.pkg.toml"), """
                schema_version = "2"
                [project]
                root = "."
                [packages.foreign]
                files = ["worker.ores"]
                """);
        SecurityException rejected = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.discover(entry));
        assertTrue(rejected.getMessage().contains("nested namespace package boundary"));
    }

    @Test
    void danglingNestedManifestSymlinkIsStillAPackageBoundary() throws Exception {
        Path project = temp.resolve("dangling-nested-manifest");
        billing(project);
        Path entry = source(project, "src/client.ores", "app",
                "pub fnc main(): void { return; }");
        Path marker = project.resolve("src/billing/.ores.pkg.toml");
        try {
            Files.createSymbolicLink(marker, Path.of("not-yet-created.toml"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not permit creating symlink test fixture");
        }
        SecurityException rejected = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.discover(entry));
        assertTrue(rejected.getMessage().contains("nested namespace package boundary"));
    }

    @Test
    void symlinkedProjectRootIsRejectedBeforeGuestExecution() throws Exception {
        Path project = temp.resolve("root-alias-project");
        Path main = source(project, "src/main.ores", "app", """
                pub fnc main(): void {
                  stdio.println(7);
                  return;
                }
                """);
        // The alias points *inside* the package, so containment checking
        // alone would accept it. The target can still change after admission.
        Path rootAlias = project.resolve("alias");
        try {
            Files.createSymbolicLink(rootAlias, project.resolve("src"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not permit creating symlink test fixture");
        }
        Files.writeString(project.resolve(".ores.pkg.toml"), """
                schema_version = "2"
                [project]
                root = "alias"
                [packages.app]
                files = ["main.ores"]
                """);
        SecurityException denied = assertThrows(SecurityException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(denied.getMessage().contains("symlinked namespace project root"));
    }

    @Test
    void sourceDigestAuthenticatesExactManifestMembersNotCallerSubset() throws Exception {
        Path project = temp.resolve("digest-member-set");
        Path manifest = billing(project);
        List<Path> members = List.of(
                project.resolve("src/billing/charge.ores"),
                project.resolve("src/billing/refund.ores"),
                project.resolve("src/billing/balance.ores"),
                project.resolve("src/billing/invoice.ores"));
        // The digest is independent of manifest member enumeration order,
        // but its exact set is fixed by the authenticated manifest.
        String expected = PackageNamespaceWorkspace.sourceDigest(manifest, members);
        assertEquals(expected, PackageNamespaceWorkspace.sourceDigest(
                manifest, List.of(members.get(3), members.get(2),
                        members.get(1), members.get(0))));

        Path unlisted = source(project, "src/billing/hidden.ores", "billing",
                "pub fnc hidden(): int { return 99; }");
        SecurityException foreign = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.sourceDigest(manifest, List.of(unlisted)));
        assertTrue(foreign.getMessage().contains("not manifest-listed"));

        SecurityException omitted = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.sourceDigest(manifest, members.subList(0, 3)));
        assertTrue(omitted.getMessage().contains("source set differs from manifest members"));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceWorkspace.sourceDigest(manifest,
                        List.of(members.get(0), members.get(1), members.get(2),
                                members.get(3), members.get(0))));
        assertTrue(duplicate.getMessage().contains("duplicate namespace digest source"));
    }

    @Test
    void sourceDigestRejectsFilesOutsideAuthenticatedProjectRoot() throws Exception {
        Path project = temp.resolve("digest-boundary");
        Path manifest = billing(project);
        Path foreign = source(temp.resolve("other-project"), "src/foreign.ores", "billing",
                "pub fnc injected(): int { return 999; }");
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.sourceDigest(manifest, List.of(foreign)));
        assertTrue(denied.getMessage().contains("outside manifest root"));
    }

    @Test
    void sourceDigestRejectsSymlinkedSourceDirectoryInsideProject() throws Exception {
        Path project = temp.resolve("digest-alias");
        Path manifest = billing(project);
        Path alias = project.resolve("src-link");
        try {
            Files.createSymbolicLink(alias, project.resolve("src"));
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not permit creating symlink test fixture");
        }
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.sourceDigest(manifest,
                        List.of(alias.resolve("billing/charge.ores"))));
        assertTrue(denied.getMessage().contains("symlinked namespace digest source"));
    }

    @Test
    void rootManifestSymlinkSwapWithIdenticalBytesStillFailsClosed() throws Exception {
        Path project = temp.resolve("root-manifest-symlink-swap");
        Path main = source(project, "src/main.ores", "app",
                "pub fnc main(): void { return; }");
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, """
                schema_version = "2"
                [project]
                root = "."
                [packages.app]
                files = ["src/main.ores"]
                """);
        PackageNamespaceWorkspace.Workspace admitted =
                PackageNamespaceWorkspace.discover(main);
        Path sameBytes = temp.resolve("identical-root-manifest.toml");
        Files.write(sameBytes, Files.readAllBytes(manifest));
        try {
            Files.delete(manifest);
            Files.createSymbolicLink(manifest, sameBytes);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not permit creating symlink test fixture");
        }
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyRootManifestSnapshot(admitted));
        assertTrue(denied.getMessage().contains("root namespace manifest changed"));
    }

    @Test
    void malformedUtf8RootManifestFailsBeforeIndexing() throws Exception {
        Path project = temp.resolve("invalid-utf8-manifest");
        Path entry = source(project, "src/main.ores", "app",
                "pub fnc main(): void { return; }");
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.write(manifest, new byte[]{(byte) 0xc3, (byte) 0x28});
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceWorkspace.discover(entry));
        assertTrue(denied.getMessage().contains("valid UTF-8"));
    }

    @Test
    void oversizedRootManifestFailsBeforeParsing() throws Exception {
        Path project = temp.resolve("oversized-manifest");
        Path entry = source(project, "src/main.ores", "app",
                "pub fnc main(): void { return; }");
        Path manifest = project.resolve(".ores.pkg.toml");
        byte[] oversized = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        Files.write(manifest, oversized);
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceWorkspace.discover(entry));
        assertTrue(denied.getMessage().contains("manifest exceeds byte budget"));
    }

    @Test
    void unchangedRootProviderBytesMatchAdmittedSourceSnapshot() throws Exception {
        Path project = temp.resolve("stable-root-source-snapshot");
        billing(project);
        Path entry = project.resolve("src/billing/charge.ores");
        PackageNamespaceWorkspace.Workspace snapshot =
                PackageNamespaceWorkspace.discover(entry);
        Map<String, String> admitted = new java.util.LinkedHashMap<>();
        for (Path file : snapshot.files()) {
            admitted.put(file.toRealPath().toString().replace('\\', '/'),
                    Files.readString(file));
        }
        PackageNamespaceWorkspace.verifyPinnedSnapshot(snapshot, admitted);
    }

    @Test
    void rootProviderSameBytesReplacementChangesPhysicalFileIdentity() throws Exception {
        Path project = temp.resolve("root-source-physical-replacement");
        billing(project);
        Path entry = project.resolve("src/billing/charge.ores");
        PackageNamespaceWorkspace.Workspace snapshot =
                PackageNamespaceWorkspace.discover(entry);
        Path member = project.resolve("src/billing/refund.ores").toRealPath();
        Object expectedKey = snapshot.providerFileKeys().get(member);
        org.junit.jupiter.api.Assumptions.assumeTrue(expectedKey != null,
                "filesystem does not supply stable file keys");

        Map<String, String> admitted = new java.util.LinkedHashMap<>();
        for (Path file : snapshot.files()) {
            admitted.put(file.toRealPath().toString().replace('\\', '/'), Files.readString(file));
        }
        PackageNamespaceWorkspace.verifyPinnedSnapshot(snapshot, admitted);

        Path copy = member.resolveSibling("refund-identical-copy.ores");
        Files.write(copy, Files.readAllBytes(member));
        Object copyKey = Files.readAttributes(copy,
                java.nio.file.attribute.BasicFileAttributes.class,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey();
        org.junit.jupiter.api.Assumptions.assumeTrue(!expectedKey.equals(copyKey),
                "filesystem does not distinguish same-byte replacement files");
        Files.move(copy, member, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(admitted.get(member.toString().replace('\\', '/')), Files.readString(member));
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyPinnedSnapshot(snapshot, admitted));
        assertTrue(denied.getMessage().contains("namespace provider file identity changed during admission"));
    }

    @Test
    void rootProviderSameByteSymlinkSwapAfterDiscoveryIsRejected() throws Exception {
        Path project = temp.resolve("root-source-symlink-swap");
        billing(project);
        Path entry = project.resolve("src/billing/charge.ores");
        PackageNamespaceWorkspace.Workspace snapshot =
                PackageNamespaceWorkspace.discover(entry);
        Map<String, String> admitted = new java.util.LinkedHashMap<>();
        for (Path file : snapshot.files()) {
            admitted.put(file.toRealPath().toString().replace('\\', '/'),
                    Files.readString(file));
        }
        Path provider = project.resolve("src/billing/refund.ores");
        Path sameBytes = temp.resolve("identical-refund-provider.ores");
        Files.write(sameBytes, Files.readAllBytes(provider));
        try {
            Files.delete(provider);
            Files.createSymbolicLink(provider, sameBytes);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "filesystem does not permit creating symlink test fixture");
        }
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyPinnedSnapshot(snapshot, admitted));
        assertTrue(denied.getMessage().contains("symlinked namespace package member"));
    }

    @Test
    void rootProviderMutationAfterDiscoveryIsRejected() throws Exception {
        Path project = temp.resolve("root-source-drift");
        billing(project);
        Path entry = project.resolve("src/billing/charge.ores");
        PackageNamespaceWorkspace.Workspace snapshot =
                PackageNamespaceWorkspace.discover(entry);
        Map<String, String> admitted = new java.util.LinkedHashMap<>();
        for (Path file : snapshot.files()) {
            admitted.put(file.toRealPath().toString().replace('\\', '/'),
                    Files.readString(file));
        }
        Path changed = project.resolve("src/billing/refund.ores");
        Files.writeString(changed, "namespace billing; pub fnc refund(): int { return 999; }");
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyPinnedSnapshot(snapshot, admitted));
        assertTrue(denied.getMessage().contains("namespace provider bytes changed during admission"));
    }

    @Test
    void dependencyProviderMutationAfterDiscoveryIsRejected() throws Exception {
        Path provider = temp.resolve("dependency-source-drift");
        Path dependencyManifest = billing(provider);
        List<Path> members = List.of(
                provider.resolve("src/billing/charge.ores"),
                provider.resolve("src/billing/refund.ores"),
                provider.resolve("src/billing/balance.ores"),
                provider.resolve("src/billing/invoice.ores"));
        Path client = temp.resolve("client-source-drift");
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
                manifest = "../dependency-source-drift/.ores.pkg.toml"
                package = "checkout"
                manifest_sha256 = "%s"
                sources_sha256 = "%s"
                """.formatted(
                PackageNamespaceWorkspace.sha256(Files.readAllBytes(dependencyManifest)),
                PackageNamespaceWorkspace.sourceDigest(dependencyManifest, members)));
        PackageNamespaceWorkspace.Workspace snapshot =
                PackageNamespaceWorkspace.discover(main);
        Map<String, String> admitted = new java.util.LinkedHashMap<>();
        for (Path file : snapshot.files()) {
            admitted.put(file.toRealPath().toString().replace('\\', '/'),
                    Files.readString(file));
        }
        PackageNamespaceWorkspace.verifyPinnedSnapshot(snapshot, admitted);
        Path changed = provider.resolve("src/billing/refund.ores");
        Files.writeString(changed, "namespace billing; pub fnc refund(): int { return 888; }");
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyPinnedSnapshot(snapshot, admitted));
        assertTrue(denied.getMessage().contains("namespace provider bytes changed during admission"));
    }

    @Test
    void namespaceIndexUsesAdmittedRootManifestNotLaterOwnerEdit() throws Exception {
        Path project = temp.resolve("immutable-root-index");
        Path main = source(project, "src/main.ores", "app",
                "pub fnc main(): void { return; }");
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, """
                schema_version = "2"
                [project]
                root = "."
                [packages.original]
                files = ["src/main.ores"]
                """);
        PackageNamespaceWorkspace.Workspace snapshot =
                PackageNamespaceWorkspace.discover(main);
        String id = main.toRealPath().toString().replace('\\', '/');
        Map<String, String> sources = Map.of(id, Files.readString(main));
        String original = dev.oreslang.compiler.PackageNamespaceIndex.packageId(
                manifest, "original");
        var before = dev.oreslang.compiler.PackageNamespaceIndex.loadFromSnapshots(
                snapshot.manifestSnapshots(), sources, snapshot.aliases());
        assertEquals(original, before.ownerOf(id));

        // Rewriting the file cannot retarget a previously admitted linker.
        Files.writeString(manifest, Files.readString(manifest)
                .replace("[packages.original]", "[packages.foreign]"));
        var after = dev.oreslang.compiler.PackageNamespaceIndex.loadFromSnapshots(
                snapshot.manifestSnapshots(), sources, snapshot.aliases());
        assertEquals(original, after.ownerOf(id));
        assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyRootManifestSnapshot(snapshot));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.manifestSnapshots().clear());
    }

    @Test
    void namespaceIndexUsesAdmittedDependencyNotLaterOwnerEdit() throws Exception {
        Path provider = temp.resolve("immutable-dependency");
        Path providerManifest = billing(provider);
        List<Path> members = List.of(
                provider.resolve("src/billing/charge.ores"),
                provider.resolve("src/billing/refund.ores"),
                provider.resolve("src/billing/balance.ores"),
                provider.resolve("src/billing/invoice.ores"));
        Path client = temp.resolve("immutable-client");
        Path main = source(client, "src/main.ores", "app", """
                import namespace billing as payments from "checkout";
                pub fnc main(): void {
                  stdio.println(payments.refund());
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
                manifest = "../immutable-dependency/.ores.pkg.toml"
                package = "checkout"
                manifest_sha256 = "%s"
                sources_sha256 = "%s"
                """.formatted(
                PackageNamespaceWorkspace.sha256(Files.readAllBytes(providerManifest)),
                PackageNamespaceWorkspace.sourceDigest(providerManifest, members)));
        PackageNamespaceWorkspace.Workspace snapshot =
                PackageNamespaceWorkspace.discover(main);
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        for (Path file : snapshot.files()) {
            sources.put(file.toRealPath().toString().replace('\\', '/'),
                    Files.readString(file));
        }
        String importer = main.toRealPath().toString().replace('\\', '/');
        String original = dev.oreslang.compiler.PackageNamespaceIndex.packageId(
                providerManifest, "checkout");
        Files.writeString(providerManifest, Files.readString(providerManifest)
                .replace("[packages.checkout]", "[packages.foreign]"));

        var index = dev.oreslang.compiler.PackageNamespaceIndex.loadFromSnapshots(
                snapshot.manifestSnapshots(), sources, snapshot.aliases());
        assertEquals(original, index.resolve(importer, "checkout", "billing").packageId());
        assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyPinnedSnapshot(snapshot, sources));
    }

    @Test
    void rootManifestChangeAfterDiscoveryMustFailBeforeLinking() throws Exception {
        Path project = temp.resolve("root-reassignment");
        Path main = source(project, "src/main.ores", "app", """
                pub fnc main(): void { return; }
                """);
        Path manifest = project.resolve(".ores.pkg.toml");
        Files.writeString(manifest, """
                schema_version = "2"
                [project]
                root = "."
                [packages.app]
                files = ["src/main.ores"]
                """);
        PackageNamespaceWorkspace.Workspace admitted =
                PackageNamespaceWorkspace.discover(main);
        assertTrue(admitted != null);
        PackageNamespaceWorkspace.verifyRootManifestSnapshot(admitted);

        // Membership and source bytes remain identical; only ownership
        // changes after package admission. Do not trust the second manifest.
        Files.writeString(manifest, Files.readString(manifest)
                .replace("[packages.app]", "[packages.foreign]"));
        SecurityException denied = assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyRootManifestSnapshot(admitted));
        assertTrue(denied.getMessage().contains("root namespace manifest changed"));
        assertThrows(SecurityException.class,
                () -> PackageNamespaceWorkspace.verifyPinnedSnapshot(admitted, Map.of()));
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
    void boundedSourceDigestPreservesBytesAndRejectsOverBudgetSource() throws Exception {
        Path small = temp.resolve("small-digest.ores");
        Files.writeString(small, "namespace billing; pub fnc value(): int { return 5; }");
        assertEquals(PackageNamespaceWorkspace.sha256(Files.readAllBytes(small)),
                PackageNamespaceWorkspace.boundedSourceSha256(small));

        // Exercise the reader itself; a manifest-membership Files.size()
        // precheck would reject this file before reaching the digest path.
        Path oversized = temp.resolve("over-budget-digest.ores");
        Files.write(oversized, new byte[8 * 1024 * 1024 + 1]);
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> PackageNamespaceWorkspace.boundedSourceSha256(oversized));
        assertTrue(denied.getMessage().contains("exceeds byte budget"));
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
