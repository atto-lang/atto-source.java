package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModuleContractLinkingTest {

    @Test
    void namedCrossFileModuleContractConformanceWorks() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define contract WorkerApi as
                  fnc run(int value): int;
                  name: String;
                end
                """);
        sources.put("worker.ores", """
                import contract WorkerApi from "./api.ores";

                define module Worker conforms WorkerApi as
                  pub val String name = "worker";
                  pub fnc run(int value): int { return value + 1; }
                end
                """);

        assertDoesNotThrow(() -> compiler.compile(sources));
    }

    @Test
    void aliasedCrossFileContractIsTheConformanceName() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define contract WorkerApi as
                  fnc run(): int;
                end
                """);
        sources.put("worker.ores", """
                import contract WorkerApi as Api from "./api.ores";

                define module Worker conforms Api as
                  pub fnc run(): int { return 1; }
                end
                """);

        assertDoesNotThrow(() -> compiler.compile(sources));
    }

    @Test
    void linkedContractRejectsMissingFieldAndWrongCallableSignature() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define contract WorkerApi as
                  const name: String;
                  fnc run(int value): int;
                end
                """);
        sources.put("worker.ores", """
                import contract WorkerApi from "./api.ores";

                define module Worker conforms WorkerApi as
                  pub let String name = "mutable";
                  pub fnc run(String value): int { return 1; }
                end
                """);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources));
        assertTrue(failure.getMessage().contains("does not conform")
                        || failure.getMessage().contains("binding kind"),
                failure::getMessage);
    }

    @Test
    void importedInheritedContractFailsClosedWithoutParentProvenance() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define contract Base as
                  fnc base(): int;
                end
                pub define contract Child extends Base as
                  fnc child(): int;
                end
                """);
        sources.put("worker.ores", """
                import contract Child from "./api.ores";

                define module Worker conforms Child as
                  pub fnc base(): int { return 1; }
                  pub fnc child(): int { return 2; }
                end
                """);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources));
        assertTrue(failure.getMessage().contains("parent provenance"), failure::getMessage);
    }

    @Test
    void importedContractRejectsUnlinkedForeignNominalTypes() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define interface Secret as
                end
                pub define contract Api as
                  fnc reveal(Secret value): int;
                end
                """);
        sources.put("worker.ores", """
                import contract Api from "./api.ores";

                define module Worker conforms Api as
                  pub fnc reveal(Secret value): int { return 1; }
                end
                """);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources));
        assertTrue(failure.getMessage().contains("without linker provenance"), failure::getMessage);
    }
}
