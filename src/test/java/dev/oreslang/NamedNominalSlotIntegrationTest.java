package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clean-main integration proof for declaration-order named nominal binding. */
final class NamedNominalSlotIntegrationTest {
    private static void accepts(String code) {
        Ast.Program parsed = Parser.parse(code);
        assertDoesNotThrow(() -> OwnershipChecker.check(TypeChecker.check(parsed)));
    }

    private static void rejects(String code, String expected) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(code)));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    @Test
    void classArgumentsBindInDeclarationOrderNotTextOrder() {
        accepts("""
                define module app
                  define class Pair<A, B> as
                    pub val A first;
                    pub val B second;
                  end
                  fnc read(Pair<B=string, A=int> value): int {
                    return value.first;
                  }
                  fnc accept(Pair<int, string> value): int {
                    return read(value);
                  }
                end
                """);
    }

    @Test
    void nestedReorderAndTransparentAliasForwarding() {
        accepts("""
                type Entry<K, V> = record { key: K, value: V };
                define type alias Flipped<X, Y> as Entry<V=X, K=Y>;
                fnc named(Flipped<Y=string, X=int> entry): void { return; }
                fnc position(Entry<string, int> entry): void {
                    named(entry);
                    return;
                }
                """);
    }

    @Test
    void interfaceTypeApplicationsHaveNamedSlotIdentity() {
        accepts("""
                define module app
                  define interface HasValue<T>
                    value: T;
                  end
                  define class Box<T> implements HasValue<T> as
                    pub val T value;
                  end
                  fnc take(HasValue<T=int> item): void { return; }
                  fnc call(Box<T=int> item): void {
                    take(item);
                    return;
                  }
                end
                """);
    }

    @Test
    void caseSensitiveUnknownAndPositionalCollisionReject() {
        String prefix = """
                define class Pair<A, B> as
                  pub val A first;
                  pub val B second;
                end
                """;
        rejects(prefix + "fnc bad(Pair<a=int, B=string> item): void { return; }",
                "no generic parameter named 'a'");
        rejects(prefix + "fnc bad(Pair<int, A=string> item): void { return; }",
                "duplicate generic argument 'A'");
        rejects(prefix + "fnc bad(Pair<B=string> item): void { return; }",
                "expects 2 type argument");
    }

    @Test
    void nestedNamedTypeRejectsWrongInnerSlot() {
        rejects("""
                define class Box<T> as
                  pub val T value;
                end
                define class Wrapper<U> as
                  pub val U value;
                end
                fnc bad(Wrapper<U=Box<t=int>> item): void { return; }
                """, "no generic parameter named 't'");
    }

    @Test
    void parserRejectsLatePositionalBeforeCanonicalSorting() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Pair<A, B> as
                          pub val A first;
                          pub val B second;
                        end
                        fnc bad(Pair<B=string, int> item): void { return; }
                        """));
        assertTrue(error.getMessage().contains(
                "positional type argument cannot follow a named type argument"),
                error.getMessage());
    }

    @Test
    void nominalTypeFormalsCannotAcceptLiteralMetadata() {
        String prelude = """
                define class Box<T> as
                  pub val T value;
                end
                """;
        for (String value : java.util.List.of("42", "true", "false",
                "\"not-a-type\"")) {
            rejects(prelude + "fnc invalid(Box<T=" + value
                    + "> item): void { return; }",
                    "requires a type generic argument");
        }
    }

    @Test
    void nominalInferenceHolesCannotMasqueradeAsTypes() {
        rejects("""
                define class Box<T> as
                  pub val T value;
                end
                fnc invalid(Box<T=_> item): void { return; }
                """, "inference holes");
    }

    @Test
    void nestedMetadataMayAppearInsideAnActualType() {
        accepts("""
                define class Box<T> as
                  pub val T value;
                end
                fnc takes(Box<T=Vector<rank=1>[int]> value): void { return; }
                """);
    }

    @Test
    void builtInCollectionLabelHasSeparateMetadataNamespace() {
        accepts("""
                fnc array(Array<T=int> xs): void { return; }
                fnc vector(Vector<T=string> xs): void { return; }
                fnc shape(Vector<rank=1>[int] xs): void { return; }
                """);
    }
}
