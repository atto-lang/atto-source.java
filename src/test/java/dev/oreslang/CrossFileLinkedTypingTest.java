package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed linked IR v1, stage 4: an importer's body is checked against the
 * imported module's real signatures (generic, async, borrowing and nominal),
 * not against an opaque placeholder. Every positive case has a negative twin
 * that only a typed link can reject.
 */
final class CrossFileLinkedTypingTest {

    private static final String LIB = """
            define module lib as
              define pub class Command as
                pub val String executable;
                pub constructor(String executable) { self.executable = executable; }
              end

              pub fnc command(String executable): Command {
                return new Command(executable);
              }

              pub fnc executable_of(borrow Command value): String {
                return value.executable;
              }

              pub fnc first<T>(borrow Array<T> values): Option<T> {
                if values.size == 0 then return None; fi
                return Some(values.get(0));
              }

              pub async fnc later(int value): int {
                return value + 1;
              }

              pub async fnc settle(): void {
                return;
              }

              pub fnc empty_bytes(): Bytes {
                return Bytes.empty();
              }

              pub fnc total(borrow Array<int> values): int {
                let mut int sum = 0;
                for (let int i = 0; i < values.size; i = i + 1) {
                  sum = sum + values.get(i);
                }
                return sum;
              }
            end
            """;

