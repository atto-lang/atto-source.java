package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration candidate for the two currently implemented semantic negatives.
 * No LRU-private source is copied here. A passing JUnit suite proves parser/
 * ownership admission on this exact Java tree, not actor critical lowering,
 * native AOT, cross-backend parity, or the other two proposed diagnostics.
 */
final class AttoNegativeDiagnosticsAdmissionTest {
    @TempDir Path temp;

    private static void rejectCritical(String source) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source), source);
        assertTrue(failure.getMessage().contains("E_CRITICAL_SUSPENDS_DECL"),
                failure::toString);
        assertTrue(failure.getMessage().contains("critical bodies cannot suspend"),
                failure::toString);
    }

    @Test
    void criticalAndSuspendsCallableOrdersRemainDenied() {
        for (String source : java.util.List.of(
                "critical suspends fnc work(): void { return; }",
                "suspends critical fnc work(): void { return; }",
                "pub critical suspends routine work(): void { return; }",
                "pub suspends critical routine work(): void { return; }")) {
            rejectCritical(source);
        }
    }

    @Test
    void lruShapedCriticalMethodIsRejectedBeforeAnyGuestExecution() {
        rejectCritical("""
                define pub class InvalidCriticalCache as
                  pub critical suspends fnc get() -> int {
                    return 1;
                  }
                end
                """);
    }

    @Test
    void explicitAttoEntryUsesSameDiagnosticNotLegacySourceEscape() throws Exception {
        Path entry = temp.resolve("invalid.atto");
        Files.writeString(entry, """
                pub critical suspends fnc main(): void {
                  return;
                }
                """);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains("E_CRITICAL_SUSPENDS_DECL"),
                failure::toString);
    }

    private static void rejectMoveOnlyShare(String source) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)), source);
        assertTrue(failure.getMessage().contains("E_SHARE_MOVE_ONLY"),
                failure::toString);
        assertTrue(failure.getMessage().contains("move-only"),
                failure::toString);
    }

    @Test
    void ownedMutableArrayAndInlineConstructionCannotBeShared() {
        rejectMoveOnlyShare("""
                pub fnc work(): void {
                  let mut Array<int> slots = new Array<int>();
                  let alias = rt share slots;
                  return;
                }
                """);
        rejectMoveOnlyShare("""
                pub fnc work(): void {
                  let alias = rt share new Array<int>();
                  return;
                }
                """);
    }

    @Test
    void immutableCopyableScalarRemainsShareable() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                pub fnc work(): void {
                  let int value = 42;
                  let int alias = rt share value;
                  return;
                }
                """)));
    }

    @Test
    void copyableScalarShareCompilesAndExecutesAsAttoGuest() throws Exception {
        // A positive compiler/runtime control on the same source-extension
        // path. This has not been executed on hosted Java Actions yet.
        Path entry = temp.resolve("share-copyable.atto");
        Files.writeString(entry, """
                pub fnc main(): void {
                  let int source = 42;
                  let int duplicate = rt share source;
                  stdio.stdout.write(duplicate);
                  return;
                }
                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), stdout, new ByteArrayOutputStream());
        org.junit.jupiter.api.Assertions.assertEquals(
                "42", stdout.toString(StandardCharsets.UTF_8));
    }

    @Test
    void unloweredCriticalCallableDoesNotBecomeSynchronousCallable() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("pub critical fnc unsafe(): void { return; }"));
        assertTrue(failure.getMessage().contains(
                "critical callable requires non-suspending body verification"),
                failure::toString);
        assertFalse(failure.getMessage().contains("E_CRITICAL_SUSPENDS_DECL"));
    }
}
