package dev.oreslang.compiler;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.Node;
import dev.oreslang.OresLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static Truffle tree assertions for the closed-world source actor slice.
 *
 * <p>These tests prove the parser-derived spawn/send actions become
 * distinct Truffle child nodes rather than being re-interpreted as
 * an Action list during Context.eval. They do NOT prove general
 * guest expression lowering, coroutine/await or native AOT.</p>
 */
@Timeout(30)
final class NamespaceActorTruffleActionNodesTest {
    private static String source() throws Exception {
        return Files.readString(Path.of(
                "contracts/namespace-actor-state-v1/unleased-source-spawn.atto"));
    }

    private static RootCallTarget lower(String text, String unit) {
        RootCallTarget target = NamespaceActorUnleasedSpawnV0.tryCreate(
                new OresLanguage(), unit, text);
        assertNotNull(target, "declared actor must be admitted only after exact AST proof");
        return target;
    }

    private static List<Node> children(RootCallTarget target) {
        List<Node> result = new ArrayList<>();
        target.getRootNode().getChildren().forEach(result::add);
        return List.copyOf(result);
    }

    @Test
    void sourceActionsAreLoweredToDistinctTruffleChildrenInProgramOrder()
            throws Exception {
        RootCallTarget target = lower(source(), "actor-nodes/order.atto");
        List<Node> actual = children(target);
        assertEquals(List.of("SpawnNode", "SpawnNode", "SendNode", "SendNode"),
                actual.stream().map(n -> n.getClass().getSimpleName()).toList());
        assertEquals(actual.size(), new java.util.HashSet<>(actual).size(),
                "no child node instance can be reused for another source action");
    }

    @Test
    void newGuestCallTargetOwnsFreshNodeInstancesAndCannotLeakPreviousBindings()
            throws Exception {
        String code = source();
        RootCallTarget a = lower(code, "actor-nodes/first.atto");
        RootCallTarget b = lower(code, "actor-nodes/second.atto");
        assertNotSame(a.getRootNode(), b.getRootNode());
        List<Node> left = children(a);
        List<Node> right = children(b);
        assertEquals(left.size(), right.size());
        for (int i = 0; i < left.size(); i++) {
            assertNotSame(left.get(i), right.get(i),
                    "fresh parser admission must mint a new Truffle AST");
            assertEquals(left.get(i).getClass(), right.get(i).getClass());
        }
    }

    @Test
    void normalPolyglotGuestExecutesTheLoweredNodesWithoutTrustedLease()
            throws Exception {
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            Source guest = Source.newBuilder(OresLanguage.ID,
                    source(), "actor-nodes/live-source.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertEquals(42L, context.eval(guest).asLong(),
                    "real Atto source must run compiled spawn/send action nodes");
        }
    }

    @Test
    void unsupportedGuestActorExpressionsRemainRejectedAtCompileAdmission()
            throws Exception {
        String original = source();
        String[] unlowered = {
                original.replace("a.send(1);", "a.send(i);"),
                original.replace("a.send(1);", "b.send(1 + 2);"),
                original.replace("const a = spawn Meter();", "let a = spawn Meter();"),
                original.replace("b.send(7);", "b.stop();"),
                original.replace("return 42;", "return a.state.received;")
        };
        for (int i = 0; i < unlowered.length; i++) {
            final int index = i;
            assertNotEquals(original, unlowered[index],
                    "negative test must actually mutate source");
            assertThrows(RuntimeException.class, () ->
                    lower(unlowered[index], "actor-nodes/reject-" + index + ".atto"),
                    "no fallback host/evaluator dispatch may admit an unlowered expression");
        }
    }
}
