package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class TaskTypeInferenceTest {
    @Test void inferredIntTaskBothSpellingsExecute() throws Exception {
        for (String keyword : new String[]{"task", "nb"}) {
            String source = """
                    pub routine main(): void {
                      val int x = 41;
                      let worker = %s { return x + 1; };
                      let int result = await worker;
                      stdio.stdout.write(result);
                      return;
                    }
                    """.formatted(keyword);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
            assertEquals("42", run(source));
        }
    }
    @Test void inferredVoidTaskExecutes() throws Exception {
        assertEquals("42", run("""
                pub routine main(): void {
                  let worker = task { stdio.stdout.write(42); };
                  await worker;
                  return;
                }
                """));
    }
    @Test void incompatibleReturnArmsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                  let worker = task {
                    if true then return 3; else return "wrong"; fi
                  };
                  await worker;
                  return;
                }
                """));
    }
    @Test void mixedVoidAndIntReturnsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                  let worker = nb {
                    if true then return 3; else return; fi
                  };
                  await worker;
                  return;
                }
                """));
    }
    @Test void inferredIntMustReturnEveryPath() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub routine main(): void {
                  let worker = task {
                    if true then return 3; fi
                  };
                  await worker;
                  return;
                }
                """));
    }
    private String run(String text) throws Exception {
        var output = new ByteArrayOutputStream();
        var source = Source.newBuilder(OresLanguage.ID, text, "task-infer.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (var context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
