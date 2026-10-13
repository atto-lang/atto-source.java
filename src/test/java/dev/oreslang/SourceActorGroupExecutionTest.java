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
        assertEquals("1|1|1|1", run("""
                define actor RoomSession as
                  receive(ActorMail<String> mail): void {
                    val roomA = ActorGroup.new();
                    val roomB = ActorGroup.new();
                    val stale = roomA.join_capability();
                    val permit = roomA.rotate_join_capability();
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
                "room.rotate_join_capability(123);",
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
    void rejectsSourceDeclarationsThatShadowOpaqueGroupBuiltins() {
        for (String collision : java.util.List.of(
                "ActorGroup", "ActorGroupJoinCapability")) {
            String source = """
                    define class %s as
                      pub let int value = 0;
                    end
                    pub routine main(): void { return; }
                    """.formatted(collision);
            IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
            assertTrue(denied.getMessage().contains(
                    "reserved ActorGroup capability name cannot be redeclared"),
                    denied.getMessage());
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
    void rejectsDirectAndNestedActorGroupCapabilitiesAtActorBoundaries() {
        // A String mailbox would reject the permit simply for being the wrong
        // payload type. Exercise matching declared types instead: they must
        // fail specifically because opaque group authority is not sendable.
        for (String payloadType : java.util.List.of(
                "ActorGroup", "ActorGroupJoinCapability",
                "Option<ActorGroupJoinCapability>",
                "List<ActorGroup>", "List<Option<ActorGroupJoinCapability>>")) {
            String program = """
                    define actor Receiver as
                      receive(ActorMail<%s> mail): void {
                        self.end();
                        return;
                      }
                    end
                    pub routine main(): void { return; }
                    """.formatted(payloadType);
            IllegalArgumentException denied = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(program),
                    payloadType + " must not be an actor mailbox payload");
            assertTrue(denied.getMessage().contains(
                            payloadType.contains("JoinCapability")
                                    ? "ActorGroupJoinCapability" : "ActorGroup"),
                    denied.getMessage());
        }
    }

    @Test
    void rejectsGroupPermitSentToExplicitlyTypedActorRef() {
        String program = """
                define actor Receiver as
                  receive(ActorMail<ActorGroupJoinCapability> mail): void {
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
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(denied.getMessage().contains("ActorGroupJoinCapability"),
                denied.getMessage());
    }

    @Test
    void requiresActorSpawnCapabilityAtIsolateAdmission() {
        String program = """
                pub routine main(): void {
                  val room = ActorGroup.new();
                  room.close();
                  return;
                }
                """;
        dev.oreslang.runtime.IsolatePolicy withoutSpawn =
                dev.oreslang.runtime.IsolatePolicy.developer()
                        .withoutCapabilities(
                                dev.oreslang.runtime.IsolatePolicy.Capability.ACTOR_SPAWN);
        SecurityException denied = assertThrows(SecurityException.class,
                () -> OresCompiler.validateForIsolate(program, withoutSpawn));
        assertTrue(denied.getMessage().contains("ACTOR_SPAWN"),
                denied.getMessage());
    }
    @Test
    void untrustedSourceActorCannotCreateGroupAuthorities() {
        String source = """
                define untrusted actor Worker as
                  receive(ActorMail<String> mail): void {
                    val room = ActorGroup.new();
                    self.end();
                    return;
                  }
                end
                pub routine main(): void { return; }
                """;
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(denied.getMessage().contains("untrusted actors cannot create ActorGroup"),
                denied.getMessage());
    }

}
