package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.DerivedGraphCopyPlan;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compiler descriptor tests, not guest graph-copy execution evidence. */
final class DerivedGraphCopyPlanTest {
    private static Map<String, Ast.ClassDecl> classes(String source) {
        var program = assertDoesNotThrow(() -> Parser.parse(source));
        Map<String, Ast.ClassDecl> declarations = new HashMap<>();
        for (var module : program.modules()) {
            for (var declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) {
                    declarations.put(klass.name(), klass);
                }
            }
        }
        return declarations;
    }

    private static DerivedGraphCopyPlan.Graph derive(String source, String root) {
        var declarations = classes(source);
        return DerivedGraphCopyPlan.derive(Ast.TypeRef.simple(root), declarations::get);
    }

    private static DerivedGraphCopyPlan.Instance instance(DerivedGraphCopyPlan.Graph graph, int id) {
        return assertInstanceOf(DerivedGraphCopyPlan.Instance.class, graph.nodes().get(id));
    }

    @Test
    void recursiveOptionalFieldUsesFiniteDescriptorBackedge() {
        var graph = derive("""
                define class Node as
                  pub let int value = 0;
                  pub let Option<Node> next = None;
                end
                """, "Node");
        var root = instance(graph, graph.root());
        var optional = assertInstanceOf(DerivedGraphCopyPlan.OptionalValue.class,
                graph.nodes().get(root.fields().get(1).descriptor()));
        assertEquals(graph.root(), optional.element());
        assertEquals(3, graph.nodes().size());
    }

    @Test
    void mutualRecursionThroughSequencesUsesDeclarationIdentity() {
        var graph = derive("""
                define class Left as
                  pub let List<Right> children;
                end
                define class Right as
                  pub let Option<Left> parent = None;
                end
                """, "Left");
        var left = instance(graph, graph.root());
        var seq = assertInstanceOf(DerivedGraphCopyPlan.Sequence.class,
                graph.nodes().get(left.fields().getFirst().descriptor()));
        var right = instance(graph, seq.element());
        var back = assertInstanceOf(DerivedGraphCopyPlan.OptionalValue.class,
                graph.nodes().get(right.fields().getFirst().descriptor()));
        assertEquals(graph.root(), back.element());
        assertEquals(4, graph.nodes().size());
    }

    @Test
    void repeatedClassAndContainerShapesShareDescriptors() {
        var graph = derive("""
                define class Leaf as
                  pub let int value = 0;
                end
                define class Pair as
                  pub let List<Leaf> left;
                  pub let List<Leaf> right;
                end
                """, "Pair");
        var fields = instance(graph, graph.root()).fields();
        assertEquals(fields.get(0).descriptor(), fields.get(1).descriptor());
        assertEquals(4, graph.nodes().size());
    }

    @Test
    void recursionDoesNotHideUnsupportedResource() {
        var declarations = classes("""
                define class Node as
                  pub let Option<Node> next = None;
                  pub let Channel<int> channel;
                end
                """);
        var failure = assertThrows(IllegalArgumentException.class, () ->
                DerivedGraphCopyPlan.derive(Ast.TypeRef.simple("Node"), declarations::get));
        assertTrue(failure.getMessage().contains("E_COPY_GRAPH_UNPROVEN"));
        assertTrue(failure.getMessage().contains("Channel"));
    }

    @Test
    void unsignedPayloadsFailAtDescriptorAdmissionEvenUnderRecursiveContainers() {
        for (String unsigned : java.util.List.of("uint", "u8", "u16", "u32", "u64")) {
            var declarations = classes("""
                    define class Node as
                      pub let Option<Node> next = None;
                      pub let List<%s> payload;
                    end
                    """.formatted(unsigned));
            var failure = assertThrows(IllegalArgumentException.class, () ->
                    DerivedGraphCopyPlan.derive(Ast.TypeRef.simple("Node"), declarations::get));
            assertTrue(failure.getMessage().contains("E_COPY_GRAPH_UNPROVEN"), failure::toString);
            assertTrue(failure.getMessage().contains(unsigned), failure::toString);
        }
    }

    @Test
    void unsupportedInheritanceFailsClosed() {
        var declarations = classes("""
                define class Base as
                  pub let int n = 1;
                end
                define class Child extends Base as
                  pub let int m = 2;
                end
                """);
        assertThrows(IllegalArgumentException.class, () ->
                DerivedGraphCopyPlan.derive(Ast.TypeRef.simple("Child"), declarations::get));
    }
}
