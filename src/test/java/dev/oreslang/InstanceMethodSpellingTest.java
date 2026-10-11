package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class InstanceMethodSpellingTest {
    private static Ast.ClassDecl actor(String source) {
        return (Ast.ClassDecl) Parser.parse(source).modules().getFirst().declarations().getFirst();
    }

    @Test
    void actorAcceptsLegacyFncBareAndExplicitMethodWithSuspendAndFinalField() {
        Ast.ClassDecl connection = actor("""
                define actor ConnectionActor as
                    final rooms: Map<RoomId, ActorGroup>

                    suspend fnc join_room(group: ActorGroup, permit: ActorGroupJoinCapability) {
                        group.join(permit)
                    }

                    method leave_room(group: ActorGroup) {
                        group.leave()
                    }

                    static fnc version(): int { return 1; }
                end
                """);
        assertEquals(1, connection.fields().size());
        assertEquals("rooms", connection.fields().getFirst().name());
        assertEquals(Ast.BindingKind.LET, connection.fields().getFirst().bindingKind());
        assertFalse(connection.fields().getFirst().mutableReferent());
        assertEquals(3, connection.methods().size());
        assertTrue(connection.methods().get(0).suspends());
        assertFalse(connection.methods().get(0).isStatic());
        assertFalse(connection.methods().get(1).isStatic());
        assertTrue(connection.methods().get(2).isStatic());
    }

    @Test
    void allThreeInstanceSpellingsProduceEquivalentMethodMetadata() {
        String head = "define actor ConnectionActor as\n";
        String tail = "\nend\n";
        String[] forms = {
            "leave_room(group: ActorGroup) { group.leave() }",
            "fnc leave_room(group: ActorGroup) { group.leave() }",
            "method leave_room(group: ActorGroup) { group.leave() }"
        };
        Ast.MethodDecl baseline = actor(head + forms[0] + tail).methods().getFirst();
        for (String form : forms) {
            Ast.MethodDecl parsed = actor(head + form + tail).methods().getFirst();
            assertEquals(baseline.name(), parsed.name());
            assertEquals(baseline.parameters(), parsed.parameters());
            assertEquals(baseline.isStatic(), parsed.isStatic());
            assertEquals(baseline.body(), parsed.body());
        }
    }

    @Test
    void classMembersAlsoAcceptFncAndExplicitMethodButStaticRequiresFnc() {
        String source = """
                define class Cursor as
                    fnc next(): int { return 1; }
                    method current(): int { return 2; }
                    static fnc zero(): int { return 0; }
                end
                """;
        Ast.ClassDecl clazz = (Ast.ClassDecl) Parser.parse(source).modules()
                .getFirst().declarations().getFirst();
        assertEquals(3, clazz.methods().size());
        assertFalse(clazz.methods().get(0).isStatic());
        assertFalse(clazz.methods().get(1).isStatic());
        assertTrue(clazz.methods().get(2).isStatic());
        assertThrows(IllegalArgumentException.class, () -> actor("""
                define actor A as
                    static method bad() {}
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> actor("""
                define actor A as
                    static bad() {}
                end
                """));
    }

    @Test
    void bareNameMethodIsStillLegal() {
        assertEquals("method", actor("""
                define actor A as
                    method() {}
                end
                """).methods().getFirst().name());
    }
}
