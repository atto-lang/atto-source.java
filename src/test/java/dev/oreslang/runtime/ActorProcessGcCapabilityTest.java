package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Actor-local cleanup must not be confused with global JVM GC authority. */
final class ActorProcessGcCapabilityTest {
    @Test
    void supervisorCanRequestProcessGcWhenCapabilityIsPresent() {
        assertDoesNotThrow(() -> CapabilityChecker.check(Parser.parse("""
                pub routine main(): void {
                  process.gc();
                  return;
                }
                """), IsolatePolicy.developer()));
    }

    @Test
    void noActorKindMayInvokeProcessGcEvenWithDeveloperCapabilities() {
        for (String declaration : new String[] {
                "isoactor PrivateWorker",
                "shared actor SharedWorker",
                "untrusted actor HostileWorker"
        }) {
            var program = Parser.parse(declaration + """
                     {
                       pub fnc collect(): void {
                         process.gc();
                         return;
                       }
                     }
                    """);
            SecurityException problem = assertThrows(SecurityException.class,
                    () -> CapabilityChecker.check(program, IsolatePolicy.developer()),
                    declaration);
            assertTrue(problem.getMessage().contains("actor")
                    || problem.getMessage().contains("GC_CONTROL"), problem.getMessage());
        }
    }

    @Test
    void sharedActorCannotLaunderProcessGcThroughHelperFunction() {
        var program = Parser.parse("""
                fnc collect_global(): void {
                  process.gc();
                  return;
                }

                shared actor SharedWorker {
                  pub fnc bad(): void {
                    collect_global();
                    return;
                  }
                }
                """);
        SecurityException problem = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(problem.getMessage().contains("process.gc"), problem.getMessage());
    }

    @Test
    void sharedActorMayStillRequestActorLocalCleanup() {
        var program = Parser.parse("""
                shared actor SharedWorker {
                  pub fnc good(): void {
                    actor.gc();
                    return;
                  }
                }
                """);
        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
    }
}
