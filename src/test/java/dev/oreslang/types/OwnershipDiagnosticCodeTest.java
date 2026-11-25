package dev.oreslang.types;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every ownership diagnostic carries a stable code, and the codes that claim
 * to be canonical registry families match the master borrow checker
 * (atto-lang/atto-borrow-checker.rs, conformance_v1::REGISTRY families).
 */
final class OwnershipDiagnosticCodeTest {

    private static String reject(String source) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        return error.getMessage();
    }

    // Ownership-only entry point, as StaticBorrowEscapeBoundaryTest uses it.
    private static String rejectOwnership(String source) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        return error.getMessage();
    }

    private static void assertCode(OwnershipDiagnosticCode expected, String message, String prose) {
        assertEquals(expected, OwnershipDiagnosticCode.of(message), message);
        assertTrue(message.startsWith("Atto ownership error: " + expected.name() + ": "), message);
        assertTrue(message.contains(prose), message);
    }

    @Test
    void useAfterMoveIsTaggedMoved() {
        String message = reject("""
                define class Bar as
                  pub let String foo = "start";
                end
                fnc consume(Bar b): void {
                  return;
                }
                fnc bad(): void {
                  let Bar b = new Bar();
                  consume(b);
                  stdio.println(b.foo);
                  return;
                }
                """);
        assertCode(OwnershipDiagnosticCode.E_MOVED, message, "use of moved value 'b'");
    }

    @Test
    void readOnlyParameterMutationIsTaggedMutability() {
        String message = reject("""
                define class Bar as
                  pub let String foo = "start";
                end
                fnc change(Bar b): void {
                  b.foo = "foobar";
                  return;
                }
                """);
        assertCode(OwnershipDiagnosticCode.E_MUTABILITY, message, "read-only binding");
    }

    @Test
    void loanAcrossGeneratorSuspensionIsTaggedLoanSuspend() {
        String message = rejectOwnership("""
                fnc gen* leaked(Iterator<borrow int> items): Iterator<int> {
                  yield* items;
                  return;
                }
                """);
        assertCode(OwnershipDiagnosticCode.E_LOAN_SUSPEND, message, "cannot yield while a borrow is live");
    }

    @Test
    void alreadyCodedMessagesKeepTheirCode() {
        // Pre-existing codes keep their code and message after the prefix.
        assertEquals(OwnershipDiagnosticCode.E_COPY_FORBIDDEN,
                OwnershipDiagnosticCode.of("Atto ownership error: E_COPY_FORBIDDEN: rt copy of @NoCopy class 'X'"));
        assertNull(OwnershipDiagnosticCode.of("Atto ownership error: no code here"));
        assertNull(OwnershipDiagnosticCode.of("Oreslang parse error at 1:1: E_MOVED: not ownership"));
        assertNull(OwnershipDiagnosticCode.of(null));
    }

    @Test
    void registryFamiliesMatchTheMasterBorrowChecker() {
        // Must equal source-level families in conformance_v1::REGISTRY. Update both together.
        Set<OwnershipDiagnosticCode> expected = EnumSet.of(
                OwnershipDiagnosticCode.E_MOVED,
                OwnershipDiagnosticCode.E_BORROW,
                OwnershipDiagnosticCode.E_LIFETIME,
                OwnershipDiagnosticCode.E_MUTABILITY,
                OwnershipDiagnosticCode.E_LOAN_SUSPEND,
                OwnershipDiagnosticCode.E_ACTOR_LOAN_ESCAPE,
                OwnershipDiagnosticCode.E_TASK_CAPTURE,
                OwnershipDiagnosticCode.E_COPY_SHALLOW_ALIAS,
                OwnershipDiagnosticCode.E_SHARE_ALIAS);
        Set<OwnershipDiagnosticCode> actual = EnumSet.noneOf(OwnershipDiagnosticCode.class);
        for (OwnershipDiagnosticCode code : OwnershipDiagnosticCode.values()) {
            if (code.registryFamily()) {
                actual.add(code);
            }
        }
        assertEquals(expected, actual);
    }

    @Test
    void everyCheckerDiagnosticSiteNamesACode() throws IOException {
        String source = Files.readString(Path.of("src/main/java/dev/oreslang/types/OwnershipChecker.java"));
        Matcher sites = Pattern.compile("\\berror\\(").matcher(source);
        int count = 0;
        while (sites.find()) {
            String rest = source.substring(sites.end()).stripLeading();
            if (rest.startsWith("OwnershipDiagnosticCode code")) {
                continue; // the helper's own declaration
            }
            count++;
            assertTrue(rest.matches("(?s)^E_[A-Z_]+\\s*,.*"),
                    () -> "uncoded ownership diagnostic near: " + rest.substring(0, Math.min(80, rest.length())));
        }
        assertTrue(count >= 248, "diagnostic sites shrank unexpectedly: " + count);
        // No message literal may still embed its own code prefix (it would render twice).
        assertFalse(Pattern.compile("error\\(E_[A-Z_]+,\\s*\"E_[A-Z_]+:").matcher(source).find());
    }
}
