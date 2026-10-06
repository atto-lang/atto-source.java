package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModuleContractHardeningTest {

    @Test
    void unqualifiedContractFieldIsReadableRequirementAcrossBindingKinds() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
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
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define contract MutableApi as
                  let value: String;
                end

                define module Good conforms MutableApi as
                  pub let String value = "good";
                end
                """)));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define contract StableApi as
                          val value: String;
                        end

                        define module Broken conforms StableApi as
                          pub let String value = "broken";
                        end
                        """)));

        assertTrue(failure.getMessage().contains("binding kind"));
    }

    @Test
    void changingDeclaredModuleContractInvalidatesImporters() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = new LinkedHashMap<>();
        first.put("provider.ores", """
                pub define contract A as
                  fnc run(): int;
                end

                pub define contract B as
                  fnc run(): int;
                end

                define module Worker conforms A as
                  pub fnc run(): int { return 1; }
                end
                """);
        first.put("consumer.ores", """
                import * as provider from "./provider.ores";
                pub routine main(): void { return; }
                """);

        compiler.compile(first);

        Map<String, String> changed = new LinkedHashMap<>(first);
        changed.put("provider.ores", """
                pub define contract A as
                  fnc run(): int;
                end

                pub define contract B as
                  fnc run(): int;
                end

                define module Worker conforms B as
                  pub fnc run(): int { return 1; }
                end
                """);

        var result = compiler.compile(changed);
        assertTrue(result.rebuilt("provider.ores"));
        assertTrue(result.rebuilt("consumer.ores"),
                "changing a module's declared contract must change its exported ABI");
    }

    @Test
    void contractKindAndBindingRequirementsParticipateInAbi() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        Map<String, String> first = new LinkedHashMap<>();
        first.put("api.ores", """
                pub define contract Api as
                  value: String;
                end
                """);
        first.put("consumer.ores", """
                import * as api from "./api.ores";
                pub routine main(): void { return; }
                """);

        compiler.compile(first);

        Map<String, String> strongerField = new LinkedHashMap<>(first);
        strongerField.put("api.ores", """
                pub define contract Api as
                  const value: String;
                end
                """);

        var fieldResult = compiler.compile(strongerField);
        assertTrue(fieldResult.rebuilt("consumer.ores"),
                "changing a contract field binding requirement must invalidate importers");

        Map<String, String> changedKind = new LinkedHashMap<>(strongerField);
        changedKind.put("api.ores", """
                pub define interface Api as
                  value: String;
                end
                """);

        var kindResult = compiler.compile(changedKind);
        assertTrue(kindResult.rebuilt("consumer.ores"),
                "changing contract/interface identity must invalidate importers");
    }
}
