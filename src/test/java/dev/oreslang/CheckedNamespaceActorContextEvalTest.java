package dev.oreslang;

import dev.oreslang.compiler.CheckedNamespaceActorGuestProof;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual Truffle Context.eval entrypoint, not an independent Java-only
 * ActorRuntime harness. This experiment remains trusted-host-only:
 * unleased actor source MUST keep failing at the normal compiler boundary.
 */
@Timeout(30)
final class CheckedNamespaceActorContextEvalTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/critical-ir-multifield.atto"));
    }

    private static Source guestSource(String code, String file) throws Exception {
        return Source.newBuilder(OresLanguage.ID, code, file)
                .mimeType(OresLanguage.MIME_TYPE).build();
    }

    @Test
    void exactCheckedActorIsAdmittedThroughRealGraalContextEval() throws Exception {
        String program = source();
        try (CheckedNamespaceActorGuestProof proof =
                     CheckedNamespaceActorGuestProof.compile("guest-critical-ir.atto", program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).build()) {
            try (var lease = OresLanguage.openCheckedActorGuest(proof)) {
                // This evaluates the actual Atto source through the real
                // Graal parse/RootCallTarget/ActorRuntime boundary. The
                // checked IR path performs 1000 mailbox turns.
                assertEquals(1000L,
                        context.eval(guestSource(program, "guest-critical-ir.atto")).asLong());
            }
        }
    }

    @Test
    void graalResultComesFromActualActorFieldNotDrainedMessageCount() throws Exception {
        String changed = source()
                .replace("let int received: 0;", "let int received: 5;")
                .replace("let int budget: 10000;", "let int budget: 10005;");
        try (var proof = CheckedNamespaceActorGuestProof.compile(
                     "state-readback.atto", changed);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).build()) {
            try (var lease = OresLanguage.openCheckedActorGuest(proof)) {
                assertEquals(1005L,
                        context.eval(guestSource(changed, "state-readback.atto")).asLong(),
                        "Graal must return the actor-owned received field, not hard-coded 1000");
            }
        }
    }

    @Test
    void graalActorTypeAndNamespaceComeFromCheckedSourceDescriptor() throws Exception {
        String renamed = source()
                .replace("use namespace foo;", "use namespace metrics;")
                .replace("Probe", "Ledger");
        try (var proof = CheckedNamespaceActorGuestProof.compile(
                     "named-ledger.atto", renamed);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).build()) {
            try (var lease = OresLanguage.openCheckedActorGuest(proof)) {
                assertEquals(1000L,
                        context.eval(guestSource(renamed, "named-ledger.atto")).asLong(),
                        "declared actor type must be source-derived, not hardcoded Probe");
            }
        }
    }

    @Test
    void checkedSourceWithWrongCriticalBudgetResultFailsStateProof() throws Exception {
        String altered = source().replace(
                "p.state.budget = p.state.budget - delta;",
                "p.state.budget = p.state.budget + delta;");
        try (var proof = CheckedNamespaceActorGuestProof.compile(
                     "wrong-budget.atto", altered);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).build()) {
            try (var lease = OresLanguage.openCheckedActorGuest(proof)) {
                assertThrows(RuntimeException.class, () -> context.eval(
                        guestSource(altered, "wrong-budget.atto")),
                        "a checked but semantically wrong actor state must not be passed off as verified");
            }
        }
    }

    @Test
    void previouslyAdmittedActorSourceCannotBeReexecutedWithoutLease() throws Exception {
        String program = source();
        Source guest = guestSource(program, "one-shot-critical-ir.atto");
        try (var proof = CheckedNamespaceActorGuestProof.compile(
                     "one-shot-critical-ir.atto", program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).build()) {
            try (var lease = OresLanguage.openCheckedActorGuest(proof)) {
                assertEquals(1000L, context.eval(guest).asLong());
            }
            assertThrows(RuntimeException.class, () -> context.eval(guest),
                    "one previously approved Context.eval cannot mint another actor run");
        }
    }

    @Test
    void legacyPrecheckedScopeCannotBeNestedInsideActorGuestScope() throws Exception {
        String program = source();
        try (var proof = CheckedNamespaceActorGuestProof.compile(
                "mixed-lease-critical-ir.atto", program);
             var lease = OresLanguage.openCheckedActorGuest(proof)) {
            String harmless = "pub routine main(): void { return; }";
            assertThrows(IllegalStateException.class,
                    () -> OresLanguage.openPrecheckedProgram(
                            "mixed-lease-legacy.atto", harmless, Parser.parse(harmless)));
        }
    }

    @Test
    void normalUnleasedNamespaceActorStillCannotExecute() throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertThrows(RuntimeException.class, () -> context.eval(
                    guestSource(source(), "unleased-critical-ir.atto")));
        }
    }

    @Test
    void wrongSourceBytesPermanentlyConsumeOneShotLease() throws Exception {
        String program = source();
        try (var proof = CheckedNamespaceActorGuestProof.compile(
                     "identity-critical-ir.atto", program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).build()) {
            try (var lease = OresLanguage.openCheckedActorGuest(proof)) {
                assertThrows(RuntimeException.class, () -> context.eval(
                        guestSource(program + "\n//modified\n", "identity-critical-ir.atto")));
                assertThrows(RuntimeException.class, () -> context.eval(
                        guestSource(program, "identity-critical-ir.atto")));
            }
        }
    }

    @Test
    void wrongCodeUnitIdentityCannotBorrowVerifiedActorSource() throws Exception {
        String program = source();
        try (var proof = CheckedNamespaceActorGuestProof.compile(
                     "bound-critical-ir.atto", program);
             Context context = Context.newBuilder(OresLanguage.ID)
                     .allowAllAccess(false).build()) {
            try (var lease = OresLanguage.openCheckedActorGuest(proof)) {
                assertThrows(RuntimeException.class, () -> context.eval(
                        guestSource(program, "changed-critical-ir.atto")));
            }
        }
    }

    @Test
    void nestedCheckedActorSourceLeasesAreForbidden() throws Exception {
        String program = source();
        try (var proof = CheckedNamespaceActorGuestProof.compile(
                "nested-critical-ir.atto", program);
             var scope = OresLanguage.openCheckedActorGuest(proof)) {
            assertThrows(IllegalStateException.class,
                    () -> OresLanguage.openCheckedActorGuest(proof));
        }
    }

    @Test
    void unapprovedCriticalSourceCannotMintGuestDescriptor() throws Exception {
        String program = source();
        String[] invalid = {
                program.replace("critical mtd (Probe p) add",
                        "define mtd (Probe p) add"),
                program.replace("p.debit(mail.value);", "p.unknown(mail.value);"),
                program.replace("p.state.budget = p.state.budget - delta;",
                        "p.state.budget = dangerous(delta);"),
                program.replace("ActorMail<int>", "ActorMail<borrow int>"),
                program + "\npub fnc makeHostAlias(): int { return 1; }"
        };
        for (int i = 0; i < invalid.length; i++) {
            final int id = i;
            assertThrows(RuntimeException.class,
                    () -> CheckedNamespaceActorGuestProof.compile(
                            "forbidden-" + id + ".atto", invalid[id]));
        }
    }
}
