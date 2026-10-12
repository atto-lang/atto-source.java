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
                val Option<bool> ok = success();
                stdio.stdout.write(ok.is_some());
                stdio.stdout.write(ok.unwrap());
                stdio.stdout.write(unequal().is_none());
                stdio.stdout.write(equal().is_none());
                stdio.stdout.write(different().is_none());
                stdio.stdout.write(untrue().is_none());
                stdio.stdout.write(unfalse().is_none());
                stdio.stdout.write(failed().is_none());
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
