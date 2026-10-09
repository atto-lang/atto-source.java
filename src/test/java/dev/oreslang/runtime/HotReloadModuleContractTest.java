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

                define module Worker conforms A as
                  pub fnc run(): int { return 1; }
                end
                """);

        compiler.compile(first);

        var changed = java.util.Map.of(
                "worker.ores",
                """
                pub define contract B as
                  fnc run(): int;
                end

                define module Worker conforms B as
                  pub fnc run(): int { return 1; }
                end
                """);

        var result = compiler.compile(changed);
        assertEquals(true, result.rebuilt("worker.ores"));
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
}
