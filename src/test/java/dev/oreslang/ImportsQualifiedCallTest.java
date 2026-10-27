package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** imports::name selects only an explicitly imported fnc in this code unit. */
final class ImportsQualifiedCallTest {
    @TempDir Path temp;

    @Test
    void classLocalFileRootAndImportedCallablesCanShareOneName() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                pub fnc roodles(): int { return 23; }
                """);
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, """
                import fnc roodles from "./lib.ores";

                fnc roodles(): int { return 99; }

                define class Foo as
                  pub roodles(): int { return 7; }
                  pub park_it(): void {
                    stdio.stdout.write(roodles());
                    stdio.stdout.write("|");
                    stdio.stdout.write(self.roodles());
                    stdio.stdout.write("|");
                    stdio.stdout.write(pkg::roodles());
                    stdio.stdout.write("|");
                    stdio.stdout.write(imports::roodles());
                  }
                end

                pub routine main(): void {
                  val foo = new Foo();
                  foo.park_it();
                }
                """);
        assertEquals("7|7|99|23", run(entry));
    }

    @Test
    void importAliasesAreTheOnlyExposedImportNames() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                pub fnc roodles(): int { return 23; }
                """);
        Path entry = temp.resolve("alias.ores");
        Files.writeString(entry, """
                import fnc roodles as remote_roodles from "./lib.ores";
                pub routine main(): void {
                  stdio.stdout.write(imports::remote_roodles());
                }
                """);
        assertEquals("23", run(entry));

        IllegalArgumentException missingSourceName = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import fnc roodles as remote_roodles from "./lib.ores";
                        pub fnc test(): void { imports::roodles(); }
                        """)));
        assertTrue(missingSourceName.getMessage().contains("imports::roodles"),
                missingSourceName.getMessage());
    }

    @Test
    void eligibleImportedFunctionCanBeUsedAsAnExplicitQualifiedValue() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                pub fnc roodles(int x): int { return x + 1; }
                """);
        Path entry = temp.resolve("function-value.ores");
        Files.writeString(entry, """
                import fnc roodles from "./lib.ores";
                pub routine main(): void {
                  val r = imports::roodles;
                  stdio.stdout.write(r(41));
                }
                """);
        assertEquals("42", run(entry));
    }

    @Test
    void importQualifierDoesNotFlattenWildcardOrInventExports() {
        IllegalArgumentException noImport = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc roodles(): int { return 99; }
                        pub fnc test(): int { return imports::roodles(); }
                        """)));
        assertTrue(noImport.getMessage().contains("imports::roodles"), noImport.getMessage());

        IllegalArgumentException wildcard = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import * as lib from "./lib.ores";
                        pub fnc test(): void { imports::roodles(); }
                        """)));
        assertTrue(wildcard.getMessage().contains("imports::roodles"), wildcard.getMessage());
    }

    @Test
    void importedOverloadCannotSilentlyWinAfterWrongLocalArity() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                pub fnc roodles(int value): int { return value + 30; }
                """);
        Path entry = temp.resolve("exact-arity.ores");
        Files.writeString(entry, """
                import fnc roodles from "./lib.ores";
                fnc roodles(): int { return 99; }
                pub routine main(): void {
                  stdio.stdout.write(imports::roodles(12));
                }
                """);
        assertEquals("42", run(entry));

        IllegalArgumentException wrongLocalArity = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import fnc roodles from "./lib.ores";
                        fnc roodles(): int { return 99; }
                        pub fnc test(): void { roodles(12); }
                        """)));
        assertTrue(wrongLocalArity.getMessage().contains("imports::roodles"),
                wrongLocalArity.getMessage());
    }

    @Test
    void importQualifierRejectsNonFunctionKinds() {
        IllegalArgumentException nonFnc = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import class Widget from "./lib.ores";
                        pub fnc test(): void { imports::Widget(); }
                        """)));
        assertTrue(nonFnc.getMessage().contains("imports::Widget"), nonFnc.getMessage());
    }

    @Test
    void importedPrivateFunctionCannotBypassExportValidation() throws Exception {
        Files.writeString(temp.resolve("lib.ores"), """
                fnc roodles(): int { return 123; }
                """);
        Path entry = temp.resolve("private.ores");
        Files.writeString(entry, """
                import fnc roodles from "./lib.ores";
                pub routine main(): void {
                  stdio.stdout.write(imports::roodles());
                }
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry));
        assertTrue(failure.getMessage().contains("export")
                || failure.getMessage().contains("public"), failure.getMessage());
    }

    @Test
    void qualifiedDotAccessDoesNotBecomeSpecialImportLookup() {
        AstVerifier.verifyDotIsOrdinary();
    }

    private static String run(Path entry) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                out, new ByteArrayOutputStream());
        return out.toString(StandardCharsets.UTF_8);
    }

    private static final class AstVerifier {
        static void verifyDotIsOrdinary() {
            var program = Parser.parse("""
                    pub fnc main(): void {
                      val imports = infer struct{roodles: 17};
                      val v = imports.roodles;
                    }
                    """);
            assertNotNull(program);
            // Syntax distinction: only :: is a compile-time import qualifier.
            assertDoesNotThrow(() -> TypeChecker.check(program));
        }
    }
}
