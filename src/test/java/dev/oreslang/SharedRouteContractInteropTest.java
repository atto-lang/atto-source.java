package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-side compatibility test for the public ORESoftware/ores-interfaces
 * contracts/oreslang-route/v1/RouteContract.ores proposal.
 * A source-only or JavaScript assertion in the contract repo is insufficient:
 * check the actual parser, cross-file contract importer, type checker, and
 * Truffle guest execution with Java host access denied by default.
 *
 * The v1 contract remains an independently reviewed candidate; this test
 * does NOT certify HTTP ingress, wire contracts, or a deployable Oreslang SDK.
 */
final class SharedRouteContractInteropTest {
    @TempDir
    Path temp;

    private static final String CONTRACT = """
            // Oreslang REST route module contract. Canonical source:
            // oresoftware/ores-interfaces/contracts/oreslang-route/v1/RouteContract.ores
            // A concrete Route module must implement both signatures.
            pub define contract RouteContract as
              fnc get(String request) => String;
              fnc post(String request) => String;
            end
            """;

    /** Git blob of the reviewed public Oreslang RouteContract.ores in PR #70. */
    private static final String REVIEWED_CONTRACT_BLOB =
            "5bfd8552d4c6cc5bd369f43d81a7004ba01c2d45";

    @Test
    void copiedContractSourceIsExactlyTheReviewedGitBlob() throws Exception {
        byte[] body = CONTRACT.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("blob " + body.length).getBytes(StandardCharsets.UTF_8);
        byte[] toHash = new byte[header.length + 1 + body.length];
        System.arraycopy(header, 0, toHash, 0, header.length);
        toHash[header.length] = 0;
        System.arraycopy(body, 0, toHash, header.length + 1, body.length);
        assertEquals(REVIEWED_CONTRACT_BLOB,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(toHash)),
                "embedded contract has drifted from ORESoftware/ores-interfaces PR #70");
    }

    private Path source(String name, String body) throws Exception {
        Path root = temp.resolve("routes");
        Files.createDirectories(root);
        Files.writeString(root.resolve("RouteContract.ores"), CONTRACT);
        Path target = root.resolve(name + ".ores");
        Files.writeString(target, """
                import contract RouteContract from "./RouteContract.ores";
                """ + body);
        return target;
    }

    private static final String VALID = """
            define module Route conforms RouteContract as
              pub fnc get(String request): String {
                return request;
              }

              pub fnc post(String request): String {
                return request;
              }
            end

            pub routine main(): void {
              stdio.stdout.write(Route.get("get-ok"));
              stdio.stdout.write("|");
              stdio.stdout.write(Route.post("post-ok"));
              return;
            }
            """;

    @Test
    void incrementalAbiTracksContractKindAndConformance() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        String id = "/trusted/contracts.ores";
        var contract = compiler.compile(Map.of(id, CONTRACT));
        var ordinaryInterface = compiler.compile(Map.of(
                id, CONTRACT.replace("define contract RouteContract", "define interface RouteContract")));
        assertNotEquals(contract.units().get(id).abiDigest(),
                ordinaryInterface.units().get(id).abiDigest(),
                "a module contract cannot share its ABI with an ordinary interface");

        String routeId = "/trusted/route.ores";
        String withConforms = CONTRACT + "\n" + VALID;
        var strict = new IncrementalCompiler().compile(Map.of(routeId, withConforms));
        var permissive = new IncrementalCompiler().compile(Map.of(
                routeId, withConforms.replace("Route conforms RouteContract", "Route")));
        assertNotEquals(strict.units().get(routeId).abiDigest(),
                permissive.units().get(routeId).abiDigest(),
                "changing a module conforms clause is a public ABI change");
    }

    @Test
    void correctModulePassesRealCrossFileCompilation() throws Exception {
        Path valid = source("valid-route", VALID);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(valid));
    }

    @Test
    void correctModuleExecutesOnActualTruffleGuest() throws Exception {
        Path valid = source("valid-route", VALID);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                valid, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), out, new ByteArrayOutputStream());
        assertEquals("get-ok|post-ok", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void missingPublicPostMethodFailsClosed() throws Exception {
        Path invalid = source("missing-post", """
                define module Route conforms RouteContract as
                  pub fnc get(String request): String {
                    return request;
                  }
                end
                pub routine main(): void { return; }
                """);
        Exception failure = assertThrows(Exception.class, () -> LinkedProgramRunner.validate(invalid));
        assertTrue(failure.getMessage().contains("does not conform"),
                "negative contract test must fail for conformance, not an unrelated linker error: " + failure);
    }

    @Test
    void wrongReturnTypeFailsClosed() throws Exception {
        Path invalid = source("wrong-return", """
                define module Route conforms RouteContract as
                  pub fnc get(String request): int { return 1; }
                  pub fnc post(String request): String { return request; }
                end
                pub routine main(): void { return; }
                """);
        Exception failure = assertThrows(Exception.class, () -> LinkedProgramRunner.validate(invalid));
        assertTrue(failure.getMessage().contains("does not conform"),
                "negative contract test must fail for conformance, not an unrelated linker error: " + failure);
    }
}
