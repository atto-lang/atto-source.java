package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class AliasExtensionMethodTest {
    @Test
    void parsesExtensionMethodAsPrivateReceiverExplicitCallable() {
        Ast.Program program = Parser.parse("""
                declare type Code = int;
                define mtd double on Code(): int { return self + self; }
                """);
        var declarations = program.modules().getFirst().declarations();
        Ast.FunctionDecl method = assertInstanceOf(Ast.FunctionDecl.class, declarations.get(1));
        assertEquals("$mtd$Code$double", method.name());
        assertEquals("self", method.parameters().getFirst().name());
        assertEquals(Ast.TypeRef.simple("Code"), method.parameters().getFirst().type());
        assertEquals(Ast.Visibility.PRIVATE, method.visibility());
    }

    @Test
    void typeChecksAndExecutesAliasExtensionWithoutBoxing() throws Exception {
        assertEquals("14", run("""
                declare type Code = int;
                define mtd double on Code(): int { return self + self; }
                fnc calculate(Code code): int { return code.double(); }
                pub fnc main(): void {
                  stdio.stdout.write(calculate(7));
                  return;
                }
                """));
    }

    @Test
    void buildTreeShakingRetainsCheckedAliasMethodTarget() {
        var result = OresCompiler.compileForBuild("""
                declare type Code = int;
                define mtd double on Code(): int { return self + self; }
                pub fnc main(): void {
                  val code: Code = 6;
                  stdio.stdout.write(code.double());
                  return;
                }
                """, dev.oreslang.compiler.BuildOptions.executable(java.util.Map.of()));
        assertTrue(result.retained(Parser.ROOT_MODULE + ".$mtd$Code$double"),
                "checked member call must keep its synthetic extension function");
    }

    @Test
    void extensionDispatchMetadataRequiresExactCheckedCallIdentity() {
        Ast.CallExpr first = new Ast.CallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("code"), "double"), java.util.List.of());
        Ast.CallExpr sameShape = new Ast.CallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("code"), "double"), java.util.List.of());
        assertNull(Ast.CheckedExtensionCall.lookup(first));
        Ast.CheckedExtensionCall.record(first, "$mtd$Code$double");
        assertEquals("$mtd$Code$double", Ast.CheckedExtensionCall.lookup(first));
        assertNull(Ast.CheckedExtensionCall.lookup(sameShape));
        Ast.copyCheckedMetadata(first, sameShape);
        assertEquals("$mtd$Code$double", Ast.CheckedExtensionCall.lookup(sameShape));
        assertThrows(IllegalStateException.class,
                () -> Ast.CheckedExtensionCall.record(first, "$mtd$Code$unsafe"));
    }

    @Test
    void typedAliasLocalCarriesExtensionButShadowingDoesNot() throws Exception {
        assertEquals("12", run("""
                declare type Code = int;
                define mtd double on Code(): int { return self + self; }
                pub fnc main(): void {
                  val code: Code = 6;
                  stdio.stdout.write(code.double());
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                declare type Code = int;
                define mtd double on Code(): int { return self + self; }
                fnc invalid(Code value): int {
                  if true then
                    val value: int = 1;
                    return value.double();
                  else
                    return 0;
                  fi
                }
                """));
    }

    @Test
    void doesNotAttachCodeExtensionsToUnderlyingInt() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        declare type Code = int;
                        define mtd double on Code(): int { return self + self; }
                        fnc misuse(int raw): int { return raw.double(); }
                        """));
        assertNotNull(failure.getMessage());
    }

    @Test
    void requiresDeclaredAliasAsExtensionReceiver() {
        for (String receiver : new String[]{"int", "Missing"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "define mtd double on " + receiver + "(): int { return self + self; }")));
            assertTrue(failure.getMessage().contains("EXTENSION_RECEIVER"), failure::getMessage);
        }
    }

    @Test
    void retainsArityOnlyOverloadAndDisallowsDuplicates() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck("""
                declare type Code = int;
                define mtd choose on Code(): int { return self; }
                define mtd choose on Code(int value): int { return value; }
                fnc call(Code code): int { return code.choose() + code.choose(3); }
                """));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        declare type Code = int;
                        define mtd choose on Code(int first): int { return first; }
                        define mtd choose on Code(bool second): int { return self; }
                        """)));
        assertTrue(failure.getMessage().contains("arity"), failure::getMessage);
    }

    @Test
    void rejectsMutableReceiverSpoofingAndUnsupportedGenerics() {
        for (String source : new String[]{
                "declare type Code = int; define mtd bad on Code<int>(): int { return self; }",
                "declare type Code = int; define mtd bad on Code(self: int): int { return self; }",
                "declare type Code = int; define mtd init on Code(): int { return self; }"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "alias-mtd.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