    private static Map<String, String> program(String main) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lib.atto", LIB);
        sources.put("main.atto", main);
        return sources;
    }

    private static void accepts(String main) {
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(program(main)), main);
    }

    private static void rejects(String main, String expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(program(main)), main);
        String message = failure.getMessage() + " " + (failure.getCause() == null ? "" : failure.getCause().getMessage());
        assertTrue(message.contains(expected), () -> "expected '" + expected + "' in: " + message);
    }

    @Test
    void aPartiallyLinkedModulePreservesTheSuspendsEffect() {
        String provider = """
                define module timer as
                  pub suspends fnc pause(): int {
                    await process.sleep_async(1);
                    return 42;
                  }
                  pub fnc unrelated(Any value): void { return; }
                end
                """;
        String imports = "import module timer from \"./timer\";\n";
        assertDoesNotThrow(() -> new IncrementalCompiler().compile(Map.of(
                "timer.atto", provider,
                "main.atto", imports + "pub susp fnc main(): int { return timer.pause(); }")));
        IllegalArgumentException plainCaller = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "timer.atto", provider,
                        "main.atto", imports + "pub fnc main(): int { return timer.pause(); }")));
        assertTrue(plainCaller.getMessage().contains("pause") && plainCaller.getMessage().contains("suspend"),
                plainCaller::getMessage);
        IllegalArgumentException reified = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compile(Map.of(
                        "timer.atto", provider,
                        "main.atto", imports + "pub susp fnc main(): int { val callback = timer.pause; return 0; }")));
        assertTrue(reified.getMessage().contains("cannot be used as a first-class callable"), reified::getMessage);
    }

    @Test
    void genericFunctionInstantiatesAtTheCallSite() {
        accepts("""
                import module lib from "./lib.atto";
                pub fnc main(): int {
                  let mut Array<int> values = new Array<int>();
                  values.add(42);
                  let Option<int> head = lib.first(rt borrow values);
                  return head.unwrap_or(0);
                }
                """);
        rejects("""
                import module lib from "./lib.atto";
                pub fnc main(): int {
                  let mut Array<int> values = new Array<int>();
                  values.add(42);
                  let Option<String> head = lib.first(rt borrow values);
                  return 0;
                }
                """, "head");
    }

    @Test
    void asyncFunctionKeepsItsFutureTypeAcrossFiles() {
        accepts("""
                import module lib from "./lib.atto";
                pub async fnc main(): int {
                  let int value = await lib.later(41);
                  return value;
                }
                """);
        rejects("""
                import module lib from "./lib.atto";
                pub async fnc main(): int {
                  let int value = lib.later(41);
                  return value;
                }
                """, "value");
    }

    @Test
    void moduleClassesAreLinkedUnderTheModuleAlias() {
        accepts("""
                import module lib from "./lib.atto";
                pub fnc main(): int {
                  let lib.Command made = lib.command("ls");
                  let String name = lib.executable_of(rt borrow made);
                  return 0;
                }
                """);
        rejects("""
                import module lib from "./lib.atto";
                pub fnc main(): int {
                  let int made = lib.command("ls");
                  return made;
                }
                """, "made");
        accepts("""
                import module lib as proc from "./lib.atto";
                pub fnc main(): int {
                  let proc.Command made = proc.command("ls");
                  return 0;
                }
                """);
    }

    @Test
    void borrowedParametersKeepTheirLoanMode() {
        accepts("""
                import module lib from "./lib.atto";
                pub fnc main(): int {
                  let mut Array<int> values = new Array<int>();
                  values.add(40);
                  values.add(2);
                  return lib.total(rt borrow values);
                }
                """);
        rejects("""
                import module lib from "./lib.atto";
                pub fnc main(): int {
                  return lib.total("not an array");
                }
                """, "argument");
    }

    @Test
    void wrongArityAndUnknownMembersFailAtCompileTime() {
        rejects("""
                import module lib from "./lib.atto";
                pub fnc main(): int { return lib.total(); }
                """, "arity");
        rejects("""
                import module lib from "./lib.atto";
                pub fnc main(): int { return lib.missing(1); }
                """, "missing");
    }

    @Test
    void bodyOnlyEditsDoNotRecheckImportersButSignatureEditsDo() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        String main = """
                import module lib from "./lib.atto";
                pub fnc main(): int {
                  let mut Array<int> values = new Array<int>();
                  return lib.total(rt borrow values);
                }
                """;
        compiler.compile(program(main));

        Map<String, String> bodyEdit = program(main);
        bodyEdit.put("lib.atto", LIB.replace("return value + 1;", "return value + 2;"));
        var second = compiler.compile(bodyEdit);
        assertTrue(second.rebuiltUnits().contains("lib.atto"));
        assertFalse(second.rebuiltUnits().contains("main.atto"),
                "an implementation-only edit must not re-check importers");

        Map<String, String> signatureEdit = program(main);
        signatureEdit.put("lib.atto", LIB.replace(
                "pub fnc total(borrow Array<int> values): int {",
                "pub fnc total(borrow Array<int> values, int extra): int {")
                .replace("return sum;\n", "return sum + extra;\n"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> compiler.compile(signatureEdit),
                "an interface edit must re-check importers and surface their errors");
        assertTrue(failure.getMessage().contains("main.atto"), failure::getMessage);
        assertTrue(failure.getMessage().contains("arity"), failure::getMessage);
    }

    private static final String ROOT_LIB = """
            define pub class Point as
              pub val int x;
              pub constructor(int x) { self.x = x; }
            end
            pub fnc origin(): Point { return new Point(0); }
            pub fnc wrap<T>(T value): Option<T> { return Some(value); }
            pub fnc twice(int value): Array<int> {
              let mut Array<int> out = new Array<int>();
              out.add(value);
              out.add(value);
              return out;
            }
            """;

    private static void compileWithRoot(String main) {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("geo.atto", ROOT_LIB);
        sources.put("main.atto", main);
        new IncrementalCompiler().compile(sources);
    }

    @Test
    void wildcardNamespaceImportsTypeFileRootDeclarations() {
        assertDoesNotThrow(() -> compileWithRoot("""
                import * as geo from "./geo.atto";
                pub fnc main(): int {
                  let geo.Point p = geo.origin();
                  let String name = "a";
                  let Option<String> boxed = geo.wrap(name);
                  let Array<int> pair = geo.twice(21);
                  return p.x;
                }
                """));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> compileWithRoot("""
                        import * as geo from "./geo.atto";
                        pub fnc main(): int {
                          let String name = "a";
                          let Option<int> boxed = geo.wrap(name);
                          return 0;
                        }
                        """));
        assertTrue(failure.getMessage().contains("boxed"), failure::getMessage);
    }

    @Test
    void asyncNamespaceSignatureUsesTheImportedTypeFromTheResolvedFile() {
        String token = """
                pub define class Token as
                  pub val int value;
                end
                pub fnc make(): Token { return new Token(17); }
                """;
        String sink = """
                import * as model from "./model.atto";
                pub async fnc write(model.Token token): int { return token.value; }
                """;
        String main = """
                import * as actual from "./model.atto";
                import * as model from "./decoy.atto";
                import * as sink from "./sink.atto";
                pub async fnc main(): int { return await sink.write(actual.make()); }
                """;
        var compiler = new IncrementalCompiler();
        assertDoesNotThrow(() -> compiler.compile(Map.of(
                "model.atto", token, "decoy.atto", token, "sink.atto", sink, "main.atto", main)));
        var failure = assertThrows(IllegalArgumentException.class, () -> compiler.compile(Map.of(
                "model.atto", token, "decoy.atto", token, "sink.atto", sink,
                "main.atto", main.replace("actual.make()", "model.make()"))));
        assertTrue(failure.getMessage().contains("argument 1"), failure::getMessage);
    }

    @Test
    void importedFunctionBindingsAreTypedThroughImports() {
        assertDoesNotThrow(() -> compileWithRoot("""
                import fnc twice from "./geo.atto";
                pub fnc main(): int {
                  let Array<int> pair = imports::twice(21);
                  return pair.get(0) + pair.get(1);
                }
                """));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> compileWithRoot("""
                        import fnc twice from "./geo.atto";
                        pub fnc main(): int {
                          let String wrong = imports::twice(21);
                          return 0;
                        }
                        """));
        assertTrue(failure.getMessage().contains("wrong"), failure::getMessage);
    }

    @Test
    void builtinNominalsAndFutureVoidAreLinked() {
        accepts("""
                import module lib from "./lib.atto";
                pub async fnc main(): int {
                  await lib.settle();
                  let Bytes data = lib.empty_bytes();
                  return 0;
                }
                """);
        rejects("""
                import module lib from "./lib.atto";
                pub fnc main(): int {
                  let int data = lib.empty_bytes();
                  return data;
                }
                """, "data");
    }
}
