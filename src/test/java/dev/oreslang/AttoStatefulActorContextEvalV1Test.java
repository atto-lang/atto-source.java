package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is real Polyglot Context.eval of a manifest-linked physical Atto actor
 * constituent. Guest results are Truffle interop values; no test calls a
 * Java-generated Plan.spawn or handwritten actor dispatcher.
 *
 * It is intentionally NOT yet unrestricted native Atto 'spawn' or 'main'.
 */
final class AttoStatefulActorContextEvalV1Test {
    @TempDir Path project;

    private Path fixture() throws Exception {
        Path source = Path.of("contracts", "declared-actor-stateful-manifest-v1");
        for (String file : List.of(
                ".atto.pkg.toml", "src/actor.atto", "src/state.atto",
                "src/init.atto", "src/receive.atto", "src/critical.atto")) {
            Path target = project.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, Files.readString(source.resolve(file)));
        }
        return project.resolve(".atto.pkg.toml");
    }

    private Source source(Path actorFile, String contents) throws Exception {
        return Source.newBuilder(OresLanguage.ID, contents,
                actorFile.toAbsolutePath().normalize().toString())
                .mimeType(OresLanguage.MIME_TYPE)
                .cached(true)
                .build();
    }

    @Test
    void physicalStatefulCounterRunsViaActualContextEvalAndOwnedActorMailbox()
            throws Exception {
        Path manifest = fixture();
        Path actorUnit = project.resolve("src/actor.atto");
        String text = Files.readString(actorUnit);
        Source physical = source(actorUnit, text);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value guest;
            try (var permit = OresLanguage.openVerifiedStatefulActor(
                    manifest, actorUnit, text, "CounterActor")) {
                guest = context.eval(physical);
            }
            assertTrue(guest.canExecute(),
                    "Context.eval must return a true Truffle guest callable");
            assertEquals(10L, guest.execute(1L, 2L, 3L, 4L).asLong());
            assertThrows(RuntimeException.class, () -> guest.execute(1L),
                    "replaying a guest actor batch must never reuse old state");
        }
    }

    @Test
    void ordinaryContextEvalCannotBypassSealedStateTypeChecker() throws Exception {
        Path manifest = fixture();
        Path actorUnit = project.resolve("src/actor.atto");
        String text = Files.readString(actorUnit);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            assertThrows(RuntimeException.class,
                    () -> context.eval(source(actorUnit, text)),
                    "ordinary raw source must not implicitly acquire a manifest witness");
        }
    }

    @Test
    void physicalSourceMismatchConsumesOneShotLease() throws Exception {
        Path manifest = fixture();
        Path actorUnit = project.resolve("src/actor.atto");
        String text = Files.readString(actorUnit);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build();
             var permit = OresLanguage.openVerifiedStatefulActor(
                     manifest, actorUnit, text, "CounterActor")) {
            assertThrows(RuntimeException.class,
                    () -> context.eval(source(actorUnit, text + "\n// mismatch")),
                    "mismatched source must burn the lease");
            assertThrows(RuntimeException.class,
                    () -> context.eval(source(actorUnit, text)),
                    "burnt parse lease may not be retried");
        }
    }

    @Test
    void mismatchedPhysicalFilesAndUnsafeCriticalStateDoNotReachGuest() throws Exception {
        Path manifest = fixture();
        Path actorUnit = project.resolve("src/actor.atto");
        Path critical = project.resolve("src/critical.atto");
        String text = Files.readString(actorUnit);
        String criticalText = Files.readString(critical);
        Files.writeString(critical, criticalText.replace(
                "s.value = s.value + delta;", "c.value = c.value + delta;"));

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build();
             var permit = OresLanguage.openVerifiedStatefulActor(
                     manifest, actorUnit, text, "CounterActor")) {
            assertThrows(RuntimeException.class,
                    () -> context.eval(source(actorUnit, text)),
                    "illegal attached actor state cannot enter a guest call target");
        }
    }

    @Test
    void sameContextRepeatedEvalWithoutFreshLeaseCannotReuseGuestCallTarget()
            throws Exception {
        Path manifest = fixture();
        Path actorUnit = project.resolve("src/actor.atto");
        String text = Files.readString(actorUnit);
        Source physical = source(actorUnit, text);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            try (var permit = OresLanguage.openVerifiedStatefulActor(
                    manifest, actorUnit, text, "CounterActor")) {
                assertTrue(context.eval(physical).canExecute());
            }
            assertThrows(RuntimeException.class, () -> context.eval(physical),
                    "cached source without new execute authorization must fail");
        }
    }

    @Test
    void oversizedOrWrongTypeGuestInputsAreRejectedWithoutPartialProcessing()
            throws Exception {
        Path manifest = fixture();
        Path actorUnit = project.resolve("src/actor.atto");
        String text = Files.readString(actorUnit);
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Value guest;
            try (var permit = OresLanguage.openVerifiedStatefulActor(
                    manifest, actorUnit, text, "CounterActor")) {
                guest = context.eval(source(actorUnit, text));
            }
            assertThrows(RuntimeException.class, () -> guest.execute("untyped"));
            assertThrows(RuntimeException.class, () -> guest.execute(1L),
                    "invalid call must consume the one-shot execution capability");
        }
    }

    @Test
    void wrongActorUnitCannotMintGuestLease() throws Exception {
        Path manifest = fixture();
        String text = Files.readString(project.resolve("src/actor.atto"));
        assertThrows(IllegalArgumentException.class,
                () -> OresLanguage.openVerifiedStatefulActor(
                        manifest, project.resolve("src/receive.atto"), text, "CounterActor"));
    }
}
