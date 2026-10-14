package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parser and checker regression contract for transparent record/tuple aliases. */
final class TransparentTypeAliasTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejected(String source, String diagnostic) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(diagnostic), failure.getMessage());
    }

    @Test
    void newAndLegacySpellingsResolveToExactlyTheSameTupleType() {
        accepted("""
                type Entry = [int, string];
                define type alias Pair as Entry;
                type Legacy = Entry;
                fnc second(Pair value): string { return value[1]; }
                fnc old(Legacy value): string { return value[1]; }
                """);
    }

    @Test
    void genericAliasExpandsIntoKeyValueRecordWithoutNominalConversion() {
        accepted("""
                type Entry<K, V> = record { key: K, value: V };
                define type alias Pair<K, V> as Entry<K, V>;
                fnc get(Pair<string, int> entry): int { return entry.value; }
                fnc fromOriginal(Entry<string, int> entry): int {
                    return get(entry);
                }
                """);
    }

    @Test
    void genericPairAliasesPreserveTupleSlotTypesAndArity() {
        accepted("""
                define type alias Pair<A, B> as Tuple[A, B];
                fnc first(Pair<int, string> pair): int { return pair[0]; }
                fnc second(Pair<int, string> pair): string { return pair[1]; }
                """);
        rejected("""
                type Pair<A, B> = Tuple[A, B];
                fnc bad(Pair<int, string> pair): string {
                    return pair[2];
                }
                """, "outside arity");
    }

    @Test
    void aliasCannotSilentlyTurnTupleIntoNamedFieldRecord() {
        rejected("""
                type Entry<A, B> = record { key: A, value: B };
                define type alias Pair<A, B> as Entry<A, B>;
                fnc bad(Pair<int, string> pair): int {
                    return pair[0];
                }
                """, "object/map key has type INT but expected STRING");
    }

    @Test
    void aliasGenericArityMustMatchDefinition() {
        rejected("""
                type Entry<A, B> = [A, B];
                define type alias Pair<A, B> as Entry<A, B>;
                fnc bad(Pair<int> pair): void { return; }
                """, "expects 2 type argument");
    }

    @Test
    void aliasCyclesFailClosed() {
        rejected("""
                define type alias Pair as Entry;
                type Entry = Pair;
                fnc read(Pair item): void { return; }
                """, "type alias cycle");
    }

    @Test
    void contextualAliasMarkerDoesNotBreakExistingAliasIdentifier() {
        accepted("""
                type alias = int;
                fnc identity(alias item): alias { return item; }
                """);
    }

    @Test
    void explicitAliasMarkerRequiresAsNotEqualSign() {
        rejected("""
                type Entry = int;
                define type alias Pair = Entry;
                """, "expected 'as'");
    }

    @Test
    void newSpellingPreservesVisibilityAndGenericParametersInAst() {
        Ast.Program program = Parser.parse("""
                define pub type alias Pair<A, B> as Tuple[A, B];
                """);
        Ast.TypeAliasDecl alias = (Ast.TypeAliasDecl)
                program.modules().getFirst().declarations().getFirst();
        assertEquals("Pair", alias.name());
        assertEquals(Ast.Visibility.PUBLIC, alias.visibility());
        assertEquals(java.util.List.of("A", "B"), alias.genericParameters());
    }
}
