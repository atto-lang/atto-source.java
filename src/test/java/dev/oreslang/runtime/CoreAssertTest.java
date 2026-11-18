package dev.oreslang.runtime;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

final class CoreAssertTest {
    private String run(String body) throws Exception {
        Path entry = Files.createTempFile("ores-assert-", ".ores");
        try {
            Files.writeString(entry, "import module Assert from \"std/assert\";\n" + body);
            var out = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(), out, new ByteArrayOutputStream());
            assertTrue(build.units().containsKey("@std/assert.ores"));
            return out.toString(StandardCharsets.UTF_8).trim();
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test void predicatesUseLanguageEqualityAndBooleans() throws Exception {
        assertEquals("truefalsetruetruetruetruefalse", run("""
            pub routine main(): void {
                stdio.stdout.write(Assert.equal(7, 7));
                stdio.stdout.write(Assert.equal(7, 8));
                stdio.stdout.write(Assert.equal("abc", "a" + "bc"));
                stdio.stdout.write(Assert.not_equal(true, false));
                stdio.stdout.write(Assert.same(7, 7));
                stdio.stdout.write(Assert.true(true));
                stdio.stdout.write(Assert.false(true));
                return;
            }
            """));
    }

    @Test void qualifiedDoubleColonAndCallSiteTrapAreEquivalent() throws Exception {
        for (String qualifier : new String[]{".", "::"}) {
            assertEquals("truetruetruetruetruetrue", run("""
                pub routine main(): void {
                    const success = Assert%sequal_throws(10, 10);
                    const wrapped = trap Assert%sequal_throws(10, 10);
                    const failure = trap Assert%sequal_throws(10, 20);
                    stdio.stdout.write(success);
                    stdio.stdout.write(wrapped.is_ok());
                    stdio.stdout.write(wrapped.unwrap());
                    stdio.stdout.write(failure.is_err());
                    stdio.stdout.write(Assert%sequal(3, 3));
                    stdio.stdout.write(Assert%snot_equal(3, 4));
                    return;
                }
                """.formatted(qualifier, qualifier, qualifier, qualifier, qualifier)
                ));
        }
    }

    @Test void callSiteTrapNeverCatchesPanic() {
        for (String qualifier : new String[]{".", "::"}) {
            Exception failure = assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                    const absent = trap Assert%sequal_throws(1, 2);
                    const not_catchable = trap absent.unwrap();
                    stdio.stdout.write(not_catchable);
                    return;
                }
                """.formatted(qualifier)));
            assertTrue(failure.getMessage().contains("Result::unwrap"), failure::toString);
        }
    }

    @Test void callSiteTrapAfterCooperationPreservesSuccessAndAbsence() throws Exception {
        assertEquals("truetrue", run("""
            pub async fnc main(): void {
                rt cooperate;
                const success = trap Assert::equal_throws(10, 10);
                const failure = trap Assert.equal_throws(10, 20);
                stdio.stdout.write(success.is_ok());
                stdio.stdout.write(failure.is_err());
                return;
            }
            """));
    }

    @Test void callSiteTrapAfterCooperationDoesNotMaskPanic() {
        Exception failure = assertThrows(Exception.class, () -> run("""
            pub async fnc main(): void {
                rt cooperate;
                const absent = trap Assert.equal_throws(10, 20);
                const blocked = trap absent.unwrap();
                stdio.stdout.write(blocked);
                return;
            }
            """));
        assertTrue(failure.getMessage().contains("Result::unwrap"), failure::toString);
    }

    @Test void trapExpressionPreservesLinkedCallReachabilityAndExplicitResultType() throws Exception {
        assertEquals("truetrue", run("""
            fnc failure_by_name(): bool {
                return Assert::equal_throws(2, 3);
            }
            pub routine main(): void {
                const missing: Result<bool, Error> = trap failure_by_name();
                const present: Result<bool, Error> = trap Assert.equal_throws(3, 3);
                stdio.stdout.write(missing.is_err());
                stdio.stdout.write(present.is_ok());
                return;
            }
            """));
    }

    @Test void trapExpressionIsNotAnImplicitBooleanOrBorrowEscape() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
            pub fnc illegal(): bool { return trap 1; }
            """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
            pub fnc illegal(int x): Result<int, Error> { return trap &x; }
            """));
    }

    @Test void doubleColonDoesNotConfuseTypeAnnotationsOrTernary() throws Exception {
        assertEquals("true", run("""
            pub routine main(): void {
                const result: bool = Assert::equal(1, 1);
                stdio.stdout.write(result ? true : false);
                return;
            }
            """));
    }

    @Test void trapPreservesSuccessAndCatchesEveryAssertionFailure() throws Exception {
        assertEquals("truetruetruetruetruetruetruetruetruetruetrue", run("""
            trap fnc success(): bool {
                Assert.not_equal_throws(1, 2);
                Assert.same_throws(1, 1);
                Assert.true_throws(true);
                Assert.false_throws(false);
                return Assert.equal_throws("x", "x");
            }
            trap fnc unequal(): bool { return Assert.equal_throws(1, 2); }
            trap fnc equal(): bool { return Assert.not_equal_throws(1, 1); }
            trap fnc different(): bool { return Assert.same_throws(1, 2); }
            trap fnc untrue(): bool { return Assert.true_throws(false); }
            trap fnc unfalse(): bool { return Assert.false_throws(true); }
            trap fnc failed(): void { Assert.fail("custom assertion failure"); return; }
            pub routine main(): void {
                val Result<bool, Error> ok = success();
                stdio.stdout.write(ok.is_ok());
                stdio.stdout.write(ok.unwrap());
                stdio.stdout.write(unequal().is_err());
                stdio.stdout.write(equal().is_err());
                stdio.stdout.write(different().is_err());
                stdio.stdout.write(untrue().is_err());
                stdio.stdout.write(unfalse().is_err());
                stdio.stdout.write(failed().is_err());
                stdio.stdout.write(Assert.equal(1, 2) == false);
                stdio.stdout.write(Assert.true(false) == false);
                stdio.stdout.write(Assert.false(true) == false);
                return;
            }
            """));
    }

    @Test void assertionFailureRunsCleanupAndCanBeCaught() throws Exception {
        assertEquals("cleanupcaughtfinallyafter", run("""
            fnc check(): void {
                defer stdio.stdout.write("cleanup");
                Assert.true_throws(false);
                stdio.stdout.write("unreachable");
                return;
            }
            pub routine main(): void {
                try { check(); }
                catch (error) { stdio.stdout.write("caught"); }
                finally { stdio.stdout.write("finally"); }
                stdio.stdout.write("after");
                return;
            }
            """));
    }

    @Test void scheduledTrapStillLetsPanicEscape() {
        Exception failure = assertThrows(Exception.class, () -> run("""
            trap fnc check(): bool {
                Assert.equal_throws(1, 1);
                val Option<bool> absent = None;
                return absent.unwrap();
            }
            pub routine main(): void {
                check();
                stdio.stdout.write("unreachable");
                return;
            }
            """));
        assertTrue(failure.getMessage().contains("Option::unwrap"), failure::toString);
    }

    @Test void untrappedFailureHasUsefulMessage() {
        Exception failure = assertThrows(Exception.class, () -> run("""
            pub routine main(): void { Assert.equal_throws(1, 2); return; }
            """));
        assertTrue(failure.getMessage().contains("Assert.equal: values are not equal"), failure::toString);
    }

    @Test void scheduledTrapAfterCooperationPreservesOptionAndUnwind() throws Exception {
        assertEquals("truetruetruecleantrue", run("""
            trap fnc passes_after_yield(): bool {
                rt cooperate;
                return Assert.equal_throws(11, 11);
            }
            trap fnc fails_after_yield(): bool {
                rt cooperate;
                return Assert.equal_throws(11, 12);
            }
            trap fnc fails_with_cleanup(): bool {
                defer stdio.stdout.write("clean");
                rt cooperate;
                Assert.fail("failure after scheduler handoff");
                return true;
            }
            pub async fnc main(): void {
                val Result<bool, Error> ok = passes_after_yield();
                val Result<bool, Error> bad = fails_after_yield();
                stdio.stdout.write(ok.is_ok());
                stdio.stdout.write(ok.unwrap());
                stdio.stdout.write(bad.is_err());
                val Result<bool, Error> cleaned = fails_with_cleanup();
                stdio.stdout.write(cleaned.is_err());
                return;
            }
            """));
    }

    @Test void synchronousPanicCannotBeMaskedByDeferredAssertionFailure() {
        Exception failure = assertThrows(Exception.class, () -> run("""
            trap fnc check(): bool {
                defer Assert.fail("cleanup is ordinary");
                val Option<bool> absent = None;
                return absent.unwrap();
            }
            pub routine main(): void {
                check();
                stdio.stdout.write("unreachable");
                return;
            }
            """));
        assertTrue(failure.getMessage().contains("Option::unwrap"),
                () -> "panic must remain authoritative: " + failure);
    }

    @Test void resumedPanicCannotBeMaskedByDeferredAssertionFailure() {
        Exception failure = assertThrows(Exception.class, () -> run("""
            trap fnc check(): bool {
                defer Assert.fail("cleanup is ordinary");
                rt cooperate;
                val Option<bool> absent = None;
                return absent.unwrap();
            }
            pub async fnc main(): void {
                check();
                stdio.stdout.write("unreachable");
                return;
            }
            """));
        assertTrue(failure.getMessage().contains("Option::unwrap"),
                () -> "resumed panic must remain authoritative: " + failure);
    }

    @Test void panicCannotBeMaskedByFinallyAssertionFailure() {
        Exception failure = assertThrows(Exception.class, () -> run("""
            trap fnc check(): bool {
                try {
                    val Option<bool> absent = None;
                    return absent.unwrap();
                }
                catch (error) { return true; }
                finally { Assert.fail("cleanup is ordinary"); }
            }
            pub routine main(): void {
                check();
                stdio.stdout.write("unreachable");
                return;
            }
            """));
        assertTrue(failure.getMessage().contains("Option::unwrap"),
                () -> "finally must not replace a panic: " + failure);
    }

    @Test void assertImportCannotBeShadowedByApplicationFile() throws Exception {
        Path project = Files.createTempDirectory("ores-assert-shadow-");
        Path std = Files.createDirectories(project.resolve("std"));
        Path entry = project.resolve("main.ores");
        try {
            Files.writeString(std.resolve("assert.ores"), """
                define module Assert as
                  pub fnc equal(Any a, Any b): bool { return true; }
                end
                """);
            Files.writeString(entry, """
                import module Assert from "std/assert";
                pub routine main(): void {
                  stdio.stdout.write(Assert.equal(11, 12));
                  return;
                }
                """);
            var out = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(
                    entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    out, new ByteArrayOutputStream());
            assertTrue(build.units().containsKey("@std/assert.ores"));
            assertEquals("false", out.toString(StandardCharsets.UTF_8).trim(),
                    "application std/assert must never shadow the packaged assertion module");
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(std.resolve("assert.ores"));
            Files.deleteIfExists(std);
            Files.deleteIfExists(project);
        }
    }

    @Test void trapCannotTurnDeniedFilesystemWritesIntoNone() throws Exception {
        Path blocked = Files.createTempFile("ores-assert-denied-", ".txt");
        Path entry = Files.createTempFile("ores-assert-policy-", ".ores");
        try {
            Files.delete(blocked);
            for (String prefix : new String[]{"", "Assert.equal_throws(1, 1);",
                    "defer Assert.fail(\"cleanup must not mask denial\");"}) {
                Files.writeString(entry, """
                    import module Assert from "std/assert";
                    trap fnc guarded(): bool {
                      %s
                      await fs.write_text("%s", "forbidden");
                      return true;
                    }
                    pub routine main(): void { guarded(); return; }
                    """.formatted(prefix, blocked.toString().replace("\\", "\\\\").replace("\"", "\\\"")));
                RuntimeException failure = assertThrows(RuntimeException.class,
                        () -> LinkedProgramRunner.run(
                                entry,
                                IsolatePolicy.developer().withCapabilities(
                                        IsolatePolicy.Capability.FILESYSTEM_WRITE),
                                RuntimePermissions.denyAll(),
                                PermissionCheckMode.RUNTIME,
                                ExecutionProfile.serverJit(),
                                java.util.Set.of(),
                                System.getenv(),
                                new ByteArrayOutputStream(),
                                new ByteArrayOutputStream()));
                StringBuilder diagnostic = new StringBuilder();
                for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
                    diagnostic.append(cursor.getMessage()).append(' ');
                }
                assertTrue(diagnostic.toString().contains("permission denied"),
                        () -> "authorization failure must escape trap: " + diagnostic);
                assertFalse(Files.exists(blocked),
                        "a failed authorization must not create the destination");
            }
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(blocked);
        }
    }

    @Test void explicitGuestCatchCanRecoverDeniedOperationAcrossSuspension() throws Exception {
        Path blocked = Files.createTempFile("ores-assert-catch-denied-", ".txt");
        Path entry = Files.createTempFile("ores-assert-catch-policy-", ".ores");
        try {
            Files.delete(blocked);
            for (boolean resumed : new boolean[]{false, true}) {
                Files.writeString(entry, """
                    import module Assert from "std/assert";
                    trap fnc guarded(): bool {
                        %s
                        try {
                            await fs.write_text("%s", "forbidden");
                        } catch (error) {
                            stdio.stdout.write("caught-denial");
                        } finally {
                            stdio.stdout.write("finally");
                        }
                        return true;
                    }
                    pub %s main(): void {
                        val Result<bool, Error> handled = guarded();
                        stdio.stdout.write(handled.is_ok());
                        return;
                    }
                    """.formatted(
                        resumed ? "rt cooperate;" : "",
                        blocked.toString().replace("\\", "\\\\").replace("\"", "\\\""),
                        resumed ? "async fnc" : "routine"));
                var out = new ByteArrayOutputStream();
                LinkedProgramRunner.run(
                        entry,
                        IsolatePolicy.developer().withCapabilities(
                                IsolatePolicy.Capability.FILESYSTEM_WRITE),
                        RuntimePermissions.denyAll(),
                        PermissionCheckMode.RUNTIME,
                        ExecutionProfile.serverJit(),
                        java.util.Set.of(),
                        System.getenv(),
                        out,
                        new ByteArrayOutputStream());
                assertEquals("caught-denialfinallytrue",
                        out.toString(StandardCharsets.UTF_8).trim(),
                        "explicit guest catch is permitted to recover a denied operation");
                assertFalse(Files.exists(blocked), "catch must not grant the denied write");
            }
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(blocked);
        }
    }

    @Test void panicCannotBeCaughtOrMaskedByFinallyAcrossSuspension() {
        for (boolean resumed : new boolean[]{false, true}) {
            Exception failure = assertThrows(Exception.class, () -> run("""
                trap fnc check(): bool {
                    %s
                    try {
                        val Option<bool> absent = None;
                        return absent.unwrap();
                    } catch (error) {
                        stdio.stdout.write("caught-panic");
                        return true;
                    } finally {
                        Assert.fail("ordinary cleanup");
                    }
                }
                pub %s main(): void {
                    check();
                    stdio.stdout.write("unreachable");
                    return;
                }
                """.formatted(resumed ? "rt cooperate;" : "",
                        resumed ? "async fnc" : "routine")));
            assertTrue(failure.getMessage().contains("Option::unwrap"),
                    () -> "panic must bypass catch and remain authoritative: " + failure);
        }
    }

    @Test void ordinaryAssertionFailureStillCanBeCaughtInSyncAndResumedTry() throws Exception {
        for (boolean resumed : new boolean[]{false, true}) {
            assertEquals("caughtfinally", run("""
                pub %s main(): void {
                    %s
                    try { Assert.fail("ordinary"); }
                    catch (error) { stdio.stdout.write("caught"); }
                    finally { stdio.stdout.write("finally"); }
                    return;
                }
                """.formatted(resumed ? "async fnc" : "routine",
                        resumed ? "rt cooperate;" : "")));
        }
    }

    @Test void failurePrimitiveChecksMessageTypeAndArity() {
        for (String expression : new String[]{"rt fail(1)", "rt fail()", "rt fail(\"a\", \"b\")"}) {
            assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(
                    "pub fnc main(): void { " + expression + "; return; }"));
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
            pub fnc main(): void { rt fail("ordinary failure"); return; }
            """));
    }
}
