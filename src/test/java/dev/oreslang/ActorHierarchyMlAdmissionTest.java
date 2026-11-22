package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-compiler actor hierarchy admission targets. This suite intentionally
 * refuses to count parser success as runtime or ML/autograd support.
 * Review against actor protocol PR #185 and sealed-state PR #1804.
 */
@Timeout(45)
final class ActorHierarchyMlAdmissionTest {
    @TempDir Path temporary;
    @Test
    void defineActorNormalizesToSharedNominalClassWithoutAmbientSuperpowers() {
        Ast.Program source = Parser.parse("""
                define actor A1 as
                  pub value(): int { return 42; }
                end
                """);
        Ast.ClassDecl a1 = (Ast.ClassDecl) source.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass && klass.name().equals("A1"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.SHARED, a1.actorKind());
    }

    @Test
    void aCompatibleActorSuperclassCanSupplyAnInheritedMethod() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Base {
                  pub fnc value(): int { return 42; }
                }
                shared actor A1 extends Base {
                  pub fnc derived(): int { return self.value(); }
                }
                """)));
    }

    @Test
    void actorCannotWeakenTheInheritedExecutionDomain() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        isoactor Parent {}
                        shared actor A1 extends Parent {}
                        """)));
        assertTrue(error.getMessage().contains("isolation kind"), error.getMessage());
    }

    // The second-concrete-actor-parent rule (Parser: "may extend at most one
    // actor superclass") is introduced and pinned by #2425
    // (ActorHierarchyAdmissionTest.multipleActorSuperclassesAreRejected...);
    // main still admits ordinary-class style multiple parents for actors, so
    // this test-only PR keeps only the ancestry rule main already enforces.
    @Test
    void rejectOrdinaryObjectActorSuperclass() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Plain as
                        end
                        define actor A1 extends Plain as
                          pub value(): int { return 42; }
                        end
                        """)),
                "Actor ancestry must not gain a nonactor parent");
    }

    @Test
    void rejectOutOfTurnStateAndModelSyntaxBeforeAssumingSupport() {
        for (String source : new String[] {
                """
                define actor A1 as
                  state { int count = 1; }
                  pub leak(): int { return self.count; }
                end
                """,
                """
                define model Neural as
                  Dense(2,1)
                end
                """
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)),
                    "Staged actor-state/model grammar must not silently imply executable ownership");
        }
    }

    @Test
    void identicalSharedScalarGradientSourceExecutesInActualGraalGuest() throws Exception {
        byte[] bytes;
        try (InputStream stream = getClass().getResourceAsStream(
                "/parity/actor-ml-scalar-step.ores")) {
            assertNotNull(stream, "shared source fixture must be packaged");
            bytes = stream.readAllBytes();
        }
        assertEquals("37dfb0ba856762dc656b628a085684558e5ac1d0392ccd6947d6ccdc35ed7d8e",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                "identical guest source is mandatory across all five toolchains");
        Path source = temporary.resolve("actor-ml-scalar-step.ores");
        Files.write(source, bytes);
        var result = LinkedProgramRunner.runWithExitStatus(
                source, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(42, result.exitStatus(),
                "real Java/Graal Oreslang guest must return the native scalar step 42");
    }
    @Test
    void canonicalCounterActorFixtureIsPinnedAsTargetSyntaxOnly() throws Exception {
        byte[] source;
        try (InputStream input = getClass().getResourceAsStream(
                "/parity/actor-counter-target.ores")) {
            assertNotNull(input, "portable actor Counter fixture missing");
            source = input.readAllBytes();
        }
        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        blob.write(("blob " + source.length + "\0").getBytes(StandardCharsets.US_ASCII));
        blob.write(source);
        assertEquals("c49780b4b42251db20ae9ac864eb8606bf7da922",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                        .digest(blob.toByteArray())),
                "target actor fixture must match all five compiler trees");
        // No guest-execution claim until state loans, spawn and mailbox
        // dispatch are successfully lowered on the same backend.
    }

}
