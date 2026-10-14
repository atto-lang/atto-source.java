package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual front-end Error root admission, rather than just parser-only .ores
 * smoke checks. Guest effect lowering is independently feature gated.
 */
final class NativeGuestErrorRootTest {
    @TempDir Path temp;
    @Test void sourceOwnedErrorLibrariesTypecheckWithSealedBuiltinRoot() throws Exception {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                CoreLibrarySources.source("std/errors")));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                CoreLibrarySources.source("std/actor_errors")));
    }

    @Test void ordinaryGuestClassesCanExtendErrorAndUseNominalAssignments() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                define class ExampleError extends Error as
                    pub val int code;
                end
                define class SpecificError extends ExampleError as
                end
                pub fnc main(): int {
                    val Error base = new SpecificError(1001);
                    val ExampleError typed = new SpecificError(1001);
                    return 0;
                }
                """));
    }

    @Test void errorRootIsCompilerOwnedAndNotGeneric() {
        IllegalArgumentException shadowed = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Error as
                        end
                        pub fnc main(): void { }
                        """));
        assertTrue(shadowed.getMessage().contains("compiler-owned"));

        IllegalArgumentException generic = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class BadError extends Error<int> as
                        end
                        pub fnc main(): void { }
                        """));
        assertTrue(generic.getMessage().contains("Error"));
    }

    @Test void constructedErrorSubclassRunsAsAnOreslangValue() throws Exception {
        Path path = temp.resolve("guest-error.ores");
        Files.writeString(path, """
                define class ExampleError extends Error as
                    pub val int code;
                end
                define class SpecificError extends ExampleError as
                end
                pub routine main(): int {
                    val Error failure = new SpecificError(1001);
                    return 17;
                }
                """);
        var result = LinkedProgramRunner.runWithExitStatus(
                path, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                new ByteArrayOutputStream(), new ByteArrayOutputStream());
        assertEquals(17, result.exitStatus());
    }

    @Test void aliasesAndGenericsCannotShadowCompilerOwnedError() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define type Error as int;
                        pub fnc main(): int { return 1; }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Masked<Error> as
                            pub val Error code;
                        end
                        pub fnc main(): int { return 1; }
                        """));
    }

    @Test void ordinaryClassesCannotPretendToBeGuestErrors() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        define class Plain as
                            pub val int code;
                        end
                        pub fnc main(): int {
                            val Error fake = new Plain(1);
                            return 0;
                        }
                        """));
    }

    @Test void exceptionalEffectsRemainGatedUntilSupervisorLoweringIsVerified() {
        for (String effect : new String[]{"throw", "raise", "panic"}) {
            IllegalArgumentException unsupported = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            "pub fnc main(): void { " + effect
                                + " new MyError(); }"));
            assertTrue(unsupported.getMessage().contains("not yet enabled"));
        }
    }
}
