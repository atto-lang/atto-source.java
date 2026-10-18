package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class LinkedContractConformanceHardeningTest {

    @Test
    void linkedModuleContractConformanceUsesTypeOnlyMetadata() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define contract WorkerApi as
                  name: string;
                  fnc run(int value): int;
                end
                """);
        sources.put("worker.ores", """
                import contract WorkerApi from "./api.ores";

                define module Worker conforms WorkerApi as
                  pub const name: string = "worker";
                  pub fnc run(int value): int { return value + 1; }
                end
                """);

        var result = assertDoesNotThrow(
                () -> new IncrementalCompiler().compile(sources));
        assertEquals(2, result.units().size());
    }

    @Test
    void linkedStaticContractPreservesStaticKindAndAlias() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define static contract Factory as
                  version: string;
                  fnc create(): int;
                end
                """);
        sources.put("factory.ores", """
                import contract Factory as Api from "./api.ores";

                pub define class IntFactory implements static Api as
                  pub static const version: string = "1";
                  pub static fnc create(): int { return 42; }
                end
                """);

        assertDoesNotThrow(() -> new IncrementalCompiler().compile(sources));
    }

    @Test
    void linkedContractKindCannotBeRetargetedAcrossModuleAndStaticSides() {
        Map<String, String> staticApi = new LinkedHashMap<>();
        staticApi.put("api.ores", """
                pub define static contract Api as
                  fnc ping(): int;
                end
                """);
        staticApi.put("consumer.ores", """
                import contract Api from "./api.ores";
                define module Worker conforms Api as
                  pub fnc ping(): int { return 1; }
                end
                """);
        IllegalArgumentException moduleFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(staticApi));
        assertTrue(moduleFailure.getMessage().contains("static contract"));

        Map<String, String> moduleApi = new LinkedHashMap<>();
        moduleApi.put("api.ores", """
                pub define contract Api as
                  fnc ping(): int;
                end
                """);
        moduleApi.put("consumer.ores", """
                import contract Api from "./api.ores";
                pub define class Worker implements static Api as
                  pub static fnc ping(): int { return 1; }
                end
                """);
        IllegalArgumentException classFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(moduleApi));
        assertTrue(classFailure.getMessage().contains("module contract"));
    }

    @Test
    void importedInheritedContractFailsClosedWithoutParentProvenance() {
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

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(failure.getMessage().contains("parent provenance")
                || failure.getMessage().contains("inherited obligations"));
    }

    @Test
    void importedContractRejectsForeignNominalTypeWithoutProvenance() {
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

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(failure.getMessage().contains("without linker provenance"));
    }

    @Test
    void explicitWriteCapabilityIsExactAcrossLinkedModuleContract() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define contract MutableName as
                  let name: string;
                end
                """);
        sources.put("worker.ores", """
                import contract MutableName from "./api.ores";
                define module Worker conforms MutableName as
                  pub const name: string = "fixed";
                end
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(failure.getMessage().contains("binding kind"));
    }

    @Test
    void linkedWriteCapableFieldIsTypeInvariant() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define contract MutableSlot as
                  let value: any;
                end
                """);
        sources.put("worker.ores", """
                import contract MutableSlot from "./api.ores";
                define module Worker conforms MutableSlot as
                  pub let value: int = 1;
                end
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(sources));
        assertTrue(failure.getMessage().contains("type-invariant")
                || failure.getMessage().contains("does not conform"));
    }

    @Test
    void contractAbiChangeInvalidatesAndRechecksDependent() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define contract WorkerApi as
                  name: string;
                end
                """);
        sources.put("worker.ores", """
                import contract WorkerApi from "./api.ores";
                define module Worker conforms WorkerApi as
                  pub const name: string = "worker";
                end
                """);

        var first = assertDoesNotThrow(() -> compiler.compile(sources));
        assertEquals(2, first.rebuiltUnits().size());
        var second = assertDoesNotThrow(() -> compiler.compile(sources));
        assertEquals(2, second.reusedUnits().size());

        sources.put("api.ores", """
                pub define contract WorkerApi as
                  let name: string;
                end
                """);
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class,
                        () -> compiler.compile(sources));
        assertTrue(failure.getMessage().contains("binding kind"));
    }

    @Test
    void changingContractCategoryWithSameNameIsAnAbiChange() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api.ores", """
                pub define static contract Api as
                  fnc ping(): int;
                end
                """);
        sources.put("consumer.ores", """
                import contract Api from "./api.ores";
                pub define class Consumer implements static Api as
                  pub static fnc ping(): int { return 1; }
                end
                """);

        assertDoesNotThrow(() -> compiler.compile(sources));
        sources.put("api.ores", """
                pub define contract Api as
                  fnc ping(): int;
                end
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(sources));
        assertTrue(failure.getMessage().contains("module contract"));
    }
}
