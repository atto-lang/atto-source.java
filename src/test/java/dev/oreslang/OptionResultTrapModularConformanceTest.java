package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Candidate source-guest differential baseline for conformance PR #290.
 * These test sources match the locked .ores modular v2 fixtures.
 * A source-only JUnit pass is not LLVM/Wasm/Cranelift/NativeAOT parity proof.
 */
final class OptionResultTrapModularConformanceTest {

    @Test
    void ort2_option_scalar() throws Exception {
        final String source = """
                // ORT2-OPTION_SCALAR: native guest assertions, not executed evidence
                
                fnc emit(bool yes): void {
                  if yes; then
                    stdio.stdout.write("1");
                  else
                    stdio.stdout.write("0");
                  fi
                  return;
                }
                pub fnc main(): void {
                  val Option<int> zero = Some(0);
                  val Option<int> missing = None;
                  val Option<bool> no = Some(false);
                  val Option<Option<int>> nested_missing = Some(None);
                  val Option<Option<int>> outer_missing = None;
                  val Option<Option<int>> nested_value = Some(Some(23));
                  stdio.stdout.write("ORT2:option_scalar:");
                  // CHECK some_zero_tag
                  emit(zero.is_some());
                  // CHECK some_zero_payload
                  emit(zero.unwrap() == 0);
                  // CHECK none_tag
                  emit(missing.is_none());
                  // CHECK none_fallback
                  emit(missing.unwrap_or(13) == 13);
                  // CHECK some_false_tag
                  emit(no.is_some());
                  // CHECK some_false_payload
                  emit(no.unwrap() == false);
                  // CHECK nested_some_none_tag
                  emit(nested_missing.is_some());
                  // CHECK nested_some_none_inner
                  emit(nested_missing.unwrap().is_none());
                  // CHECK nested_outer_none
                  emit(outer_missing.is_none());
                  // CHECK nested_value
                  emit(nested_value.unwrap().unwrap() == 23);
                  stdio.stdout.write(":END");
                  return;
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("ORT2:option_scalar:1111111111:END", run(source));
    }

    @Test
    void ort2_result_scalar() throws Exception {
        final String source = """
                // ORT2-RESULT_SCALAR: native guest assertions, not executed evidence
                
                fnc emit(bool yes): void {
                  if yes; then
                    stdio.stdout.write("1");
                  else
                    stdio.stdout.write("0");
                  fi
                  return;
                }
                pub fnc main(): void {
                  val Result<int,String> ok = Ok(11);
                  val Result<int,String> error = Err("fail");
                  val Result<Result<int,String>,String> nested = Ok(Err("nested"));
                  val Result<bool,String> false_result = Ok(false);
                  val Result<int,String> zero_result = Ok(0);
                  stdio.stdout.write("ORT2:result_scalar:");
                  // CHECK result_ok_tag
                  emit(ok.is_ok());
                  // CHECK result_ok_payload
                  emit(ok.unwrap() == 11);
                  // CHECK result_err_tag
                  emit(error.is_err());
                  // CHECK result_err_fallback
                  emit(error.unwrap_or(17) == 17);
                  // CHECK nested_outer_ok
                  emit(nested.is_ok());
                  // CHECK nested_inner_err
                  emit(nested.unwrap().is_err());
                  // CHECK ok_false_tag
                  emit(false_result.is_ok());
                  // CHECK ok_false_payload
                  emit(false_result.unwrap() == false);
                  // CHECK ok_zero_tag
                  emit(zero_result.is_ok());
                  // CHECK ok_zero_payload
                  emit(zero_result.unwrap() == 0);
                  stdio.stdout.write(":END");
                  return;
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("ORT2:result_scalar:1111111111:END", run(source));
    }

    @Test
    void ort2_trap_scalar() throws Exception {
        final String source = """
                // ORT2-TRAP_SCALAR: native guest assertions, not executed evidence
                trap fnc value(): int { return 27; }
                trap fnc optional(bool present): Option<int> {
                  if present; then return Some(9); fi
                  return None;
                }
                trap fnc complete(): void { return; }
                fnc emit(bool yes): void {
                  if yes; then
                    stdio.stdout.write("1");
                  else
                    stdio.stdout.write("0");
                  fi
                  return;
                }
                pub fnc main(): void {
                  val Option<int> success = value();
                  val Option<Option<int>> nested_none = optional(false);
                  val Option<Option<int>> nested_some = optional(true);
                  val Option<void> finished = complete();
                  stdio.stdout.write("ORT2:trap_scalar:");
                  // CHECK trap_value_some
                  emit(success.is_some());
                  // CHECK trap_value_payload
                  emit(success.unwrap() == 27);
                  // CHECK trap_absent_outer
                  emit(nested_none.is_some());
                  // CHECK trap_absent_inner
                  emit(nested_none.unwrap().is_none());
                  // CHECK trap_present_outer
                  emit(nested_some.is_some());
                  // CHECK trap_present_payload
                  emit(nested_some.unwrap().unwrap() == 9);
                  // CHECK trap_void_some
                  emit(finished.is_some());
                  stdio.stdout.write(":END");
                  return;
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("ORT2:trap_scalar:1111111:END", run(source));
    }

    @Test
    void ort2_trap_result_data() throws Exception {
        final String source = """
                // ORT2-TRAP_RESULT_DATA: native guest assertions, not executed evidence
                trap fnc error_value(): Result<int,String> { return Err("ordinary result data"); }
                trap fnc success_value(): Result<int,String> { return Ok(42); }
                fnc emit(bool yes): void {
                  if yes; then
                    stdio.stdout.write("1");
                  else
                    stdio.stdout.write("0");
                  fi
                  return;
                }
                pub fnc main(): void {
                  val Option<Result<int,String>> trapped_err = error_value();
                  val Option<Result<int,String>> trapped_ok = success_value();
                  stdio.stdout.write("ORT2:trap_result_data:");
                  // CHECK error_data_outer_some
                  emit(trapped_err.is_some());
                  // CHECK error_data_inner_err
                  emit(trapped_err.unwrap().is_err());
                  // CHECK success_outer_some
                  emit(trapped_ok.is_some());
                  // CHECK success_inner_ok
                  emit(trapped_ok.unwrap().is_ok());
                  // CHECK success_inner_payload
                  emit(trapped_ok.unwrap().unwrap() == 42);
                  stdio.stdout.write(":END");
                  return;
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("ORT2:trap_result_data:11111:END", run(source));
    }

    @Test
    void ort2_trap_cast() throws Exception {
        final String source = """
                // ORT2-TRAP_CAST: native guest assertions, not executed evidence
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end
                trap fnc as_dog(Animal animal): Dog { return animal as Dog; }
                fnc emit(bool yes): void {
                  if yes; then
                    stdio.stdout.write("1");
                  else
                    stdio.stdout.write("0");
                  fi
                  return;
                }
                pub fnc main(): void {
                  val Option<Dog> missing = as_dog(new Cat());
                  val Option<Dog> good = as_dog(new Dog());
                  stdio.stdout.write("ORT2:trap_cast:");
                  // CHECK cast_failure_none
                  emit(missing.is_none());
                  // CHECK cast_success_some
                  emit(good.is_some());
                  stdio.stdout.write(":END");
                  return;
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("ORT2:trap_cast:11:END", run(source));
    }

    @Test
    void ort2_neg_no_option_to_result_rejects_at_compile_time() {
        final String source = """
                // ORT2-NEG-NO_OPTION_TO_RESULT: must produce a semantic type error, never launch guest.
                fnc invalid(): void {
                  val Option<int> a = Some(1);
                  val Result<int,String> b = a;
                  return;
                }
                
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void ort2_neg_no_result_to_option_rejects_at_compile_time() {
        final String source = """
                // ORT2-NEG-NO_RESULT_TO_OPTION: must produce a semantic type error, never launch guest.
                fnc invalid(): void {
                  val Result<int,String> a = Err("e");
                  val Option<int> b = a;
                  return;
                }
                
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void ort2_neg_no_nested_option_flatten_rejects_at_compile_time() {
        final String source = """
                // ORT2-NEG-NO_NESTED_OPTION_FLATTEN: must produce a semantic type error, never launch guest.
                fnc invalid(): void {
                  val Option<Option<int>> a = Some(None);
                  val Option<int> b = a;
                  return;
                }
                
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void ort2_neg_no_nested_result_flatten_rejects_at_compile_time() {
        final String source = """
                // ORT2-NEG-NO_NESTED_RESULT_FLATTEN: must produce a semantic type error, never launch guest.
                fnc invalid(): void {
                  val Result<Result<int,String>,String> a = Ok(Err("e"));
                  val Result<int,String> b = a;
                  return;
                }
                
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void ort2_neg_no_trap_result_flatten_rejects_at_compile_time() {
        final String source = """
                // ORT2-NEG-NO_TRAP_RESULT_FLATTEN: must produce a semantic type error, never launch guest.
                trap fnc f(): Result<int,String> { return Err("e"); }
                fnc invalid(): void {
                  val Result<int,String> b = f();
                  return;
                }
                
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void trapped_unwrap_panic_bypasses_option_boundary() throws Exception {
        final String source = """
                // Must fail with a guest Option::unwrap panic; must NEVER print "UNREACHABLE".
                trap fnc panicking(): int {
                  val Option<int> missing = None;
                  return missing.unwrap();
                }
                pub fnc main(): void {
                  val Option<int> ignored = panicking();
                  stdio.stdout.write("UNREACHABLE");
                  return;
                }
                
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Source guest = Source.newBuilder(OresLanguage.ID, source, "ort-panic.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            PolyglotException error = assertThrows(PolyglotException.class,
                    () -> context.eval(guest));
            assertTrue(error.getMessage().contains("Option::unwrap"));
        }
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("UNREACHABLE"));
    }

    private static String run(String source) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Source guest = Source.newBuilder(OresLanguage.ID, source, "ort-modular.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
