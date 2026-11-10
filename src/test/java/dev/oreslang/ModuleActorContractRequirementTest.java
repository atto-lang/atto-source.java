package dev.oreslang;

import dev.oreslang.compiler.ExportInterfaceCodec;
import dev.oreslang.compiler.ExportInterfaceCollector;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage-one module actor contracts: only nominal actor presence is admitted.
 * Standalone receiver-method binding is not enabled by this test.
 */
final class ModuleActorContractRequirementTest {

    private static void check(String source) {
        TypeChecker.check(Parser.parse(source));
    }

    @Test
    void publicActorSatisfiesNominalModuleRequirement() {
        assertDoesNotThrow(() -> check("""
                define contract CartApi as
                  actor Cart;
                end

                define module cart_mod conforms CartApi as
                  pub define actor Cart as
                  end
                end
                """));
    }

    @Test
    void missingRequiredActorIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module cart_mod conforms CartApi as
                end
                """));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }

    @Test
    void ordinaryClassCannotSatisfyActorRequirement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module cart_mod conforms CartApi as
                  pub define class Cart as
                  end
                end
                """));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }

    @Test
    void privateActorCannotSatisfyPublicRequirement() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                end
                define module cart_mod conforms CartApi as
                  define actor Cart as
                  end
                end
                """));
        assertTrue(error.getMessage().contains("missing required public actor"));
    }

    @Test
    void inheritedActorRequirementIsChecked() {
        assertDoesNotThrow(() -> check("""
                define contract Base as
                  actor Cart;
                end
                define contract Derived extends Base as
                  fnc status(): int;
                end
                define module cart_mod conforms Derived as
                  pub define actor Cart as
                  end
                  pub fnc status(): int { return 1; }
                end
                """));
    }

    @Test
    void duplicateActorRequirementsAreRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> check("""
                define contract CartApi as
                  actor Cart;
                  actor Cart;
                end
                """));
        assertTrue(error.getMessage().contains("duplicate contract actor requirement"));
    }

    @Test
    void nominalInterfaceCannotRequireModuleActor() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define interface Invalid as
                  actor Cart;
                end
                """));
        assertTrue(error.getMessage().contains("actor requirements belong only in module contracts"));
    }

    @Test
    void changingRequiredActorChangesExportedContractAbi() {
        var header = new ExportInterfaceCodec.Header("", "test-compiler", "test-abi", "safe");
        var cart = ExportInterfaceCollector.collect(Parser.parse("""
                pub define contract Api as
                  actor Cart;
                end
                """), header);
        var worker = ExportInterfaceCollector.collect(Parser.parse("""
                pub define contract Api as
                  actor Worker;
                end
                """), header);
        assertNotEquals(cart.sha256(), worker.sha256());
    }
}
