package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class TypeDeclarationsAreNotValuesTest {
    private static final String BOX = """
            define class Box as
              pub static fnc twice(int value): int { return value * 2; }
            end
            """;

    private static IllegalArgumentException rejects(String body) {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + body)));
    }

    @Test
    void classDeclarationCannotEscapeThroughInferredOrAnnotatedBinding() {
        assertTrue(rejects("fnc bad(): void { val captured = Box; }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
        assertTrue(rejects("fnc bad(): void { val Box captured = Box; }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
    }

    @Test
    void classDeclarationCannotBeReturnedOrPassedAsValue() {
        assertTrue(rejects("fnc bad(): Box { return Box; }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
        assertTrue(rejects("""
                fnc consume(int number): int { return number; }
                fnc bad(): int { return consume(Box); }
                """).getMessage().contains("E_TYPE_NOT_VALUE"));
    }

    @Test
    void classDeclarationCannotBeExpressionStatementOrConsoleArgument() {
        assertTrue(rejects("fnc bad(): void { Box; }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
        assertTrue(rejects("fnc bad(): void { stdio.println(Box); }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
        assertTrue(rejects("fnc bad(): void { stdio.stdout.write(Box); }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
    }

    @Test
    void traitAndAliasDeclarationsAreNeverRuntimeValues() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define trait Printable as end
                        fnc bad(): void { val escaped = Printable; }
                        """))).getMessage().contains("E_TYPE_NOT_VALUE"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define type Count = int;
                        fnc bad(): void { val escaped = Count; }
                        """))).getMessage().contains("E_TYPE_NOT_VALUE"));
    }

    @Test
    void classNamespaceCannotEscapeThroughCollectionsAndGenericArguments() {
        assertTrue(rejects("fnc bad(): void { val elements = [Box]; }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
        assertTrue(rejects("fnc bad(): void { val elements = tuple (Box, 42); }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
        assertTrue(rejects("""
                fnc identity<T>(T value): T { return value; }
                fnc bad(): void { val escaped = identity(Box); }
                """).getMessage().contains("E_TYPE_NOT_VALUE"));
    }

    @Test
    void declarationsCannotBeComparedAsRuntimeValues() {
        assertTrue(rejects("fnc bad(): bool { return Box eq Box; }")
                .getMessage().contains("E_TYPE_NOT_VALUE"));
    }

    @Test
    void interfaceDeclarationIsTypeOnly() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Endpoint as end
                        fnc bad(): void { val escaped = Endpoint; }
                        """))).getMessage().contains("E_TYPE_NOT_VALUE"));
    }

    @Test
    void lexicalValueShadowingStillWorksForClassTypedInstances() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + """
                fnc echo(Box Box): Box { return Box; }
                fnc send_instance(): Box { return echo(new Box()); }
                """)));
    }

    @Test
    void staticAccessAndConcreteInstanceArgumentReturnRemainValid() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + """
                fnc make(): Box { return new Box(); }
                fnc inspect(Box object): int { return Box.twice(21); }
                fnc valid(): int { return inspect(make()); }
                """)));
    }
}
