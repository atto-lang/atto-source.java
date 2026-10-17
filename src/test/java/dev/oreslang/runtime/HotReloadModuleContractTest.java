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
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> generation.invokePublic("Worker.run"));
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    generation::start,
                    "module candidates must not use the legacy main-executing start path");
            assertEquals(false, generation.started());

            loader.initializeModule(generation);
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
            assertEquals(1, generation.invokePublic("Worker.run").asInt());
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
                      pub routine init(): void {
                        val int broken = 1 / 0;
                        return;
                      }
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
            assertEquals(1, oldGeneration.invokePublic("Worker.run").asInt(),
                    "callers pinned to the old generation can finish against its implementation");
            assertEquals(2, newGeneration.invokePublic("Worker.run").asInt());
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
                      pub routine init(): void {
                        val int broken = 1 / 0;
                        return;
                      }
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
            loader.initializeModule(candidate);
            assertEquals(oldGeneration, loader.active("./worker.ores"),
                    "initialization alone must not publish the candidate");

            loader.activateModule(candidate);
            assertEquals(candidate, loader.active("worker.ores"));
            assertEquals(false, oldGeneration.closed());
            assertEquals(2, loader.liveGenerations());
        }
    }

    @Test
    void staleStagedCandidateCannotReplaceANewerGeneration() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            java.util.function.Function<Integer, String> version = value -> """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return %s; }
                    end
                    """.formatted(value);

            HotReloadManager.Generation original =
                    loader.reloadModule("worker.ores", "Worker", version.apply(1), "WorkerApi");
            HotReloadManager.Generation candidateA =
                    loader.loadModule("worker.ores", "Worker", version.apply(2), "WorkerApi");
            HotReloadManager.Generation candidateB =
                    loader.loadModule("worker.ores", "Worker", version.apply(3), "WorkerApi");

            loader.initializeModule(candidateA);
            loader.activateModule(candidateA);

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> loader.initializeModule(candidateB));

            assertEquals(candidateA, loader.active("worker.ores"));
            assertEquals(true, candidateB.closed());
            assertEquals(2, loader.liveGenerations(),
                    "the stale candidate should be removed; original remains available for draining");
            assertEquals(false, original.closed());
        }
    }

    @Test
    void failedLegacyStartRetiresClosedGenerationAndClearsActiveSlot() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String source = """
                    pub routine main(): void {
                      val int failure = 1 / 0;
                      return;
                    }
                    """;

            org.junit.jupiter.api.Assertions.assertThrows(
                    RuntimeException.class,
                    () -> loader.loadAndStart("broken.ores", source));

            assertEquals(0, loader.liveGenerations(),
                    "a failed start must not leak a closed generation into the registry");
            assertEquals(null, loader.active("broken.ores"));
            assertEquals(null, loader.active());
        }
    }

    @Test
    void repeatedInitializationAndActivationDoNotRetireActiveGeneration() {
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

            assertEquals(generation, loader.initializeModule(generation));
            assertEquals(generation, loader.initializeModule(generation));
            assertEquals(generation, loader.activateModule(generation));
            assertEquals(generation, loader.activateModule(generation));
            assertEquals(generation, loader.active("worker.ores"));
            assertEquals(false, generation.closed());
            assertEquals(1, loader.liveGenerations());
        }
    }

    @Test
    void closingGenerationDirectlyRemovesItsActiveSlot() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            HotReloadManager.Generation generation = loader.loadAndStart(
                    "ordinary.ores",
                    """
                    pub routine main(): void { return; }
                    """);
            assertEquals(generation, loader.active("ordinary.ores"));

            generation.close();
            generation.close();

            assertEquals(true, generation.closed());
            assertEquals(null, loader.active("ordinary.ores"));
            assertEquals(null, loader.active());
            assertEquals(0, loader.liveGenerations());
        }
    }

    @Test
    void sameNamedContractCannotBeWeakenedDuringReload() {
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
                      fnc run(int value): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(int value): int { return value + 1; }
                    end
                    """;
            HotReloadManager.Generation original =
                    loader.reloadModule("worker.ores", "Worker", v1, "WorkerApi");

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> loader.reloadModule("worker.ores", "Worker", v2, "WorkerApi"));

            assertEquals(original, loader.active("worker.ores"));
            assertEquals(1, original.invokePublic("Worker.run").asInt());
            assertEquals(false, original.closed());
            assertEquals(1, loader.liveGenerations());
        }
    }

    @Test
    void reloadCannotDropAnAdditionalDeclaredModuleContract() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String v1 = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    pub define contract HealthApi as
                      fnc health(): int;
                    end
                    define module Worker conforms WorkerApi, HealthApi as
                      pub fnc run(): int { return 1; }
                      pub fnc health(): int { return 100; }
                    end
                    """;
            String v2 = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    pub define contract HealthApi as
                      fnc health(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 2; }
                      pub fnc health(): int { return 200; }
                    end
                    """;
            HotReloadManager.Generation original =
                    loader.reloadModule("worker.ores", "Worker", v1, "WorkerApi");

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> loader.reloadModule("worker.ores", "Worker", v2, "WorkerApi"));

            assertEquals(original, loader.active("worker.ores"));
            assertEquals(1, original.invokePublic("Worker.run").asInt());
            assertEquals(100, original.invokePublic("Worker.health").asInt());
            assertEquals(1, loader.liveGenerations());
        }
    }

    @Test
    void reloadCannotWeakenAnExplicitContractFieldBindingRequirement() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String v1 = """
                    pub define contract WorkerApi as
                      const name: String;
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub const String name = "stable";
                      pub fnc run(): int { return 1; }
                    end
                    """;
            String v2 = """
                    pub define contract WorkerApi as
                      name: String;
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub let String name = "mutable";
                      pub fnc run(): int { return 2; }
                    end
                    """;
            HotReloadManager.Generation original =
                    loader.reloadModule("worker.ores", "Worker", v1, "WorkerApi");

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> loader.reloadModule("worker.ores", "Worker", v2, "WorkerApi"));

            assertEquals(original, loader.active("worker.ores"));
            assertEquals(1, original.invokePublic("Worker.run").asInt());
            assertEquals(false, original.closed());
        }
    }

    @Test
    void inheritedContractChangesInvalidateModuleHotReload() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String v1 = """
                    pub define contract BaseApi as
                      fnc base(): int;
                    end
                    pub define contract WorkerApi extends BaseApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc base(): int { return 10; }
                      pub fnc run(): int { return 1; }
                    end
                    """;
            String v2 = """
                    pub define contract BaseApi as
                      fnc base(int value): int;
                    end
                    pub define contract WorkerApi extends BaseApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc base(int value): int { return value; }
                      pub fnc run(): int { return 2; }
                    end
                    """;
            HotReloadManager.Generation original =
                    loader.reloadModule("worker.ores", "Worker", v1, "WorkerApi");

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> loader.reloadModule("worker.ores", "Worker", v2, "WorkerApi"));

            assertEquals(original, loader.active("worker.ores"));
            assertEquals(1, original.invokePublic("Worker.run").asInt());
            assertEquals(10, original.invokePublic("Worker.base").asInt());
        }
    }

    @Test
    void moduleGenerationCannotInvokePublicCallablesOutsideItsContractSet() {
        try (HotReloadManager loader =
                     new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit())) {
            String source = """
                    pub define contract WorkerApi as
                      fnc run(): int;
                    end
                    define module Worker conforms WorkerApi as
                      pub fnc run(): int { return 1; }
                      pub fnc debug(): int { return 999; }
                    end
                    """;
            HotReloadManager.Generation generation =
                    loader.reloadModule("worker.ores", "Worker", source, "WorkerApi");

            assertEquals(1, generation.invokePublic("Worker.run").asInt());
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> generation.invokePublic("Worker.debug"));
        }
    }



    @Test
    void managerShutdownClosesStagedModulesAndRejectsFurtherLoads() {
        HotReloadManager loader =
                new HotReloadManager(IsolatePolicy.developer(), ExecutionProfile.serverJit());
        String source = """
                pub define contract WorkerApi as
                  fnc run(): int;
                end
                define module Worker conforms WorkerApi as
                  pub fnc run(): int { return 1; }
                end
                """;
        HotReloadManager.Generation candidate =
                loader.loadModule("worker.ores", "Worker", source, "WorkerApi");

        loader.close();
        loader.close();

        assertEquals(true, candidate.closed());
        assertEquals(0, loader.liveGenerations());
        assertEquals(null, loader.active("worker.ores"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> loader.loadModule("worker.ores", "Worker", source, "WorkerApi"));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> loader.activateModule(candidate));
    }

    @Test
    void moduleGenerationDoesNotExposeRawContextThatCouldBypassAdmission() {
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

            assertThrows(IllegalStateException.class, generation::context);
            assertEquals(false, generation.started());
            assertEquals(null, loader.active("worker.ores"));
        }
    }
}
