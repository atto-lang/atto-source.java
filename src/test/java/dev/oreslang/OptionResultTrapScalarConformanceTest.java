package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed scalar observation entrypoints copied from the pinned
 * v3 native .ores differential fixture suite (conformance PR #290).
 * These methods do NOT prove LLVM/Wasm/Cranelift/.NET parity.
 */
final class OptionResultTrapScalarConformanceTest {

    @Test
    void ort3_option_present() throws Exception {
        final String source = """
                // ORT3-OPTION_PRESENT: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                pub fnc main(): int {
                  val Option<int> value = Some(17);
                  if value.is_some(); then
                    return value.unwrap() - 17;
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-OPTION_PRESENT");
    }

    @Test
    void ort3_option_absent() throws Exception {
        final String source = """
                // ORT3-OPTION_ABSENT: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                pub fnc main(): int {
                  val Option<int> value = None;
                  if value.is_none(); then
                    return 0;
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-OPTION_ABSENT");
    }

    @Test
    void ort3_option_nested_absent() throws Exception {
        final String source = """
                // ORT3-OPTION_NESTED_ABSENT: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                pub fnc main(): int {
                  val Option<Option<int>> value = Some(None);
                  if value.is_some(); then
                    val Option<int> inner = value.unwrap();
                    if inner.is_none(); then
                      return 0;
                    else
                      return 2;
                    fi
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-OPTION_NESTED_ABSENT");
    }

    @Test
    void ort3_result_success() throws Exception {
        final String source = """
                // ORT3-RESULT_SUCCESS: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                pub fnc main(): int {
                  val Result<int, String> value = Ok(31);
                  if value.is_ok(); then
                    return value.unwrap() - 31;
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-RESULT_SUCCESS");
    }

    @Test
    void ort3_result_error() throws Exception {
        final String source = """
                // ORT3-RESULT_ERROR: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                pub fnc main(): int {
                  val Result<int, String> value = Err("ordinary-data");
                  if value.is_err(); then
                    return value.unwrap_or(23) - 23;
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-RESULT_ERROR");
    }

    @Test
    void ort3_trap_success() throws Exception {
        final String source = """
                // ORT3-TRAP_SUCCESS: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                trap fnc checked(): int { return 41; }
                pub fnc main(): int {
                  val Option<int> result = checked();
                  if result.is_some(); then
                    return result.unwrap() - 41;
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-TRAP_SUCCESS");
    }

    @Test
    void ort3_trap_nested() throws Exception {
        final String source = """
                // ORT3-TRAP_NESTED: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                trap fnc checked(): Option<int> { return None; }
                pub fnc main(): int {
                  val Option<Option<int>> result = checked();
                  if result.is_some(); then
                    val Option<int> inside = result.unwrap();
                    if inside.is_none(); then
                      return 0;
                    else
                      return 2;
                    fi
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-TRAP_NESTED");
    }

    @Test
    void ort3_trap_err_data() throws Exception {
        final String source = """
                // ORT3-TRAP_ERR_DATA: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                trap fnc checked(): Result<int, String> { return Err("not-throw"); }
                pub fnc main(): int {
                  val Option<Result<int, String>> result = checked();
                  if result.is_some(); then
                    val Result<int, String> inside = result.unwrap();
                    if inside.is_err(); then
                      return 0;
                    else
                      return 2;
                    fi
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-TRAP_ERR_DATA");
    }

    @Test
    void ort3_trap_cast_failure() throws Exception {
        final String source = """
                // ORT3-TRAP_CAST_FAILURE: candidate native guest, not executed evidence.
                // A real compiler-generated guest must return signed int 0 (not a host mock).
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end
                trap fnc as_dog(Animal animal): Dog { return animal as Dog; }
                pub fnc main(): int {
                  val Option<Dog> result = as_dog(new Cat());
                  if result.is_none(); then
                    return 0;
                  else
                    return 1;
                  fi
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals(0L, executeGuestMain(source), "ORT3-TRAP_CAST_FAILURE");
    }

    private static long executeGuestMain(String source) throws Exception {
        final Source program = Source.newBuilder(OresLanguage.ID, source, "ort-scalar.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx=Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            // Evaluation returns the actual Oreslang public main() result,
            // not process exit status, stdout text or a host-side oracle.
            Value result=ctx.eval(program);
            assertTrue(result.fitsInLong(),"guest main must return signed int");
            return result.asLong();
        }
    }
}
