package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Real guest parser, typechecker and interpreter tests, not host Map mirrors. */
final class SymbolMapGuestSourceTest {
    private static String execute(String program) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "symbol-map.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test void symbolMapCanWriteAndReadWithLogicalKeys() throws Exception {
        String program = """
                pub routine main(): void {
                    let m = new Map<Symbol, int>();
                    m[#ready] = 41;
                    m[#ready] = 42;
                    if m[#ready] eq 42 then
                        stdio.stdout.write("matched");
                    else
                        stdio.stdout.write("wrong");
                    fi
                    return;
                }
                """;
        assertEquals("matched", execute(program));
    }

    @Test void mixedKeysAreDistinctAtRuntime() throws Exception {
        String program = """
                pub routine main(): void {
                    let m = new Map<Symbol | String, int>();
                    m[#ready] = 5;
                    m["ready"] = 9;
                    if (m[#ready] eq 5) && (m["ready"] eq 9) then
                        stdio.stdout.write("separate");
                    else
                        stdio.stdout.write("aliased");
                    fi
                    return;
                }
                """;
        assertEquals("separate", execute(program));
    }

    @Test void staticCheckerRejectsWrongKeyAndValueTypes() {
        String wrongKey = """
                pub fnc bad(): void {
                    let m = new Map<Symbol, int>();
                    m["ready"] = 3;
                    return;
                }
                """;
        String wrongValue = """
                pub fnc bad(): void {
                    let m = new Map<Symbol, int>();
                    m[#ready] = "bad";
                    return;
                }
                """;
        String wrongReadKey = """
                pub fnc bad(): void {
                    let m = new Map<String, int>();
                    let x = m[#ready];
                    return;
                }
                """;
        for (String program : new String[]{wrongKey, wrongValue, wrongReadKey}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(program)));
        }
    }

    @Test void invalidMapDomainsAndConstructorShapesFailClosed() {
        String[] sources = {
            "pub fnc bad(): void { let m = new Map<int, int>(); return; }",
            "pub fnc bad(): void { let m = new Map<Symbol>(); return; }",
            "pub fnc bad(): void { let m = new Map<Symbol, void>(); return; }",
            "pub fnc bad(): void { let m = new Map<Symbol, int>(1); return; }",
            "pub fnc bad(): void { let m = new Map<Symbol | int, int>(); return; }"
        };
        for (String source : sources) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
    }

    @Test void mapKeysNeverBecomeStringNamedDotProperties() {
        String[] invalid = {
            "pub fnc bad(): void { let m = new Map<Symbol, int>(); let v = m.ready; return; }",
            "pub fnc bad(): void { let m = new Map<Symbol, int>(); m.ready = 4; return; }",
            "pub fnc bad(): void { let m = new Map<String, int>(); m.get(\"ready\"); return; }"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
    }

    @Test void keylessOrAmbiguousGenericMapCannotBeReified() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "pub fnc bad(): void { let m = new Map<>(); return; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "class Map { }")));
    }
}
