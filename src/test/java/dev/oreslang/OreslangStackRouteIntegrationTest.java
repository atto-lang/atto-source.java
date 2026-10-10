package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable cross-repository regression for the Oreslang Stack demo.
 * Copied from the reviewed dummy API sources after PRs #6 and #7:
 * Contract SHA-1 blob 16a59e4085c2691b90361f962b00bf49d9591d28,
 * route SHA-1 blob 1c2f29a66256dc927590b21ce6537a6e741b29e1,
 * handlers SHA-1 blob 32a9b7889dc20e9767e70ebbb935765f14b2375d.
 * No mocked interpreter or host-language implementation is used here.
 */
final class OreslangStackRouteIntegrationTest {
    @TempDir Path temp;

    private static final String CONTRACT = """
            pub define contract RouteContract as
              fnc get(String request) => String;
              fnc post(String request) => String;
            end
            """;

    private static final String ROUTE = """
            // @ores-route GET / get
            // @ores-route POST / post
            import contract RouteContract from "../../contracts/RouteContract";
            import module handlers from "./handlers";

            define module Route conforms RouteContract as
              pub fnc get(String request) -> String { return handlers.get(request); }
              pub fnc post(String request) -> String { return handlers.post(request); }
            end
            """;

    private static final String HANDLERS = """
            define module handlers as
              pub fnc get(String request) -> String { return "hello"; }
              pub fnc post(String request) -> String { return "hello"; }
            end
            """;

    private Path writeSources(String contract, String route, String handlers) throws Exception {
        Path src = temp.resolve("src");
        Files.createDirectories(src.resolve("contracts"));
        Files.createDirectories(src.resolve("routes/rest"));
        Files.writeString(src.resolve("contracts/RouteContract.ores"), contract);
        Files.writeString(src.resolve("routes/rest/route.ores"), route);
        Files.writeString(src.resolve("routes/rest/handlers.ores"), handlers);
        Path main = src.resolve("main.ores");
        Files.writeString(main, """
                import module Route from "./routes/rest/route";

                pub routine main(): void {
                  stdio.stdout.write(Route.get("first"));
                  stdio.stdout.write("|");
                  stdio.stdout.write(Route.post("second"));
                  return;
                }
                """);
        return main;
    }

    @Test
    void realCompilerAndJitExecuteImportedGetPostContract() throws Exception {
        Path main = writeSources(CONTRACT, ROUTE, HANDLERS);
        var build = LinkedProgramRunner.validate(main);
        assertEquals(4, build.units().size(), "all four real Oreslang compilation units must link");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        var executed = LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                err);
        assertEquals(4, executed.units().size());
        assertEquals("hello|hello", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsIncorrectContractArrowsAndLegacyConformsTo() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                CONTRACT.replace("=> String;", "-> String;")));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                ROUTE.replace("conforms RouteContract", "conforms to RouteContract")));
    }

    @Test
    void missingPostImplementationMustNotPassLinker() throws Exception {
        String missing = ROUTE.replace(
                "  pub fnc post(String request) -> String { return handlers.post(request); }\n", "");
        Path main = writeSources(CONTRACT, missing, HANDLERS);
        assertThrows(IllegalArgumentException.class, () -> LinkedProgramRunner.validate(main));
    }
}
