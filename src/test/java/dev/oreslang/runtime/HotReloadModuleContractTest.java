package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HotReloadModuleContractTest {

    @Test
    void loaderRequiresExplicitModuleConformance() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String source = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end

                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                    end
                    """;

            HotReloadManager.Generation generation =
                    loader.loadModule("worker.ores", "Worker", source, "WorkerApi");

            assertEquals("worker.ores", generation.codeUnitId());
            assertEquals(null, loader.active("worker.ores"),
                    "a staged candidate must not become active before initialization");
            assertEquals(false, generation.started());

            generation.start();
            loader.activateModule(generation);
            assertEquals(generation, loader.active("worker.ores"));
        }
    }

    @Test
    void loaderRejectsMissingOrWrongContractBeforeStaging() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String source = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end

                    define module Worker as
                      pub fnc run(): int { return 1; }
                    end
                    """;

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> loader.loadModule("worker.ores", "Worker", source, "WorkerApi"));

            assertEquals("hot-loaded module 'Worker' must declare 'conforms WorkerApi'",
                    failure.getMessage());
            assertEquals(0, loader.liveGenerations());
        }
    }

    @Test
    void incrementalAbiIncludesDeclaredModuleContracts() {
        dev.oreslang.compiler.IncrementalCompiler compiler =
                new dev.oreslang.compiler.IncrementalCompiler();

        var first = java.util.Map.of(
                "worker.ores",
                """
                pub define contract A as
                  fnc run(): int;
                end

                pub define contract B as
                  fnc run(): int;
                end

                define module Worker conforms A as
                  pub fnc run(): int { return 1; }
                end
                """,
                "consumer.ores",
                """
                import * as worker from "./worker.ores";
                pub routine main(): void { return; }
                """);

        compiler.compile(first);

        var changed = java.util.Map.of(
                "worker.ores",
                """
                pub define contract A as
                  fnc run(): int;
                end

                pub define contract B as
                  fnc run(): int;
                end

                define module Worker conforms B as
                  pub fnc run(): int { return 1; }
                end
                """,
                "consumer.ores",
                """
                import * as worker from "./worker.ores";
                pub routine main(): void { return; }
                """);

        var result = compiler.compile(changed);
        assertEquals(true, result.rebuilt("worker.ores"));
        assertEquals(true, result.rebuilt("consumer.ores"),
                "a declared-conformance change must invalidate importers");
    }

    @Test
    void moduleReloadInitializesModuleWithoutRunningProgramMain() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String source = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end

                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                      pub routine main(): void {
                        val int shouldNotRun = 1 / 0;
                        return;
                      }
                    end
                    """;

            HotReloadManager.Generation generation =
                    loader.reloadModule("worker.ores", "Worker", source, "WorkerApi");

            assertEquals(generation, loader.active("worker.ores"));
            assertEquals(true, generation.started());
            assertEquals(1, loader.liveGenerations());
        }
    }

    @Test
    void failedModuleInitializationRestoresPreviousGeneration() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String stable = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end

                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                    end
                    """;
            HotReloadManager.Generation previous =
                    loader.reloadModule("worker.ores", "Worker", stable, "WorkerApi");

            String broken = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end

                    define module Worker conforms WorkerApi as
                      pub val int broken = 1 / 0;
                      pub fnc run(): int { return 2; }
                    end
                    """;

            org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class,
                    () -> loader.reloadModule("worker.ores", "Worker", broken, "WorkerApi"));

            assertEquals(previous, loader.active("worker.ores"));
            assertEquals(false, previous.closed());
            assertEquals(1, loader.liveGenerations());
        }
    }

    @Test
    void unsupportedImportedAndGenericContractLoadsFailClosed() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String imported = """
                    import module Dependencies from "./dependencies.ores";

                    pub define contract WorkerApi as
                      fnc run(): int;
                    end

                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                    end
                    """;
            IllegalArgumentException importFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> loader.loadModule("worker.ores", "Worker", imported, "WorkerApi"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    importFailure.getMessage().contains("imports require linked-code-unit loading"));

            String generic = """
                    pub define contract WorkerApi<T> as
                      fnc run(T value): T;
                    end

                    define module Worker conforms WorkerApi<int> as
                      pub fnc run(int value): int { return value; }
                    end
                    """;
            IllegalArgumentException genericFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> loader.loadModule("worker.ores", "Worker", generic, "WorkerApi"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    genericFailure.getMessage().contains("generic module contracts"));
            assertEquals(0, loader.liveGenerations());
        }
    }

    @Test
    void loaderRejectsMultipleNamedModulesRatherThanInitializingUnrelatedModules() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String source = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end

                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                    end

                    define module Unrelated as
                      pub val int dangerous = 1 / 0;
                    end
                    """;
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> loader.loadModule("worker.ores", "Worker", source, "WorkerApi"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    failure.getMessage().contains("exactly one named module"));
            assertEquals(0, loader.liveGenerations());
        }
    }

    @Test
    void contractKindAndFieldBindingRequirementParticipateInAbi() {
        dev.oreslang.compiler.IncrementalCompiler compiler =
                new dev.oreslang.compiler.IncrementalCompiler();

        var first = java.util.Map.of(
                "api.ores",
                """
                pub define contract Api as
                  value: String;
                end
                """,
                "consumer.ores",
                """
                import * as api from "./api.ores";
                pub routine main(): void { return; }
                """);
        compiler.compile(first);

        var explicitBinding = java.util.Map.of(
                "api.ores",
                """
                pub define contract Api as
                  const value: String;
                end
                """,
                "consumer.ores",
                """
                import * as api from "./api.ores";
                pub routine main(): void { return; }
                """);
        var bindingResult = compiler.compile(explicitBinding);
        assertEquals(true, bindingResult.rebuilt("consumer.ores"),
                "changing a binding requirement must invalidate importers");

        var changedKind = java.util.Map.of(
                "api.ores",
                """
                pub define interface Api as
                  value: String;
                end
                """,
                "consumer.ores",
                """
                import * as api from "./api.ores";
                pub routine main(): void { return; }
                """);
        var kindResult = compiler.compile(changedKind);
        assertEquals(true, kindResult.rebuilt("consumer.ores"),
                "changing contract/interface identity must invalidate importers");
    }

    @Test
    void unqualifiedContractFieldsAcceptAnyReadableExportedBinding() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                dev.oreslang.types.TypeChecker.check(
                        dev.oreslang.parser.Parser.parse("""
                        define contract Readable as
                          value: String;
                        end

                        define module Mutable conforms Readable as
                          pub let String value = "mutable";
                        end

                        define module Stable conforms Readable as
                          pub val String value = "stable";
                        end

                        define module Constant conforms Readable as
                          pub const String value = "constant";
                        end
                        """)));
    }

    @Test
    void explicitContractFieldBindingKindRemainsExact() {
        dev.oreslang.types.TypeChecker.check(dev.oreslang.parser.Parser.parse("""
                define contract MutableApi as
                  let value: String;
                end

                define module Good conforms MutableApi as
                  pub let String value = "good";
                end
                """));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.check(dev.oreslang.parser.Parser.parse("""
                        define contract StableApi as
                          val value: String;
                        end

                        define module Broken conforms StableApi as
                          pub let String value = "broken";
                        end
                        """)));
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("binding kind"));
    }

    @Test
    void successfulReloadKeepsPreviousGenerationAliveUntilExplicitRetirement() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String v1 = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                    end
                    """;
            String v2 = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 2; }
                    end
                    """;

            HotReloadManager.Generation oldGeneration =
                    loader.reloadModule("worker.ores", "Worker", v1, "WorkerApi");
            HotReloadManager.Generation newGeneration =
                    loader.reloadModule("worker.ores", "Worker", v2, "WorkerApi");

            assertEquals(newGeneration, loader.active("worker.ores"));
            assertEquals(false, oldGeneration.closed(),
                    "old-generation actors/requests may still be draining");
            assertEquals(2, loader.liveGenerations());

            loader.retire(oldGeneration.id());
            assertEquals(true, oldGeneration.closed());
            assertEquals(1, loader.liveGenerations());
            assertEquals(newGeneration, loader.active("worker.ores"));
        }
    }

    @Test
    void failedReloadOfAnotherCodeUnitRestoresGlobalActivePointer() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String stable = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                    end
                    """;
            HotReloadManager.Generation stableGeneration =
                    loader.reloadModule("stable.ores", "Worker", stable, "WorkerApi");

            String broken = """
                    pub define contract BrokenApi as
                      fnc run(): int;
                    end
                    define module Broken conforms BrokenApi as
                      pub val int broken = 1 / 0;
                      pub fnc run(): int { return 1; }
                    end
                    """;

            org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class,
                    () -> loader.reloadModule("broken.ores", "Broken", broken, "BrokenApi"));

            assertEquals(stableGeneration, loader.active());
            assertEquals(stableGeneration, loader.active("stable.ores"));
            assertEquals(null, loader.active("broken.ores"));
            assertEquals(1, loader.liveGenerations());
        }
    }

    @Test
    void rootScopeRejectsExecutableDeclarationsDuringModuleHotLoad() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String source = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    pub val int unrelated = 1 / 0;
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                    end
                    """;
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> loader.loadModule("worker.ores", "Worker", source, "WorkerApi"));
            org.junit.jupiter.api.Assertions.assertTrue(
                    failure.getMessage().contains("root scope may contain only module contract declarations"));
            assertEquals(0, loader.liveGenerations());
        }
    }

    @Test
    void stagingCandidateDoesNotPublishUntilExplicitActivation() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String v1 = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                    end
                    """;
            String v2 = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 2; }
                    end
                    """;

            HotReloadManager.Generation oldGeneration =
                    loader.reloadModule("worker.ores", "Worker", v1, "WorkerApi");
            HotReloadManager.Generation candidate =
                    loader.loadModule("./worker.ores", "Worker", v2, "WorkerApi");

            assertEquals(oldGeneration, loader.active("worker.ores"));
            candidate.start();
            assertEquals(oldGeneration, loader.active("./worker.ores"),
                    "initialization alone must not publish the candidate");

            loader.activateModule(candidate);
            assertEquals(candidate, loader.active("worker.ores"));
            assertEquals(false, oldGeneration.closed());
            assertEquals(2, loader.liveGenerations());
        }
    }

    @Test
    void failedGenericLoadAndStartPreservesActiveGeneration() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var old = loader.loadAndStart("stable.ores", "pub val int answer = 1;");
            assertThrows(RuntimeException.class, () ->
                    loader.loadAndStart("stable.ores", "pub val int answer = 1 / 0;"));
            assertEquals(old, loader.active("stable.ores"));
            assertEquals(old, loader.active());
            assertEquals(false, old.closed());
            assertEquals(1, loader.liveGenerations());
        }
    }

    @Test
    void closedManagerRejectsNewStagesAndActivation() {
        HotReloadManager loader =
                new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit());
        loader.close();
        loader.close();
        assertEquals("hot-reload manager is closed",
                assertThrows(IllegalStateException.class, () ->
                        loader.load("worker.ores", "pub val int answer = 1;")).getMessage());
        assertThrows(IllegalStateException.class, () ->
                loader.loadModule("worker.ores", "Worker", "invalid", "WorkerApi"));
    }

    @Test
    void closedGenerationIsNeverReportedAsActive() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            var loaded = loader.load("worker.ores", "pub val int answer = 1;");
            loaded.close();
            assertEquals(null, loader.active());
            assertEquals(null, loader.active("worker.ores"));
            assertEquals(true, loader.activeGenerations().isEmpty());
            loader.retire(loaded.id());
            assertEquals(0, loader.liveGenerations());
        }
    }
}
