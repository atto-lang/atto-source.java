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

    @Test
    void bareAndExplicitMtdSupportBothClassMethodBodyStyles() {
        Ast.Program program = Parser.parse("""
                define class X1 as
                    foo(): void {}
                end
                define class X2 as
                    foo() -> void ::
                    end
                end
                define class X3 as
                    mtd foo(): void {}
                end
                define class X4 as
                    mtd foo() -> void ::
                    end
                end
                """);
        assertEquals(4, program.modules().getFirst().declarations().size());
        for (Ast.Decl declaration : program.modules().getFirst().declarations()) {
            Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class, declaration);
            assertEquals(1, klass.methods().size());
            Ast.MethodDecl method = klass.methods().getFirst();
            assertEquals("foo", method.name());
            assertEquals(0, method.arity());
            assertFalse(method.isStatic());
            assertEquals("void", method.returnType().name());
            assertTrue(method.body().isEmpty());
        }
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void mtdAndBareMethodsRetainIdenticalReceiverAndAritySemantics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Counter as
                    mtd read(): int { return 1; }
                    mtd read(self, int n) -> int ::
                        return n;
                    end
                    read(int n, int extra): int { return n + extra; }
                end
                """)));
        for (String pair : new String[] {
                "foo(): void {} mtd foo() -> void :: end",
                "foo(self): void {} mtd foo() -> void :: end",
                "mtd foo(self, int n): void {} foo(int n): void {}"
        }) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(
                    Parser.parse("define class Clash as " + pair + " end")));
        }
    }

    @Test
    void mtdIsContextualAndSupportsActorMethodsWithoutChangingStaticFnc() {
        Ast.Program program = Parser.parse("""
                define actor Worker as
                    mtd run() -> void ::
                    end
                    mtd(): void {}
                    method(): void {}
                    static fnc utility(): void {}
                end
                """);
        Ast.ClassDecl actor = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(4, actor.methods().size());
        assertEquals("run", actor.methods().get(0).name());
        assertEquals("mtd", actor.methods().get(1).name());
        assertEquals("method", actor.methods().get(2).name());
        assertTrue(actor.methods().get(3).isStatic());
    }

    @Test
    void methodBodySeparatorsAndInvalidMarkerCombinationsFailClosed() {
        for (String declaration : new String[] {
                "foo(): void :: end",
                "mtd foo(): void :: end",
                "mtd fnc foo(): void {}",
                "fnc mtd foo(): void {}",
                "static mtd foo(): void {}",
                "mtd static foo(): void {}",
                "mtd foo() -> void :: return;",
                "mtd foo() -> void :: end end"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("define class Bad as " + declaration + " end"),
                    declaration);
        }
    }


    @Test
    void mtdStartsAfterSemicolonFreeFieldsAndOwnsItsEnd() {
        Ast.Program program = Parser.parse("""
                define class Store as
                    final int count = 1
                    mtd read() -> int ::
                        if true then
                            return 1;
                        else
                            return 2;
                        fi
                    end
                    mtd blank(): void {}
                end
                """);
        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(1, klass.fields().size());
        assertEquals(2, klass.methods().size());
        assertEquals(0, klass.methods().getFirst().arity());
        assertFalse(klass.methods().getFirst().body().isEmpty());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void optionalMtdMarkerPreservesCallableNamesAndGenericHeaders() {
        Ast.Program program = Parser.parse("""
                define class Named as
                    mtd do(): int { return 1; }
                    mtd select() -> int ::
                        return 2;
                    end
                    mtd mtd(): int { return 3; }
                    mtd generic<T>(T value) -> T ::
                        return value;
                    end
                    method mtd(int value): int { return value; }
                end
                """);
        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals("do", klass.methods().get(0).name());
        assertEquals("select", klass.methods().get(1).name());
        assertEquals("mtd", klass.methods().get(2).name());
        assertEquals("generic", klass.methods().get(3).name());
        assertEquals(1, klass.methods().get(3).arity());
        assertEquals("mtd", klass.methods().get(4).name());
    }

    @Test
    void semicolonFreeFieldsAcceptKeywordNamedAndSymbolMarkedMethodsOnlyOnNewLines() {
        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                Parser.parse("""
                        define class Boundaries as
                            final int count = 1
                            mtd select(): int { return 2; }
                            final int other = 3
                            method do() -> int ::
                                return 4;
                            end
                            final int another = 5
                            mtd [Symbol.iterator](): int { return 6; }
                        end
                        """).modules().getFirst().declarations().getFirst());
        assertEquals(3, klass.fields().size());
        assertEquals(3, klass.methods().size());
        assertEquals("select", klass.methods().get(0).name());
        assertEquals("do", klass.methods().get(1).name());
        assertEquals("Symbol.iterator", klass.methods().get(2).name());

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Wrong as
                    final int count = 1 mtd select(): int { return 2; }
                end
                """));
    }


    @Test
    void semicolonFreeClassMembersMustHaveRealLineBoundary() {
        for (String next : new String[] {
                "pub mtd select(): int { return 2; }",
                "private mtd select(): int { return 2; }",
                "async mtd select(): int { return 2; }",
                "mtd select(): int { return 2; }",
                "mtd [Symbol.iterator](): int { return 2; }",
                "final int other = 2"
        }) {
            String invalid = "define class C as final int count = 1 " + next + " end";
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(invalid), invalid);
        }

        Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class,
                Parser.parse("""
                        define class C as
                            final int count = 1;
                            pub mtd select(): int { return 2; }
                            final int other = 3
                            private mtd [Symbol.iterator](): int { return 4; }
                        end
                        """).modules().getFirst().declarations().getFirst());
        assertEquals(2, klass.fields().size());
        assertEquals(2, klass.methods().size());
    }

}
