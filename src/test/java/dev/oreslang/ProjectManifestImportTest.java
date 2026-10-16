package dev.oreslang;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.graalvm.polyglot.PolyglotException;
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

final class ProjectManifestImportTest {
    @TempDir Path temp;

    @Test
    void manifestSourceRootsResolveBareImportsThroughCompileAndRuntimeLinking() throws Exception {
        Path project = temp.resolve("project");
        Path src = project.resolve("src");
        Files.createDirectories(src.resolve("pkg"));
        Path main = src.resolve("main.ores");

        Files.writeString(project.resolve(OresProjectConfig.MANIFEST_NAME), """
                schema_version = "1"

                [project]
                name = "manifest-demo"
                root = "."

                [source]
                roots = ["src"]

                [entrypoints]
                main = "src/main.ores"
                """);
        Files.writeString(src.resolve("pkg/greeting.ores"), """
                pub fnc greeting() : string {
                  return "manifest-path";
                }
                """);
        Files.writeString(main, """
                import fnc greeting from "pkg/greeting";

                pub fnc main() : void {
                  stdio.println(greeting());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("manifest-path"));
    }

    @Test
    void importedWildcardActorCallableCannotBypassActorCompositionBoundary() throws Exception {
        Path app = temp.resolve("actor-import");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path main = app.resolve("main.ores");

        Files.writeString(child, """
                pub actor fnc child(int value): int {
                  return value + 1;
                }
                """);

        Files.writeString(main, """
                import * as external from "./child.ores";

                actor fnc parent(int value): int {
                  return external.child(value);
                }

                pub routine main(): void {
                  stdio.stdout.write(parent(1));
                  return;
                }
                """);

        PolyglotException failure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        main,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));

        assertTrue(failure.getMessage().contains("mailbox-oriented actor composition"));
    }

    @Test
    void wildcardNamespaceCannotExtractRoutineOrActorCallableValues() throws Exception {
        Path app = temp.resolve("wildcard-direct-only");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path routineMain = app.resolve("routine-main.ores");
        Path actorMain = app.resolve("actor-main.ores");

        Files.writeString(child, """
                pub routine direct_only(int value): int {
                  return value + 1;
                }

                pub actor fnc actor_only(int value): int {
                  return value + 1;
                }
                """);

        Files.writeString(routineMain, """
                import * as external from "./child.ores";

                pub routine main(): void {
                  val callback = external.direct_only;
                  return;
                }
                """);

        Files.writeString(actorMain, """
                import * as external from "./child.ores";

                pub routine main(): void {
                  val callback = external.actor_only;
                  return;
                }
                """);

        PolyglotException routineFailure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        routineMain,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(routineFailure.getMessage().contains("direct-call-only"));

        PolyglotException actorFailure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        actorMain,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(actorFailure.getMessage().contains("scheduler-dispatched"));
        assertTrue(actorFailure.getMessage().contains("cannot be extracted"));
    }

    @Test
    void importedTailCallsPreserveCallerReturnContracts() throws Exception {
        Path app = temp.resolve("tail-contract-import");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path namedMain = app.resolve("named-main.ores");
        Path wildcardMain = app.resolve("wildcard-main.ores");

        Files.writeString(child, """
                pub fnc wrong(): String {
                  return "not-an-int";
                }
                """);

        Files.writeString(namedMain, """
                import fnc wrong from "./child.ores";

                fnc wrapped(): int {
                  return wrong();
                }

                pub routine main(): void {
                  stdio.stdout.write(wrapped());
                  return;
                }
                """);

        Files.writeString(wildcardMain, """
                import * as external from "./child.ores";

                fnc wrapped(): int {
                  return external.wrong();
                }

                pub routine main(): void {
                  stdio.stdout.write(wrapped());
                  return;
                }
                """);

        for (Path entry : List.of(namedMain, wildcardMain)) {
            PolyglotException failure = assertThrows(
                    PolyglotException.class,
                    () -> LinkedProgramRunner.run(
                            entry,
                            IsolatePolicy.developer(),
                            ExecutionProfile.serverJit(),
                            Set.of(),
                            Map.of(),
                            new ByteArrayOutputStream(),
                            new ByteArrayOutputStream()));
            assertTrue(failure.getMessage().contains("function wrapped returned String"));
            assertTrue(failure.getMessage().contains("name=int"));
        }
    }

    @Test
    void importFncRejectsDirectOnlyRoutineAndActorCallableAtLinkValidation() throws Exception {
        Path app = temp.resolve("fnc-import-contract");
        Files.createDirectories(app);
        Path routineUnit = app.resolve("routine.ores");
        Path actorUnit = app.resolve("actor.ores");
        Path routineMain = app.resolve("routine-main.ores");
        Path actorMain = app.resolve("actor-main.ores");

        Files.writeString(routineUnit, """
                pub routine work(int value): int {
                  return value + 1;
                }
                """);
        Files.writeString(actorUnit, """
                pub actor fnc work(int value): int {
                  return value + 1;
                }
                """);
        Files.writeString(routineMain, """
                import fnc work from "./routine.ores";
                pub routine main(): void { return; }
                """);
        Files.writeString(actorMain, """
                import fnc work from "./actor.ores";
                pub routine main(): void { return; }
                """);

        for (Path entry : List.of(routineMain, actorMain)) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry));
            assertTrue(failure.getMessage().contains("does not match an exported declaration"));
        }
    }

    @Test
    void genericFncsCannotBeReifiedThroughCrossFileImports() throws Exception {
        Path app = temp.resolve("generic-fnc-import-contract");
        Files.createDirectories(app);
        Path child = app.resolve("child.ores");
        Path namedMain = app.resolve("named-main.ores");
        Path wildcardMain = app.resolve("wildcard-main.ores");
        Path classMain = app.resolve("class-main.ores");

        Files.writeString(child, """
                pub fnc identity<T>(T value): T {
                  return value;
                }

                define module exported
                pub define class GenericTools as
                  pub static fnc identity<T>(T value): T {
                    return value;
                  }
                end
                end
                """);

        Files.writeString(namedMain, """
                import fnc identity from "./child.ores";
                pub routine main(): void { return; }
                """);

        IllegalArgumentException namedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(namedMain));
        assertTrue(namedFailure.getMessage().contains("does not match an exported declaration"));

        Files.writeString(wildcardMain, """
                import * as external from "./child.ores";

                pub routine main(): void {
                  val callback = external.identity;
                  return;
                }
                """);

        PolyglotException wildcardFailure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        wildcardMain,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(wildcardFailure.getMessage().contains("polymorphic function values are not supported yet"));

        Files.writeString(classMain, """
                import * as external from "./child.ores";

                pub routine main(): void {
                  val callback = external.GenericTools.identity;
                  return;
                }
                """);

        PolyglotException classFailure = assertThrows(
                PolyglotException.class,
                () -> LinkedProgramRunner.run(
                        classMain,
                        IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(),
                        Set.of(),
                        Map.of(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()));
        assertTrue(classFailure.getMessage().contains("generic static fnc"));
        assertTrue(classFailure.getMessage().contains("polymorphic function values are not supported yet"));
    }

    @Test
    void oreslangPathResolvesBareImportsWithoutAManifest() throws Exception {
        Path app = temp.resolve("app");
        Path shared = temp.resolve("shared-root");
        Files.createDirectories(app);
        Files.createDirectories(shared.resolve("common"));
        Path main = app.resolve("main.ores");

        Files.writeString(shared.resolve("common/message.ores"), """
                pub fnc message() : string {
                  return "oreslang-path";
                }
                """);
        Files.writeString(main, """
                import fnc message from "common/message";

                pub fnc main() : void {
                  stdio.println(message());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(OresProjectConfig.ENV_ORESLANG_PATH, shared.toString()),
                out,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("oreslang-path"));
    }

    @Test
    void actorClassMayExecuteExplicitCrossFileImportedFunction() throws Exception {
        Path app = temp.resolve("actor-explicit-import");
        Files.createDirectories(app);
        Path helper = app.resolve("helper.ores");
        Path main = app.resolve("main.ores");

        Files.writeString(helper, """
                pub fnc imported_marker(): void {
                  stdio.stdout.write("imported");
                  return;
                }
                """);

        Files.writeString(main, """
                import fnc imported_marker from "./helper.ores";

                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    imported_marker();
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("imported", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void resolvedSelfImportByFilenameCannotBypassActorLexicalBoundary() throws Exception {
        Path app = temp.resolve("actor-self-import");
        Files.createDirectories(app);
        Path main = app.resolve("main.ores");

        Files.writeString(main, """
                import fnc helper from "./main.ores";

                pub fnc helper(): int {
                  return 7;
                }

                define actor Worker as
                  pub run(): int {
                    return helper();
                  }
                end

                pub routine main(): void { return; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(failure.getMessage().contains("cannot import itself"),
                failure.getMessage());
    }


    @Test
    void packageRootAndZedAliasesLinkThroughTheSameCompilerGraph() throws Exception {
        Path project = temp.resolve("package-integration");
        Path src = project.resolve("src");
        Path external = temp.resolve("external-zed");
        Files.createDirectories(src);
        Files.createDirectories(external.resolve("src"));
        Files.createDirectories(project.resolve("zed_modules/acme"));
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [package]
                name = "integration"
                [source]
                roots = ["src"]
                [dependencies]
                tools = { zed = "acme/tools" }
                """);
        Files.writeString(src.resolve("local.ores"), """
                pub fnc local() : string { return "local:"; }
                """);
        Files.writeString(external.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [package]
                name = "tools"
                [source]
                roots = ["src"]
                """);
        Files.writeString(external.resolve("src/internal.ores"), """
                pub fnc internal() : string { return "zed"; }
                """);
        Files.writeString(external.resolve("src/remote.ores"), """
                import fnc internal from "@/src/internal";
                pub fnc remote() : string { return internal(); }
                """);
        try {
            Files.createSymbolicLink(project.resolve("zed_modules/acme/tools"), external);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "filesystem symlinks unavailable");
        }
        Path main = src.resolve("main.ores");
        Files.writeString(main, """
                import fnc local from "@/src/local";
                import fnc remote from "pkg:tools/src/remote";

                pub fnc main() : void {
                  stdio.println(local() + remote());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var build = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(), Map.of(), out, new ByteArrayOutputStream());
        assertEquals(4, build.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("local:zed"));
    }

    @Test
    void symlinkedSelfImportCannotEvadeIdentityChecks() throws Exception {
        Path project = temp.resolve("symlinked-self");
        Files.createDirectories(project);
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                """);
        Path main = project.resolve("main.ores");
        Files.writeString(main, """
                import fnc helper from "@/alias";
                pub fnc helper() : int { return 42; }
                pub fnc main() : void { return; }
                """);
        try {
            Files.createSymbolicLink(project.resolve("alias.ores"), main);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "filesystem symlinks unavailable");
        }
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(failure.getMessage().contains("cannot import itself"));
    }


