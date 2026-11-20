package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.ReturnCleanupChecker;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The canonical .atto fixtures are the language under test. Java is used only
 * for the compiler/Truffle test harness, never to emulate enum dispatch.
 */
final class EnumMatchArmSyntaxGuestTest {
    private static String fixture(String name) throws Exception {
        String path = "/atto/enum-match/" + name + ".atto";
        try (InputStream input = EnumMatchArmSyntaxGuestTest.class.getResourceAsStream(path)) {
            assertNotNull(input, "missing guest source fixture " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void executesAllCases(String name) throws Exception {
        String guest = fixture(name);
        Ast.Program parsed = Parser.parse(guest);
        Ast.Program typed = TypeChecker.check(parsed);
        OwnershipChecker.check(typed);
        ReturnCleanupChecker.check(typed);

        Ast.FunctionDecl next = parsed.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(declaration -> declaration instanceof Ast.FunctionDecl fn
                        && fn.name().equals("next"))
                .map(declaration -> (Ast.FunctionDecl) declaration)
                .findFirst().orElseThrow();
        // `return match ... over` is one returned, ordered value match whose
        // arms each yield exactly one enum symbol. No expression arm is discarded.
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, next.body().getFirst());
        Ast.MatchExpr matched = assertInstanceOf(Ast.MatchExpr.class, returned.value());
        assertTrue(matched.first());
        assertEquals(3, matched.arms().size());
        for (Ast.MatchValueArm arm : matched.arms()) {
            assertTrue(arm.prefixStatements().isEmpty());
            assertInstanceOf(Ast.MemberExpr.class, arm.value());
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, guest, name + ".atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("true\ntrue\ntrue",
                output.toString(StandardCharsets.UTF_8)
                        .trim().replace("\r", ""));
    }

    @Test void blockArmsWithExplicitReturnProduceEnumSymbols() throws Exception {
        executesAllCases("return-match-block");
    }

    @Test void expressionArmsProduceTheSameEnumSymbols() throws Exception {
        executesAllCases("return-match-expression");
    }

    @Test void nonExhaustiveEnumMatchIsRejected() throws Exception {
        String guest = fixture("missing-case");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test void repeatedEnumCaseIsRejectedAsUnreachable() throws Exception {
        String guest = fixture("duplicate-case");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test void payloadEnumCaseCannotBeUsedAsNullarySymbol() throws Exception {
        String guest = fixture("payload-requires-construction");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
        assertTrue(failure.getMessage().contains("requires a payload argument"),
                failure.getMessage());
    }

    @Test void foreignEnumSymbolCannotBeReturnedAsLocalEnum() throws Exception {
        String guest = fixture("foreign-variant-attempt");
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(guest)));
    }

    @Test void bareEnumSymbolsCannotBeMisreadAsIntegerOrString() throws Exception {
        for (String declaration : List.of("int", "string")) {
            String guest = """
                    define enum Light as
                        Red
                    end

                    fnc bad(): %s {
                        return Light.Red;
                    }
                    """.formatted(declaration);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(guest)));
        }
    }
}
