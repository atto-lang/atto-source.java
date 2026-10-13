package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ArityDispatchTableRuntimeTest {

    @Test
    void fullyQualifiedParentsWithSameSimpleNameDispatchIndependently() throws Exception {
        String source = """
                define module alpha
                  pub define class Base as
                    pub pick(): int { return 10; }
                    pub pick(int value): int { return value + 10; }
                  end
                  pub define class Child extends alpha.Base as
                    pub pick(int value): int { return value + 20; }
                  end
                end
                define module beta
                  pub define class Base as
                    pub pick(): int { return 30; }
                    pub pick(int value): int { return value + 30; }
                  end
                  pub define class Child extends beta.Base as
                    pub pick(int value): int { return value + 40; }
                  end
                end
                pub routine main(): void {
                  val a = new alpha.Child();
                  val b = new beta.Child();
                  stdio.stdout.write(a.pick());
                  stdio.stdout.write(":");
                  stdio.stdout.write(a.pick(1));
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.pick());
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.pick(1));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "arity-dispatch-modules.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("10:21:30:41", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void instanceAndStaticSlotsWithSameArityRemainIndependent() throws Exception {
        String source = """
                define class Base as
                  pub pick(int value): int { return value + 1; }
                  pub static fnc pick(int value): int { return value + 10; }
                end
                define class Child extends Base as
                  pub pick(int value): int { return value + 2; }
                  pub static fnc pick(int value): int { return value + 20; }
                end
                pub routine main(): void {
                  val value = new Child();
                  stdio.stdout.write(value.pick(4));
                  stdio.stdout.write(":");
                  stdio.stdout.write(Child.pick(4));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "arity-dispatch-kinds.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("6:24", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void diamondWithOneOriginalDeclarationHasOneDispatchSlot() throws Exception {
        String source = """
                define class Root as
                  pub pick(int value): int { return value + 1; }
                end
                define class Left extends Root as end
                define class Right extends Root as end
                define class Diamond extends Left, Right as end

                pub routine main(): void {
                  val value = new Diamond();
                  stdio.stdout.write(value.pick(6));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = IsolatePolicy.developer()
                .restrictedContextBuilder(ExecutionProfile.serverJit()).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "arity-dispatch-diamond.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("7", out.toString(StandardCharsets.UTF_8));
    }
}