    @Test
    void nestedPackageHasItsOwnRootThroughRelativeAndBareImports() throws Exception {
        Path project = temp.resolve("nested-packages");
        Path src = project.resolve("src");
        Path nested = project.resolve("packages/leaf");
        Files.createDirectories(src);
        Files.createDirectories(nested.resolve("src"));
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["packages"]
                """);
        Files.writeString(nested.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [source]
                roots = ["src"]
                """);
        Files.writeString(src.resolve("util.ores"), """
                pub fnc util(): string { return "WRONG PARENT"; }
                """);
        Files.writeString(nested.resolve("src/util.ores"), """
                pub fnc util(): string { return "nested-root"; }
                """);
        Files.writeString(nested.resolve("src/child.ores"), """
                import fnc util from "@/src/util";
                pub fnc child(): string { return util(); }
                """);

        for (String path : List.of("../packages/leaf/src/child", "leaf/src/child")) {
            Path main = src.resolve(path.startsWith(".") ? "main-relative.ores" : "main-bare.ores");
            Files.writeString(main, """
                    import fnc child from "%s";
                    pub fnc main(): void {
                      stdio.println(child());
                      return;
                    }
                    """.formatted(path));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var built = LinkedProgramRunner.run(main, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), Set.of(), Map.of(),
                    out, new ByteArrayOutputStream());
            assertEquals(3, built.units().size());
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("nested-root"));
        }
    }

    @Test
    void installedPackageWithoutManifestCannotResolveConsumerRootImport() throws Exception {
        Path project = temp.resolve("no-dep-manifest");
        Path installed = project.resolve("zed_modules/acme/legacy");
        Files.createDirectories(installed.resolve("src"));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [dependencies]
                legacy = "acme/legacy"
                """);
        Files.writeString(project.resolve("src/secret.ores"), """
                pub fnc secret(): string { return "consumer-data"; }
                """);
        Files.writeString(installed.resolve("src/child.ores"), """
                import fnc secret from "@/src/secret";
                pub fnc child(): string { return secret(); }
                """);
        Path main = project.resolve("src/main.ores");
        Files.writeString(main, """
                import fnc child from "pkg:legacy/src/child";
                pub fnc main(): void { return; }
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(failure.getMessage().contains("require a .ores.pkg.toml"),
                failure.getMessage());
    }


    @Test
    void manifestlessTransitiveBareImportsRetainOriginalSearchRoot() throws Exception {
        Path app = temp.resolve("legacy-transitive");
        Files.createDirectories(app.resolve("components"));
        Files.createDirectories(app.resolve("shared"));
        Files.writeString(app.resolve("shared/answer.ores"), """
                pub fnc answer(): string { return "legacy-still-works"; }
                """);
        Files.writeString(app.resolve("components/first.ores"), """
                import fnc answer from "shared/answer";
                pub fnc first(): string { return answer(); }
                """);
        Path main = app.resolve("main.ores");
        Files.writeString(main, """
                import fnc first from "components/first";
                pub fnc main(): void {
                  stdio.println(first());
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var built = LinkedProgramRunner.run(main, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out, new ByteArrayOutputStream());
        assertEquals(3, built.units().size());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("legacy-still-works"));
    }


    @Test
    void physicallySharedSourceCannotSilentlyInheritTwoPackageRoots() throws Exception {
        Path project = temp.resolve("context-conflict");
        Path one = project.resolve("zed_modules/acme/one");
        Path two = project.resolve("zed_modules/acme/two");
        Path common = temp.resolve("same-physical-source.ores");
        Files.createDirectories(one.resolve("src"));
        Files.createDirectories(two.resolve("src"));
        Files.writeString(project.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME), """
                schema_version = "1"
                [dependencies]
                one = "acme/one"
                two = "acme/two"
                """);
        Files.writeString(one.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(two.resolve(OresProjectConfig.PACKAGE_MANIFEST_NAME),
                "schema_version = \"1\"\n");
        Files.writeString(common, """
                pub fnc first(): int { return 1; }
                pub fnc second(): int { return 2; }
                """);
        try {
            Files.createSymbolicLink(one.resolve("src/first.ores"), common);
            Files.createSymbolicLink(two.resolve("src/second.ores"), common);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "filesystem symlinks unavailable");
        }
        Path main = project.resolve("main.ores");
        Files.writeString(main, """
                import fnc first from "pkg:one/src/first";
                import fnc second from "pkg:two/src/second";
                pub fnc main(): void { return; }
                """);
        IllegalArgumentException conflicting = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));
        assertTrue(conflicting.getMessage().contains("conflicting project contexts"),
                conflicting.getMessage());
    }

}
