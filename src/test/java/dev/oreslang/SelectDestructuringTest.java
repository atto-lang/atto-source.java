package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SelectDestructuringTest {
    private static Ast.Program accepts(String code) {
        Ast.Program program = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(program);
        return program;
    }

    private static IllegalArgumentException rejects(String code) {
        return assertThrows(IllegalArgumentException.class, () -> accepts(code));
    }

    @Test
    void canonicalWhenReadAcceptsImplicitConstAndExplicitTypedBindings() {
        Ast.Program checked = accepts("""
                fnc receive(Channel<int> foo): void {
                  select {
                    when readch foo: int f -> {
                      stdio.println(f);
                    }
                  }
                  select {
                    when readch foo: const int f -> {
                      stdio.println(f);
                    }
                  }
                  select {
                    when readch foo: val f {
                      stdio.println(f);
                    }
                  }
                  return;
                }
                """);
        var fn = (Ast.FunctionDecl) checked.modules().getFirst().declarations().getFirst();
        var arm = ((Ast.SelectStmt) fn.body().getFirst()).arms().getFirst();
        assertEquals("f", arm.bindingName());
        assertEquals("int", arm.bindingType().name());
        assertEquals(Ast.BindingKind.CONST, arm.bindingKind());
    }

    @Test
    void selectWhenSupportsTuplePrefixDiscardTypedRestAndReadonlyRecords() {
        accepts("""
                fnc receive(
                    Channel<[int, string, bool]> tuples,
                    Channel<{foo: string, bar: int}> records
                ): void {
                  select {
                    when readch tuples: const [int id] -> {
                      stdio.println(id);
                    }
                    when readch records: const {foo: string} -> {
                      stdio.println(foo);
                    }
                  }
                  select {
                    when readch tuples: [_, string label] -> {
                      stdio.println(label);
                    }
                    when readch records: const record: {foo: string, bar: int} -> {
                      stdio.println(record.foo);
                    }
                  }
                  select {
                    when readch tuples: const [int id, ...tail: [string, bool]] -> {
                      stdio.println(id);
                    }
                  }
                  return;
                }
                """);
    }

    @Test
    void rejectsTypeMismatchMissingRecordMemberAndTooLongPrefix() {
        IllegalArgumentException mismatch = rejects("""
                fnc wrong(Channel<int> foo): void {
                  select {
                    when readch foo: string f -> {
                      stdio.println(f);
                    }
                  }
                  return;
                }
                """);
        assertTrue(mismatch.getMessage().contains("select read binding"), mismatch.getMessage());
        IllegalArgumentException field = rejects("""
                fnc wrong(Channel<{foo: string}> bar): void {
                  select {
                    when readch bar: const {missing: int} -> {
                      stdio.println(missing);
                    }
                  }
                  return;
                }
                """);
        assertTrue(field.getMessage().contains("requires"), field.getMessage());
        IllegalArgumentException arity = rejects("""
                fnc wrong(Channel<[int, string]> bar): void {
                  select {
                    when readch bar: const [int id, string label, bool flag] -> {
                      stdio.println(id);
                    }
                  }
                  return;
                }
                """);
        assertTrue(arity.getMessage().contains("destructure arity mismatch"), arity.getMessage());
    }

    @Test
    void nonblockingWhenKeepsArmLocalBindingsAndDoesNotCaptureTheirNames() {
        accepts("""
                actor fnc readLater(): void {
                  val Channel<[int, string]> input = Channel.new<[int, string]>(1);
                  do nb select {
                    when readch input: const [int id, string name] -> {
                      stdio.println(id);
                      stdio.println(name);
                    }
                  }
                  return;
                }
                """);
    }

    @Test
    void runtimeSelectReceivesAndDestructuresTypedPayloads() throws Exception {
        String code = """
                pub routine main(): void {
                  val Channel<[int, string, bool]> tuples =
                      Channel.new<[int, string, bool]>(1);
                  writech tuples, (42, "Alice", true);
                  select {
                    when readch tuples: const [int id] -> {
                      stdio.stdout.write(id);
                    }
                  }
                  writech tuples, (43, "Bob", false);
                  select {
                    when readch tuples: const [_, string name] -> {
                      stdio.stdout.write(":" + name);
                    }
                  }
                  return;
                }
                """;
        accepts(code);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "select-destructure.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42:Bob", output.toString(StandardCharsets.UTF_8));
    }
}
