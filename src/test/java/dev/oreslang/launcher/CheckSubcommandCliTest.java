package dev.oreslang.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Compiler command spelling must not change check admission or run guest main. */
final class CheckSubcommandCliTest {
    @TempDir Path temp;

    @Test
    void checkSubcommandIsTheSameLinkedCompilerAdmissionAsCheckFlag() throws Exception {
        Path source = temp.resolve("main.ores");
        Files.writeString(source, "pub routine main(): int { return 42; }\n");

        assertEquals(0, OresMain.exitStatusForArgs(new String[] {"--check", source.toString()}));
        assertEquals(0, OresMain.exitStatusForArgs(new String[] {"check", source.toString()}));
        // Normal execution is distinguishable: check never calls guest main().
        assertEquals(42, OresMain.exitStatusForArgs(new String[] {source.toString()}));
    }

    @Test
    void malformedSourceIsRejectedInBothSpellingForms() throws Exception {
        Path source = temp.resolve("malformed.ores");
        Files.writeString(source, "pub routine main(): void { let = ; }\n");

        assertEquals(1, OresMain.exitStatusForArgs(new String[] {"--check", source.toString()}));
        assertEquals(1, OresMain.exitStatusForArgs(new String[] {"check", source.toString()}));
    }

    @Test
    void compactAndExpandedKeywordIfHaveTheSameSyntaxAdmission() throws Exception {
        Path source = temp.resolve("conditional.ores");
        Files.writeString(source, """
                pub routine main(): void {
                  if true then return; fi
                }
                """);
        assertAliasParity(source, 0);
        Files.writeString(source, """
                pub routine main(): void {
                  if true then
                    return;
                  fi
                }
                """);
        assertAliasParity(source, 0);
        Files.writeString(source, """
                pub routine main(): void {
                  if true then
                    return;
                }
                """);
        assertAliasParity(source, 1);
    }


    @Test
    void compactMultiArmKeywordIfAndExpandedFormHaveSameAdmission() throws Exception {
        Path source = temp.resolve("multi-arm.ores");
        Files.writeString(source, """
                pub routine main(): void {
                  if true then return; elif false then return; else return; fi
                }
                """);
        assertAliasParity(source, 0);

        Files.writeString(source, """
                pub routine main(): void {
                  if true then
                    return;
                  elif false then
                    return;
                  else
                    return;
                  fi
                }
                """);
        assertAliasParity(source, 0);
    }

    @Test
    void noncanonicalPhysicalLayoutIsNotACompilerSyntaxError() throws Exception {
        Path source = temp.resolve("style-only.ores");
        Files.writeString(source,
                "pub routine main(): void { if true then return; else return; fi }   \n");
        assertAliasParity(source, 0);
    }

    @Test
    void checkIsByteReadOnlyForValidAndInvalidSources() throws Exception {
        Path source = temp.resolve("read-only.ores");
        byte[] valid = "pub routine main(): void { if true then return; fi }   \n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(source, valid);
        assertAliasParity(source, 0);
        org.junit.jupiter.api.Assertions.assertArrayEquals(valid, Files.readAllBytes(source));

        byte[] invalid = "pub routine main(): void { let = ; }\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(source, invalid);
        assertAliasParity(source, 1);
        org.junit.jupiter.api.Assertions.assertArrayEquals(invalid, Files.readAllBytes(source));
    }

    @Test
    void checkValidatesImportedSourcesAndKeepsTheWholeClosureByteReadOnly() throws Exception {
        Path app = temp.resolve("imports");
        Files.createDirectories(app);
        Path entry = app.resolve("main.ores");
        Path child = app.resolve("child.ores");

        byte[] entryBytes = """
                import fnc helper from "./child.ores";
                pub routine main(): void { return; }
                """.getBytes(StandardCharsets.UTF_8);
        byte[] invalidChild = "pub fnc helper(): int { let = ; }\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(entry, entryBytes);
        Files.write(child, invalidChild);

        assertAliasParity(entry, 1);
        org.junit.jupiter.api.Assertions.assertArrayEquals(entryBytes, Files.readAllBytes(entry));
        org.junit.jupiter.api.Assertions.assertArrayEquals(invalidChild, Files.readAllBytes(child));

        byte[] validChild = "pub fnc helper(): int { return 7; }\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(child, validChild);
        assertAliasParity(entry, 0);
        org.junit.jupiter.api.Assertions.assertArrayEquals(entryBytes, Files.readAllBytes(entry));
        org.junit.jupiter.api.Assertions.assertArrayEquals(validChild, Files.readAllBytes(child));
    }

