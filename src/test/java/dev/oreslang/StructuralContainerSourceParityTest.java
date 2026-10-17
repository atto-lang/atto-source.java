package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralContainerSourceParityTest {

    @Test
    void structuralArrayAndListStayDistinctAndExecuteReadonlyReads() throws Exception {
        String sourceText = """
                define module app
                  fnc head_array(structural Array<int> xs): int { return xs[0]; }
                  fnc head_list(structural List<int> xs): int { return xs[0]; }

                  pub fnc main(): void {
                    val Array<int> a = [7, 8];
                    val List<int> l = [9, 10];
                    stdio.println(head_array(a));
                    stdio.println(head_array([11, 12]));
                    stdio.println(head_list(l));
                    return;
                  }
                end
                """;
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(sourceText)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));

        var out = new ByteArrayOutputStream();
        var source = org.graalvm.polyglot.Source.newBuilder(
                OresLanguage.ID, sourceText, "structural-container-v3.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (var context = org.graalvm.polyglot.Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(source);
        }
        assertEquals("7\n11\n9\n",
                out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void arrayListInterchangeAndReadonlyMutationFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc take(structural Array<int> xs): int { return xs[0]; }
                  fnc bad(List<int> xs): int { return take(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc take(structural List<float> xs): float { return xs[0]; }
                  fnc bad(List<int> xs): float { return take(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural List<int> xs): int {
                    xs[0] = 7;
                    return xs[0];
                  }
                end
                """)));
    }

    @Test
    void fixedArrayAndFixedListRequireExactKindAndExtent() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc head(structural FixedArray[2 of int] xs): int { return xs[0]; }
                  fnc ok(FixedArray[2 of int] xs): int { return head(xs); }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc empty(structural FixedArray[0 of int] xs): int { return xs.length; }
                  fnc ok(FixedArray[0 of int] xs): int { return empty(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc empty(structural FixedArray[0 of int] xs): int { return xs.length; }
                  fnc bad(FixedArray[0 of string] xs): int { return empty(xs); }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc empty(structural FixedList[0 of string] xs): int { return xs.length; }
                  fnc ok(FixedList[0 of string] xs): int { return empty(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc empty(structural FixedList[0 of string] xs): int { return xs.length; }
                  fnc bad(FixedList[0 of int] xs): int { return empty(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc head(structural FixedArray[2 of int] xs): int { return xs[0]; }
                  fnc bad(FixedArray[3 of int] xs): int { return head(xs); }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc head(structural FixedArray[2 of int] xs): int { return xs[0]; }
                  fnc bad(FixedList[2 of int] xs): int { return head(xs); }
                end
                """)));
    }

    @Test
    void structuralMapUsesInvariantStableKeysAndOptionalLookup() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc lookup(structural Map<string,int> values): Option<int> {
                    return values.get("missing");
                  }
                  fnc pass(Map<string,int> values): Option<int> {
                    return lookup(values);
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc read_list(structural List<string> values): string {
                    return values[0];
                  }
                  fnc list_literal_projection(List<"literal"> values): string {
                    return read_list(values);
                  }
                  fnc read_map(structural Map<string,string> values): Option<string> {
                    return values.get("key");
                  }
                  fnc map_literal_projection(Map<string,"literal"> values): Option<string> {
                    return read_map(values);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc lookup(structural Map<string,int> values): Option<int> {
                    return values.get("missing");
                  }
                  fnc bad(Map<"literal",int> values): Option<int> {
                    return lookup(values);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural Map<float,int> values): Option<int> {
                    return values.get(1.0);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural Map<string,int> values): void {
                    values.put("key", 1);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(Map<string,int> values): void {
                    values.unknown_map_operation();
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural List<int> values): void {
                    values.add(1);
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural List<List<int>> values): int {
                    return 0;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc bad(structural Map<string,List<int>> values): int {
                    return 0;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc sink(Channel<List<int>> ch, structural List<int> values): void {
                    writech(ch, values);
                    return;
                  }
                end
                """)));
    }

    @Test
    void contractRejectsShapeOnlyOverloadAndDynamicSpread() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc classify(structural Array<int> values): int { return 1; }
                  fnc classify(structural List<int> values): int { return 2; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc add(int left, int right): int { return left + right; }
                  fnc bad(List<int> values): int {
                    return add(...values);
                  }
                end
                """)));
    }

    @Test
    void zeroWidthFixedMetadataCannotHideUnsafePayloadTypes() {
        IllegalArgumentException channelBorrow = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(Channel<FixedArray[0 of &int]> payload): void {
                          return;
                        }
                        """)));
        assertTrue(channelBorrow.getMessage().contains("cannot transport borrowed payloads"),
                channelBorrow::getMessage);

        IllegalArgumentException actorFuture = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(FixedArray[0 of Future<int>] payload): void {
                          return;
                        }
                        """)));
        assertTrue(actorFuture.getMessage().contains("cannot use Future")
                        && actorFuture.getMessage().contains("actor boundary"),
                actorFuture::getMessage);

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc ok(FixedArray[0 of int] payload): void {
                  return;
                }
                """)));
    }

    @Test
    void structuralCollectionLoanCannotCrossSuspendBoundary() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  suspends fnc bad(structural List<int> values): int {
                    return values[0];
                  }
                end
                """)));
    }
}
