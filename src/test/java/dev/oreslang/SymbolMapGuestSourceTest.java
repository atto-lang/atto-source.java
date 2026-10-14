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
                    let mut m = new Map<Symbol, int>();
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
                    let mut m = new Map<Symbol | String, int>();
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
                    let mut m = new Map<Symbol, int>();
                    m["ready"] = 3;
                    return;
                }
                """;
        String wrongValue = """
                pub fnc bad(): void {
                    let mut m = new Map<Symbol, int>();
                    m[#ready] = "bad";
                    return;
                }
                """;
        String wrongReadKey = """
                pub fnc bad(): void {
                    let mut m = new Map<String, int>();
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
            "pub fnc bad(): void { let mut m = new Map<int, int>(); return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol>(); return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, void>(); return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(1); return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol | int, int>(); return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, float>(); return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, Array<int>>(); return; }"
        };
        for (String source : sources) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
    }

    @Test void mapKeysNeverBecomeStringNamedDotProperties() {
        String[] invalid = {
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); let v = m.ready; return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); m.ready = 4; return; }",
            "pub fnc bad(): void { let mut m = new Map<String, int>(); m.get(\"ready\"); return; }"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
    }

    @Test void guestStringMapValueTypeChecksAreEnforced() throws Exception {
        String guest = """
                pub routine main(): void {
                    let mut m = new Map<String, String>();
                    m["status"] = "ready";
                    if m["status"] eq "ready" then
                        stdio.stdout.write("ok");
                    fi
                    return;
                }
                """;
        assertEquals("ok", execute(guest));
    }

    @Test void wrongScalarValueOrKeyIsRejectedAtCompileTime() {
        String[] invalid = {
            "pub fnc bad(): void { let mut m = new Map<Symbol, bool>(); m[#ready] = 1; return; }",
            "pub fnc bad(): void { let mut m = new Map<String, Symbol>(); m[\"state\"] = \"ready\"; return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); m[5] = 6; return; }"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
    }

    @Test void valGrantsMutableReferentWithoutAllowingRebinding() throws Exception {
        String guest = """
                pub routine main(): void {
                    val m = new Map<Symbol, int>();
                    m[#ready] = 12;
                    if m[#ready] eq 12 then
                        stdio.stdout.write("val-mut-ok");
                    fi
                    return;
                }
                """;
        assertEquals("val-mut-ok", execute(guest));
    }

    @Test void capturedMapInLexicalLambdaIsRejectedByLifetimeGate() {
        String source = """
                pub fnc bad(): void {
                    let mut m = new Map<Symbol, int>();
                    m[#ready] = 1;
                    let callback = () -> m[#ready];
                    return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("cannot capture typed Map"),
                failure.getMessage());
    }

    @Test void localMapInsideClosureCanBeUsedFromNestedScope() {
        String source = """
                pub fnc ok(): void {
                    let callback = () -> {
                        let mut m = new Map<Symbol, int>();
                        if true then
                            m[#ready] = 7;
                        fi
                    };
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test void closureCanStillCaptureOrdinaryNonMapLocals() {
        String source = """
                pub fnc ok(): void {
                    let mut m = new Map<Symbol, int>();
                    let n = 42;
                    let callback = () -> n;
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test void nestedContainersCannotLaunderMapOwnership() {
        String[] sources = {
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); let xs = [m]; return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); let pair = (m, 1); return; }",
            "pub fnc bad(): void { let xs = [new Map<Symbol, int>()]; return; }"
        };
        for (String source : sources) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
            assertTrue(failure.getMessage().contains("aliased or embedded"),
                    failure.getMessage());
        }
    }

    @Test void functionArgumentsCannotSmuggleUnmovedMaps() {
        String source = """
                pub fnc accept(Map<Symbol, int> value): void { return; }
                pub fnc bad(): void {
                    let mut m = new Map<Symbol, int>();
                    accept(m);
                    return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("cannot escape through a call argument"),
                failure.getMessage());
    }

    @Test void mapReturnRequiresMoveSemantics() {
        String source = """
                pub fnc bad(): Map<Symbol, int> {
                    return new Map<Symbol, int>();
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("typed Map return requires"),
                failure.getMessage());
    }

    @Test void moduleCannotShadowBuiltinMapName() {
        String source = "define module Map as end";
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test void ordinaryConstAndLetBindingsCannotMutateMapReferent() {
        String[] invalid = {
            "pub fnc bad(): void { const m = new Map<Symbol, int>(); m[#ready] = 1; return; }",
            "pub fnc bad(): void { let m = new Map<Symbol, int>(); m[#ready] = 1; return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); let mut alias = m; alias[#ready] = 1; return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); let mut alias = m; return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); m = new Map<Symbol, int>(); return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); const alias = m; return; }",
            "pub fnc bad(): void { let mut m = new Map<Symbol, int>(); let alias = m; return; }"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.checkTypes(Parser.parse(source)), source);
        }
    }

    @Test void readOnlyMapAliasesAreRejectedUntilBorrowLifetimeTrackingExists() {
        String source = """
                pub fnc bad(): void {
                    val m = new Map<Symbol, int>();
                    m[#ready] = 1;
                    const alias = m;
                    alias[#ready] = 2;
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(source)));
    }

    @Test void keylessOrAmbiguousGenericMapCannotBeReified() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "pub fnc bad(): void { let mut m = new Map<>(); return; }")));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(Parser.parse(
                "class Map { }")));
    }
}
