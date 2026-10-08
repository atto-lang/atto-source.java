package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLocalReferencesLanguageTest {
    @TempDir Path temp;

    @Test
    void actorCanBuildLocalStrongWeakHandlesAndCopyScalarValues() throws Exception {
        assertEquals("9", run("""
                define actor Worker as
                  receive(ActorMail<string> mail): void {
                    val count = Ref.new(7);
                    val weak = count.downgrade();
                    val upgraded = weak.upgrade().unwrap();
                    upgraded.set(9);
                    self.send(upgraded.get_copy());
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("go");
                  val result = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(result.value);
                  await worker.done;
                  return;
                }
                """));
    }

    @Test
    void actorCanAllocateEmptyReferenceAndFormLocalCircularValue() throws Exception {
        Files.writeString(temp.resolve("models.ores"), """
                pub define class Link as
                  pub val Ref<Link> next;
                end
                """);
        Files.writeString(temp.resolve("main.ores"), """
                import class Link from "./models";

                define actor Worker as
                  receive(ActorMail<string> mail): void {
                    val head: Ref<Link> = Ref.empty<Link>();
                    head.set(new Link(head));
                    val recovered: Link = head.take().unwrap();
                    self.send(!head.is_set());
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("go");
                  val result = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(result.value);
                  await worker.done;
                  return;
                }
                """);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                temp.resolve("main.ores"),
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(), Map.of(), output, new ByteArrayOutputStream());
        assertEquals("true", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void compilerRejectsAllocationOutsideActorAndCrossActorMail() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val node = Ref.new(1);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<Ref<int>> message): void { return; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(Ref<int> unsafe): int { return 1; }
                """)));
    }

    @Test
    void compilerRejectsCompositeGetCopyAndBorrowStorage() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<string> mail): void {
                    val node = Ref.new(arr[1, 2]);
                    val unsafe = node.get_copy();
                    return;
                  }
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<string> mail): void {
                    val value = 3;
                    val cell = Ref.new(&value);
                    return;
                  }
                end
                """)));
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "actor-managed-refs.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
