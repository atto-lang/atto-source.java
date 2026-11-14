package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ExplicitReceiverMtdV1Test {
    @Test
    void canonicalReadAndMutableMethodsAttachToLocalClass() {
        Ast.Program program = Parser.parse("""
                define class Point as
                  pub let int x = 4;
                end
                mtd (self: Point) read_x(): int {
                  return self.x;
                }
                mtd (mut self: Point) move_by(int dx): void {
                  self.x = self.x + dx;
                }
                fnc run(): int {
                  let mut Point p = new Point();
                  p.move_by(3);
                  return p.read_x();
                }
                """);
        Ast.ClassDecl point = assertInstanceOf(Ast.ClassDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertEquals(2, point.methods().size());
        assertEquals("read_x", point.methods().get(0).name());
        assertEquals("move_by", point.methods().get(1).name());
        assertEquals(0, point.methods().get(0).arity());
        assertEquals(1, point.methods().get(1).arity());
        assertEquals("Point", point.methods().get(0).explicitReceiverType().name());
        assertTrue(point.methods().get(1).explicitReceiverType().mutableBorrow());
        assertEquals("Point",
                point.methods().get(1).explicitReceiverType().borrowedTarget().name());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void receiverCanPrecedeClassWithinTheSameModule() {
        Ast.Program program = Parser.parse("""
                define module Geometry as
                  mtd (self: Point) read_x(): int { return self.x; }
                  define class Point as
                    pub let int x = 7;
                  end
                end
                """);
        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.ClassDecl point = assertInstanceOf(Ast.ClassDecl.class,
                module.declarations().getFirst());
        assertEquals("read_x", point.methods().getFirst().name());
        assertDoesNotThrow(() -> TypeChecker.check(program));
    }

    @Test
    void rejectsAbsentForeignAndAmbiguousReceivers() {
        for (String source : new String[] {
                "mtd (self: Missing) read(): int { return 1; }",
                """
                define class Point as end
                mtd (self: Point) read(): int { return 1; }
                mtd (mut self: Point) read(): int { return 2; }
                """,
                """
                define class Point as
                  read(): int { return 1; }
                end
                mtd (self: Point) read(): int { return 2; }
                """,
                """
                define module A as
                  define class Point as end
                end
                define module B as
                  mtd (self: Point) read(): int { return 1; }
                end
                """,
                """
                define class Box<T> as end
                mtd (self: Box) read(): int { return 1; }
                """,
                """
                define actor ActorBox as end
                mtd (mut self: ActorBox) set(int n): void { return; }
                """
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void rejectsMalformedAndUnsafeReceiverSpelling() {
        for (String receiver : new String[] {
                "self Point", "mut self Point", "&mut self: Point",
                "*self: Point", "mut Point: Point", "self: Point<int>"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define class Point as end
                    mtd (%s) read(): int { return 1; }
                    """.formatted(receiver)), receiver);
        }
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Point as end
                static mtd (self: Point) wrong(): int { return 1; }
                """));
    }

    @Test
    void attoFixturesReachRealParserAndTypeChecker() throws Exception {
        String positive = readFixture("positive-local-class.atto");
        String immutable = readFixture("negative-immutable-receiver.atto");
        String actor = readFixture("negative-external-actor-receiver.atto");
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(positive)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(immutable)));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(actor));
    }

    private static String readFixture(String name) throws Exception {
        String resource = "/atto/mtd-receiver-v1/" + name;
        try (var stream = ExplicitReceiverMtdV1Test.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void rejectsAnnotationAndLifecyclePrivilegeEscalation() {
        for (String source : new String[] {
                """
                define class Point as end
                @Structural
                mtd (self: Point) value(): int { return 1; }
                """,
                """
                define class Point as end
                mtd (self: Point) new(): Point { return new Point(); }
                """,
                """
                define class Point as end
                mtd (self: Point) constructor(): void { return; }
                """,
                """
                define class Point as end
                mtd (self: Point) on_start(): void { return; }
                """,
                """
                define class Point as end
                mtd (self: Point) init(): void { return; }
                """
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void rejectsAmbiguousNominalClassesAndRepeatedExternalArity() {
        for (String source : new String[] {
                """
                define class Point as end
                define class Point as end
                mtd (self: Point) read_x(): int { return 1; }
                """,
                """
                define class Point as end
                mtd (self: Point) read_x(int x): int { return x; }
                mtd (mut self: Point) read_x(int x): int { return x; }
                """
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void immutableReceiverCannotModifyOwnerState() {
        Ast.Program program = Parser.parse("""
                define class Point as
                  pub let int x = 0;
                end
                mtd (self: Point) wrong(): void {
                  self.x = 1;
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(program));
    }
}
