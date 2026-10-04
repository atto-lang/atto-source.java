package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StructMapAotTest {
    @Test
    void reservedWordsRemainCallableNamesAndFixedObjectKeys() {
        var tokens = new Lexer("stop do done struct").scan();
        assertEquals(Token.Type.STOP, tokens.get(0).type());
        assertEquals(Token.Type.DO, tokens.get(1).type());
        assertEquals(Token.Type.DONE, tokens.get(2).type());
        assertEquals(Token.Type.STRUCT, tokens.get(3).type());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc stop(): int { return 1; }
                  routine do(): int { return 2; }
                  fnc done(): int { return 3; }

                  pub fnc main(): int {
                    val values = obj{stop: 4, 'do': 5, "done": 6};
                    return stop() + do() + done()
                        + values["stop"] + values["do"] + values["done"];
                  }
                end
                """)));
    }

    @Test
    void callableLocalStructAndInterfaceAreLexicallyHoistedAndCompileTimeOnly() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc make(): int {
                    interface HasValue {
                      value: int;
                    }

                    struct Box implements HasValue {
                      value: int;
                    }

                    val Box box = Box { value = 7 };
                    return box.value;
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Holder as
                  make(): int {
                    struct Local {
                      value: int;
                    }
                    val Local item = obj{value: 9};
                    return item.value;
                  }
                end
                """)));
    }

    @Test
    void classModuleAndNamespaceRemainOutsideCallableScopes() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc bad(): void {
                    define class Local as
                    end
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc bad(): void {
                    define module local
                    end
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc bad(): void {
                    define namespace local as
                    end
                    return;
                  }
                end
                """));
    }

    @Test
    void mapsOwnDynamicKeyInsertionAtRuntime() throws Exception {
        String program = """
                define module app
                  pub fnc main(): void {
                    let Map<String,int> bag = new Map<String,int>();
                    bag["stop"] = 1;
                    bag["done"] = 2;
                    const key = "do";
                    bag[key] = 3;
                    stdio.println(bag["stop"] + bag["done"] + bag[key]);
                    return;
                  }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "map-dynamic-keys.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("6"));
    }

    @Test
    void mapsCanUseTypedNonStringKeys() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc ok(): String {
                    let Map<int,String> values = new Map<int,String>();
                    values[7] = "seven";
                    return values[7];
                  }
                end
                """)));
    }

    @Test
    void mapsRejectWrongKeyAndValueTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(): void {
                    let Map<String,int> bag = new Map<String,int>();
                    bag[1] = 42;
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(): void {
                    let Map<String,int> bag = new Map<String,int>();
                    bag["answer"] = "forty-two";
                    return;
                  }
                end
                """)));
    }

    @Test
    void dynamicStructAndDynamicObjKeysAreRejectedWithMapMigrationPath() {
        IllegalArgumentException dynamicStruct = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          fnc bad(): void {
                            let DynamicStruct<int> bag = new DynamicStruct<int>();
                            return;
                          }
                        end
                        """)));
        assertTrue(dynamicStruct.getMessage().contains("Map"));

        IllegalArgumentException dynamicObject = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module app
                          fnc bad(string key): void {
                            val value = obj{`key`: 1};
                            return;
                          }
                        end
                        """)));
        assertTrue(dynamicObject.getMessage().contains("Map"));
    }

    @Test
    void structsHaveFixedShapeAndAreNotConstructedWithNew() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  struct Point {
                    x: int;
                    y: int;
                  }

                  fnc make(): Point {
                    return Point { x = 2, y = 3 };
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  struct Point {
                    x: int;
                  }

                  fnc bad(): Point {
                    return new Point(1);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  struct Point {
                    x: int;
                  }

                  fnc bad(): Point {
                    return Point { x = 1, y = 2 };
                  }
                end
                """)));
    }
}
