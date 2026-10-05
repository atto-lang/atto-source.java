package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ModuleContractsTest {

    @Test
    void modulesConformWithFunctionsAndFieldsAndCanBeIteratedAsTypedHandles() throws Exception {
        String output = run("""
                pub define interface StorageModule
                  kind: String;
                  fnc health() => int;
                end

                define module RedisStorage with StorageModule as
                  pub const String kind = "redis";
                  pub fnc health(): int { return 1; }
                end

                define module PostgresStorage with StorageModule as
                  pub const String kind = "postgres";
                  pub fnc health(): int { return 2; }
                end

                pub routine main(): void {
                  val List<Module<StorageModule>> stores = [RedisStorage, PostgresStorage];
                  for store of stores do
                    stdio.stdout.write(store.kind);
                    stdio.stdout.write(":");
                    stdio.stdout.write(store.health());
                    stdio.stdout.write("|");
                  done
                  return;
                }
                """);

        assertEquals("redis:1|postgres:2|", output);
    }

    @Test
    void explicitModuleContractsRejectMissingFieldsAndBadCallableShapes() {
        IllegalArgumentException missingField = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub define interface StorageModule
                          kind: String;
                          fnc health() => int;
                        end

                        define module Broken with StorageModule as
                          pub fnc health(): int { return 1; }
                        end
                        """)));
        assertTrue(missingField.getMessage().contains("does not adhere"));

        IllegalArgumentException badCallable = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub define interface StorageModule
                          kind: String;
                          fnc health() => int;
                        end

                        define module Broken with StorageModule as
                          pub const String kind = "broken";
                          pub fnc health(): String { return "bad"; }
                        end
                        """)));
        assertTrue(badCallable.getMessage().contains("does not adhere"));
    }

    @Test
    void moduleHandleTypeCannotBeSatisfiedByAnOrdinaryStructuralRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub define interface StorageModule
                  kind: String;
                  fnc health() => int;
                end

                fnc use(Module<StorageModule> storage): int {
                  return storage.health();
                }

                fnc bad(): int {
                  val record = {kind: "not-a-module"};
                  return use(record);
                }
                """)));
    }

    @Test
    void importedInterfacesCanContractMultipleModulesAcrossFiles() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("storage-contract.ores", """
                pub define interface StorageModule
                  kind: String;
                  fnc health() => int;
                end
                """);
        sources.put("redis.ores", """
                import interface StorageModule from "./storage-contract.ores";

                define module RedisStorage with StorageModule as
                  pub const String kind = "redis";
                  pub fnc health(): int { return 1; }
                end

                pub fnc selected(): Module<StorageModule> {
                  return RedisStorage;
                }
                """);
        sources.put("postgres.ores", """
                import interface StorageModule as BackendContract from "./storage-contract.ores";

                define module PostgresStorage with BackendContract as
                  pub const String kind = "postgres";
                  pub fnc health(): int { return 2; }
                end

                pub fnc selected(): Module<BackendContract> {
                  return PostgresStorage;
                }
                """);

        var result = compiler.compile(sources);
        assertEquals(3, result.rebuiltUnits().size());
        assertTrue(result.units().containsKey("redis.ores"));
        assertTrue(result.units().containsKey("postgres.ores"));
    }

    @Test
    void importedModuleContractFailsClosedWhenImplementationDrifts() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        Map<String, String> sources = Map.of(
                "storage-contract.ores", """
                        pub define interface StorageModule
                          kind: String;
                          fnc health() => int;
                        end
                        """,
                "broken.ores", """
                        import interface StorageModule from "./storage-contract.ores";

                        define module Broken with StorageModule as
                          pub const String kind = "broken";
                          pub fnc health(): String { return "bad"; }
                        end
                        """);

        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> compiler.compile(sources));
        assertTrue(failure.getMessage().contains("does not adhere"));
    }

    @Test
    void moduleContractsParticipateInAbiInvalidation() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = new LinkedHashMap<>();
        first.put("contracts.ores", """
                pub define interface A
                  fnc run() => int;
                end

                pub define interface B
                  fnc run() => int;
                end
                """);
        first.put("provider.ores", """
                import interface A from "./contracts.ores";
                import interface B from "./contracts.ores";

                define module Worker with A as
                  pub fnc run(): int { return 1; }
                end
                """);
        first.put("consumer.ores", """
                import module Worker from "./provider.ores";
                pub routine main(): void { return; }
                """);

        compiler.compile(first);

        Map<String, String> changed = new LinkedHashMap<>(first);
        changed.put("provider.ores", """
                import interface A from "./contracts.ores";
                import interface B from "./contracts.ores";

                define module Worker with B as
                  pub fnc run(): int { return 1; }
                end
                """);

        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("provider.ores"));
        assertTrue(result.rebuilt("consumer.ores"),
                "changing a module's declared contract is part of its public ABI");
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "module-contracts.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
