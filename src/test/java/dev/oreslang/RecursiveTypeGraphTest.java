package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.runtime.ActorRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Recursive types are finite, guarded structural knots; actor mail remains isolated. */
final class RecursiveTypeGraphTest {
    private static void checks(String program) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)), program);
    }

    private static void rejects(String program, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)), program);
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test
    void acceptsCanonicalRecursiveStructAliasAndMemberAccess() {
        checks("""
                type Node = struct{
                    value: int,
                    next: Option<Node>
                };
                fnc id(Node item): Node { return item; }
                fnc value(Node item): int { return item.value; }
                fnc next_value(Node item): int { return item.next.unwrap().value; }
                """);
    }

    @Test
    void acceptsRecursiveContainersAndMutualRecursion() {
        checks("""
                type Tree<T> = struct{
                    value: T,
                    children: List<Tree<T>>
                };
                fnc value(Tree<int> tree): int { return tree.value; }
                """);
        checks("""
                type Left = struct{right: Option<Right>};
                type Right = struct{left: Option<Left>};
                fnc from_left(Left left): Option<Right> { return left.right; }
                """);
        checks("""
                type Chain = Option<Chain>;
                fnc same(Chain chain): Chain { return chain; }
                """);
    }

    @Test
    void supportsNominalSelfReferencesAndGuardedResultUnions() {
        checks("""
                define class Link as
                    pub let Option<Link> next = None();
                end
                fnc read(Link link): Option<Link> { return link.next; }
                """);
        checks("""
                type Step = Result<int, Step>;
                fnc keep(Step step): Step { return step; }
                """);
    }

    @Test
    void recursiveAliasesMustNotCollapseDifferentFieldTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                type IntNode = struct{value: int, next: Option<IntNode>};
                type TextNode = struct{value: string, next: Option<TextNode>};
                fnc accept(IntNode node): void { return; }
                fnc wrong(TextNode node): void { accept(node); return; }
                """)));
    }

    @Test
    void preservesPreviouslySupportedBareStructuralTypeSyntax() {
        checks("""
                type Point = {x: int, y: int};
                fnc x(Point point): int { return point.x; }
                """);
    }

    @Test
    void rejectsUnguardedCyclesAndUnboundedGenericExpansion() {
        rejects("type Loop = Loop;", "unguarded type alias cycle");
        rejects("type A = B; type B = A;", "unguarded type alias cycle");
        rejects("type A = struct{next: A};", "unguarded type alias cycle");
        rejects("type A<T> = Option<A<List<T>>>;", "polymorphic recursive type alias");
    }

    @Test
    void actorTransportRejectsCyclicDataButAcceptsAcyclicSharedSubgraphs() {
        List<Object> self = new ArrayList<>();
        self.add(self);
        IllegalArgumentException listError = assertThrows(
                IllegalArgumentException.class, () -> ActorRuntime.freeze(self));
        assertTrue(listError.getMessage().contains("cyclic"), listError.getMessage());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("self", map);
        IllegalArgumentException mapError = assertThrows(
                IllegalArgumentException.class, () -> ActorRuntime.freeze(map));
        assertTrue(mapError.getMessage().contains("cyclic"), mapError.getMessage());

        List<Integer> leaf = List.of(1, 2);
        assertEquals(List.of(leaf, leaf), ActorRuntime.freeze(List.of(leaf, leaf)));
    }
}
