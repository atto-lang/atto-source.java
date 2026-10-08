package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ActorLocalRefLanguageTest {
    @Test
    void actorTurnCanCloneStrongHandlesUpgradeWeakAndConstructCycles() throws Exception {
        String program = """
                // A recursive type may carry a managed actor-local back-edge.
                type Loop = Option<Ref<Loop>>;

                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Ref<int> owner = Ref.new<int>(3);
                    val WeakRef<int> weak = owner.downgrade();
                    val Ref<int> alias = owner.clone();
                    alias.set(7);
                    self.send(owner.get_copy());
                    self.send(weak.is_alive());

                    val Option<Ref<int>> resurrected = weak.upgrade();
                    self.send(resurrected.is_some());

                    // Explicit handle duplication creates a circular graph;
                    // the underlying Loop value is moved into the cell.
                    val Ref<Loop> circular = Ref.new<Loop>(None);
                    circular.set(Some(circular.clone()));
                    self.send("cycle");

                    alias.drop();
                    owner.drop();
                    circular.drop();
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("go");
                  val a = (await worker.outputs.next()).value.unwrap();
                  val b = (await worker.outputs.next()).value.unwrap();
                  val c = (await worker.outputs.next()).value.unwrap();
                  val d = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(a.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(b.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(c.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(d.value);
                  await worker.done;
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "actor-local-ref.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("7|true|true|cycle", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void nonActorCodeCannotCreateActorLocalReference() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): Ref<int> {
                          return Ref.new<int>(1);
                        }
                        """)));
        assertTrue(error.getMessage().contains("actor turn"), error.getMessage());
    }

    @Test
    void actorBoundaryForbidsStrongAndWeakLocalReferences() {
        for (String type : new String[]{"Ref<int>", "WeakRef<int>"}) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                    "actor fnc bad(" + type + " value): void { return; }")));
        }
    }

    @Test
    void getCopyCannotExposeAliasedAggregates() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(): void {
                          val Ref<List<int>> values = Ref.new<List<int>>([]);
                          val exposed = values.get_copy();
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("Copy scalar")
                        || error.getMessage().contains("Copy type"), error.getMessage());
    }
}
