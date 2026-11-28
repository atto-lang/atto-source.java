package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stacked on Java #1176. These are unchanged Oreslang sources from
 * oreslang-common-source authority-guest-v1, not proof of LLVM parity.
 *
 * Admission only: this class does not execute compiled guest code.
 */
final class AuthorityGuestConformanceTypeV1Test {
    private record Fixture(String id, String source, String gitBlobSha,
                           boolean accept, String checker) {}

    private static final List<Fixture> CASES = List.of(
            new Fixture("guard-any-accepted", "pub fnc foo(any v): int {\n  return Integer.is_int(v) ? v : 0;\n}\n",
                    "9d8cb47f7631fd41262e2d2c49d548170903e440", true, "type"),
            new Fixture("guard-unknown-accepted", "pub fnc foo(unknown v): int {\n  return Integer.is_int(v) ? v : 0;\n}\n",
                    "5eba2cbdb6f0b61e9718b8ccdd7f37c6918df491", true, "type"),
            new Fixture("unguarded-any-return-rejected", "pub fnc foo(any v): int {\n  return v;\n}\n",
                    "d14160d1756e7437a75e87040ecebe3ab464d05f", false, "type"),
            new Fixture("unknown-to-any-rejected", "pub fnc foo(unknown v): any {\n  return v;\n}\n",
                    "8a12f0c82d5383fb7eecdeccbfa962224950f989", false, "type"),
            new Fixture("opaque-member-rejected", "pub fnc bad(any value): void {\n  value.length;\n  return;\n}\n",
                    "0ac9b8def7c1734f5b45f0014234eea45900925d", false, "type"),
            new Fixture("opaque-call-rejected", "pub fnc bad(any callback): void {\n  callback(1);\n  return;\n}\n",
                    "16ae029deb1b9a6ddcab2b3a82625526f4d8061a", false, "type"),
            new Fixture("opaque-channel-rejected", "pub fnc bad(Channel<any> values): void {\n  return;\n}\n",
                    "0840e085c48532544648c44a44cc4589c25ef6ec", false, "type"),
            new Fixture("opaque-async-rejected", "pub async fnc bad(any v): void {\n  return;\n}\n",
                    "e27bfb844cba54b4697420faed23b97a73320ade", false, "type"),
            new Fixture("untrusted-process-access-rejected", "pub untrusted actor fnc worker(): void {\n  val id = process.context_id;\n  return;\n}\n",
                    "a0c2024297d8ad822a75110fe283f6732ff03ee9", false, "cap"),
            new Fixture("untrusted-helper-access-rejected", "fnc helper(): void {\n  val id = process.context_id;\n  return;\n}\npub untrusted actor fnc worker(): void {\n  helper();\n  return;\n}\n",
                    "f31c9156097e94f894ae5d2886c016034ba7f726", false, "cap")
    );

    private static String gitBlobSha(String source) throws Exception {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(sha1.digest(bytes));
    }

    @Test
    void exactCommonSourceFixturesHavePinnedGitBlobIdentity() throws Exception {
        assertEquals(10, CASES.size());
        for (Fixture fixture : CASES) {
            assertEquals(fixture.gitBlobSha(), gitBlobSha(fixture.source()), fixture.id());
        }
    }

    @Test
    void typesAndOwnershipAreCompilerCheckedNotMerelyParsed() {
        for (Fixture fixture : CASES) {
            if (!fixture.checker().equals("type")) continue;
            // The stage matters: syntax failure cannot certify a semantic
            // any/unknown or ownership boundary. Parse must succeed first.
            var parsed = assertDoesNotThrow(
                    () -> Parser.parse(fixture.source()), "parser: " + fixture.id());
            if (fixture.accept()) {
                assertDoesNotThrow(() -> TypeChecker.check(parsed), fixture.id());
            } else {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(parsed), fixture.id());
                // Pin established checker categories where the candidate
                // already has stable diagnostics. A generic type error is
                // not sufficient for a capability/opaque transport claim.
                switch (fixture.id()) {
                    case "unguarded-any-return-rejected" ->
                            assertTrue(failure.getMessage().contains("return value"), fixture.id());
                    case "opaque-member-rejected" ->
                            assertTrue(failure.getMessage().contains("no statically available members"), fixture.id());
                    case "opaque-call-rejected" ->
                            assertTrue(failure.getMessage().contains("not directly callable"), fixture.id());
                    default -> assertNotNull(failure.getMessage(), fixture.id());
                }
            }
        }
    }

    @Test
    void untrustedActorCapabilitiesRejectDirectAndTransitiveProcessAccess() {
        for (Fixture fixture : CASES) {
            if (!fixture.checker().equals("cap")) continue;
            // A parser error is not proof of capability isolation.
            var parsed = assertDoesNotThrow(
                    () -> Parser.parse(fixture.source()), "parser: " + fixture.id());
            var denial = assertThrows(SecurityException.class,
                    () -> CapabilityChecker.check(parsed, IsolatePolicy.developer()),
                    fixture.id());
            assertTrue(denial.getMessage().contains("PROCESS_INFO"),
                    "must be PROCESS_INFO denial: " + fixture.id());
        }
    }
}
