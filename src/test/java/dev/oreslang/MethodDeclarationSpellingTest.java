package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
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
    void compilerTypeChecksBothInstanceMarkersAndStaticFunctions() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Counter as
                    fnc legacy(int value): int { return value + 1; }
                    method explicit(int value): int { return value + 2; }
                    static fnc independent(int value): int { return value + 3; }
                end
                """)));
    }

    @Test
    void rejectsAmbiguousDoubleMarkers() {
        for (String declaration : new String[] {
                "fnc method both(): int { return 1; }",
                "method fnc both(): int { return 1; }"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define class C as " + declaration + " end"));
        }
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
    void semicolonFreeFinalFieldsAcceptOnlyActualMemberBoundaries() {
        Ast.ClassDecl actor = assertInstanceOf(Ast.ClassDecl.class, Parser.parse("""
                define actor ConnectionActor as
                    final rooms: Map<int, int>
                    suspend method join_room(): void { return; }
                    final other: int
                    fnc leave_room(): void { return; }
                end
                """).modules().getFirst().declarations().getFirst());
        assertEquals(2, actor.fields().size());
        assertEquals(2, actor.methods().size());
        assertTrue(actor.methods().getFirst().suspends());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Bad as
                    final rooms: Map<int, int> suspend fnc join(): void { return; }
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define actor Bad as
                    final rooms: Map<int, int>
                    nonsensical identifier
                end
                """));
    }

    @Test
    void typedCallbackNeverImplicitlyBindsInstanceButStaticRemainsReifiable() {
        IllegalArgumentException bound = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Worker as
                            pub method work(int value): int { return value; }
                            pub static fnc reusable(int value): int { return value; }
                        end
                        fnc apply(Fnc<int, int> action, int value): int {
                            return action(value);
                        }
                        fnc bad(): int {
                            val worker = new Worker();
                            return apply(worker.work, 1);
                        }
                        """)));
        assertTrue(bound.getMessage().contains("direct-call-only"), bound::getMessage);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Worker as
                    pub method work(int value): int { return value; }
                    pub static fnc reusable(int value): int { return value; }
                end
                fnc apply(Fnc<int, int> action, int value): int {
                    return action(value);
                }
                fnc good(): int {
                    val worker = new Worker();
                    return apply(|int value| -> { return worker.work(value); }, 1)
                           + apply(Worker.reusable, 2);
                }
                """)));
    }

    @Test
    void inferredMemberExtractionCannotCaptureInstanceReceiver() {
        // An expected Fnc<T> has its own separate path. Also reject an
        // unannotated binding: it must not infer a receiver-bound closure.
        IllegalArgumentException captured = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Worker as
                            pub fnc work(int value): int { return value + 1; }
                            pub static fnc reusable(int value): int { return value + 2; }
                        end
                        fnc invalid(): void {
                            val worker = new Worker();
                            val captured = worker.work;
                        }
                        """)));
        assertTrue(captured.getMessage().contains("direct-call-only"), captured::getMessage);

        // Static functions are still first-class even without contextual Fnc
        // typing, and explicit lambdas may capture their instance receiver.
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Worker as
                    pub method work(int value): int { return value + 1; }
                    pub static fnc reusable(int value): int { return value + 2; }
                end
                fnc valid(): int {
                    val worker = new Worker();
                    val static_callback = Worker.reusable;
                    val Fnc<int, int> instance_callback = |int value| -> { return worker.work(value); };
                    return static_callback(1) + instance_callback(2);
                }
                """)));
    }

    @Test
    void contextualMethodWordIsStillAValidBareMethodName() {
        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                Parser.parse("""
                        define class C as
                            method(): int { return 7; }
                        end
                        """).modules().getFirst().declarations().getFirst());
        assertEquals("method", klass.methods().getFirst().name());
        assertFalse(klass.methods().getFirst().isStatic());
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
