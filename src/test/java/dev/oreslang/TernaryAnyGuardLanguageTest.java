package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Regression specification for sealed integer guards and conditional narrowing. */
final class TernaryAnyGuardLanguageTest {
    private static void accept(String source) {
        assertDoesNotThrow(() -> {
            var checked = TypeChecker.check(Parser.parse(source));
            OwnershipChecker.check(checked);
        });
    }

    private static void reject(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void lowercaseTypesAreLexicallyReservedAndRemainDistinct() {
        var scanned = new Lexer("any unknown Any Unknown").scan();
        assertEquals(Token.Type.ANY, scanned.get(0).type());
        assertEquals(Token.Type.UNKNOWN, scanned.get(1).type());
        assertEquals(Token.Type.IDENT, scanned.get(2).type());
        assertEquals(Token.Type.IDENT, scanned.get(3).type());

        accept("fnc valid(any v): unknown { return v; }");
        reject("fnc invalid(unknown v): any { return v; }");
        reject("type any = int;");
        reject("fnc bad<any>(any v): int { return 0; }");
    }

    @Test
    void staticNarrowingCoversTernaryAndIf() {
        accept("""
                pub fnc foo(any v): int {
                  return Integer.is_int(v) ? v : 0;
                }
                fnc plusOne(any v): int {
                  return Integer.is_int(v) ? v + 1 : 0;
                }
                fnc viaIf(any v): int {
                  if Integer.is_int(v) then
                    return v;
                  else
                    return 0;
                  fi
                }
                fnc viaAnd(any v): int {
                  if Integer.is_int(v) && v > 0 then
                    return v;
                  else
                    return 0;
                  fi
                }
                fnc explicitBinding(any v): int {
                  return v is type int narrowed ? narrowed + 1 : 0;
                }
                fnc repeated(any v): int {
                  return (Integer.is_int(v) && Integer.is_int(v)) ? v : 0;
                }
                """);
        reject("fnc bad(any v): int { return v; }");
        reject("fnc bad(any v): int { return Integer.is_int(v) ? 0 : v; }");
        reject("fnc bad(any mut v): int { return Integer.is_int(v) ? v : 0; }");
    }

    @Test
    void ternaryRemainsRightAssociativeAndBooleanOnly() {
        accept("fnc nested(bool first, bool second): int { return first ? 1 : second ? 2 : 3; }");
        accept("fnc verified(unknown value): int { return Integer.is_int(value) ? value : 0; }");
        reject("fnc bad(): int { return 3 ? 1 : 0; }");
        reject("fnc bad(any value): int { return value ? 1 : 0; }");
    }

    @Test
    void runtimeEvaluatesOnlyTheChosenBranch() throws Exception {
        String program = """
                pub fnc foo(any v): int {
                  return Integer.is_int(v) ? v : 0;
                }
                fnc explicitBinding(any v): int {
                  return v is type int narrowed ? narrowed + 1 : 0;
                }
                fnc boxString(string v): any { return v; }
                pub fnc main(): void {
                  stdio.println(foo(7));
                  stdio.println(foo("seven"));
                  stdio.println(foo(true));
                  stdio.println(Integer.is_int(3) ? 11 : 22);
                  stdio.println(Integer.is_int(boxString("no")) ? 11 : 22);
                  stdio.println(explicitBinding(4));
                  stdio.println(explicitBinding(boxString("no")));
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "ternary-any.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        assertEquals("7\n0\n0\n11\n22\n5\n0",
                output.toString(StandardCharsets.UTF_8).trim().replace("\r", ""));
    }

    @Test
    void rejectDynamicTypeofAndInvalidGuardArity() {
        reject("fnc bad(any v): int { return typeof v == int ? v : 0; }");
        reject("fnc bad(any v): bool { return Integer.is_int(); }");
        reject("fnc bad(any v): bool { return Integer.is_int(v, v); }");
        reject("fnc bad(any v): bool { return Integer.is_int<int>(v); }");
        reject("fnc bad(any v): int { return Integer.is_int(v) ? v : v; }");
    }

    @Test
    void integerNamespaceCannotBeSpoofedOrShadowed() {
        reject("define class Integer as end");
        reject("type Integer = int;");
        reject("fnc bad(int Integer): bool { return Integer.is_int(Integer); }");
        reject("fnc bad(): bool { val int Integer = 1; return Integer.is_int(1); }");
    }

    @Test
    void lowercaseTypeTokensAreNotRuntimeValues() {
        reject("fnc bad(): any { return any; }");
        reject("fnc bad(): unknown { return unknown; }");
    }
}
