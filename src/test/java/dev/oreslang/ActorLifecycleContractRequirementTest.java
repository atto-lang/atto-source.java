package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statically require actor-local hooks without making them actor mailbox APIs. */
final class ActorLifecycleContractRequirementTest {
    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    private static String contract(String requirement) {
        return "define contract ActorLifecycle as\n"
                + "  actor Worker;\n"
                + "  " + requirement + "\n"
                + "end\n";
    }

    private static String actor(String methods) {
        return "define module workers conforms ActorLifecycle as\n"
                + "  pub define actor Worker as\n"
                + methods
                + "  end\n"
                + "end\n";
    }

    private static void rejectsContract(String signature, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> check(contract(signature)));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test
    void privateStartupRequirementMatchesExistingActorHook() {
        assertDoesNotThrow(() -> check(contract("mtd on_start(Worker w)(): void;")
                + actor("    on_start(): void { return; }\n")));
    }

    @Test
    void privateTeardownRequirementMatchesExistingActorHook() {
        assertDoesNotThrow(() -> check(contract("mtd tear_down(Worker w)(): void;")
                + actor("    tear_down(): void { return; }\n")));
    }

    @Test
    void missingStartupHookCannotSatisfyModuleContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> check(contract("mtd on_start(Worker w)(): void;") + actor("")));
        assertTrue(error.getMessage().contains("missing required receiver method"), error.getMessage());
    }

    @Test
    void missingTeardownHookCannotSatisfyModuleContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> check(contract("mtd tear_down(Worker w)(): void;") + actor("")));
        assertTrue(error.getMessage().contains("missing required receiver method"), error.getMessage());
    }

    @Test
    void publicLifecycleHookIsNotMailboxCallable() {
        rejectsContract("pub mtd on_start(Worker w)(): void;",
                "actor-local lifecycle contract hook");
    }

    @Test
    void lifecycleContractCannotDeclareArguments() {
        rejectsContract("mtd on_start(Worker w)(int value): void;",
                "actor-local lifecycle contract hook");
    }

    @Test
    void lifecycleContractCannotDeclareResult() {
        rejectsContract("mtd tear_down(Worker w)(): int;",
                "actor-local lifecycle contract hook");
    }

    @Test
    void actorCannotInterceptItsOwnPanicViaContract() {
        rejectsContract("mtd on_panic(Worker w)(): void;",
                "reserved actor lifecycle/control hook");
    }

    @Test
    void freeFunctionCannotSatisfyActorLifecycleContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                check(contract("mtd on_start(Worker w)(): void;")
                        + "define module workers conforms ActorLifecycle as\n"
                        + "  pub define actor Worker as end\n"
                        + "  pub fnc on_start(Worker w): void { return; }\n"
                        + "end\n"));
        assertTrue(error.getMessage().contains("missing required receiver method"), error.getMessage());
    }
}
