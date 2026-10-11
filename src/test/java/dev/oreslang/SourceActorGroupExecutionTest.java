package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorGroupExecutionTest {
    private static String run(String body) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, body, "actor-group-source.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void supervisorCanCreateAndCloseHostOwnedGroupWithoutJavaInterop() throws Exception {
        assertEquals("0|true", run("""
                pub routine main(): void {
                  val room = ActorGroup.new();
                  val permit = room.join_capability();
                  stdio.stdout.write(room.member_count());
                  room.close();
                  stdio.stdout.write("|");
                  stdio.stdout.write(room.is_closed());
                  return;
                }
                """));
    }

    @Test
    void sourceActorCanCooperateInTwoGroupsAndLeaveOneIndependently() throws Exception {
        assertEquals("1|2|1|1", run("""
                define actor RoomSession as
                  receive(ActorMail<String> mail): void {
                    val roomA = ActorGroup.new();
                    val roomB = ActorGroup.new();
                    val permit = roomA.join_capability();
                    permit.join();
                    self.send(roomA.member_count());
                    self.send(roomB.member_count());
                    roomA.leave();
                    self.send(roomB.member_count());
                    roomA.close();
                    self.send(roomB.member_count());
                    roomB.close();
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn RoomSession();
                  await worker.get_ready();
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
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void staticallyRejectsForgedCapabilityCallsOrMemberAccess() {
        String prefix = """
                pub routine main(): void {
                  val room = ActorGroup.new();
                """;
        for (String bad : java.util.List.of(
                "room.leave(123);",
                "room.join_capability(123);",
                "room.member_count;",
                "room.not_a_method();",
                "ActorGroup.new(1);",
                "room.is_closed(1);")) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(prefix + bad + "\n}")),
                    bad);
        }
    }

    @Test
    void staticallyRejectsJoiningWithoutActorTurn() {
        String source = """
                pub routine main(): void {
                  val group = ActorGroup.new();
                  val permit = group.join_capability();
                  permit.join();
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("requires an actor turn"),
                failure.getMessage());
    }

    @Test
    void rejectsCapabilityAsActorMailboxPayload() {
        String program = """
                define actor Receiver as
                  receive(ActorMail<String> mail): void {
                    self.end();
                    return;
                  }
                end
                pub routine main(): void {
                  val group = ActorGroup.new();
                  val ref = spawn Receiver();
                  ref.send(group.join_capability());
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(program));
    }
}
