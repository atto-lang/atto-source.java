package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ImportResolutionTest {
    @Test
    void validatesSelectedModuleClassAndFunctionAgainstResolvedUnit() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        assertDoesNotThrow(() -> compiler.compile(Map.of(
                "lib.ores", """
                        define module math
                          pub fnc add(int a, int b) => int { return a + b; }
                        end
                        define class Widget
                        end
                        pub fnc answer() => int { return 42; }
                        """,
                "app.ores", """
                        import module math from "./lib.ores";
                        import class Widget from "./lib.ores";
                        import fnc answer from "./lib.ores";
                        pub fnc main() => void { return; }
                        """)));

        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(Map.of(
                "lib.ores", "pub fnc answer() => int { return 42; }",
                "app.ores", """
                        import class answer from "./lib.ores";
                        pub fnc main() => void { return; }
                        """)));

        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(Map.of(
                "lib.ores", "fnc hidden() => int { return 1; }",
                "app.ores", """
                        import fnc hidden from "./lib.ores";
                        pub fnc main() => void { return; }
                        """)));

        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(Map.of(
                "lib.ores", "pub routine worker() => void { return; }",
                "app.ores", """
                        import fnc worker from "./lib.ores";
                        pub fnc main() => void { return; }
                        """)));
    }

    @Test
    void rejectsMissingAndAmbiguousSelectedExports() {
        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(Map.of(
                "lib.ores", "pub fnc present() => int { return 1; }",
                "app.ores", """
                        import fnc missing from "./lib.ores";
                        pub fnc main() => void { return; }
                        """)));

        assertThrows(IllegalArgumentException.class, () -> new IncrementalCompiler().compile(Map.of(
                "lib.ores", """
                        define module left
                          pub fnc ping() => int { return 1; }
                        end
                        define module right
                          pub fnc ping() => int { return 2; }
                        end
                        """,
                "app.ores", """
                        import fnc ping from "./lib.ores";
                        pub fnc main() => void { return; }
                        """)));
    }

    @Test
    void javaImportsAreNotTreatedAsSourceDependencies() {
        var result = assertDoesNotThrow(() -> new IncrementalCompiler().compile(Map.of(
                "app.ores", """
                        import fnc abs from "java:java.lang.Math";
                        pub fnc main() => void { return; }
                        """)));
        assertTrue(result.units().get("app.ores").dependencies().isEmpty());
    }
}
