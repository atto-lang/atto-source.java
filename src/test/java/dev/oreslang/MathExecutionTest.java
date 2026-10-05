package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MathExecutionTest {
    @TempDir
    Path tempDir;

    @Test
    void matrixVectorSyntaxExecutesThroughTheLinkedRuntime() throws Exception {
        Path source = tempDir.resolve("math.ores");
        Files.writeString(source, """
                pub routine main() => void {
                  val a = matrix[[1.0, 2.0], [3.0, 4.0]];
                  val x = vector[5.0, 6.0];
                  val y = a @ x;
                  stdio.stdout.write(y.get(0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(y.get(1));
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                new ByteArrayOutputStream());

        assertEquals("17.0|39.0", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void electricalReferenceSemanticsExecuteFromOreslangStdlibSource() throws Exception {
        Path electrical = tempDir.resolve("electrical.ores");
        Path source = tempDir.resolve("main.ores");
        Files.copy(Path.of("stdlib/math/electrical.ores"), electrical);

        Files.writeString(source, """
                import fnc {
                  omega,
                  z_r,
                  z_c_unchecked,
                  parallel
                } from "./electrical.ores";

                pub routine main() => void {
                  stdio.stdout.write(omega(0.5));
                  stdio.stdout.write("|");
                  stdio.stdout.write(math.abs(z_r(100.0)));
                  stdio.stdout.write("|");
                  stdio.stdout.write(math.abs(parallel(z_r(100.0), z_r(100.0))));
                  stdio.stdout.write("|");
                  stdio.stdout.write(math.abs(z_c_unchecked(1.0, 0.5)));
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.run(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertEquals(
                "3.141592653589793|100.0|50.0|2.0",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void legacyEeFacadeStaysInParityWithOreslangReferenceFormulas() throws Exception {
        Path electrical = tempDir.resolve("electrical-parity.ores");
        Path source = tempDir.resolve("main-parity.ores");
        Files.copy(Path.of("stdlib/math/electrical.ores"), electrical);

        Files.writeString(source, """
                import fnc {
                  omega as ores_omega,
                  z_r as ores_z_r,
                  z_l as ores_z_l,
                  z_c_unchecked as ores_z_c,
                  admittance as ores_admittance,
                  parallel as ores_parallel
                } from "./electrical-parity.ores";

                pub routine main() => void {
                  val w = ores_omega(60.0);
                  stdio.stdout.write(w == ee.omega(60.0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(ores_z_r(100.0) == ee.z_r(100.0));
                  stdio.stdout.write("|");
                  stdio.stdout.write(ores_z_l(w, 0.01) == ee.z_l(w, 0.01));
                  stdio.stdout.write("|");
                  stdio.stdout.write(ores_z_c(w, 0.0001) == ee.z_c(w, 0.0001));
                  stdio.stdout.write("|");
                  stdio.stdout.write(
                    ores_parallel(ores_z_r(100.0), ores_z_r(100.0))
                      == ee.parallel(ee.z_r(100.0), ee.z_r(100.0))
                  );
                  stdio.stdout.write("|");
                  stdio.stdout.write(
                    ores_admittance(ores_z_r(100.0))
                      == ee.admittance(ee.z_r(100.0))
                  );
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                new ByteArrayOutputStream());

        assertEquals(
                "true|true|true|true|true|true",
                output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void kotlinStyleFunctionAliasExecutesAcrossLinkedCodeUnits() throws Exception {
        Path library = tempDir.resolve("solver.ores");
        Path source = tempDir.resolve("main.ores");

        Files.writeString(library, """
                pub fnc solve(int value) => int {
                  return value + 1;
                }
                """);

        Files.writeString(source, """
                import fnc {solve as linear_solve} from "./solver.ores";

                pub routine main() => void {
                  stdio.stdout.write(linear_solve(41));
                  return;
                }
                """);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IncrementalCompiler.BuildResult build = LinkedProgramRunner.run(
                source,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                output,
                new ByteArrayOutputStream());

        assertEquals(2, build.units().size());
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }
}
