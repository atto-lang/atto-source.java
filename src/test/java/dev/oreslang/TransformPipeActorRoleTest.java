package dev.oreslang;

import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TransformPipeActorRoleTest {
    private static final String FORWARD =
            "receive_forward(Forward m): void { return; }\n";
    private static final String BACKWARD =
            "receive_backward(Backward m): void { return; }\n";
    private static final String HEADER =
            "define transform pipe actor Stage<forward_messages=Forward,backward_messages=Backward> as\n";

    private static Ast.ClassDecl role(Ast.Program program) {
        return (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
    }

    @Test
    void enforcesBothClosedDirectionalSignatureSpelling() {
        Ast.ClassDecl stage = role(Parser.parse(HEADER + FORWARD + BACKWARD + "end\n"));
        assertNotNull(stage.pipeTransformSpec());
        assertEquals("Forward", stage.pipeTransformSpec().forwardMessages());
        assertEquals("Backward", stage.pipeTransformSpec().backwardMessages());
        assertEquals(2, stage.methods().size());
        assertEquals("receive_forward", stage.methods().get(0).name());
        assertEquals("receive_backward", stage.methods().get(1).name());
    }

    @Test
    void rejectsUntrustedOrAmbiguousCallbacks() {
        assertRejected(HEADER + FORWARD + "end", "requires exactly one receive_forward");
        assertRejected(HEADER + BACKWARD + "end", "requires exactly one receive_forward");
        assertRejected(HEADER + FORWARD.replace("Forward m", "Backward m")
                + BACKWARD + "end", "must be an instance void handler");
        assertRejected(HEADER + FORWARD.replace(": void", ": int")
                + BACKWARD + "end", "must be an instance void handler");
        assertRejected(HEADER + FORWARD + FORWARD + BACKWARD + "end",
                "requires exactly one receive_forward");
        assertRejected(HEADER.replace("backward_messages=Backward",
                "forward_messages=Backward") + FORWARD + BACKWARD + "end",
                "requires distinct forward_messages");
        assertRejected(HEADER + FORWARD + BACKWARD.replace("receive_backward", "receive")
                + "end", "rejects ordinary receive");
        assertRejected("define actor Plain as\n"
                + FORWARD + "end", "require define transform pipe actor");
    }

    @Test
    void preservesRoleAcrossAnnotationExpansionAndOptimization() {
        String source = HEADER.replace("define transform", "define pub transform")
                + FORWARD + BACKWARD + "end\n";
        Ast.Program expanded = AnnotationExpander.expand(Parser.parse(source));
        assertNotNull(role(expanded).pipeTransformSpec(),
                "annotation normalization must not erase scheduler role");
        Ast.Program shaken = TreeShaker.shake(expanded,
                BuildOptions.library(Map.of())).program();
        assertNotNull(role(shaken).pipeTransformSpec(),
                "tree-shaking must not erase scheduler role");
    }

    @Test
    void moduleNestedRoleIsEquivalent() {
        String source = "define module M as\n" + HEADER + FORWARD
                + BACKWARD + "end\nend\n";
        Ast.ClassDecl stage = role(Parser.parse(source));
        assertEquals("Forward", stage.pipeTransformSpec().forwardMessages());
    }

    @Test
    void javaGuestRuntimeRemainsExplicitlyFailClosed() {
        IllegalArgumentException denied = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(Parser.parse(
                        HEADER + FORWARD + BACKWARD + "end")));
        assertTrue(denied.getMessage().contains(
                "requires Java directional DATA ingress dispatcher"));
    }

    private static void assertRejected(String source, String reason) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains(reason), failure.getMessage());
    }
}
