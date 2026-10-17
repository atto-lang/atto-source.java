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
