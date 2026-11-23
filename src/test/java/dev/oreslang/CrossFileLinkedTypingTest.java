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