    @Test
    void checkJavacValidatesMixedJavaWithoutRunningJavaMainOrRewritingSource() throws Exception {
        Path source = temp.resolve("MixedCheck.java");
        byte[] valid = """
                public final class MixedCheck {
                  public static void main(String[] args) {
                    throw new AssertionError("check executed Java main");
                  }

                  ores {
                    pub fnc helper(): int { return 7; }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(source, valid);

        assertAliasParity(
                source,
                0,
                "--allow=JAVA_INTEROP,JAVA_SOURCE_INTEROP");
        org.junit.jupiter.api.Assertions.assertArrayEquals(valid, Files.readAllBytes(source));

        byte[] invalid = """
                public final class MixedCheck {
                  public static void main(String[] args) {
                    this is not valid Java;
                  }

                  ores {
                    pub fnc helper(): int { return 7; }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
        Files.write(source, invalid);

        assertAliasParity(
                source,
                1,
                "--allow=JAVA_INTEROP,JAVA_SOURCE_INTEROP");
        org.junit.jupiter.api.Assertions.assertArrayEquals(invalid, Files.readAllBytes(source));
    }

    @Test
    void endOfOptionsAllowsDashPrefixedSourcePathWithoutChangingCheckAdmission() throws Exception {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path source = Files.createTempFile(cwd, "--oreslang-check-", ".ores");
        String relative = source.getFileName().toString();
        try {
            Files.writeString(source, "pub routine main(): void { return; }\n");
            assertEquals(2, OresMain.exitStatusForArgs(new String[] {"check", relative}));
            assertEquals(0, OresMain.exitStatusForArgs(new String[] {"check", "--", relative}));
            assertEquals(0, OresMain.exitStatusForArgs(new String[] {"--check", "--", relative}));
        } finally {
            Files.deleteIfExists(source);
        }
    }

    @Test
    void subcommandStillPreservesUsageFailuresAndExplicitOptions() throws Exception {
        Path missing = temp.resolve("missing.ores");
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                "check", "--build-analysis", missing.toString()
        }));
        assertEquals(2, OresMain.exitStatusForArgs(new String[] {
                "check", "--unknown", missing.toString()
        }));
    }

    /**
     * Alias parity covers diagnostics and usage, not just the exit code.
     * Keep the capture scoped and restore host streams even on a failed assertion.
     */
    @Test
    void bothSpellingsEmitIdenticalSyntaxAndNameDiagnostics() throws Exception {
        Path source = temp.resolve("diagnostic.ores");
        Files.writeString(source, "pub routine main(): void { let = ; }\n");
        CapturedExit malformed = assertAliasParity(source, 1);
        assertEquals("", malformed.stdout());
        org.junit.jupiter.api.Assertions.assertTrue(malformed.stderr().contains("error:"));

        Files.writeString(source,
                "pub routine main(): int { return missing_variable; }\n");
        CapturedExit unresolved = assertAliasParity(source, 1);
        org.junit.jupiter.api.Assertions.assertTrue(unresolved.stderr().contains("error:"));
    }

    @Test
    void aliasPreservesOptionsAndErrorClassification() throws Exception {
        Path valid = temp.resolve("valid.ores");
        Files.writeString(valid, "pub routine main(): void { return; }\n");
        assertAliasParity(valid, 0, "--no-core-log", "--permission-check=compile");

        Path missing = temp.resolve("missing.ores");
        assertAliasParity(missing, 1);
        assertAliasParity(missing, 2, "--unknown");
        assertAliasParity(missing, 2, "--build-analysis");
    }

    @Test
    void bothChecksAvoidGuestProcessExitWhileExecutionReturnsItsCode() throws Exception {
        Path source = temp.resolve("guest-exit.ores");
        Files.writeString(source,
                "pub routine main(): void { std.process.exit(47); }\n");
        CapturedExit checked = assertAliasParity(source, 0);
        assertEquals("", checked.stdout());
        assertEquals(47, OresMain.exitStatusForArgs(new String[] {source.toString()}));
    }

    private CapturedExit assertAliasParity(Path source, int expected, String... options) {
        String[] legacy = new String[options.length + 2];
        String[] alias = new String[options.length + 2];
        legacy[0] = "--check";
        alias[0] = "check";
        System.arraycopy(options, 0, legacy, 1, options.length);
        System.arraycopy(options, 0, alias, 1, options.length);
        legacy[legacy.length - 1] = source.toString();
        alias[alias.length - 1] = source.toString();
        CapturedExit flagResult = capturedExit(legacy);
        CapturedExit subcommandResult = capturedExit(alias);
        assertEquals(flagResult, subcommandResult,
                "check and --check must produce identical status/stdout/stderr");
        assertEquals(expected, subcommandResult.status());
        return subcommandResult;
    }

    private record CapturedExit(int status, String stdout, String stderr) { }

    private static CapturedExit capturedExit(String... args) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            int status = OresMain.exitStatusForArgs(args);
            capturedOut.flush();
            capturedErr.flush();
            return new CapturedExit(status,
                    stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

}
