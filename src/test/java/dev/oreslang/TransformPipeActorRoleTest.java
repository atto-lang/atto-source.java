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
    void actorClassCannotDeclareOrdinaryInstanceData() {
        assertRejected(HEADER + "let value: int = 0;\n" + FORWARD + BACKWARD + "end",
                "actor class instance data must be declared in its state map");
        assertRejected(HEADER + "const value: int = 0;\n" + FORWARD + BACKWARD + "end",
                "actor class instance data must be declared in its state map");
        assertRejected(HEADER + "val value: int = 0;\n" + FORWARD + BACKWARD + "end",
                "actor class instance data must be declared in its state map");
    }

    @Test
    void stateMapIsSingleFixedKeyOwnedSchemaNotAnActorClassField() {
        String body = "state { value: int = 0; }\n";
        Ast.Program parsed = Parser.parse(HEADER + body + FORWARD + BACKWARD + "end");
        assertTrue(role(parsed).fields().isEmpty());
        assertNotNull(role(parsed).stateSpec());
        assertEquals("value", role(parsed).stateSpec().entries().getFirst().name());
        assertRejected(HEADER + body + body + FORWARD + BACKWARD + "end",
                "actor may declare exactly one state map");
        assertRejected(HEADER
                + "state { value: int = 0; value: int = 3; }\n"
                + FORWARD + BACKWARD + "end", "duplicate actor state key");
    }

    @Test
    void compilerPassesPreserveStateSchemaAndFailClosedBeforeExecution() {
        String source = HEADER.replace("define transform", "define pub transform")
                + "state { value: int = 0; }\n" + FORWARD + BACKWARD + "end";
        Ast.Program expanded = AnnotationExpander.expand(Parser.parse(source));
        assertNotNull(role(expanded).stateSpec());
        Ast.Program optimized = TreeShaker.shake(expanded,
                BuildOptions.library(Map.of())).program();
        assertNotNull(role(optimized).stateSpec());
        assertEquals(1, role(optimized).stateSpec().entries().size());
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(optimized));
    }

    @Test
    void usingReadWriteCannotBypassUnimplementedJavaStateLoans() {
        String state = "state { value: int = 0; }\n";
        String read = FORWARD.replace("return;",
                "const next = using state.read(|state| -> { return state.value; }); return;");
        String write = FORWARD.replace("return;",
                "const next = using state.write(critical |state| -> { "
                + "state.value = 1; return state.value; }); return;");
        assertRejected(HEADER + state + read + BACKWARD + "end",
                "using state.read/write requires Java scoped actor-state");
        assertRejected(HEADER + state + write + BACKWARD + "end",
                "using state.read/write requires Java scoped actor-state");
    }

    @Test
    void canonicalHandleStateReadsAndCriticalWritesFailClosedOnJava() {
        String state = "state { value: int = 0; }\n";
        String read = FORWARD.replace("return;",
                "const next = self.handle_state(|state| -> { return state.value; }); return;");
        String write = FORWARD.replace("return;",
                "const next = self.handle_state(critical |state| -> { "
                + "state.value = 1; return state.value; }); return;");
        assertRejected(HEADER + state + read + BACKWARD + "end",
                "self.handle_state requires Java scoped actor-state");
        assertRejected(HEADER + state + write + BACKWARD + "end",
                "self.handle_state requires Java scoped actor-state");
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
