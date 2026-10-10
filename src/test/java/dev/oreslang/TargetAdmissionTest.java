package dev.oreslang;

import dev.oreslang.compiler.TargetAdmission;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class TargetAdmissionTest {
    private static final String HELPER = """
            // ores:targets=browser,edge-js,server
            // ores:requires=net.client
            pub fnc get(): int { return 1; }
            """;

    private static final String ENTRY = """
            // ores:targets=browser,edge-js
            // ores:requires=net.client
            import fnc get from "./shared.ores";
            pub fnc main(): int { return get(); }
            """;

    @Test
    void allowsPortableImportClosureWithExplicitCapabilityBudget() {
        Map<String, TargetAdmission.Metadata> admitted = TargetAdmission.validate(
                "src/./main.ores",
                Map.of("src/main.ores", ENTRY, "src/shared.ores", HELPER),
                "browser",
                Set.of("net.client"));
        assertEquals(2, admitted.size());
        assertEquals(Set.of("browser", "edge-js"),
                admitted.get("src/main.ores").targets());
    }

    @Test
    void rejectsUndeclaredHeadersAndUnknownTargets() {
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.header("a.ores", "pub fnc main(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.header("a.ores", "// ores:targets=broser\npub fnc main(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.header("a.ores", "// ores:targets=browser,browser\n"));
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.header("a.ores", "// ores:targets=browser\n// ores:requires=fs.reed\n"));
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.header("a.ores", "// ores:targets=browser\n// ores:targets=server\n"));
    }

    @Test
    void rejectsNarrowerUnconditionalImportedTargets() {
        String browserAndServer = ENTRY.replace("browser,edge-js", "browser,server");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("src/main.ores",
                        Map.of("src/main.ores", browserAndServer,
                                "src/shared.ores", HELPER.replace("browser,edge-js,server", "server")),
                        "server", Set.of("net.client")));
        assertTrue(error.getMessage().contains("dependency"));
    }

    @Test
    void rejectsCapabilityEscalationAndMissingGrants() {
        String underDeclared = ENTRY.replace("// ores:requires=net.client\n", "");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("src/main.ores",
                        Map.of("src/main.ores", underDeclared, "src/shared.ores", HELPER),
                        "browser", Set.of("net.client")));
        assertTrue(error.getMessage().contains("must declare imported capabilities"));

        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("src/main.ores",
                        Map.of("src/main.ores", ENTRY, "src/shared.ores", HELPER),
                        "browser", Set.of()));
    }

    @Test
    void rejectsTransitiveTargetViolation() {
        String middle = """
                // ores:targets=browser,edge-js
                import fnc secret from "./only-server.ores";
                pub fnc get(): int { return secret(); }
                """;
        String main = """
                // ores:targets=browser,edge-js
                import fnc get from "./middle.ores";
                pub fnc main(): int { return get(); }
                """;
        String onlyServer = """
                // ores:targets=server
                pub fnc secret(): int { return 42; }
                """;
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("src/main.ores", Map.of(
                        "src/main.ores", main, "src/middle.ores", middle,
                        "src/only-server.ores", onlyServer), "browser", Set.of()));
    }

    @Test
    void rejectsMissingSourceAndUnresolvedImports() {
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("src/main.ores", Map.of("src/main.ores", ENTRY),
                        "browser", Set.of("net.client")));
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("src/missing.ores", Map.of("src/main.ores", ENTRY),
                        "browser", Set.of("net.client")));
    }

    @Test
    void hostInteropMustBeExplicitAndOnlyInSupportedEnvironments() {
        String host = """
                // ores:targets=server
                // ores:requires=interop.java
                import class Math from "java:java.lang.Math";
                pub fnc main(): void { return; }
                """;
        assertEquals(1, TargetAdmission.validate("main.ores",
                Map.of("main.ores", host), "server", Set.of("interop.java")).size());
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("main.ores", Map.of("main.ores",
                        host.replace("server", "browser")), "browser", Set.of("interop.java")));
        // Even a server build must reject a file that falsely promises browser
        // compatibility while unconditionally importing Java host APIs.
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("main.ores", Map.of("main.ores",
                        host.replace("targets=server", "targets=browser,server")),
                        "server", Set.of("interop.java")));
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("main.ores", Map.of("main.ores",
                        host.replace("// ores:requires=interop.java\n", "")), "server", Set.of("interop.java")));
    }

    @Test
    void importResolutionMapSupportsPinnedBareImports() {
        String main = """
                // ores:targets=browser
                import fnc get from "shared";
                pub fnc main(): int { return get(); }
                """;
        String dep = """
                // ores:targets=browser
                pub fnc get(): int { return 5; }
                """;
        assertEquals(2, TargetAdmission.validate("src/main.ores",
                Map.of("src/main.ores", main, "vendor/lib.ores", dep),
                Map.of("src/main.ores", Map.of("shared", "vendor/lib.ores")),
                "browser", Set.of()).size());
    }

    @Test
    void rejectsMixedJavaSourcesAndDuplicateNormalizedIds() {
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("a.ores", Map.of("a.ores", ENTRY,
                        "source.java", "class A {}"), "browser", Set.of("net.client")));
        assertThrows(IllegalArgumentException.class, () ->
                TargetAdmission.validate("a.ores", Map.of("a.ores", ENTRY,
                        "src/../a.ores", ENTRY), "browser", Set.of("net.client")));
    }
}
