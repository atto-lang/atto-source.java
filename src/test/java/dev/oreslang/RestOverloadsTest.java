package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class RestOverloadsTest {
    @Test
    void sourceFunctionsOverloadByCallerArityAndBindRestToTypedList() throws Exception {
        String source = """
                fnc any(List<bool> values): bool {
                  for const value of values do
                    if value then return true; fi
                  done
                  return false;
                }

                fnc any(bool a, bool b, rest ...bool): bool {
                  if a or b then return true; fi
                  for const value of rest do
                    if value then return true; fi
                  done
                  return false;
                }

                pub fnc main(): void {
                  stdio.println(any([]));
                  stdio.println(any([true]));
                  stdio.println(any(false, false));
                  stdio.println(any(false, true));
                  stdio.println(any(false, false, true));
                  stdio.println(any(false, false, false, false));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertDoesNotThrow(() -> OresCompiler.compileForBuild(
                source, BuildOptions.executable(Map.of())));
        assertEquals("""
                false
                true
                false
                true
                true
                false
                """, run(source));
    }

    @Test
    void exactArityWinsOverTheVariadicFallback() throws Exception {
        String source = """
                fnc choose(bool a, bool b, rest ...bool): bool {
                  return false;
                }

                fnc choose(bool a, bool b, bool c): bool {
                  return true;
                }

                pub fnc main(): void {
                  stdio.println(choose(false, false));
                  stdio.println(choose(false, false, false));
                  stdio.println(choose(false, false, false, false));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertEquals("false\ntrue\nfalse\n", run(source));
    }

    @Test
    void restParameterIsLastAndRepresentsAListElementType() {
        Ast.Program parsed = Parser.parse("""
                fnc combine(bool a, bool b, rest ...bool): bool {
                  for const value of rest do
                    if value then return true; fi
                  done
                  return a xor b;
                }
                """);
        Ast.FunctionDecl fn = assertInstanceOf(Ast.FunctionDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        assertEquals(3, fn.parameters().size());
        assertFalse(fn.parameters().get(0).rest());
        assertTrue(fn.parameters().getLast().rest());
        assertEquals("bool", fn.parameters().getLast().type().name());
        assertDoesNotThrow(() -> TypeChecker.check(parsed));

        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc bad(rest ...bool, bool x): void { return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc bad(rest ...bool, other ...bool): void { return; }"));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(
                        "fnc bad(rest ...void): void { return; }")));
    }

    @Test
    void ambiguousVariadicFallbacksAndUnsupportedAbiSurfacesFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(bool x, rest ...bool): bool { return x; }
                        fnc bad(bool x, bool y, rest ...bool): bool { return y; }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Bad as
                          constructor(rest ...bool) {}
                        end
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Bad as
                          bad(rest ...bool): void { return; }
                        end
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        async fnc unsupported(rest ...bool): bool {
                          return false;
                        }
                        """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rest-overloads.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
