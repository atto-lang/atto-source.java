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
}
