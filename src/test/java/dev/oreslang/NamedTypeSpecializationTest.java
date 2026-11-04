package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Named generic type specializations bind by declaration slot, not by textual
 * label order or the collection-metadata namespace. Do not conflate named
 * type arguments with label: expression call arguments.
 */
final class NamedTypeSpecializationTest {
    private static void accepts(String source) {
        Ast.Program parsed = Parser.parse(source);
        assertDoesNotThrow(() -> OwnershipChecker.check(TypeChecker.check(parsed)));
    }

    private static void rejects(String source, String diagnostic) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(diagnostic), failure.getMessage());
    }

    @Test
    void namedClassGenericOrderAndMixedPositionalNamedBinding() {
        accepts("""
                define module app
                  define class Pair<A, B> as
                    pub val A left;
                    pub val B right;
                  end
                  fnc read(Pair<B=string, A=int> item): int {
                    return item.left;
                  }
                  fnc forward(Pair<int, B=string> item): int {
                    return read(item);
                  }
                  fnc construct(): Pair<A=int, B=string> {
                    return new Pair<B=string, A=int>(42, "yes");
                  }
                end
                """);
    }

    @Test
    void namedTypeAliasesExpandInDeclarationOrder() {
        accepts("""
                type Entry<K, V> = record { key: K, value: V };
                define type alias Pair<K, V> as Entry<K, V>;
                fnc read(Pair<V=int, K=string> item): int {
                  return item.value;
                }
                fnc positional(Pair<string, int> item): int {
                  return read(item);
                }
                """);
    }

    @Test
    void namedAliasesForwardInnerNamedGenericSlots() {
        // An alias can forward its own reordered slots into a different
        // declaration order. This must work recursively without using
        // metadata keys or the textual order as type identity.
        accepts("""
                type Entry<K, V> = record { key: K, value: V };
                define type alias Flipped<X, Y> as Entry<V=X, K=Y>;
                fnc named(Flipped<Y=string, X=int> item): void { return; }
                fnc positional(Entry<string, int> item): void {
                  named(item);
                  return;
                }
                """);
    }

    @Test
    void namedNominalSlotsAreCaseSensitiveAndNestedUnknownNamesReject() {
        String declarations = """
                define class Box<T> as
                  pub val T value;
                end
                define class Outer<T> as
                  pub val T value;
                end
                """;
        rejects(declarations + "fnc bad(Box<t=int> item): void { return; }",
                "no generic parameter named 't'");
        rejects(declarations
                        + "fnc bad(Outer<T=Box<t=int>> item): void { return; }",
                "no generic parameter named 't'");
    }

    @Test
    void positionalAfterNamedReportsTheCanonicalParserDiagnostic() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Pair<A, B> as
                          pub val A first;
                          pub val B second;
                        end
                        fnc bad(Pair<B=string, int> item): void { return; }
                        """));
        assertTrue(failure.getMessage().contains(
                "positional type argument cannot follow a named type argument"),
                failure.getMessage());
    }

    @Test
    void namedInterfaceArgumentsPreserveNominalSubstitution() {
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
    void builtInCollectionTIsDistinctFromBracketShapeAndRankMetadata() {
        accepts("""
                fnc array_value(Array<T=int> xs): void { return; }
                fnc vector_value(Vector<T=string> xs): void { return; }
                fnc shape_value(Vector<rank=1>[int] xs): void { return; }
                fnc legacy_value(Vector<int> xs): void { return; }
                """);
        rejects("""
                fnc bad(Vector<T=int>[int] xs): void { return; }
                """, "T");
    }

    @Test
    void userDefinedArrayListMustActuallyDeclareT() {
        accepts("""
                define class ArrayList<T> as
                  pub val T item;
                end
                fnc use(ArrayList<T=string> xs): string {
                  return xs.item;
                }
                """);
        rejects("""
                define class ArrayList<E> as
                  pub val E item;
                end
                fnc bad(ArrayList<T=int> xs): void { return; }
                """, "no generic parameter named 'T'");
    }

    @Test
    void genericTypeArgumentsRejectUnknownDuplicateAndCollidingSlots() {
        String prelude = """
                define class Pair<A, B> as
                  pub val A first;
                  pub val B second;
                end
                """;
        rejects(prelude + "fnc bad(Pair<C=int, B=string> item): void { return; }",
                "no generic parameter named 'C'");
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(prelude
                        + "fnc bad(Pair<A=int, A=bool> item): void { return; }"));
        rejects(prelude + "fnc bad(Pair<int, A=bool> item): void { return; }",
                "duplicate generic argument 'A'");
        rejects(prelude + "fnc bad(Pair<B=string> item): void { return; }",
                "expects 2 type argument");
    }

    @Test
    void positionalAfterNamedIsRejectedInTheParser() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Pair<A, B> as
                  pub val A first;
                  pub val B second;
                end
                fnc bad(Pair<B=string, int> item): void { return; }
                """));
    }

    @Test
    void inferenceHolesRemainForbiddenInConcreteTypePositions() {
        rejects("""
                define class Box<T> as
                  pub val T item;
                end
                fnc bad(Box<T=_> box): void { return; }
                """, "inference holes");
    }

    @Test
    void nestedNamedTypesAreCheckedRecursively() {
        accepts("""
                define class Box<T> as
                  pub val T value;
                end
                define class Outer<A, B> as
                  pub val A first;
                  pub val B second;
                end
                fnc use(Outer<B=Box<T=string>, A=Box<T=int>> nested): void { return; }
                """);
    }
}
