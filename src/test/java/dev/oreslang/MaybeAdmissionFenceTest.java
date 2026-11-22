package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class MaybeAdmissionFenceTest {
    @Test
    void checkedGuestFixtureIsRealAttoSourceAndPreservesFunctionReturnAndDefer() throws Exception {
        String source = Files.readString(Path.of("tests/fixtures/maybe-result-defer-v1/return-diverges.atto"));
        var typed = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(typed);
        assertEquals("D8", run(source));
    }

    @Test
    void questionEqualHasOneTokenAndDoesNotStealTernary() {
        var tokens = new Lexer("Ok(value) ?= get(); true ? 1 : 0;").scan();
        assertEquals(1, tokens.stream().filter(t -> t.type() == Token.Type.FALLIBLE_BIND).count());
        assertEquals(1, tokens.stream().filter(t -> t.type() == Token.Type.QUESTION).count());
        var spaced = new Lexer("Ok(value) ? = get();").scan();
        assertEquals(0, spaced.stream().filter(t -> t.type() == Token.Type.FALLIBLE_BIND).count());
    }

    @Test
    void contextualMaybeIdentifierRemainsLegal() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc same(int maybe): int { return maybe; }
                fnc plus_one(int maybe): int { return maybe + 1; }
                """));
    }

    @Test
    void returnMaybeNestedReturnsAreTrueFunctionReturnsNotValueMatchResults() {
        var program = Parser.parse("""
                fnc f(Result<int, String> value): Result<int, String> {
                  return maybe
                    Ok(n) ?= value;
                    return Ok(n + 1)
                  else
                    Err(reason) -> { return Err(reason); };
                  end;
                }
                """);
        var fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertInstanceOf(Ast.BlockStmt.class, fn.body().getFirst());
        Ast.BlockStmt body = (Ast.BlockStmt) fn.body().getFirst();
        Ast.MatchStmt match = (Ast.MatchStmt) body.body().getFirst();
        assertTrue(match.procedural());
        assertInstanceOf(Ast.ReturnStmt.class, match.arms().getFirst().body().getFirst());
        assertDoesNotThrow(() -> OwnershipChecker.check(TypeChecker.check(program)));
    }

    @Test
    void doMaybeAndValueReturnMaybeExecuteWithFirstFailureOnly() throws Exception {
        String source = """
                fnc do_case(Result<int, String> value): Result<int, String> {
                  defer stdio.stdout.write("D");
                  do maybe
                    Ok(n) ?= value;
                    return Ok(n + 1);
                  else
                    Err(reason) -> {
                      stdio.stdout.write("E");
                      return Err(reason);
                    };
                  end
                }
                fnc option_case(Option<int> value): int {
                  return maybe
                    Some(n) ?= value;
                    n + 4
                  else
                    None -> 0;
                  end;
                }
                pub fnc main(): void {
                  stdio.stdout.write(do_case(Ok(7)).unwrap());
                  stdio.stdout.write("|");
                  stdio.stdout.write(do_case(Err("bad")).is_err());
                  stdio.stdout.write("|");
                  stdio.stdout.write(option_case(Some(3)));
                  stdio.stdout.write("|");
                  stdio.stdout.write(option_case(None));
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(typed);
        assertEquals("D8|EDtrue|7|0", run(source));
    }

    @Test
    void multipleBindsStopAtFirstFailureAndOuterDeferRunsOnce() throws Exception {
        String source = """
                fnc passthrough(Result<int, String> one, Result<int, String> two): Result<int, String> {
                  defer stdio.stdout.write("D");
                  return maybe
                    Ok(first) ?= one;
                    Ok(second) ?= two;
                    return Ok(first + second)
                  else
                    Err(reason) -> {
                      stdio.stdout.write("E");
                      return Err(reason)
                    };
                  end;
                }
                pub fnc main(): void {
                  stdio.stdout.write(passthrough(Ok(3), Ok(4)).unwrap());
                  stdio.stdout.write("|");
                  stdio.stdout.write(passthrough(Err("one"), Err("two")).is_err());
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(typed);
        assertEquals("D7|EDtrue", run(source));
    }

    @Test
    void firstMiddleLastFailureSkipsAllLaterBindingsAndRunsDeferOnce() throws Exception {
        String source = """
                fnc first(bool fail): Result<int, String> {
                  stdio.stdout.write("A");
                  if fail then
                    return Err("first");
                  else
                    return Ok(1);
                  fi
                }
                fnc second(bool fail): Result<int, String> {
                  stdio.stdout.write("B");
                  if fail then
                    return Err("second");
                  else
                    return Ok(2);
                  fi
                }
                fnc third(bool fail): Result<int, String> {
                  stdio.stdout.write("C");
                  if fail then
                    return Err("third");
                  else
                    return Ok(3);
                  fi
                }
                fnc exercise(bool a, bool b, bool c): Result<int, String> {
                  defer stdio.stdout.write("D");
                  return maybe
                    Ok(x) ?= first(a);
                    Ok(y) ?= second(b);
                    Ok(z) ?= third(c);
                    return Ok(x + y + z)
                  else
                    Err(reason) -> {
                      stdio.stdout.write("E");
                      return Err(reason)
                    };
                  end;
                }
                pub fnc main(): void {
                  stdio.println(exercise(false, false, false).is_ok());
                  stdio.stdout.write("|");
                  stdio.println(exercise(true, false, false).is_ok());
                  stdio.stdout.write("|");
                  stdio.println(exercise(false, true, false).is_ok());
                  stdio.stdout.write("|");
                  stdio.println(exercise(false, false, true).is_ok());
                  return;
                }
                """;
        var typed = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(typed);
        assertEquals("ABCDtrue\n|AEDfalse\n|ABEDfalse\n|ABCEDfalse\n",
                run(source).replace("\r", ""));
    }

    @Test
    void failClosedOnMissingElseAndWrongFailureHandler() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(Option<int> o): int {
                  do maybe
                    Some(n) ?= o;
                    return n;
                  end
                }
                """));
        var error=assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(Option<int> o): int {
                  do maybe
                    Some(n) ?= o;
                    return n;
                  else
                    Err(reason) -> return 0;
                  end
                }
                """));
        assertTrue(error.getMessage().contains("non-exhaustive maybe"), error::getMessage);
    }

    @Test
    void rejectUnsafeInBodyDeferAndFallibleBindingOutsideScope() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(Option<int> o): int {
                  do maybe
                    Some(n) ?= o;
                    defer stdio.stdout.write("unsafe-order");
                    return n;
                  else
                    None -> return 0;
                  end
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc f(Result<int, String> r): int {
                  Ok(n) ?= r;
                  return n;
                }
                """));
    }

    private static String run(String source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        var guest=Source.newBuilder(OresLanguage.ID,source,"maybe-guest.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx=Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(guest);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
