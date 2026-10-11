package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class MethodDeclarationSpellingTest {
    @Test
    void connectionActorSpellingIsParsedAsInstanceMethods() {
        Ast.Program program = Parser.parse("""
                define actor ConnectionActor as
                    final rooms: Map<RoomId, ActorGroup>

                    suspend fnc join_room(
                        group: ActorGroup,
                        permit: ActorGroupJoinCapability
                    ) {
                        group.join(permit)
                    }

                    fnc leave_room(group: ActorGroup) {
                        group.leave()
                    }
                end
                """);
        Ast.ClassDecl actor = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(Ast.ActorKind.SHARED, actor.actorKind());
        assertEquals(1, actor.fields().size());
        assertEquals("rooms", actor.fields().getFirst().name());
        assertEquals(Ast.BindingKind.CONST, actor.fields().getFirst().bindingKind());
        assertEquals(2, actor.methods().size());
        assertTrue(actor.methods().get(0).suspends());
        assertFalse(actor.methods().get(0).isStatic());
        assertFalse(actor.methods().get(1).isStatic());
        assertEquals("join_room", actor.methods().get(0).name());
        assertEquals("leave_room", actor.methods().get(1).name());
    }

    @Test
    void optionalMethodKeywordAndBareSpellingHaveSameDispatchKind() {
        Ast.Program program = Parser.parse("""
                define class Client as
                    fnc legacy(): int { return 1; }
                    method explicit(): int { return 2; }
                    bare(): int { return 3; }
                    suspend method pending(): void { return; }
                    method method(): int { return 4; }
                    static fnc callable(): int { return 5; }
                end
                """);
        Ast.ClassDecl client = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(6, client.methods().size());
        for (int i = 0; i < 5; i++) {
            assertFalse(client.methods().get(i).isStatic());
        }
        assertTrue(client.methods().get(3).suspends());
        assertEquals("method", client.methods().get(4).name());
        assertTrue(client.methods().get(5).isStatic());
    }

    @Test
    void forbidsStaticMethodMarkerAndKeepsStaticFnc() {
        for (String body : new String[] {
                "static method wrong(): void { return; }",
                "method static wrong(): void { return; }"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse("define actor A as " + body + " end"));
            assertTrue(error.getMessage().contains("static") || error.getMessage().contains("fnc"));
        }
        assertDoesNotThrow(() -> Parser.parse("""
                define actor A as
                    static fnc leave_room(group: ActorGroup) {
                        group.leave()
                    }
                end
                """));
    }

    @Test
    void doesNotReinterpretAnOrdinarySuspendMethodNameAsModifier() {
        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                Parser.parse("""
                        define class C as
                            suspend(): int { return 1; }
                        end
                        """).modules().getFirst().declarations().getFirst());
        assertEquals("suspend", klass.methods().getFirst().name());
        assertFalse(klass.methods().getFirst().suspends());
    }
}
